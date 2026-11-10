package hagrid.hannover.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.HagridConfig;
import hagrid.core.config.SectionSettings;
import hagrid.core.routing.*;
import hagrid.core.routing.sections.*;
import hagrid.core.util.OutputFileSet;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;

/** Stages a complete split-network routing group before publishing carriers and network together. */
final class SectionOfflineRouting {
 private SectionOfflineRouting() {}
 static void run(Scenario scenario,HagridConfig config,ThreadingType threading,Carriers delivery,Carriers supply,CarrierVehicleTypes types) throws IOException {
  if(!(scenario instanceof MutableScenario mutable))throw new IllegalArgumentException("Section routing requires a mutable scenario");
  var settings=SectionSettings.from(config.getAdditionalConfigurationValues());
  if(settings.fastRegret() && config.resources().getInternalSolverThreads()!=1)throw new IllegalArgumentException("routing.sectionFastRegret requires resources.internalSolverThreads=1");
  Path catalogPath=settings.hagridInputsFile().isPresent()
    ? SectionEvidencePreparation.prepareHagrid(scenario.getNetwork(),delivery,SectionEvidencePreparation.resolveHagridInputs(settings.hagridInputsFile().orElseThrow(),config.paths().getFreightDemand()),Path.of(config.io().deliveryCarriersRouted()+".hagrid-inputs"),config.getAdditionalConfigurationValues())
    : SectionEvidencePreparation.prepare(scenario.getNetwork(),Path.of(settings.haltCatalogFile().orElseThrow()),config.getAdditionalConfigurationValues(),config.getMinLinkLength());
  var prepared=SectionCatalogInputs.read(catalogPath);
  var all=new LinkedHashMap<String,Carrier>();for(var collection:List.of(delivery,supply))for(var c:collection.getCarriers().values())if(all.putIfAbsent(c.getId().toString(),c)!=null)throw new IllegalArgumentException("Duplicate carrier "+c.getId());
  if(!all.keySet().containsAll(prepared.keySet()))throw new IllegalArgumentException("Section catalog references missing carrier");
  if(settings.fastRegret())for(String id:prepared.keySet())if(all.get(id).getCarrierCapabilities().getFleetSize()!=CarrierCapabilities.FleetSize.INFINITE)throw new IllegalArgumentException("routing.sectionFastRegret requires INFINITE fleet for "+id);
  if(!scenario.getPopulation().getPersons().isEmpty() || !scenario.getActivityFacilities().getFacilities().isEmpty() || (scenario.getTransitSchedule()!=null && !scenario.getTransitSchedule().getTransitLines().isEmpty()))throw new IllegalArgumentException("Section routing cannot yet remap existing population, facilities, or transit routes");
  Network originalNetwork=scenario.getNetwork();mutable.setNetwork(originalNetwork);
  var views=SplitRoadNetworkBuilder.buildUnion(originalNetwork,prepared.values());var common=views.values().iterator().next();
  var topology=StaticSectionRoadCosts.prepareTopology(views.values());
  var turnPolicy=SectionRoadCosts.TurnPolicy.valueOf(config.getAdditionalConfigurationValues().getOrDefault("routing.sectionUnknownTurnPolicy",settings.hagridInputsFile().isPresent()?"NETWORK_REVERSAL":"STRICT"));
  var captureScope=SectionBindingCompanion.captureScope(common.network());String executionNetworkFingerprint=captureScope.fingerprint();
  var selected=new LinkedHashMap<String,Carrier>();var catalogs=new LinkedHashMap<String,SectionCatalog>();var bindingDocs=new TreeMap<String,Object>();var sectionMetrics=new ArrayList<CarrierRoutingMetrics>();
  var turnEvidence=new TreeMap<String,SectionBindingCompanion.TurnEvidence>();
  var modeCosts=new HashMap<String,VRPTransportCosts>();
  for(var entry:prepared.entrySet()) {
   var source=all.get(entry.getKey());var view=views.get(entry.getKey());
   var catalog=SectionCatalogBuilder.build(source,view,settings.maxSpanMeters().orElseThrow(),config.routing().getDemandBorder());catalogs.put(entry.getKey(),catalog);
   var mode=CarrierModeResolver.resolve(source).orElseThrow();var costs=modeCosts.computeIfAbsent(mode,m->NativeModalTransportCosts.create(common.network(),types,m));
   long start=System.nanoTime();var roads=StaticSectionRoadCosts.prepare(topology,view,source.getCarrierCapabilities().getCarrierVehicles().values(),costs,config.routing().getUTurnPenaltyCost(),turnPolicy);org.apache.logging.log4j.LogManager.getLogger(SectionOfflineRouting.class).info("HAGRID section carrier={} turnPolicy={} unresolvedNetworkTransitionPairs={}",entry.getKey(),turnPolicy,roads.unknownTransitionCount());long preparedAt=System.nanoTime();
   var result=new CarrierOptimizationService().searchSections(catalog,view.network(),roads,config.routing().getSolverMaxRouteDurationSeconds(),config.routing().getMaxDriverTimeMinutes()*60,config.routing().getUTurnPenaltyCost(),HAGRIDRouterUtils.resolveSolverSeed(config.randomSeeds().seed()),config.routing().getJspritIterations(),config.resources().snapshot(),settings.fastRegret());long searched=System.nanoTime();
   var expanded=SectionPlanExpander.expand(result,catalog,roads);var validation=PlanValidator.validateSections(expanded.carrier(),expanded.plan(),costs,null,config.routing().getSolverMaxRouteDurationSeconds(),config.routing().getUTurnPenaltyCost(),roads);
   if(!validation.valid())throw new IllegalStateException("Invalid expanded section plan "+source.getId()+": "+validation.violations());
   if(Math.abs(validation.finalCost()-result.solution().getCost())>1e-6*Math.max(1,Math.abs(result.solution().getCost())))throw new IllegalStateException("Expanded objective differs for "+source.getId());
   var hub=expanded.carrier().getAttributes().getAttribute("hub");if(hub instanceof hagrid.core.util.Hub h)expanded.carrier().getAttributes().putAttribute("hub",SectionInputRemapper.remapHub(h,common));
   HAGRIDRouterUtils.recordUTurnPenalty(expanded.carrier(),config.routing().getUTurnPenaltyCost());
   expanded.carrier().addPlan(expanded.plan());expanded.carrier().setSelectedPlan(expanded.plan());CarriersUtils.setJspritIterations(expanded.carrier(),result.iterations());
   double elapsed=(System.nanoTime()-start)/1e9;CarriersUtils.setJspritComputationTime(expanded.carrier(),elapsed);selected.put(entry.getKey(),expanded.carrier());
   var evidence=captureScope.capture(expanded.carrier(),roads);turnEvidence.put(entry.getKey(),evidence);
   var ids=new TreeMap<String,String>();expanded.bindings().forEach((job,link)->ids.put(job,link.toString()));
   var allowed=new TreeMap<String,Set<String>>();for(var section:catalog.sections())for(var variant:section.variants())for(int i=0;i<variant.orderedJobIds().size();i++)allowed.computeIfAbsent(variant.orderedJobIds().get(i),k->new TreeSet<>()).add(variant.orderedHaltLinks().get(i).toString());
   var doc=new LinkedHashMap<String,Object>();doc.put("catalogFingerprint",catalog.fingerprint());doc.put("networkFingerprint",view.fingerprint());doc.put("roadFingerprint",roads.fingerprint());doc.put("executionNetworkFingerprint",executionNetworkFingerprint);doc.put("executionFingerprint",SectionBindingCompanion.executionFingerprint(expanded.carrier()));doc.put("originalJobsFingerprint",CarrierInputFingerprint.jobs(catalog.originalCarrier()));doc.put("turnPolicy",evidence.turnPolicy().name());doc.put("turnTransitions",evidence.records());doc.put("bindings",ids);doc.put("allowed",allowed);doc.put("canonical",canonical(catalog.originalCarrier()));bindingDocs.put(entry.getKey(),SectionBindingCompanion.seal(doc));
   double searchSecs=(searched-preparedAt)/1e9;var execution=new CarrierRoutingMetrics.Execution(roads.fingerprint(),"VALIDATED",result.iterations(),Optional.empty(),result.solution().getCost(),validation.finalCost(),expanded.plan().getScore(),result.iterations()>=config.routing().getJspritIterations()?"MAXIMUM":"LEGACY_NO_IMPROVEMENT");
   sectionMetrics.add(new CarrierRoutingMetrics(entry.getKey(),String.valueOf(source.getAttributes().getAttribute("provider")),source.getServices().size(),0,source.getServices().values().stream().mapToInt(CarrierService::getCapacityDemand).sum(),0,0,source.getServices().size()>=350?"LARGE":"SMALL",threading.toString(),delivery.getCarriers().containsKey(source.getId())?"delivery":"supply",searchSecs,elapsed-searchSecs,elapsed,Optional.of(execution)));
  }
  var ordinaryDelivery=new Carriers();var ordinarySupply=new Carriers();
  for(var source:delivery.getCarriers().values())if(!selected.containsKey(source.getId().toString()))ordinaryDelivery.addCarrier(SectionInputRemapper.remap(source,common));
  for(var source:supply.getCarriers().values())if(!selected.containsKey(source.getId().toString()))ordinarySupply.addCarrier(SectionInputRemapper.remap(source,common));
  var normal=OfflineCarrierRouting.prepare(ordinaryDelivery,ordinarySupply,types,common.network());var metrics=new ArrayList<CarrierRoutingMetrics>();
  try(var router=new Router(threading,new CarrierRoutingStatusLogger(Path.of(config.io().routingStatus())),config,types)){normal.route(router,ordinaryDelivery,"delivery");normal.route(router,ordinarySupply,"supply");metrics.addAll(router.getMetrics());}metrics.addAll(sectionMetrics);
  var stagedDelivery=new Carriers();var stagedSupply=new Carriers();
  for(var source:delivery.getCarriers().values())stagedDelivery.addCarrier(selected.getOrDefault(source.getId().toString(),ordinaryDelivery.getCarriers().get(source.getId())));
  for(var source:supply.getCarriers().values())stagedSupply.addCarrier(selected.getOrDefault(source.getId().toString(),ordinarySupply.getCarriers().get(source.getId())));
  catalogs.forEach((id,catalog)->catalog.requireSourceUnchanged(all.get(id)));
  var canonical=new Carriers();catalogs.values().forEach(c->canonical.addCarrier(c.originalCarrier()));
  var io=config.io();Path carrierPath=Path.of(io.deliveryCarriersRouted());Path canonicalPath=Path.of(io.deliveryCarriersRouted()+".section-originals.xml.gz");Path bindingsPath=Path.of(io.deliveryCarriersRouted()+".section-bindings.json");
  Network filtered=common.network();
  var oldElements=new LinkedHashMap<String,Object>();var replacements=new LinkedHashMap<String,Object>();
  for(String key:List.of("hubList","parcelLockerList","shippingPointList")) {
   Object old=scenario.getScenarioElement(key);if(old==null)continue;if(!(old instanceof Map<?,?> map))throw new IllegalArgumentException("Unsupported scenario element "+key);
   var remapped=new LinkedHashMap<Object,Object>();for(var e:map.entrySet()){if(!(e.getValue() instanceof hagrid.core.util.Hub hub))throw new IllegalArgumentException("Unsupported location in "+key);remapped.put(e.getKey(),SectionInputRemapper.remapHub(hub,common));}oldElements.put(key,old);replacements.put(key,remapped);
  }
  for(String key:List.of("carFilteredNetwork","parcelServiceNetwork")) {
   Object old=scenario.getScenarioElement(key);if(old==null)continue;if(!(old instanceof Network oldNetwork))throw new IllegalArgumentException("Unsupported network element "+key);
   oldElements.put(key,old);replacements.put(key,ModalRoutingNetwork.forMode(common.network(),"car",link->{Object raw=link.getAttributes().getAttribute("sectionOriginalLinkId");return oldNetwork.getLinks().containsKey(org.matsim.api.core.v01.Id.createLinkId(raw==null?link.getId().toString():raw.toString()));}));
  }
  var boundEvidence=captureScope.bind(turnEvidence);
  Object oldContext=scenario.getScenarioElement(SectionBindingCompanion.CONTEXT);
  try {
   mutable.setNetwork(common.network());for(var e:replacements.entrySet()){mutable.removeScenarioElement(e.getKey());mutable.addScenarioElement(e.getKey(),e.getValue());}
   OutputFileSet.publish(List.of(new OutputFileSet.Artifact(carrierPath,p->new CarrierPlanWriter(stagedDelivery).writePreciseTimes(p.toString())),new OutputFileSet.Artifact(Path.of(io.supplyCarriersRouted()),p->new CarrierPlanWriter(stagedSupply).writePreciseTimes(p.toString())),new OutputFileSet.Artifact(Path.of(io.routingMetrics()),p->RoutingMetricsCsv.write(metrics,p)),new OutputFileSet.Artifact(Path.of(io.networkFiltered()),p->new NetworkWriter(filtered).write(p.toString())),new OutputFileSet.Artifact(canonicalPath,p->new CarrierPlanWriter(canonical).writePreciseTimes(p.toString())),new OutputFileSet.Artifact(bindingsPath,p->new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(p.toFile(),bindingDocs))));
   SectionBindingCompanion.install(scenario,boundEvidence);
   delivery.getCarriers().clear();delivery.getCarriers().putAll(stagedDelivery.getCarriers());supply.getCarriers().clear();supply.getCarriers().putAll(stagedSupply.getCarriers());
  } catch(IOException|RuntimeException failure){mutable.removeScenarioElement(SectionBindingCompanion.CONTEXT);if(oldContext!=null)mutable.addScenarioElement(SectionBindingCompanion.CONTEXT,oldContext);mutable.setNetwork(originalNetwork);for(var e:oldElements.entrySet()){mutable.removeScenarioElement(e.getKey());mutable.addScenarioElement(e.getKey(),e.getValue());}throw failure;}
 }
 private static Map<String,Object> canonical(Carrier c) {
  var services=new TreeMap<String,Object>();for(var s:c.getServices().values())services.put(s.getId().toString(),Map.of("link",s.getServiceLinkId().toString(),"demand",s.getCapacityDemand(),"duration",s.getServiceDuration(),"windowStart",s.getServiceStaringTimeWindow().getStart(),"windowEnd",s.getServiceStaringTimeWindow().getEnd(),"attributes",value(s.getAttributes().getAsMap())));
  var fleet=new TreeMap<String,Object>();for(var v:c.getCarrierCapabilities().getCarrierVehicles().values()){var t=v.getType();var cost=t.getCostInformation();var fields=new LinkedHashMap<String,Object>();fields.put("type",t.getId().toString());fields.put("mode",t.getNetworkMode());fields.put("depot",v.getLinkId().toString());fields.put("earliest",v.getEarliestStartTime());fields.put("latest",v.getLatestEndTime());fields.put("capacity",t.getCapacity().getOther());fields.put("speed",t.getMaximumVelocity());fields.put("fixed",cost.getFixedCosts());fields.put("perMeter",cost.getCostsPerMeter());fields.put("perSecond",cost.getCostsPerSecond());fields.put("vehicleAttributes",value(v.getAttributes().getAsMap()));fields.put("typeAttributes",value(t.getAttributes().getAsMap()));fields.put("costAttributes",value(cost.getAttributes().getAsMap()));fields.put("capacityAttributes",value(t.getCapacity().getAttributes().getAsMap()));fields.put("engineAttributes",value(t.getEngineInformation().getAttributes().getAsMap()));fleet.put(v.getId().toString(),fields);}
  return Map.of("id",c.getId().toString(),"attributes",value(c.getAttributes().getAsMap()),"fleetSize",c.getCarrierCapabilities().getFleetSize().toString(),"vehicles",fleet,"services",services);
 }
 private static Object value(Object input) {
  if(input==null || input instanceof String || input instanceof Number || input instanceof Boolean)return input;
  if(input instanceof org.matsim.api.core.v01.Id<?> || input instanceof Enum<?> || input instanceof java.time.temporal.TemporalAccessor || input instanceof java.time.Duration || input instanceof UUID || input instanceof Character)return input.toString();
  if(input instanceof org.matsim.api.core.v01.Coord c){var out=new LinkedHashMap<String,Object>();out.put("x",c.getX());out.put("y",c.getY());if(c.hasZ())out.put("z",c.getZ());return out;}
  if(input instanceof hagrid.core.util.Hub h){var out=new LinkedHashMap<String,Object>();out.put("id",h.getId().toString());out.put("provider",h.getProvider());out.put("address",h.getAddress());out.put("type",h.getType());out.put("coord",value(h.getCoord()));out.put("link",value(h.getLink()));out.put("assignedSupplyDemand",h.getAssignedSupplyDemand());out.put("capacityLimit",h.getCapacityLimit());out.put("hasCapacity",h.isCapacityEnabled());out.put("whiteLabelDemand",value(h.getWLSupplyDemandByProvider()));out.put("attributes",value(h.getAttributes().getAsMap()));return out;}
  if(input instanceof Map<?,?> m){var out=new TreeMap<String,Object>();for(var e:m.entrySet()){String key=String.valueOf(e.getKey());if(out.containsKey(key))throw new IllegalArgumentException("Conflicting metadata key "+key);out.put(key,value(e.getValue()));}return out;}
  if(input instanceof Collection<?> c){var out=new ArrayList<>();for(var item:c)out.add(value(item));return out;}
  if(input.getClass().isArray()){var out=new ArrayList<>();for(int i=0;i<java.lang.reflect.Array.getLength(input);i++)out.add(value(java.lang.reflect.Array.get(input,i)));return out;}
  throw new IllegalArgumentException("Unsupported canonical companion metadata "+input.getClass().getName());
 }

}
