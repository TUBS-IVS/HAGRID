package hagrid.integrated.drt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.Vehicles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters a MATSim transit schedule down to {@code rail} TransitRoutes only (drops bus + tram),
 * and removes transit vehicles no longer referenced by any surviving departure.
 *
 * <p>Rationale (rail-PT design, 2026-06-23): rail is kept so DRT can act as a feeder; bus is
 * dropped so DRT is a clean substitute; tram is dropped (Cottbus is out of the DRT service area).
 * Mode is read per {@link TransitRoute#getTransportMode()} (gtfs2matsim tags it per route).</p>
 *
 * <p>Stop facilities are deliberately left as a superset — SwissRailRaptor only routes over stops
 * referenced by surviving routes, and pruning them risks dangling references for no benefit.</p>
 */
public final class RailScheduleFilter {

    public static final String RAIL_MODE = "rail";

    private RailScheduleFilter() {}

    /** In-place filter: keep only rail routes + the transit vehicles they use. */
    public static void filter(TransitSchedule schedule, Vehicles transitVehicles) {
        Set<Id<Vehicle>> usedVehicles = new HashSet<>();
        List<TransitLine> linesToRemove = new ArrayList<>();

        for (TransitLine line : schedule.getTransitLines().values()) {
            List<TransitRoute> routesToRemove = new ArrayList<>();
            for (TransitRoute route : line.getRoutes().values()) {
                if (!RAIL_MODE.equals(route.getTransportMode())) {
                    routesToRemove.add(route);
                } else {
                    route.getDepartures().values()
                            .forEach(d -> usedVehicles.add(d.getVehicleId()));
                }
            }
            routesToRemove.forEach(line::removeRoute);
            if (line.getRoutes().isEmpty()) {
                linesToRemove.add(line);
            }
        }
        linesToRemove.forEach(schedule::removeTransitLine);

        // Drop transit vehicles no longer referenced by any surviving departure.
        List<Id<Vehicle>> vehiclesToRemove = new ArrayList<>();
        for (Id<Vehicle> id : transitVehicles.getVehicles().keySet()) {
            if (!usedVehicles.contains(id)) {
                vehiclesToRemove.add(id);
            }
        }
        vehiclesToRemove.forEach(transitVehicles::removeVehicle);
    }

    /** File-to-file: read schedule + transit vehicles, filter, write both. */
    public static void run(String scheduleIn, String vehiclesIn, String scheduleOut, String vehiclesOut) {
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:25832");
        config.transit().setTransitScheduleFile(scheduleIn);
        config.transit().setVehiclesFile(vehiclesIn);
        config.transit().setUseTransit(true);
        Scenario scenario = ScenarioUtils.loadScenario(config);

        filter(scenario.getTransitSchedule(), scenario.getTransitVehicles());

        new TransitScheduleWriter(scenario.getTransitSchedule()).writeFile(scheduleOut);
        new MatsimVehicleWriter(scenario.getTransitVehicles()).writeFile(vehiclesOut);
    }
}
