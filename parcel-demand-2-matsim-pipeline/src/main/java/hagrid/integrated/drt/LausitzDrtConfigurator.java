package hagrid.integrated.drt;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.contrib.vsp.scoring.RideScoringParamsFromCarParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a MATSim {@link Config} for a passenger-only Lausitz DRT run.
 *
 * <p>The native matsim-lausitz config is used as the <em>scoring base</em> (it
 * carries the calibrated mode-choice constants but, deliberately, NO activity
 * params and NO DRT). On top of it this class:</p>
 * <ol>
 *   <li>redirects network/plans IO to the clipped DRT inputs;</li>
 *   <li>configures the controller (output dir, run id, iterations);</li>
 *   <li>strips PT and freight: {@code transit} off + file refs nulled,
 *       {@code counts} input nulled (it is a remote file — must NOT be fetched),
 *       {@code pt} removed from {@code subtourModeChoice.modes}, and the
 *       {@code longDistanceFreight} mode dropped from the qsim/routing/
 *       travel-time mode sets (no freight agents in this milestone);</li>
 *   <li>adds the standard VSP typical-duration activity params + derives ride
 *       scoring from car scoring;</li>
 *   <li>composes HAGRID's full-DVRP DRT via {@link DrtConfigComposer}.</li>
 * </ol>
 *
 * <p>The native config keeps its {@code pt} scoring {@link org.matsim.core.config.groups.ScoringConfigGroup.ModeParams}
 * (PT is removed as a <em>mode</em>, but {@link DrtConfigComposer} derives the
 * {@code drt} leg ASC from the pt constant). The returned config is ready for
 * {@code ScenarioUtils.createScenario}.</p>
 *
 * @see DrtConfigComposer DRT-specific composition (reused, not reimplemented)
 */
public final class LausitzDrtConfigurator {

    /** Ride disutility multiplier on car scoring (matsim-lausitz native value). */
    private static final double RIDE_SCORING_FACTOR = 2.0;

    private LausitzDrtConfigurator() {}

    /**
     * Builds the fully-composed passenger-only DRT config.
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

        // 1) Load the native config. loadConfig only PARSES — it never fetches the
        //    remote SVN file refs (verified in the spike).
        Config config = ConfigUtils.loadConfig(baseConfigPath);

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

        // 4) Strip PT (DRT-only, no transit). Null the remote file refs so nothing is fetched.
        config.transit().setUseTransit(false);
        config.transit().setTransitScheduleFile(null);
        config.transit().setVehiclesFile(null);
        // counts is a remote file — clearing it prevents a remote fetch at scenario load.
        config.counts().setInputFile(null);
        // The native config's `vehicles` module points vehiclesFile at a remote SVN URL and
        // sets qsim.vehiclesSource=modeVehicleTypesFromVehiclesData (which REQUIRES that file).
        // A real DRT-only run must be self-contained and must NOT fetch from SVN: null the
        // vehicle-types file and fall back to the built-in `defaultVehicle` source (MATSim
        // synthesises a default car vehicle type from config). The DRT fleet vehicles come
        // from the DVRP fleet file (composeConfig), independent of this passenger-mode source.
        org.matsim.core.config.groups.VehiclesConfigGroup vehicles =
                ConfigUtils.addOrGetModule(config, org.matsim.core.config.groups.VehiclesConfigGroup.class);
        vehicles.setVehiclesFile(null);
        if (config.qsim().getVehiclesSource()
                == org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData
                || config.qsim().getVehiclesSource()
                == org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.fromVehiclesData) {
            config.qsim().setVehiclesSource(
                    org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.defaultVehicle);
        }
        // Remove pt as a CHOICE mode (keep walk/bike/ride/car). drt is added later by composeConfig.
        // NB: the pt SCORING modeParams stays — composeConfig derives the drt ASC from it.
        String[] modes = config.subtourModeChoice().getModes();
        List<String> choiceModes = new ArrayList<>();
        for (String m : modes) {
            if (!TransportMode.pt.equals(m)) {
                choiceModes.add(m);
            }
        }
        config.subtourModeChoice().setModes(choiceModes.toArray(new String[0]));

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

        return config;
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
