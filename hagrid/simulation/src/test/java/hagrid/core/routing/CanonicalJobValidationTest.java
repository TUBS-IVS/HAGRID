package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CanonicalJobValidationTest {
    @Test void staleWindowRejectsOldPlanAndSelectsFreshCandidate() {
        try (var f=new OptimizationFixture()) {
            var original=f.service("a",1,600,900); var old=f.plan(0,original);
            var current=f.service("a",1,0,100); var fresh=f.plan(0,current);
            var validation=PlanValidator.validate(f.carrier,old,f.request,f.config);
            assertThat(validation.valid()).isFalse();
            assertThat(validation.violations()).anyMatch(v -> v.contains("time window"));
            assertThat(PlanSelection.choose(old,f.result(fresh,1),f.carrier,f.request,f.config).selected()).isSameAs(fresh);
        }
    }
    @Test void equivalentDetachedServiceObjectsRemainValid() {
        try (var f=new OptimizationFixture()) {
            var original=f.service("a",1,0,500); var plan=f.plan(0,original);
            f.service("a",1,0,500);
            assertThat(PlanValidator.validate(f.carrier,plan,f.request,f.config).violations()).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"capacity","skills","duration","location"})
    void serviceReferenceContentMustMatchCanonicalJob(String changed) {
        try (var f=new OptimizationFixture()) {
            var original=f.service("a",1,0,500); var plan=f.plan(0,original);
            var canonical=CarrierService.Builder.newInstance(original.getId(),Id.createLinkId(changed.equals("location")?"road":"stop"))
                    .setCapacityDemand(changed.equals("capacity")?2:1).setServiceDuration(changed.equals("duration")?11:10)
                    .setServiceStartingTimeWindow(TimeWindow.newInstance(0,500)).build();
            if (changed.equals("skills")) CarriersUtils.setSkills(canonical,Set.of("cold"));
            f.carrier.getServices().put(canonical.getId(),canonical);
            assertThat(PlanValidator.validate(f.carrier,plan,f.request,f.config).violations()).anyMatch(v -> v.contains(changed));
        }
    }
    @ParameterizedTest @ValueSource(strings={"capacity","skills","pickup window","delivery window","pickup duration","delivery duration"})
    void bothShipmentActivitiesCheckTheWholeCurrentShipment(String changed) {
        try (var f=new OptimizationFixture()) {
            var old=shipment(changed,false); var canonical=shipment(changed,true);
            CarriersUtils.addShipment(f.carrier,canonical);
            var b=Tour.Builder.newInstance(Id.create("s",Tour.class));b.scheduleStart(f.vehicle.getLinkId());
            b.addLeg(new Tour.Leg());b.schedulePickup(old);b.addLeg(new Tour.Leg());b.scheduleDelivery(old);b.addLeg(new Tour.Leg());b.scheduleEnd(f.vehicle.getLinkId());
            var tour=b.build();
            for (var element:tour.getTourElements()) if (element instanceof Tour.ShipmentBasedActivity a)
                assertThat(CanonicalJobValidator.validate(f.carrier,a)).isNotEmpty();
        }
    }
    private static CarrierShipment shipment(String changed,boolean current) {
        var s=CarrierShipment.Builder.newInstance(Id.create("s",CarrierShipment.class),Id.createLinkId("road"),Id.createLinkId("stop"),current&&changed.equals("capacity")?2:1)
                .setPickupDuration(current&&changed.equals("pickup duration")?2:1)
                .setDeliveryDuration(current&&changed.equals("delivery duration")?2:1)
                .setPickupStartingTimeWindow(TimeWindow.newInstance(0,current&&changed.equals("pickup window")?200:100))
                .setDeliveryStartingTimeWindow(TimeWindow.newInstance(0,current&&changed.equals("delivery window")?200:100)).build();
        if(current&&changed.equals("skills"))CarriersUtils.setSkills(s,Set.of("cold")); return s;
    }
}
