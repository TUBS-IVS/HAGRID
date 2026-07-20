package hagrid.integrated.drt;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams;
import org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a MATSim {@link Config} for a Lausitz DRT run — either DRT-only (no rail)
 * or rail-PT + DRT intermodal (when rail schedule/vehicle/vehicle-types files are provided).
 *
 * <p>The native matsim-lausitz config is used as the <em>scoring base</em> (it
 * carries the calibrated mode-choice constants but, deliberately, NO activity
 * params and NO DRT). On top of it this class:</p>
 * <ol>
 *   <li>redirects network/plans IO to the clipped DRT inputs;</li>
 *   <li>configures the controller (output dir, run id, iterations);</li>
 *   <li><em>DRT-only (null rail params):</em> strips PT — transit off + file refs nulled,
 *       {@code pt} removed from {@code subtourModeChoice.modes}, and a teleported pt
 *       router added for legacy pt legs in the population.
 *       <em>Rail-on (non-null rail params):</em> enables transit with the filtered rail
 *       schedule + vehicles, keeps {@code pt} as a choice mode, configures
 *       SwissRailRaptor with DRT intermodal access/egress, and sets native vehicle-types.</li>
 *   <li>drops the {@code counts} remote file ref and the {@code simwrapper} module;</li>
 *   <li>drops the {@code longDistanceFreight} mode from qsim/routing/travelTimeCalculator;</li>
 *   <li>adds the standard VSP typical-duration activity params + derives ride
 *       scoring from car scoring;</li>
 *   <li>composes HAGRID's full-DVRP DRT via {@link DrtConfigComposer}.</li>
 * </ol>
 *
 * <p>The native config keeps its {@code pt} scoring {@link org.matsim.core.config.groups.ScoringConfigGroup.ModeParams}
 * (in DRT-only mode, PT is removed as a <em>mode</em>, but {@link DrtConfigComposer} derives the
 * {@code drt} leg ASC from the pt constant). The returned config is ready for
 * {@code ScenarioUtils.createScenario}.</p>
 *
 * @see DrtConfigComposer DRT-specific composition (reused, not reimplemented)
 */
public final class LausitzDrtConfigurator {

    /** Ride disutility multiplier on car scoring (matsim-lausitz native value). */
    private static final double RIDE_SCORING_FACTOR = 2.0;

    /** DRT access/egress radius for intermodal rail feeding (native value, metres). */
    private static final double DRT_INTERMODAL_RADIUS_M = 50_000.0;
    private static final double DRT_INTERMODAL_SEARCH_EXT_M = 1_000.0;

    /** Stop attribute set by {@code PrepareTransitSchedule.tagIntermodalStops}. */
    static final String DRT_ACCESS_ATTR = "allowDrtAccessEgress";

    private LausitzDrtConfigurator() {}

    /**
     * Builds the fully-composed passenger-only DRT config (DRT-only, no rail PT).
     *
     * <p>Delegates to the 11-arg overload with {@code null} rail params — transit is stripped,
     * a teleported pt router is added for legacy pt legs, and no SwissRailRaptor intermodality
     * is configured. All existing callers and tests continue to work unchanged.</p>
     *
     * @param baseConfigPath   native matsim-lausitz config (scoring/mode-choice base)
     * @param drtNetworkFile   network clipped to the DRT service area (drt added as a mode)
     * @param plansFile        passenger plans clipped to the service area
     * @param serviceAreaShp   DRT service-area shapefile
     * @param fleetFile        generated DVRP fleet vehicles file
     * @param outputDir        MATSim output directory
     * @param runId            run id
     * @param lastIteration    last MATSim iteration
     * @return a {@link Config} ready for {@code ScenarioUtils.createScenario}
     */
    public static Config build(String baseConfigPath, String drtNetworkFile, String plansFile,
                               String serviceAreaShp, String fleetFile,
                               String outputDir, String runId, int lastIteration) {
        return build(baseConfigPath, drtNetworkFile, plansFile, serviceAreaShp, fleetFile,
                /*railScheduleFile*/ null, /*railTransitVehiclesFile*/ null, /*vehicleTypesFile*/ null,
                outputDir, runId, lastIteration);
    }

