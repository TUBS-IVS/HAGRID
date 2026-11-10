package hagrid.core.simulation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CarrierScoringLifecycleTest {
    @Test void finishIncludesFixedCostsBeforeExport() {
        var carrier = carrierWithTour();
        var scoring = new ScoringFunctions(NetworkUtils.createNetwork(),0).createScoringFunction(carrier);
        scoring.finish();
        assertThat(scoring.getScore()).isEqualTo(-100);
        assertThat(carrier.getAttributes().getAttribute("costFix")).isEqualTo(100.0);
        assertThat(carrier.getAttributes().getAttribute("costTotal")).isEqualTo(100.0);
    }
    @Test void repeatedGetScoreAndFinishDoNotChangeCosts() {
        var carrier = carrierWithTour();
        var scoring = new ScoringFunctions(NetworkUtils.createNetwork(),0).createScoringFunction(carrier);
        var before = new LinkedHashMap<>(carrier.getAttributes().getAsMap());
        scoring.getScore(); scoring.getScore();
        assertThat(carrier.getAttributes().getAsMap()).isEqualTo(before);
        scoring.finish();
        var snapshot = new LinkedHashMap<>(carrier.getAttributes().getAsMap());
        for (int i=0;i<3;i++) { assertThat(scoring.getScore()).isEqualTo(-100); scoring.finish(); }
        assertThat(carrier.getAttributes().getAsMap()).isEqualTo(snapshot);
    }
    @Test void missingOrEmptyPlanHasZeroCosts() {
        for (boolean emptyPlan : List.of(false,true)) {
            var carrier = CarriersUtils.createCarrier(Id.create("empty",Carrier.class));
            if (emptyPlan) { var plan=new CarrierPlan(List.of()); carrier.addPlan(plan); carrier.setSelectedPlan(plan); }
            var scoring = new ScoringFunctions(NetworkUtils.createNetwork(),0).createScoringFunction(carrier);
            scoring.finish();
            assertThat(scoring.getScore()).isZero();
            assertThat(carrier.getAttributes().getAttribute("costTotal")).isEqualTo(0.0);
        }
    }
    private static Carrier carrierWithTour() {
        var carrier=CarriersUtils.createCarrier(Id.create("test",Carrier.class));
        var type=VehicleUtils.createVehicleType(Id.create("truck",VehicleType.class),"car");
        type.getCostInformation().setFixedCost(100.0);
        var vehicle=CarrierVehicle.Builder.newInstance(Id.create("v",Vehicle.class),Id.createLinkId("depot"),type).build();
        CarriersUtils.addCarrierVehicle(carrier,vehicle);
        var service=CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),Id.createLinkId("stop"),1).build();
        CarriersUtils.addService(carrier,service);
        var builder=Tour.Builder.newInstance(Id.create("tour",Tour.class));
        builder.scheduleStart(vehicle.getLinkId()); builder.addLeg(new Tour.Leg()); builder.scheduleService(service);
        builder.addLeg(new Tour.Leg()); builder.scheduleEnd(vehicle.getLinkId());
        var plan=new CarrierPlan(List.of(ScheduledTour.newInstance(builder.build(),vehicle,0)));
        carrier.addPlan(plan); carrier.setSelectedPlan(plan);
        return carrier;
    }
}
