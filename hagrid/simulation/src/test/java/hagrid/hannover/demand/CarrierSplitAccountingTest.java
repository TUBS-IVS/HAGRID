package hagrid.hannover.demand;

import hagrid.core.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.freight.carriers.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarrierSplitAccountingTest {
 @TempDir Path directory;
 private record Fixture(DemandModalFixture source,CarrierGenerator generator,HAGRIDSummary summary){}
 private Delivery delivery(String id,int amount,Delivery.ParcelType type){
  return Delivery.builder().id(id).coordinate(new Coord(75,10)).provider("hermes").amount(amount).parcelType(type)
   .postalCode("12345").individualWeights(new ArrayList<>(Collections.nCopies(amount,1.0)))
   .deliveryMode(Delivery.DeliveryMode.HOME).supplyDistribution(new HashMap<>()).build();
 }
 private Fixture fixture(int b2c,int b2b)throws Exception{
  var f=new DemandModalFixture(directory,"car");((org.matsim.core.scenario.MutableScenario)f.scenario).removeScenarioElement("carriers");
  f.config.setCepVehicleSizes(List.of("10_m"));f.config.routing().setCarrierMergeThreshold(1);
  f.hubs.values().forEach(h->h.setAssignedSupplyDemand(0));
  var input=new ArrayList<>(List.of(delivery("private",b2c,Delivery.ParcelType.B2C),delivery("business",b2b,Delivery.ParcelType.B2B)));
  f.scenario.addScenarioElement("deliveries",Map.of("hermes_12345_0",input));
  var summary=HAGRIDSummary.builder().totalDeliveries(2).totalB2BDeliveries(1).totalParcels(b2c+b2b).totalB2BParcels(b2b).build();
  f.scenario.addScenarioElement("summary",summary);
  Files.createDirectories(Path.of(f.config.io().vehicleTypesOutput()).getParent());
  var generator=new CarrierGenerator();DemandModalFixture.set(generator,"scenario",f.scenario);DemandModalFixture.set(generator,"hagridConfig",f.config);
  return new Fixture(f,generator,summary);
 }
 private void check(Fixture f,int parcels,int allServices,int businessServices){
  assertThatCode(f.generator::run).doesNotThrowAnyException();
  var carriers=(Carriers)f.source.scenario.getScenarioElement("carriers");
  var services=carriers.getCarriers().values().stream().flatMap(c->c.getServices().values().stream()).toList();
  assertThat(services).hasSize(allServices);
  assertThat(services.stream().mapToInt(CarrierService::getCapacityDemand).sum()).isEqualTo(parcels);
  assertThat(services.stream().filter(s->"B2B".equals(s.getAttributes().getAttribute("type").toString())).count()).isEqualTo(businessServices);
 }
 @Test void privateSplitDoesNotIncreaseExpectedBusinessServiceCount()throws Exception{check(fixture(12,2),14,3,1);}
 @Test void businessSplitStillIncreasesBothServiceCounts()throws Exception{check(fixture(2,12),14,3,2);}
 @Test void mixedSplitsAreCountedByTheirOwnType()throws Exception{check(fixture(12,12),24,4,2);}
 @Test void lateFailureRestoresEverySplitCounterAndPublishesNothing()throws Exception{
  var f=fixture(2,12);String original=f.summary.toString();
  f.source.scenario.addScenarioElement("carrierMergeLog",new hagrid.hannover.pipeline.CarrierMergeLog());
  assertThatThrownBy(f.generator::run).isInstanceOf(IllegalStateException.class);
  assertThat(f.summary.toString()).isEqualTo(original);
  assertThat(f.source.scenario.getScenarioElement("carriers")).isNull();
  assertThat(Path.of(f.source.config.io().deliveryCarriersUnrouted())).doesNotExist();
 }
 @Test void realBusinessCountMismatchStillFails()throws Exception{
  var f=fixture(2,2);f.summary.setTotalB2BDeliveries(2);
  assertThatThrownBy(f.generator::run).hasRootCauseMessage("Validation failed: Total B2B deliveries do not match. Expected 2, but got 1."+System.lineSeparator());
  assertThat(f.source.scenario.getScenarioElement("carriers")).isNull();
 }
}