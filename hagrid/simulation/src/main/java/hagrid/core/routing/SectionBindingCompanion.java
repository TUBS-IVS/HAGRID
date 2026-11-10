package hagrid.core.routing;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import hagrid.core.routing.sections.SectionRoadCosts;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.freight.carriers.*;

/** Checks persisted section membership, selected roads and common split network before simulation. */
public final class SectionBindingCompanion {
 public static final String MARKER="section.catalogFingerprint";
 private static final ObjectMapper JSON=new ObjectMapper();
 private SectionBindingCompanion() {}
 public static void requireStaticExecution(Carriers carriers,int firstIteration,int lastIteration) {
  if(lastIteration>0 && carriers.getCarriers().values().stream().anyMatch(c->c.getAttributes().getAttribute(MARKER)!=null))throw new IllegalArgumentException("Section replanning is unsupported; run a single full-day QSim iteration");
 }
 public static Map<String,Object> seal(Map<String,Object> data) {var result=new LinkedHashMap<>(data);result.put("digest",digest(data));return Collections.unmodifiableMap(result);}
 public static void verify(Path path,Carriers carriers) {verify(path,carriers,null,false);}
 public static void verify(Path path,Carriers carriers,Network network,boolean required) { verifyInternal(path,carriers,network,required,false); }
 public static Map<String,TurnEvidence> verifyAndRead(Path path,Carriers carriers,Network network,boolean required) {
  Objects.requireNonNull(network,"Execution network required to issue section evidence");
  return verifyInternal(path,carriers,network,required,true);
 }
 private static Map<String,TurnEvidence> verifyInternal(Path path,Carriers carriers,Network network,boolean required,boolean issue) {
  var result=new TreeMap<String,TurnEvidence>();
  var marked=new TreeMap<String,Carrier>();for(var c:carriers.getCarriers().values())if(c.getAttributes().getAttribute(MARKER)!=null)marked.put(c.getId().toString(),c);
  if(marked.isEmpty()&&!required)return Map.of();
  if(!Files.isRegularFile(path))throw new IllegalStateException("Missing section binding companion: "+path);
  try {
   JsonNode root=JSON.readTree(path.toFile());if(root==null||!root.isObject())throw new IllegalStateException("Invalid section companion");
   var ids=new TreeSet<String>();root.fieldNames().forEachRemaining(ids::add);if(!ids.equals(marked.keySet())||required&&ids.isEmpty())throw new IllegalStateException("Section companion/carrier mismatch");
   String networkHash=network==null?null:networkFingerprint(network);
   for(var entry:marked.entrySet()) {
    var doc=root.path(entry.getKey());if(!doc.isObject())throw new IllegalStateException("Missing section record "+entry.getKey());
    var raw=JSON.convertValue(doc,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});Object claimed=raw.remove("digest");
    if(!(claimed instanceof String)||!MessageDigest.isEqual(((String)claimed).getBytes(java.nio.charset.StandardCharsets.UTF_8),digest(raw).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new IllegalStateException("Changed section companion "+entry.getKey());
    var c=entry.getValue();if(!Objects.equals(c.getAttributes().getAttribute(MARKER),doc.path("catalogFingerprint").asText()))throw new IllegalStateException("Changed section catalog marker "+c.getId());
    if(networkHash!=null&&!networkHash.equals(doc.path("executionNetworkFingerprint").asText()))throw new IllegalStateException("Changed section execution network");
    if(!executionFingerprint(c).equals(doc.path("executionFingerprint").asText()))throw new IllegalStateException("Changed section execution plan "+c.getId());
    var binding=doc.path("bindings");var allowed=doc.path("allowed");var originals=doc.path("canonical").path("services");if(!binding.isObject()||!allowed.isObject()||!originals.isObject())throw new IllegalStateException("Incomplete section companion");
    var requiredIds=new TreeSet<String>();c.getServices().keySet().forEach(id->requiredIds.add(id.toString()));var actualIds=new TreeSet<String>();binding.fieldNames().forEachRemaining(actualIds::add);if(!requiredIds.equals(actualIds))throw new IllegalStateException("Section member IDs changed");
    for(var job:c.getServices().values()) {
     String id=job.getId().toString(),link=binding.path(id).asText();var original=originals.path(id);var options=allowed.path(id);
     if(!job.getServiceLinkId().toString().equals(link)||!options.isArray()||!java.util.stream.StreamSupport.stream(options.spliterator(),false).anyMatch(x->x.asText().equals(link)) || original.isMissingNode() || original.path("demand").asInt(-1)!=job.getCapacityDemand() || Double.compare(original.path("duration").asDouble(Double.NaN),job.getServiceDuration())!=0 || Double.compare(original.path("windowStart").asDouble(Double.NaN),job.getServiceStaringTimeWindow().getStart())!=0 || Double.compare(original.path("windowEnd").asDouble(Double.NaN),job.getServiceStaringTimeWindow().getEnd())!=0)throw new IllegalStateException("Changed section member "+id);
    }
    var evidence=readEvidence(doc.path("turnTransitions"),c,networkHash,readTurnPolicy(doc));if(issue)result.put(entry.getKey(),evidence);
    CanonicalJobValidator.requireConsistentPlan(c,c.getSelectedPlan());
    var seen=new HashSet<String>();for(var tour:c.getSelectedPlan().getScheduledTours())for(var e:tour.getTour().getTourElements())if(e instanceof Tour.ServiceActivity a){String id=a.getService().getId().toString();if(!seen.add(id)||!binding.path(id).asText().equals(a.getLocation().toString()))throw new IllegalStateException("Changed section activity "+id);}else if(e instanceof Tour.TourActivity)throw new IllegalStateException("Unexpected section activity");
    if(!seen.equals(requiredIds))throw new IllegalStateException("Incomplete section plan "+c.getId());
   }
  } catch(IOException|IllegalArgumentException e){throw new IllegalStateException("Cannot verify section companion: "+path,e);}
  return Collections.unmodifiableMap(result);
 }
 public static final String CONTEXT="hagrid.sectionTurnEvidence";
 public record Pair(String from,String to) implements Comparable<Pair> {
  public int compareTo(Pair other) { int c=from.compareTo(other.from);return c!=0?c:to.compareTo(other.to); }
 }
 public static final class TurnEvidence {
  private final Map<Pair,SectionRoadCosts.TurnClassification> transitions;
  private final String owner,catalog,plan,network;
  private final SectionRoadCosts.TurnPolicy turnPolicy;
  private TurnEvidence(Carrier carrier,String network,Map<Pair,SectionRoadCosts.TurnClassification> transitions,SectionRoadCosts.TurnPolicy policy) {
   this.turnPolicy=Objects.requireNonNull(policy);
   this.owner=carrier.getId().toString();this.catalog=Objects.toString(carrier.getAttributes().getAttribute(MARKER),null);this.plan=executionFingerprint(carrier);this.network=network;
   if(catalog==null)throw new IllegalArgumentException("Section marker required");
   this.transitions=Collections.unmodifiableMap(new TreeMap<>(transitions));
  }
  public void requireOwner(Carrier carrier) {
   if(!owner.equals(carrier.getId().toString())||!Objects.equals(catalog,carrier.getAttributes().getAttribute(MARKER))||!plan.equals(executionFingerprint(carrier)))throw new IllegalStateException("Section evidence carrier or plan changed");
  }
  public void requireNetwork(Network actual) { if(actual==null||!Objects.equals(network,networkFingerprint(actual)))throw new IllegalStateException("Section evidence network changed"); }
  public SectionRoadCosts.TurnPolicy turnPolicy(){return turnPolicy;}
  public SectionRoadCosts.TurnClassification classification(Id<Link> from,Id<Link> to) {
   var value=transitions.get(new Pair(from.toString(),to.toString()));
   if(value==null)throw new IllegalStateException("Uncertified executed section transition "+from+" -> "+to);
   turnPolicy.charges(value);return value;
  }
  /** Evidence classification only; tariff consumers must use chargesTransition. */
  public boolean isTurn(Id<Link> from,Id<Link> to) {return classification(from,to)==SectionRoadCosts.TurnClassification.TURN;}
  public boolean chargesTransition(Id<Link> from,Id<Link> to) {return turnPolicy.charges(classification(from,to));}
  public List<Map<String,String>> records() { var out=new ArrayList<Map<String,String>>();transitions.forEach((p,v)->out.add(Map.of("from",p.from(),"to",p.to(),"classification",v.name())));return List.copyOf(out); }
  @Override public boolean equals(Object other) { return other instanceof TurnEvidence e && owner.equals(e.owner) && catalog.equals(e.catalog) && plan.equals(e.plan) && Objects.equals(network,e.network) && turnPolicy==e.turnPolicy && transitions.equals(e.transitions); }
  @Override public int hashCode() { return transitions.hashCode(); }
 }
 private static SortedSet<Pair> selectedPairs(Carrier carrier) {
  if(carrier.getSelectedPlan()==null)throw new IllegalArgumentException("Section plan missing");
  var pairs=new TreeSet<Pair>();
  for(var tour:carrier.getSelectedPlan().getScheduledTours()) { Id<Link> previous=null;
   for(var e:tour.getTour().getTourElements()) if(e instanceof Tour.Leg l) {
    if(!(l.getRoute() instanceof NetworkRoute route))throw new IllegalArgumentException("Missing section road");
    var links=new ArrayList<Id<Link>>();links.add(route.getStartLinkId());links.addAll(route.getLinkIds());links.add(route.getEndLinkId());
    for(var id:links) { if(id==null)throw new IllegalArgumentException("Null section road"); if(previous!=null&&!previous.equals(id))pairs.add(new Pair(previous.toString(),id.toString()));previous=id; }
   }
  } return pairs;
 }
 /** A capture scope retains only the static network and its fingerprint, never road tables. */
 public static final class CaptureScope {
  private final Network network; private final String fingerprint;
  private CaptureScope(Network network) { this.network=Objects.requireNonNull(network);this.fingerprint=networkFingerprint(network); }
  public String fingerprint() { return fingerprint; }
  public TurnEvidence capture(Carrier carrier,SectionRoadCosts roads) { return captureWithFingerprint(carrier,roads,fingerprint); }
  public BoundContext bind(Map<String,TurnEvidence> evidence) { return SectionBindingCompanion.bind(network,evidence); }
 }
 public static CaptureScope captureScope(Network network) { return new CaptureScope(network); }
 public static TurnEvidence capture(Carrier carrier,SectionRoadCosts roads,Network network) { return captureScope(network).capture(carrier,roads); }
 private static TurnEvidence captureWithFingerprint(Carrier carrier,SectionRoadCosts roads,String fingerprint) {
  var values=new TreeMap<Pair,SectionRoadCosts.TurnClassification>();
  for(var p:selectedPairs(carrier)) { var value=roads.classifyTransition(Id.createLinkId(p.from()),Id.createLinkId(p.to()));if(value==null)throw new IllegalStateException("Uncertifiable section transition "+p);roads.turnPolicy().charges(value);values.put(p,value); }
  return new TurnEvidence(carrier,fingerprint,values,roads.turnPolicy());
 }
 private static SectionRoadCosts.TurnPolicy readTurnPolicy(JsonNode doc) {
  var value=doc.path("turnPolicy");if(value.isMissingNode())return SectionRoadCosts.TurnPolicy.STRICT;
  if(!value.isTextual())throw new IllegalArgumentException("Malformed section turn policy");
  return SectionRoadCosts.TurnPolicy.valueOf(value.textValue());
 }
 private static TurnEvidence readEvidence(JsonNode node,Carrier carrier,String networkHash,SectionRoadCosts.TurnPolicy policy) {
  if(!node.isArray())throw new IllegalStateException("Missing section turn transitions");
  var values=new TreeMap<Pair,SectionRoadCosts.TurnClassification>();
  for(var item:node) {
   if(!item.isObject()||item.size()!=3||!item.path("from").isTextual()||!item.path("to").isTextual()||!item.path("classification").isTextual())throw new IllegalStateException("Malformed section turn transition");
   var pair=new Pair(item.path("from").textValue(),item.path("to").textValue());
   var value=SectionRoadCosts.TurnClassification.valueOf(item.path("classification").textValue());
   if(pair.from().isEmpty()||pair.to().isEmpty()||pair.from().equals(pair.to())||(value==SectionRoadCosts.TurnClassification.UNKNOWN&&policy==SectionRoadCosts.TurnPolicy.STRICT)||values.putIfAbsent(pair,value)!=null)throw new IllegalStateException("Invalid section turn transition "+pair);
  }
  if(!values.keySet().equals(selectedPairs(carrier)))throw new IllegalStateException("Section turn transition set changed");
  return new TurnEvidence(carrier,networkHash,values,policy);
 }
 /** Bound once after final static network preparation; later checks use object identity. */
 public static final class BoundContext {
  private final Network network; private final Map<String,TurnEvidence> evidence;
  private BoundContext(Network network,Map<String,TurnEvidence> evidence) { this.network=network;this.evidence=evidence; }
  public TurnEvidence forCarrier(Carrier carrier,Network actual) {
   if(actual!=network)throw new IllegalStateException("Section evidence network instance changed");
   var value=evidence.get(carrier.getId().toString());
   if(carrier.getAttributes().getAttribute(MARKER)!=null && value==null)throw new IllegalStateException("Missing verified section turn evidence for "+carrier.getId());
   if(value!=null)value.requireOwner(carrier);return value;
  }
 }
 public static BoundContext bind(Network network,Map<String,TurnEvidence> evidence) {
  Objects.requireNonNull(network,"Execution network required");var immutable=Map.copyOf(evidence);String hash=immutable.isEmpty()?null:networkFingerprint(network);
  immutable.forEach((id,value)->{if(!id.equals(value.owner)||!Objects.equals(hash,value.network))throw new IllegalStateException("Section evidence owner or network changed");});
  return new BoundContext(network,immutable);
 }
 public static void install(Scenario scenario,Map<String,TurnEvidence> evidence) { install(scenario,bind(scenario.getNetwork(),evidence)); }
 public static void install(Scenario scenario,BoundContext binding) {
  if(scenario.getNetwork()!=binding.network)throw new IllegalStateException("Section evidence network instance changed");
  var mutable=(org.matsim.core.scenario.MutableScenario)scenario;mutable.removeScenarioElement(CONTEXT);mutable.addScenarioElement(CONTEXT,binding.evidence);
 }
 @SuppressWarnings("unchecked") public static Map<String,TurnEvidence> context(Scenario scenario) {
  Object value=scenario.getScenarioElement(CONTEXT);return value==null?Map.of():(Map<String,TurnEvidence>)value;
 }
 public static String executionFingerprint(Carrier carrier) {
  if(carrier.getSelectedPlan()==null)throw new IllegalArgumentException("Section plan missing");
  var jobs=new TreeMap<String,Object>();for(var s:carrier.getServices().values())jobs.put(s.getId().toString(),List.of(s.getServiceLinkId(),s.getCapacityDemand(),s.getServiceDuration(),s.getServiceStaringTimeWindow().getStart(),s.getServiceStaringTimeWindow().getEnd(),new TreeSet<>(CarriersUtils.getSkills(s))));
  var tours=new TreeMap<String,Object>();for(var st:carrier.getSelectedPlan().getScheduledTours()){
   var elements=new ArrayList<Object>();for(var e:st.getTour().getTourElements())if(e instanceof Tour.Leg l){if(!(l.getRoute() instanceof NetworkRoute r))throw new IllegalArgumentException("Missing section road");if(r.getVehicleId()!=null)throw new IllegalArgumentException("Section road must not fix execution vehicle ID");elements.add(List.of("leg",r.getStartLinkId(),r.getLinkIds(),r.getEndLinkId()));}else if(e instanceof Tour.ServiceActivity a)elements.add(List.of("service",a.getService().getId(),a.getLocation(),a.getDuration()));else throw new IllegalArgumentException("Unexpected section activity");
   var v=st.getVehicle();var t=v.getType();var cost=t.getCostInformation();
   Double waiting=org.matsim.vehicles.VehicleUtils.getCostsPerSecondWaiting(cost),service=org.matsim.vehicles.VehicleUtils.getCostsPerSecondInService(cost);
   var profile=Arrays.asList(t.getId(),t.getNetworkMode(),t.getMaximumVelocity(),t.getCapacity().getOther(),
     new TreeSet<>(CarriersUtils.getSkills(t)),cost.getFixedCosts(),cost.getCostsPerMeter(),cost.getCostsPerSecond(),
     waiting==null?cost.getCostsPerSecond():waiting,service==null?cost.getCostsPerSecond():service,
     t.getPcuEquivalents(),t.getLength(),t.getFlowEfficiencyFactor());
   tours.put(st.getTour().getId().toString(),List.of(v.getId(),profile,v.getLinkId(),v.getEarliestStartTime(),v.getLatestEndTime(),st.getDeparture(),elements));
  }
  return RoutingData.hashValues("section-execution-v2",carrier.getCarrierCapabilities().getFleetSize(),jobs,tours);
 }
 public static String networkFingerprint(Network network) {
  return RoutingData.hash(out->{RoutingData.write(out,"section-network-execution-v1");
   for(var n:network.getNodes().values().stream().sorted(Comparator.comparing(x->x.getId().toString())).toList()){RoutingData.write(out,Arrays.asList(n.getId(),n.getCoord().getX(),n.getCoord().getY(),n.getCoord().hasZ()?n.getCoord().getZ():null));}
   for(var l:network.getLinks().values().stream().sorted(Comparator.comparing(x->x.getId().toString())).toList()){var restrictions=NetworkUtils.getDisallowedNextLinks(l);RoutingData.write(out,Arrays.asList(l.getId(),l.getFromNode().getId(),l.getToNode().getId(),l.getLength(),l.getFreespeed(),l.getCapacity(),l.getNumberOfLanes(),new TreeSet<>(l.getAllowedModes()),l.getAttributes().getAttribute("sectionOriginalLinkId"),l.getAttributes().getAttribute("sectionOriginalFromNodeId"),l.getAttributes().getAttribute("sectionOriginalToNodeId"),restrictions==null?null:restrictions.getAsMap()));}
  });
 }
 private static String digest(Object input) {
  try {var bytes=JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(input);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Cannot hash section companion",e);}
 }
}
