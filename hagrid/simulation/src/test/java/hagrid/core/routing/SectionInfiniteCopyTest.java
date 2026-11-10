package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionInfiniteCopyTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
 @Test void resultStoreRoundTripsInfiniteClone() {try(var f=new OptimizationFixture()){
  f.carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);var job=f.service("j",1,0,500);var scheduled=f.plan(0,job).getScheduledTours().iterator().next();
  for(var e:scheduled.getTour().getTourElements())if(e instanceof Tour.Leg l)((org.matsim.core.population.routes.NetworkRoute)l.getRoute()).setVehicleId(null);
  var clone=CarrierVehicle.Builder.newInstance(Id.createVehicleId("clone-store"),f.vehicle.getLinkId(),f.type).setEarliestStart(0).setLatestEnd(1000).build();var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(scheduled.getTour(),clone,0)));plan.setCarrier(f.carrier);
  var store=new OptimizationResultStore(directory,()->Optional.of("test-code"));store.save(f.carrier,f.result(plan,10),f.request,f.config,42);var loaded=store.load(f.carrier,f.request,f.config,42);assertThat(loaded).isPresent();assertThat(loaded.orElseThrow().plan().getScheduledTours().iterator().next().getVehicle().getId()).isEqualTo(clone.getId());
 }}
 @Test void copyRetainsInfiniteCloneIdentityAndDetachedTemplateType() {try(var f=new OptimizationFixture()){
  f.carrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
  var job=f.service("j",1,0,500);var original=f.plan(0,job).getScheduledTours().iterator().next();
  var clone=CarrierVehicle.Builder.newInstance(Id.createVehicleId("clone-1"),f.vehicle.getLinkId(),f.type).setEarliestStart(0).setLatestEnd(1000).build();
  var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(original.getTour(),clone,0)));plan.setCarrier(f.carrier);
  var copied=CarrierPlanCopies.copy(plan,f.carrier);var actual=copied.getScheduledTours().iterator().next().getVehicle();
  assertThat(actual.getId()).isEqualTo(clone.getId());assertThat(actual.getType()).isNotSameAs(f.type);assertThat(actual.getLinkId()).isEqualTo(f.vehicle.getLinkId());
  assertThat(f.carrier.getCarrierCapabilities().getCarrierVehicles()).doesNotContainKey(clone.getId());
 }}
 @Test void finiteUnknownCloneRemainsRejected(){try(var f=new OptimizationFixture()){
  var job=f.service("j",1,0,500);var original=f.plan(0,job).getScheduledTours().iterator().next();
  var clone=CarrierVehicle.Builder.newInstance(Id.createVehicleId("clone"),f.vehicle.getLinkId(),f.type).setEarliestStart(0).setLatestEnd(1000).build();
  var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(original.getTour(),clone,0)));plan.setCarrier(f.carrier);
  assertThatThrownBy(()->CarrierPlanCopies.copy(plan,f.carrier)).isInstanceOf(IllegalArgumentException.class);
 }}
}
