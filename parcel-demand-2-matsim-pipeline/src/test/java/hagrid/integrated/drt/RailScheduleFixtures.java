package hagrid.integrated.drt;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared test-only helper: builds and writes a minimal raw transit schedule
 * containing one rail line and one bus line, plus the corresponding transit vehicles.
 *
 * <p>Used by {@link LausitzDrtPreprocessorTest} to drive the rail-filtering path.</p>
 */
final class RailScheduleFixtures {

    private RailScheduleFixtures() {}

    /**
     * Writes a minimal schedule + transit-vehicles pair to {@code scheduleOut} and
     * {@code vehiclesOut}. The schedule contains:
     * <ul>
     *   <li>One {@code rail} transit line ("fixtureRailLine") with one route and one departure.</li>
     *   <li>One {@code bus} transit line ("fixtureBusLine") with one route and one departure.</li>
     * </ul>
     * Vehicle types have their {@code networkMode} set so MATSim does not NPE on load.
     *
     * @param scheduleOut path to write the raw transit schedule XML (may be .gz)
     * @param vehiclesOut path to write the raw transit vehicles XML (may be .gz)
     */
    static void writeRailAndBus(Path scheduleOut, Path vehiclesOut) {
        var cfg = ConfigUtils.createConfig();
        cfg.global().setCoordinateSystem("EPSG:25832");
        var scenario = ScenarioUtils.createScenario(cfg);

        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory sf = schedule.getFactory();
        Vehicles vehicles = scenario.getTransitVehicles();
        VehiclesFactory vf = vehicles.getFactory();

        // Two stop facilities on dummy links.
        TransitStopFacility s1 = sf.createTransitStopFacility(
                Id.create("s1", TransitStopFacility.class), new Coord(0, 0), false);
        s1.setLinkId(Id.createLinkId("dummy1"));
        TransitStopFacility s2 = sf.createTransitStopFacility(
                Id.create("s2", TransitStopFacility.class), new Coord(1000, 0), false);
        s2.setLinkId(Id.createLinkId("dummy2"));
        schedule.addStopFacility(s1);
        schedule.addStopFacility(s2);

        // Rail vehicle type — networkMode must be set or MATSim NPEs on load.
        VehicleType railType = vf.createVehicleType(Id.create("fixture_rail_type", VehicleType.class));
        railType.setNetworkMode("rail");
        vehicles.addVehicleType(railType);

        // Bus vehicle type.
        VehicleType busType = vf.createVehicleType(Id.create("fixture_bus_type", VehicleType.class));
        busType.setNetworkMode("bus");
        vehicles.addVehicleType(busType);

        // Rail line.
        addLine(schedule, sf, vehicles, vf, railType,
                "fixtureRailLine", "rail", s1, s2,
                "railDep1", "fixture_rail_veh_1");

        // Bus line.
        addLine(schedule, sf, vehicles, vf, busType,
                "fixtureBusLine", "bus", s1, s2,
                "busDep1", "fixture_bus_veh_1");

        new TransitScheduleWriter(schedule).writeFile(scheduleOut.toString());
        new MatsimVehicleWriter(vehicles).writeFile(vehiclesOut.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void addLine(TransitSchedule schedule, TransitScheduleFactory sf,
                                Vehicles vehicles, VehiclesFactory vf, VehicleType vt,
                                String lineId, String mode,
                                TransitStopFacility a, TransitStopFacility b,
                                String depId, String vehId) {
        TransitLine line = sf.createTransitLine(Id.create(lineId, TransitLine.class));
        List<TransitRouteStop> stops = List.of(
                sf.createTransitRouteStop(a, 0, 0),
                sf.createTransitRouteStop(b, 600, 600));
        TransitRoute route = sf.createTransitRoute(
                Id.create(lineId + "_r", TransitRoute.class), null, stops, mode);

        Vehicle v = vf.createVehicle(Id.createVehicleId(vehId), vt);
        vehicles.addVehicle(v);

        Departure dep = sf.createDeparture(Id.create(depId, Departure.class), 8 * 3600);
        dep.setVehicleId(v.getId());
        route.addDeparture(dep);
        line.addRoute(route);
        schedule.addTransitLine(line);
    }
}
