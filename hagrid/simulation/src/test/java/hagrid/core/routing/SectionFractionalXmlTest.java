package hagrid.core.routing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionFractionalXmlTest {
 @TempDir Path dir;
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"precise.xml","precise.xml.gz"})
 void preciseWriterRetainsFractionalNativeTimesAndCompanionFingerprint(String name) throws Exception {
  var f=fixture();var file=dir.resolve(name);new CarrierPlanWriter(f.carriers).writePreciseTimes(file.toString());
  var restored=new Carriers();new CarrierPlanXmlReader(restored,f.types).readFile(file.toString());
  var serviceCarrier=restored.getCarriers().get(Id.create("service-carrier",Carrier.class));
  var vehicle=serviceCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
  assertThat(vehicle.getEarliestStartTime()).isEqualTo(1.125);assertThat(vehicle.getLatestEndTime()).isEqualTo(999.875);
  var service=serviceCarrier.getServices().values().iterator().next();assertThat(service.getServiceDuration()).isEqualTo(7.625);
  assertThat(service.getServiceStaringTimeWindow().getStart()).isEqualTo(100.125);assertThat(service.getServiceStaringTimeWindow().getEnd()).isEqualTo(250.875);
  var scheduled=serviceCarrier.getSelectedPlan().getScheduledTours().iterator().next();assertThat(scheduled.getDeparture()).isEqualTo(97.5);
  var legs=scheduled.getTour().getTourElements().stream().filter(Tour.Leg.class::isInstance).map(Tour.Leg.class::cast).toList();
  assertThat(legs.getFirst().getExpectedDepartureTime()).isEqualTo(97.5);assertThat(legs.getFirst().getExpectedTransportTime()).isEqualTo(2.125);
  assertThat(legs.getLast().getExpectedDepartureTime()).isEqualTo(107.75);assertThat(legs.getLast().getExpectedTransportTime()).isEqualTo(12.375);
  assertThat(SectionBindingCompanion.executionFingerprint(serviceCarrier)).isEqualTo(SectionBindingCompanion.executionFingerprint(f.original));
  var shipment=restored.getCarriers().get(Id.create("shipment-carrier",Carrier.class)).getShipments().values().iterator().next();
  assertThat(shipment.getPickupDuration()).isEqualTo(5.125);assertThat(shipment.getDeliveryDuration()).isEqualTo(6.875);
  assertThat(shipment.getPickupStartingTimeWindow().getStart()).isEqualTo(25.125);assertThat(shipment.getPickupStartingTimeWindow().getEnd()).isEqualTo(80.875);
  assertThat(shipment.getDeliveryStartingTimeWindow().getStart()).isEqualTo(101.25);assertThat(shipment.getDeliveryStartingTimeWindow().getEnd()).isEqualTo(200.75);
 }
 @Test void preciseWriterPreservesUndefinedLegTime() throws Exception {
  var f=fixture();var leg=(Tour.Leg)f.original.getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
  leg.setDepartureTime(Double.NEGATIVE_INFINITY);var file=dir.resolve("undefined.xml");new CarrierPlanWriter(f.carriers).writePreciseTimes(file.toString());
  var restored=new Carriers();new CarrierPlanXmlReader(restored,f.types).readFile(file.toString());
  var actual=(Tour.Leg)restored.getCarriers().get(Id.create("service-carrier",Carrier.class)).getSelectedPlan().getScheduledTours().iterator().next().getTour().getTourElements().getFirst();
  assertThat(actual.getExpectedDepartureTime()).isEqualTo(Double.NEGATIVE_INFINITY);
 }
 @Test void defaultWriterRetainsLegacyTimeFormatting() throws Exception {
  var f=fixture();var file=dir.resolve("legacy.xml");new CarrierPlanWriter(f.carriers).write(file.toString());
  assertThat(Files.readString(file)).contains("end_time=\"00:01:37\"").contains("earliestStart=\"00:00:01\"").contains("serviceDuration=\"00:00:07\"");
 }
 private record Fixture(Carriers carriers,CarrierVehicleTypes types,Carrier original) {}
 private static Fixture fixture() {
  var type=VehicleUtils.createVehicleType(Id.create("van",VehicleType.class),"car");type.setMaximumVelocity(10);type.getCapacity().setOther(10);type.getCostInformation().setFixedCost(1.0).setCostsPerMeter(.01).setCostsPerSecond(.01);
  var types=new CarrierVehicleTypes();types.getVehicleTypes().put(type.getId(),type);
  var carrier=CarriersUtils.createCarrier(Id.create("service-carrier",Carrier.class));CarriersUtils.setCarrierMode(carrier,"car");
  var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("home"),type).setEarliestStart(1.125).setLatestEnd(999.875).build();CarriersUtils.addCarrierVehicle(carrier,vehicle);
  var job=CarrierService.Builder.newInstance(Id.create("j",CarrierService.class),Id.createLinkId("stop"),1).setServiceDuration(7.625).setServiceStartingTimeWindow(TimeWindow.newInstance(100.125,250.875)).build();CarriersUtils.addService(carrier,job);
  var tour=Tour.Builder.newInstance(Id.create("t",Tour.class));tour.scheduleStart(Id.createLinkId("home"));tour.addLeg(tour.createLeg(RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("home"),Id.createLinkId("stop")),97.5,2.125));tour.scheduleService(job);tour.addLeg(tour.createLeg(RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("stop"),Id.createLinkId("home")),107.75,12.375));tour.scheduleEnd(Id.createLinkId("home"));
  var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(tour.build(),vehicle,97.5)));plan.setCarrier(carrier);carrier.addPlan(plan);carrier.setSelectedPlan(plan);carrier.getAttributes().putAttribute(SectionBindingCompanion.MARKER,"fixture");
  var shipmentCarrier=CarriersUtils.createCarrier(Id.create("shipment-carrier",Carrier.class));CarriersUtils.addCarrierVehicle(shipmentCarrier,CarrierVehicle.Builder.newInstance(Id.createVehicleId("s"),Id.createLinkId("home"),type).setEarliestStart(1.125).setLatestEnd(999.875).build());
  var shipment=CarrierShipment.Builder.newInstance(Id.create("shipment",CarrierShipment.class),Id.createLinkId("home"),Id.createLinkId("stop"),1).setPickupDuration(5.125).setDeliveryDuration(6.875).setPickupStartingTimeWindow(TimeWindow.newInstance(25.125,80.875)).setDeliveryStartingTimeWindow(TimeWindow.newInstance(101.25,200.75)).build();CarriersUtils.addShipment(shipmentCarrier,shipment);
  return new Fixture(new Carriers(List.of(carrier,shipmentCarrier)),types,carrier);
 }
}

