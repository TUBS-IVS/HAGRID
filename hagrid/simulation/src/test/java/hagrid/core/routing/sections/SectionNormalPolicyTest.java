package hagrid.core.routing.sections;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SectionNormalPolicyTest {
 @Test void ordinaryEntryRejectsUncertifiedFastResourcesAndFleet(){
  var f=new SectionDepartureOptimizationTest().fixture(false);var service=new hagrid.core.routing.CarrierOptimizationService();
  var serial=hagrid.core.config.ResolvedHagridConfig.Resources.defaults();var parallel=new hagrid.core.config.ResolvedHagridConfig.Resources(2,java.util.OptionalInt.empty());
  assertThatThrownBy(()->service.searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,2,serial,true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INFINITE");
  assertThatThrownBy(()->service.searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,2,parallel,true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("internalSolverThreads");
  var real=infiniteFixture();var catalog=real.catalog();var roads=real.roads();var net=real.net();
  catalog.requireUnchanged();catalog.requireSourceUnchanged(real.source());
  var zero=service.searchSections(catalog,net,roads,20,20,0,4711,0,serial,true);assertThat(zero.iterations()).isZero();assertThat(zero.solution().getUnassignedJobs()).isEmpty();assertRoutes(zero,real);
  var problem=SectionProblemFactory.build(catalog,net,roads).problem();var state=new SectionSearchState(catalog,roads,20,20,0);
  try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   assertThatThrownBy(()->SectionInsertionAdapter.configureNormal(problem,state,4711,1,2,executor,true))
     .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("routing.sectionFastRegret requires resources.internalSolverThreads=1");
  }
  assertThat(SectionInsertionAdapter.configureNormal(problem,state,4711,1,1,null,true)).isNotNull();
  var finiteProblem=SectionProblemFactory.build(f.catalog(),f.net(),f.roads()).problem();
  assertThatThrownBy(()->SectionInsertionAdapter.configureNormal(finiteProblem,state,4711,1,1,null,true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INFINITE");
  var normal=service.searchSections(catalog,net,roads,20,20,0,4711,100,serial,true);assertThat(normal.iterations()).isPositive().isLessThan(100);assertThat(normal.solution().getUnassignedJobs()).isEmpty();assertRoutes(normal,real);
  var ordinary=service.searchSections(catalog,net,roads,20,20,0,4711,2,serial,false);assertThat(ordinary.solution().getUnassignedJobs()).isEmpty();assertRoutes(ordinary,real);
  catalog.requireUnchanged();catalog.requireSourceUnchanged(real.source());

 }
 private record RealFixture(SectionCatalog catalog,org.matsim.api.core.v01.network.Network net,SectionRoadCosts roads,org.matsim.freight.carriers.Carrier source) {}
 private RealFixture infiniteFixture() {
  var net=org.matsim.core.network.NetworkUtils.createNetwork();
  var a=org.matsim.core.network.NetworkUtils.createAndAddNode(net,org.matsim.api.core.v01.Id.createNodeId("a"),new org.matsim.api.core.v01.Coord(0,0));
  var b=org.matsim.core.network.NetworkUtils.createAndAddNode(net,org.matsim.api.core.v01.Id.createNodeId("b"),new org.matsim.api.core.v01.Coord(100,0));
  org.matsim.core.network.NetworkUtils.createAndAddLink(net,org.matsim.api.core.v01.Id.createLinkId("f"),a,b,100,10,1000,1);
  var source=org.matsim.freight.carriers.CarriersUtils.createCarrier(org.matsim.api.core.v01.Id.create("c",org.matsim.freight.carriers.Carrier.class));
  var type=org.matsim.vehicles.VehicleUtils.createVehicleType(org.matsim.api.core.v01.Id.create("v",org.matsim.vehicles.VehicleType.class),"car");type.getCapacity().setOther(3);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0).setFixedCost(0.0);
  var vehicle=org.matsim.freight.carriers.CarrierVehicle.Builder.newInstance(org.matsim.api.core.v01.Id.createVehicleId("v"),org.matsim.api.core.v01.Id.createLinkId("f"),type).setEarliestStart(0).setLatestEnd(200).build();
  source.setCarrierCapabilities(org.matsim.freight.carriers.CarrierCapabilities.Builder.newInstance().addVehicle(vehicle).setFleetSize(org.matsim.freight.carriers.CarrierCapabilities.FleetSize.INFINITE).build());
  var jobs=new java.util.ArrayList<PreparedHaltCatalog.Job>();
  for(int i=0;i<2;i++){String id="j"+i;org.matsim.freight.carriers.CarriersUtils.addService(source,org.matsim.freight.carriers.CarrierService.Builder.newInstance(org.matsim.api.core.v01.Id.create(id,org.matsim.freight.carriers.CarrierService.class),org.matsim.api.core.v01.Id.createLinkId("f"),1).setServiceDuration(0).setServiceStartingTimeWindow(org.matsim.freight.carriers.TimeWindow.newInstance(0,200)).build());jobs.add(new PreparedHaltCatalog.Job(id,"f",java.util.List.of(new PreparedHaltCatalog.Approach("f",.25+.5*i)),"",""));}
  var road=new PreparedHaltCatalog.Road("a","b",100,100,java.util.List.of(new PreparedHaltCatalog.Point(0,0),new PreparedHaltCatalog.Point(100,0)));
  var split=SplitRoadNetworkBuilder.build(net,new PreparedHaltCatalog("c",java.util.Map.of("f",road),jobs,"f"));
  var catalog=SectionCatalogBuilder.build(source,split,10,160);assertThat(catalog.fingerprint()).isEqualTo(SectionCatalogBuilder.build(source,split,10,160).fingerprint());
  SectionRoadCosts costs=new SectionRoadCosts(){public SectionLeg leg(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> x,org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> y,org.matsim.vehicles.VehicleType v){return new SectionLeg(0,0,0);}public hagrid.core.routing.RoadLeg materialize(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> x,org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> y,double d,org.matsim.vehicles.Vehicle v){throw new UnsupportedOperationException();}public org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> resolveOriginalLink(org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> id){return split.children().containsKey(id)?split.children().get(id).getLast():id;}public String fingerprint(){return "zero-road-fixture";}};
  return new RealFixture(catalog,split.network(),costs,source);
 }
 private void assertRoutes(hagrid.core.routing.CarrierOptimizationService.SectionSearchResult result,RealFixture f) {
  var state=new SectionSearchState(f.catalog(),f.roads(),20,20,0);var seen=new java.util.HashSet<String>();
  for(var route:result.solution().getRoutes()) {var order=SectionSearchState.order(route);assertThat(state.evaluate(route).evaluation().feasible()).isTrue();assertThat(new SectionRouteEvaluator(f.catalog(),f.roads(),20,20,0).evaluate(order,state.vehicle(route.getVehicle()),route.getDepartureTime()).feasible()).isTrue();seen.addAll(order);}
  assertThat(seen).containsExactlyInAnyOrderElementsOf(f.catalog().sections().stream().map(DeliverySection::id).toList());
 }
 @Test void normalPolicyAllowsConstructionOnlyAndEarlyTermination(){
  var f=new SectionDepartureOptimizationTest().fixture(false);var service=new hagrid.core.routing.CarrierOptimizationService();var resources=hagrid.core.config.ResolvedHagridConfig.Resources.defaults();
  var zero=service.searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,0,resources);assertThat(zero.iterations()).isZero();assertThat(zero.solution().getUnassignedJobs()).isEmpty();
  var normal=service.searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,100,resources);assertThat(normal.iterations()).isPositive().isLessThan(100);
  var parallel=new hagrid.core.config.ResolvedHagridConfig.Resources(2,java.util.OptionalInt.empty());
  var twoThreads=service.searchSections(f.catalog(),f.net(),f.roads(),20,20,0,4711,2,parallel);assertThat(twoThreads.iterations()).isBetween(1,2);assertThat(twoThreads.solution().getUnassignedJobs()).isEmpty();
 }}



