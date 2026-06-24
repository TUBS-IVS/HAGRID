package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RailScheduleFilter")
class RailScheduleFilterTest {

    /** Builds a schedule with one rail line, one bus line, one tram line; each route has one
     *  departure referencing its own vehicle. */
    private org.matsim.api.core.v01.Scenario buildScenario() {
        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory sf = schedule.getFactory();
        Vehicles vehicles = scenario.getTransitVehicles();
        VehiclesFactory vf = vehicles.getFactory();

        // two stop facilities, on dummy links
        TransitStopFacility s1 = sf.createTransitStopFacility(
                Id.create("s1", TransitStopFacility.class), new org.matsim.api.core.v01.Coord(0, 0), false);
        s1.setLinkId(Id.createLinkId("l1"));
        TransitStopFacility s2 = sf.createTransitStopFacility(
                Id.create("s2", TransitStopFacility.class), new org.matsim.api.core.v01.Coord(1000, 0), false);
        s2.setLinkId(Id.createLinkId("l2"));
        schedule.addStopFacility(s1);
        schedule.addStopFacility(s2);

        VehicleType vt = vf.createVehicleType(Id.create("railVeh", VehicleType.class));
        vehicles.addVehicleType(vt);

        addLine(schedule, sf, vehicles, vf, vt, "railLine", "rail", s1, s2, "railDep", "railV");
        addLine(schedule, sf, vehicles, vf, vt, "busLine", "bus", s1, s2, "busDep", "busV");
        addLine(schedule, sf, vehicles, vf, vt, "tramLine", "tram", s1, s2, "tramDep", "tramV");
        return scenario;
    }

    private void addLine(TransitSchedule schedule, TransitScheduleFactory sf,
                         Vehicles vehicles, VehiclesFactory vf, VehicleType vt,
                         String lineId, String mode, TransitStopFacility a, TransitStopFacility b,
                         String depId, String vehId) {
        TransitLine line = sf.createTransitLine(Id.create(lineId, TransitLine.class));
        List<TransitRouteStop> stops = List.of(
                sf.createTransitRouteStop(a, 0, 0),
                sf.createTransitRouteStop(b, 600, 600));
        TransitRoute route = sf.createTransitRoute(
                Id.create(lineId + "_r", TransitRoute.class), null, stops, mode);
        Vehicle v = vf.createVehicle(Id.createVehicleId(vehId), vt);
        vehicles.addVehicle(v);
        route.addDeparture(sf.createDeparture(Id.create(depId, Departure.class), 8 * 3600));
        route.getDepartures().values().iterator().next().setVehicleId(v.getId());
        line.addRoute(route);
        schedule.addTransitLine(line);
    }

    @Test
    @DisplayName("filter() keeps only rail lines and drops bus/tram vehicles")
    void keepsOnlyRail() {
        var scenario = buildScenario();
        TransitSchedule schedule = scenario.getTransitSchedule();
        Vehicles vehicles = scenario.getTransitVehicles();

        RailScheduleFilter.filter(schedule, vehicles);

        assertThat(schedule.getTransitLines().keySet().stream().map(Id::toString))
                .containsExactly("railLine");
        // every surviving route is rail
        assertThat(schedule.getTransitLines().values().stream()
                .flatMap(l -> l.getRoutes().values().stream())
                .allMatch(r -> RailScheduleFilter.RAIL_MODE.equals(r.getTransportMode()))).isTrue();
        // bus/tram vehicles removed, rail vehicle kept
        assertThat(vehicles.getVehicles().keySet().stream().map(Id::toString))
                .containsExactly("railV");
    }
}
