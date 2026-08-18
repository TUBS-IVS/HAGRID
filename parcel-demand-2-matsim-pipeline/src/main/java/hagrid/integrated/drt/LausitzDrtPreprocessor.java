package hagrid.integrated.drt;

import hagrid.HagridConfig;
import hagrid.integrated.DeliveryDistrictBuilder;
import hagrid.integrated.PopulationClipper;
import hagrid.integrated.freight.LausitzFreightPreprocessor;
import hagrid.integrated.freight.LmdDemandReader;
import hagrid.integrated.shareduse.ParcelAgentGenerator;
import hagrid.simulation.HAGRIDSimulationConfig;
import hagrid.utils.GeoUtils;
import hagrid.utils.demand.Delivery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
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
import java.util.Map;
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

    private static final Logger LOG = LogManager.getLogger(LausitzDrtPreprocessor.class);

    private LausitzDrtPreprocessor() {}

    /**
     * Core entry point: produces drt-network, clipped plans and fleet from explicit paths.
     *
     * @param rawNetwork     path to the full (un-clipped) MATSim network XML
     * @param rawPlans       path to the full passenger plans XML
     * @param serviceAreaShp path to the DRT service-area shapefile
     * @param depotCsv       path to the LMD depot CSV ({@code provider;x;y} header, one depot per row)
     * @param drtNetworkOut  output path for the drt-augmented network
     * @param plansOut       output path for the clipped person plans
     * @param fleetOut       output path for the DVRP fleet file
     * @param fleetSize      number of DRT vehicles
     * @param capacity       seating capacity per vehicle
     * @param serviceBegin   service-window start (seconds from midnight)
     * @param serviceEnd     service-window end   (seconds from midnight)
     */
    public static void run(String rawNetwork, String rawPlans, String serviceAreaShp, String depotCsv,
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
        List<Coord> depots = DrtDepotReader.readCoords(Path.of(depotCsv));
        DrtFleetGenerator.writeFromDepots(drtSubNet, depots, fleetSize, capacity,
                serviceBegin, serviceEnd, Path.of(fleetOut));
    }

    /**
     * Extended entry point: produces drt-network, clipped plans and fleet (via the 10-arg
     * overload), and additionally writes the rail-only filtered transit schedule + transit
     * vehicles when a raw schedule path is supplied.
     *
     * <p>Callers that do not need the rail artifacts can pass {@code null} (or a blank string)
     * for {@code rawSchedule}; in that case the schedule-filtering step is skipped and the
     * existing 10-arg {@code run(...)} behaviour is preserved.</p>
     *
     * @param rawNetwork        path to the full (un-clipped) MATSim network XML
     * @param rawPlans          path to the full passenger plans XML
     * @param serviceAreaShp    path to the DRT service-area shapefile
     * @param depotCsv          path to the LMD depot CSV ({@code provider;x;y} header)
     * @param drtNetworkOut     output path for the drt-augmented network
     * @param plansOut          output path for the clipped person plans
     * @param fleetOut          output path for the DVRP fleet file
     * @param rawSchedule       path to the raw (unfiltered) MATSim transit schedule XML,
     *                          or {@code null} / blank to skip rail filtering
     * @param rawTransitVehicles path to the raw transit vehicles XML
     * @param railScheduleOut   output path for the rail-only filtered transit schedule
     * @param railVehiclesOut   output path for the rail-only filtered transit vehicles
     * @param fleetSize         number of DRT vehicles
     * @param capacity          seating capacity per vehicle
     * @param serviceBegin      service-window start (seconds from midnight)
     * @param serviceEnd        service-window end   (seconds from midnight)
     */
    public static void run(String rawNetwork, String rawPlans, String serviceAreaShp, String depotCsv,
                           String drtNetworkOut, String plansOut, String fleetOut,
                           String rawSchedule, String rawTransitVehicles,
                           String railScheduleOut, String railVehiclesOut,
                           int fleetSize, int capacity, double serviceBegin, double serviceEnd) {

        // network + population + fleet exactly as before
        run(rawNetwork, rawPlans, serviceAreaShp, depotCsv, drtNetworkOut, plansOut, fleetOut,
                fleetSize, capacity, serviceBegin, serviceEnd);

        // rail-only transit artifacts (skip if no schedule supplied — DRT-only path)
        if (rawSchedule != null && !rawSchedule.isBlank()) {
            RailScheduleFilter.run(rawSchedule, rawTransitVehicles, railScheduleOut, railVehiclesOut);
        }
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

        // Shared-Use (cargo hitching) repurposes 2 of the base vehicle's seats for parcel
        // volume; every other concept keeps the full base-vehicle seat count (rev. 2026-07-20 —
        // Baseline base vehicle is now 10 seats, see SharedUse.BASE_SEATS javadoc). The rule
        // lives in DrtInputsFingerprint so the staleness guard derives it from the same place.
        HagridConfig.Scenario scenario = HagridConfig.Scenario.valueOf(cfg.getConcept().toUpperCase());
        boolean sharedUse = scenario == HagridConfig.Scenario.DRT_SHAREDUSE;
        int capacity = DrtInputsFingerprint.expectedCapacity(cfg);

        run(
                cfg.getLausitzNetworkRaw(),
                cfg.getPassengerPlansRaw(),
                cfg.getDrtServiceAreaShapefile(),
                cfg.getLmdDepotCsv(),
                cfg.getDrtNetworkClipped(),
                cfg.getPassengerPlansClipped(),
                cfg.getDrtFleetFile(),
                cfg.getLausitzTransitScheduleRaw(),
                cfg.getLausitzTransitVehiclesRaw(),
                cfg.getRailScheduleFiltered(),
                cfg.getRailTransitVehiclesFiltered(),
                cfg.getFleetSize(),
                capacity,
                0.0,
                86400.0
        );

        // Shared-Use only: inject the parcel subpopulation into the just-written, person-only
        // clipped plans. Post-step (re-read + re-write) rather than threading through the 15-arg
        // overload, since the person-filter (removing every non-"person" subpopulation) lives
        // inside the 11-arg run(...) above and would otherwise strip the parcel-persons right
        // back out.
        if (sharedUse && cfg.isNoParcels()) {
            LOG.info("SHAREDUSE noParcels=true: skipping parcel injection (8-seat DRT leakage "
                    + "control for the χ→0 validation) - {} stays person-only.",
                    cfg.getPassengerPlansClipped());
        } else if (sharedUse) {
            Population pop = PopulationUtils.readPopulation(cfg.getPassengerPlansClipped());
            // cfg.getDrtNetworkClipped() is the FULL augmented network (drt added only to
            // eligible in-area links, and MultimodalNetworkCleaner(drt) may have stripped some
            // of those again for connectivity) - snapping parcels against it directly with the
            // mode-agnostic getNearestLinkExactly risks landing a parcel activity on a link DVRP
            // can't service. Filter down to the drt-only subnetwork first, mirroring the
            // existing fleet-anchoring pattern above (TransportModeNetworkFilter -> drtSubNet).
            Network fullNet = NetworkUtils.readNetwork(cfg.getDrtNetworkClipped());
            Network drtNet = NetworkUtils.createNetwork();
            new TransportModeNetworkFilter(fullNet).filter(drtNet, Set.of(TransportMode.drt));
            Geometry area = GeoUtils.getBoundaryGeometry(
                    GeoFileReader.getAllFeatures(cfg.getDrtServiceAreaShapefile()));
            Map<String, Coord> depotCoords =
                    DrtDepotReader.readBySite(Path.of(cfg.getLmdDepotCsv()));
            // Clip BEFORE districting so 1c and 1d district the identical delivery set (D9).
            List<Delivery> clipped = LausitzFreightPreprocessor.clipToArea(
                    LmdDemandReader.group(LmdDemandReader.read(cfg.getLmdDemandShapefile())), area)
                    .values().stream().flatMap(List::stream).toList();
            List<DeliveryDistrictBuilder.District> districts = DeliveryDistrictBuilder.build(
                    clipped,
                    DeliveryDistrictBuilder.selectOpenDepots(depotCoords, null),
                    Integer.MAX_VALUE);   // 1c never splits (spec D8)
            ParcelAgentGenerator.Result r = ParcelAgentGenerator.generate(
                    districts, area, drtNet, pop, 4711L);
            LOG.info("SHAREDUSE: injected {} parcel-persons ({} parcels) into {}",
                    r.personsAdded(), r.parcels(), cfg.getPassengerPlansClipped());
            PopulationUtils.writePopulation(pop, cfg.getPassengerPlansClipped());
        }

        // Record WHAT these artifacts were built from. The run id encodes only
        // CONCEPT_date[_tag], so without this a later run with a different fleetSize /
        // seat count / noParcels setting would silently reuse them; validateInputFiles()
        // compares this fingerprint and aborts on drift.
        Path fingerprint = Path.of(cfg.getDrtInputsFingerprint());
        DrtInputsFingerprint.write(cfg, fingerprint);
        LOG.info("DRT inputs fingerprint (fleetSize={}, capacity={}, noParcels={}) -> {}",
                cfg.getFleetSize(), capacity, cfg.isNoParcels(), fingerprint);
    }
}
