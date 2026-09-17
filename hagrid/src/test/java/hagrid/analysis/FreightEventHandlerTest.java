package hagrid.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FreightEventHandler — vehicle/agent id classification")
class FreightEventHandlerTest {

    // The dedicated-LMD baseline (Lausitz) names its freight drivers/vehicles
    //   freight_<lsp>_veh_<lsp>_<vehicleTypeId>_<n>   with vehicleTypeId in {ct_cep_size_l, ct_cep_size_m}
    private static final String LMD_VAN_L = "freight_ups_veh_ups_ct_cep_size_l_2";
    private static final String LMD_VAN_M = "freight_hermes_veh_hermes_ct_cep_size_m_4";

    @Test
    @DisplayName("classifyVehicle recognises the Lausitz LMD vans as VAN")
    void classifiesLausitzLmdVansAsVan() {
        assertThat(FreightEventHandler.classifyVehicle(LMD_VAN_L))
                .isEqualTo(FreightEventHandler.VehicleType.VAN);
        assertThat(FreightEventHandler.classifyVehicle(LMD_VAN_M))
                .isEqualTo(FreightEventHandler.VehicleType.VAN);
    }

    @Test
    @DisplayName("classifyVehicle still recognises the legacy Hannover patterns (no regression)")
    void stillClassifiesHannoverPatterns() {
        assertThat(FreightEventHandler.classifyVehicle("hannover_veh_cep_van_17"))
                .isEqualTo(FreightEventHandler.VehicleType.VAN);
        assertThat(FreightEventHandler.classifyVehicle("x_veh_supply_light_van_3"))
                .isEqualTo(FreightEventHandler.VehicleType.SUPPLY_VAN);
        assertThat(FreightEventHandler.classifyVehicle("some_random_pax_agent")).isNull();
    }

    @Test
    @DisplayName("LinkLeaveEvent for a Lausitz LMD van is recorded as a vehicle tour")
    void recordsLausitzVanTour() {
        FreightEventHandler h = new FreightEventHandler();
        Id<Vehicle> v = Id.create(LMD_VAN_L, Vehicle.class);
        h.handleEvent(new LinkLeaveEvent(28800.0, v, Id.createLinkId("linkA")));
        h.handleEvent(new LinkLeaveEvent(28860.0, v, Id.createLinkId("linkB")));

        assertThat(h.getVehicleTours()).containsKey(LMD_VAN_L);
        assertThat(h.getVehicleTours().get(LMD_VAN_L)).hasSize(2);
    }

    @Test
    @DisplayName("Lausitz LMD freight driver is recognised for tour-boundary detection (isFreight)")
    void recordsLausitzTourStart() {
        FreightEventHandler h = new FreightEventHandler();
        // depot departure is the END of the leading "start" activity
        h.handleEvent(new ActivityEndEvent(28800.0, Id.create(LMD_VAN_L, Person.class),
                Id.createLinkId("depotLink"), null, "start"));

        assertThat(h.getTourStarts()).isNotEmpty();
        assertThat(h.getTourStarts().get(0).vehicleId()).isEqualTo(LMD_VAN_L);
    }
}
