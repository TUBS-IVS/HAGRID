package hagrid.integrated.drt;

import hagrid.integrated.PopulationClipper;
import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.utils.GeoUtils;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.utils.gis.GeoFileReader;
import org.matsim.run.prepare.PrepareNetwork;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Produces the three run-scoped DRT input files for a passenger-only Lausitz DRT run:
 * <ol>
 *   <li>DRT-augmented network — full network with {@code drt} added to car links inside
 *       the service area, after {@code MultimodalNetworkCleaner(drt)};</li>
 *   <li>Clipped passenger population — persons whose home is inside the service area,
 *       {@code person} subpopulation only (freight agents dropped);</li>
 *   <li>DVRP fleet file — generated with the configured fleet size.</li>
 * </ol>
 *
 * <p>Uses {@link org.matsim.run.prepare.PrepareNetwork#prepareDrtNetwork} (matsim-lausitz
 * native helper) to add {@code drt} to the full network in place, so the full network
 * link count is preserved.</p>
 */
public final class LausitzDrtPreprocessor {

    private LausitzDrtPreprocessor() {}

    /**
     * Core entry point: produces drt-network, clipped plans and fleet from explicit paths.
     *
     * @param rawNetwork     path to the full (un-clipped) MATSim network XML
     * @param rawPlans       path to the full passenger plans XML
     * @param serviceAreaShp path to the DRT service-area shapefile
     * @param drtNetworkOut  output path for the drt-augmented network
     * @param plansOut       output path for the clipped person plans
     * @param fleetOut       output path for the DVRP fleet file
     * @param fleetSize      number of DRT vehicles
     * @param capacity       seating capacity per vehicle
     * @param serviceBegin   service-window start (seconds from midnight)
     * @param serviceEnd     service-window end   (seconds from midnight)
     */
    public static void run(String rawNetwork, String rawPlans, String serviceAreaShp,
                           String drtNetworkOut, String plansOut, String fleetOut,
                           int fleetSize, int capacity, double serviceBegin, double serviceEnd) {

        // 1. Read the full network.
        var net = NetworkUtils.readNetwork(rawNetwork);

        // 2. Add drt mode to car links inside the service area (FULL net preserved).
        //    PrepareNetwork.prepareDrtNetwork mutates `net` in place:
        //    - adds "drt" to car links whose endpoint nodes lie inside the shape,
        //    - then runs MultimodalNetworkCleaner(drt) on the same network.
        PrepareNetwork.prepareDrtNetwork(net, serviceAreaShp);
        new NetworkWriter(net).write(drtNetworkOut);

        // 3. Load the service-area geometry for population clipping.
        var area = GeoUtils.getBoundaryGeometry(GeoFileReader.getAllFeatures(serviceAreaShp));

        // 4. Read the full population.
        var pop = PopulationUtils.readPopulation(rawPlans);

        // 5. Clip to service area, then keep only "person" subpopulation.
        var clipped = PopulationClipper.clip(pop, area);
        List<Person> toRemove = new ArrayList<>();
        for (Person person : clipped.getPersons().values()) {
            String subpop = PopulationUtils.getSubpopulation(person);
            if (!"person".equals(subpop)) {
                toRemove.add(person);
            }
        }
        toRemove.forEach(p -> clipped.removePerson(p.getId()));
        PopulationUtils.writePopulation(clipped, plansOut);

        // 6. Build a drt-only subnetwork view so vehicles are anchored only on drt-mode links.
        //    The full network is already written to drtNetworkOut above (D3 — no clip).
        Network drtSubNet = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(net).filter(drtSubNet, Set.of(TransportMode.drt));
        DrtFleetGenerator.write(drtSubNet, fleetSize, capacity, serviceBegin, serviceEnd, Path.of(fleetOut));
    }

    /**
     * Convenience overload: binds all paths and parameters from a {@link HAGRIDSimulationConfig}.
     * Creates the run output directory before writing.
     *
     * @param cfg scenario configuration
     * @throws IOException if the run directory cannot be created
     */
    public static void run(HAGRIDSimulationConfig cfg) throws IOException {
        // The three DRT inputs are written under the run directory
        // (hagrid-output/{RUN_ID}/), which is the PARENT of getDrtNetworkClipped() —
        // NOT cfg.getOutputDirectory() (that is the deeper hagrid-matsim-output/
        // {RUN_ID}_iter.._jsprit.. dir, created later by the Controler). Create the
        // directory that actually holds the output files, or NetworkWriter fails with
        // FileNotFoundException ("path not found").
        Path runDir = Path.of(cfg.getDrtNetworkClipped()).getParent();
        if (runDir != null) {
            Files.createDirectories(runDir);
        }
        run(
                cfg.getLausitzNetworkRaw(),
                cfg.getPassengerPlansRaw(),
                cfg.getDrtServiceAreaShapefile(),
                cfg.getDrtNetworkClipped(),
                cfg.getPassengerPlansClipped(),
                cfg.getDrtFleetFile(),
                cfg.getFleetSize(),
                8,
                0.0,
                86400.0
        );
    }
}