    /**
     * Builds the fully-composed DRT config with optional rail PT intermodality.
     *
     * <p>When {@code railScheduleFile} is {@code null} or blank (DRT-only path): transit is
     * stripped, {@code pt} is removed from mode choice, and a teleported pt router is added for
     * legacy pt legs already in the population.</p>
     *
     * <p>When {@code railScheduleFile} is non-null (rail-on path): transit is enabled with the
     * given rail-filtered schedule and vehicles, {@code pt} is kept as a choice mode, native
     * vehicle-types are wired, and SwissRailRaptor intermodal access/egress is configured with
     * {@code drt} as an additional feeder mode.</p>
     *
     * @param baseConfigPath         native matsim-lausitz config (scoring/mode-choice base)
     * @param drtNetworkFile         network clipped to the DRT service area
     * @param plansFile              passenger plans clipped to the service area
     * @param serviceAreaShp         DRT service-area shapefile
     * @param fleetFile              generated DVRP fleet vehicles file
     * @param railScheduleFile       rail-filtered transit schedule (null → DRT-only)
     * @param railTransitVehiclesFile rail-filtered transit vehicles (null → DRT-only)
     * @param vehicleTypesFile       native vehicle-types file (null → defaultVehicle fallback)
     * @param outputDir              MATSim output directory
     * @param runId                  run id
     * @param lastIteration          last MATSim iteration
     * @return a {@link Config} ready for {@code ScenarioUtils.createScenario}
     */
    public static Config build(String baseConfigPath, String drtNetworkFile, String plansFile,
                               String serviceAreaShp, String fleetFile,
                               String railScheduleFile, String railTransitVehiclesFile, String vehicleTypesFile,
                               String outputDir, String runId, int lastIteration) {

        // 1) Load the native config. loadConfig only PARSES — it never fetches the
        //    remote SVN file refs (verified in the spike).
        Config config = ConfigUtils.loadConfig(baseConfigPath);

        // Absolutise the run-scoped inputs. The native base config is loaded from a FILE
        // path, so MATSim sets its context to the config file's parent directory and
        // resolves RELATIVE network/plans/shp/fleet paths against THAT directory — not the
        // CWD the preprocessor wrote them under (HagridPaths uses a relative pipeline root).
        // That mismatch yields a doubled, non-existent path (…/config/…/hagrid-output/…).
        // Absolute paths bypass context resolution entirely (cf. DrtBaselineEndToEndTest).
        drtNetworkFile = absolutise(drtNetworkFile);
        plansFile = absolutise(plansFile);
        serviceAreaShp = absolutise(serviceAreaShp);
        fleetFile = absolutise(fleetFile);
        railScheduleFile = absolutise(railScheduleFile);
        railTransitVehiclesFile = absolutise(railTransitVehiclesFile);
        vehicleTypesFile = absolutise(vehicleTypesFile);

        // 2) Redirect IO to the clipped DRT inputs.
        config.network().setInputFile(drtNetworkFile);
        config.plans().setInputFile(plansFile);

        // 3) Controller.
        config.controller().setOutputDirectory(outputDir);
        config.controller().setRunId(runId);
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(lastIteration);
        config.controller().setOverwriteFileSetting(
                OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        boolean railEnabled = railScheduleFile != null && !railScheduleFile.isBlank();

        // 4) Transit: rail on, or fully stripped (DRT-only).
        if (railEnabled) {
            config.transit().setUseTransit(true);
            config.transit().setTransitScheduleFile(railScheduleFile);
            config.transit().setVehiclesFile(railTransitVehiclesFile);
        } else {
            // DRT-only: null the remote file refs so nothing is fetched.
            config.transit().setUseTransit(false);
            config.transit().setTransitScheduleFile(null);
            config.transit().setVehiclesFile(null);
        }
        // counts is a remote file — clearing it prevents a remote fetch at scenario load.
        config.counts().setInputFile(null);

        // 4b) PT/DRT fares — replicate the native LausitzScenario fare composition so pt and
        // drt are NOT monetarily free while car pays per km (that asymmetry made drt
        // structurally over-attractive). Consumed by PtAndDrtFareModule (installed in
        // DrtConfigComposer): DRT is fared exactly like PT, chained rides pay once (upper bound).
        composePtFareConfig(config);
        // The native config carries a `simwrapper` module, but we do NOT register the
        // SimWrapper contrib (no SimWrapperConfigGroup / no dashboards in this milestone).
        // It therefore stays an UNMATERIALIZED generic group, and MATSim's
        // UnmaterializedConfigGroupChecker aborts the Controler at startup
        // ("Unmaterialized config group: simwrapper"). Drop it — it is analysis-only and
        // plays no role in the passenger DRT mobsim.
        config.getModules().remove("simwrapper");

        // 4a) Passenger vehicle source.
        org.matsim.core.config.groups.VehiclesConfigGroup vehicles =
                ConfigUtils.addOrGetModule(config, org.matsim.core.config.groups.VehiclesConfigGroup.class);
        if (vehicleTypesFile != null && !vehicleTypesFile.isBlank()) {
            // Rail-on: native vehicle-types give best inter-scenario comparability (decision locked).
            vehicles.setVehiclesFile(vehicleTypesFile);
            config.qsim().setVehiclesSource(
                    org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
        } else {
            // DRT-only fallback: the native config's `vehicles` module points vehiclesFile at a
            // remote SVN URL and sets qsim.vehiclesSource=modeVehicleTypesFromVehiclesData (which
            // REQUIRES that remote file). Null the ref and fall back to defaultVehicle (MATSim
            // synthesises a default car vehicle type from config). The DRT fleet vehicles come
            // from the DVRP fleet file (composeConfig), independent of this passenger-mode source.
            vehicles.setVehiclesFile(null);
            if (config.qsim().getVehiclesSource()
                    == org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData
                    || config.qsim().getVehiclesSource()
                    == org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.fromVehiclesData) {
                config.qsim().setVehiclesSource(
                        org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.defaultVehicle);
            }
        }

        // 4b) Choice modes: keep pt when rail is on; strip pt when DRT-only.
        // NB: the pt SCORING modeParams stays in both cases — composeConfig derives the drt ASC from it.
        String[] modes = config.subtourModeChoice().getModes();
        List<String> choiceModes = new ArrayList<>();
        for (String m : modes) {
            if (railEnabled || !TransportMode.pt.equals(m)) {
                choiceModes.add(m);
            }
        }
        config.subtourModeChoice().setModes(choiceModes.toArray(new String[0]));

        // 4c) Legacy pt legs: real routing via rail (no teleport) OR teleport hack (DRT-only).
        //     PT is removed as a CHOICE mode in DRT-only mode, but the REAL clipped population
        //     still carries pre-existing `pt` legs in its initial plans (~12k in the Hoyerswerda
        //     clip). PersonPrepareForSim re-routes every plan at iteration start and would throw
        //     TripRouter$UnknownModeException ("unregistered main mode |pt|") because no pt routing
        //     module exists once transit is disabled. A teleported pt router lets those legs route
        //     by beeline (no transit schedule needed), keeping the milestone DRT-only while not
        //     crashing on legacy pt trips. (The fixtures only had drt legs, so this only surfaces
        //     on real data.) Speed 50 km/h + beeline factor 1.3 are standard VSP teleported-pt values.
        //     In rail-on mode: SwissRailRaptor handles all pt routing — no teleport needed.
        if (!railEnabled) {
            if (!config.routing().getTeleportedModeParams().containsKey(TransportMode.pt)) {
                org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams ptTeleport =
                        new org.matsim.core.config.groups.RoutingConfigGroup.TeleportedModeParams(TransportMode.pt);
                ptTeleport.setTeleportedModeSpeed(50.0 / 3.6);
                ptTeleport.setBeelineDistanceFactor(1.3);
                config.routing().addTeleportedModeParams(ptTeleport);
            }
        }

        // 5) Drop the longDistanceFreight network mode (no freight agents in this milestone).
        //    Leave any longDistanceFreight strategysettings untouched — harmless with no such agents.
        config.qsim().setMainModes(withoutFreight(config.qsim().getMainModes()));
        config.routing().setNetworkModes(withoutFreight(config.routing().getNetworkModes()));
        config.travelTimeCalculator().setAnalyzedModes(
                Set.copyOf(withoutFreight(config.travelTimeCalculator().getAnalyzedModes())));

        // 6) Activity + ride scoring. The native config has NO activity params (verified in the
        //    spike: scoring.activityParams == 0); SnzActivities adds the standard VSP
        //    typical-duration set (config-only, no population needed). Ride scoring is derived
        //    from the car params.
        SnzActivities.addScoringParams(config);
        RideScoringParamsFromCarParams.setRideScoringParamsBasedOnCarParams(
                config.scoring(), RIDE_SCORING_FACTOR);

        // 7) Compose the full-DVRP DRT (full sim + fleet + serviceAreaBased + drt scoring/subtour/leg).
        DrtConfigComposer.composeConfig(config, serviceAreaShp, fleetFile);

        // 8) Intermodality: SwissRailRaptor walk + drt access/egress to rail stops (rail-on only).
        if (railEnabled) {
            // Must run AFTER DrtConfigComposer.composeConfig above: configureRailIntermodality reads
            // MultiModeDrtConfigGroup.getModalElements() to set maxWalkDistance on the DRT constraints set.
            configureRailIntermodality(config);
        }

        return config;
    }

    /**
     * Configures SwissRailRaptor intermodal access/egress with both walk and DRT as feeder modes.
     *
     * <p>Uses {@code LausitzScenario.setExplicitIntermodalityParamsForWalkToPt} (public static,
     * sets {@code useIntermodalAccessEgress=true} + {@code CalcLeastCostModePerStop} + walk param),
     * then adds a DRT access/egress parameter set with the native Lausitz radius values. Also
     * couples DRT {@code maxWalkDistance} to the transit router search radius so DRT acts as a
     * proper rail feeder (mirrors {@code DrtAndIntermodalityOptions.configureDrtConfig} lines
     * 140–158 verbatim).</p>
     */
    /** Consumer-price deflation 2024→2021 (Destatis VPI 119.3/103.1), as in the native scenario. */
    private static final double PT_FARE_DEFLATION_2024_TO_2021 = 1.16;

    /** VVO Tarifzone 20 (Hoyerswerda) fare shapefile, relative to the config directory. */
    private static final String VVO_FARE_ZONE_SHP =
            "./vvo_tarifzone20/v2.0_vvo_tarifzone20_hoyerswerda_utm32n.shp";

    /**
     * Replicates the native {@code LausitzScenario.prepareConfig} fare block: trips inside VVO
     * Tarifzone 20 (Hoyerswerda) pay the zone fare (prices in the shapefile), every other trip
     * pays the Deutschlandtarif 2024 deflated to 2021 prices (factor {@value #PT_FARE_DEFLATION_2024_TO_2021}).
     *
     * <p>Unlike the native code, the Deutschlandtarif values are COPIED from the shared static
     * {@code GERMAN_WIDE_FARE_2024} instead of mutated in place — mutating the static deflates
     * a second time when {@code build()} runs twice in one JVM (tests).</p>
     */
    private static void composePtFareConfig(Config config) {
        PtFareConfigGroup ptFare = ConfigUtils.addOrGetModule(config, PtFareConfigGroup.class);
        if (!ptFare.getParameterSets(FareZoneBasedPtFareParams.SET_TYPE).isEmpty()) {
            return;   // already composed (idempotent)
        }
        FareZoneBasedPtFareParams vvo20 = new FareZoneBasedPtFareParams();
        vvo20.setTransactionPartner("VVO Tarifzone 20");
        vvo20.setDescription("VVO Tarifzone 20");
        vvo20.setOrder(1);
        vvo20.setFareZoneShp(VVO_FARE_ZONE_SHP);

        DistanceBasedPtFareParams germany = new DistanceBasedPtFareParams();
        germany.setTransactionPartner("Deutschlandtarif");
        germany.setDescription("Deutschlandtarif");
        germany.setOrder(2);
        germany.setMinFare(DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024.getMinFare());
        DistanceBasedPtFareParams.GERMAN_WIDE_FARE_2024.getDistanceClassFareParams()
                .forEach((maxDist, cls) -> {
                    DistanceBasedPtFareParams.DistanceClassLinearFareFunctionParams copy =
                            germany.getOrCreateDistanceClassFareParams(maxDist);
                    copy.setFareSlope(cls.getFareSlope() / PT_FARE_DEFLATION_2024_TO_2021);
                    copy.setFareIntercept(cls.getFareIntercept() / PT_FARE_DEFLATION_2024_TO_2021);
                });

        ptFare.addParameterSet(vvo20);
        ptFare.addParameterSet(germany);
    }

    private static void configureRailIntermodality(Config config) {
        ch.sbb.matsim.config.SwissRailRaptorConfigGroup srr =
                ConfigUtils.addOrGetModule(config, ch.sbb.matsim.config.SwissRailRaptorConfigGroup.class);

        // Native walk intermodality (reuse the matsim-lausitz helper, sets useIntermodalAccessEgress=true
        // + CalcLeastCostModePerStop + a walk access/egress param). Idempotent if already set.
        if (!srr.isUseIntermodalAccessEgress()) {
            org.matsim.run.scenarios.LausitzScenario.setExplicitIntermodalityParamsForWalkToPt(srr);
        }

        // Add drt as an intermodal access/egress mode, gated to tagged stops.
        boolean hasDrt = srr.getIntermodalAccessEgressParameterSets().stream()
                .anyMatch(p -> TransportMode.drt.equals(p.getMode()));
        if (!hasDrt) {
            ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet drtParam =
                    new ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
            drtParam.setMode(TransportMode.drt);
            drtParam.setInitialSearchRadius(DRT_INTERMODAL_RADIUS_M);
            drtParam.setMaxRadius(DRT_INTERMODAL_RADIUS_M);
            drtParam.setSearchExtensionRadius(DRT_INTERMODAL_SEARCH_EXT_M);
            drtParam.setStopFilterAttribute(DRT_ACCESS_ATTR);
            drtParam.setStopFilterValue("true");
            srr.addIntermodalAccessEgress(drtParam);
        }

        // Native couples DRT maxWalkDistance to the transit search radius (drt is a pt feeder).
        double searchRadius = ConfigUtils.addOrGetModule(
                config, org.matsim.pt.config.TransitRouterConfigGroup.class).getSearchRadius();
        for (org.matsim.contrib.drt.run.DrtConfigGroup drt :
                org.matsim.contrib.drt.run.MultiModeDrtConfigGroup.get(config).getModalElements()) {
            var set = (org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl)
                    drt.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet();
            set.maxWalkDistance = searchRadius;
        }
    }

    /**
     * Returns an absolute path string for {@code path}. Null/blank is returned unchanged
     * (so an intentionally-cleared file ref stays cleared). Absolute inputs avoid MATSim
     * resolving them against the loaded config file's directory.
     */
    private static String absolutise(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        return java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString();
    }

    /** The freight subpopulation network mode dropped in this milestone. */
    private static final String LONG_DISTANCE_FREIGHT = "longDistanceFreight";

    /** Returns the modes with {@code longDistanceFreight} removed (order preserved). */
    private static List<String> withoutFreight(java.util.Collection<String> modes) {
        List<String> kept = new ArrayList<>();
        for (String m : modes) {
            if (!LONG_DISTANCE_FREIGHT.equals(m)) {
                kept.add(m);
            }
        }
        return kept;
    }
}
