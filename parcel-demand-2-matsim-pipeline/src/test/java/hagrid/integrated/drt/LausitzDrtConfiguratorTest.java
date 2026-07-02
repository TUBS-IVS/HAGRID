package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LausitzDrtConfigurator")
class LausitzDrtConfiguratorTest {

    /** Trimmed native-like config fixture (portable; does not depend on the staged file). */
    private static String fixtureConfig() {
        URL url = LausitzDrtConfiguratorTest.class.getClassLoader()
                .getResource("lausitz-native-like.config.xml");
        assertThat(url).as("test fixture lausitz-native-like.config.xml must be on the test classpath")
                .isNotNull();
        return url.toString();
    }

    @Test
    @DisplayName("build() composes the native PtFare config (VVO Tarifzone 20 + deflated Deutschlandtarif)")
    void buildComposesPtFareConfig(@TempDir Path tmp) {
        String baseConfig = fixtureConfig();
        java.util.function.Supplier<Config> build = () -> LausitzDrtConfigurator.build(
                baseConfig, tmp.resolve("net.xml.gz").toString(), tmp.resolve("plans.xml.gz").toString(),
                tmp.resolve("area.shp").toString(), tmp.resolve("fleet.xml.gz").toString(),
                tmp.resolve("matsim").toString(), "DRT_TEST", 0);

        Config cfg = build.get();
        var fare = org.matsim.core.config.ConfigUtils.addOrGetModule(cfg,
                org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup.class);
        var zoneSets = fare.getParameterSets(org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams.SET_TYPE);
        var distSets = fare.getParameterSets(org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams.SET_TYPE);
        assertThat(zoneSets).as("VVO Tarifzone 20 fare-zone params").hasSize(1);
        assertThat(distSets).as("Deutschlandtarif distance-based params").hasSize(1);
        var vvo = (org.matsim.contrib.vsp.pt.fare.FareZoneBasedPtFareParams) zoneSets.iterator().next();
        assertThat(vvo.getFareZoneShp()).contains("vvo_tarifzone20");
        var dt = (org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams) distSets.iterator().next();
        double slopeFirst = dt.getOrCreateDistanceClassFareParams(100_000.).getFareSlope();
        assertThat(slopeFirst).isGreaterThan(0.0);
        // deflated 2024 -> 2021: strictly below the shared static's published 2024 slope
        double slope2024 = org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams
                .GERMAN_WIDE_FARE_2024.getOrCreateDistanceClassFareParams(100_000.).getFareSlope();
        assertThat(slopeFirst).isLessThan(slope2024);

        // GUARD against double deflation: the native code mutates the SHARED static
        // GERMAN_WIDE_FARE_2024 in place — building twice in one JVM (tests!) must NOT
        // deflate twice. build() has to copy the values instead of mutating the static.
        Config cfg2 = build.get();
        var dt2 = (org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams) org.matsim.core.config.ConfigUtils
                .addOrGetModule(cfg2, org.matsim.contrib.vsp.pt.fare.PtFareConfigGroup.class)
                .getParameterSets(org.matsim.contrib.vsp.pt.fare.DistanceBasedPtFareParams.SET_TYPE)
                .iterator().next();
        assertThat(dt2.getOrCreateDistanceClassFareParams(100_000.).getFareSlope())
                .as("second build must yield the SAME deflated slope (no double deflation)")
                .isEqualTo(slopeFirst);
    }

    @Test
    @DisplayName("build() produces a runnable full-DVRP DRT config (PT stripped, scoring composed)")
    void buildProducesRunnableDrtConfig(@TempDir Path tmp) {
        String baseConfig = fixtureConfig();
        String drtNet = tmp.resolve("drt_network.xml.gz").toString();
        String plans = tmp.resolve("drt_population.xml.gz").toString();
        String serviceShp = tmp.resolve("service-area.shp").toString();
        String fleet = tmp.resolve("fleet.xml.gz").toString();
        String outDir = tmp.resolve("matsim").toString();

        Config cfg = LausitzDrtConfigurator.build(
                baseConfig, drtNet, plans, serviceShp, fleet, outDir, "DRT_TEST", 0);
        // network/plans redirected
        assertThat(cfg.network().getInputFile()).isEqualTo(drtNet);
        assertThat(cfg.plans().getInputFile()).isEqualTo(plans);
        // PT stripped (DRT-only path: null rail params → transit off)
        assertThat(cfg.transit().isUseTransit()).isFalse();
        assertThat(List.of(cfg.subtourModeChoice().getModes())).doesNotContain("pt").contains("drt");
        // counts cleared (no remote fetch)
        assertThat(cfg.counts().getCountsFileName()).isNull();
        // DRT composed (full sim + fleet)
        var drt = MultiModeDrtConfigGroup.get(cfg).getModalElements().iterator().next();
        assertThat(drt.simulationType).isEqualTo(DrtConfigGroup.SimulationType.fullSimulation);
        assertThat(drt.vehiclesFile).isEqualTo(fleet);
        // activity params present (SnzActivities) — at least 'home_*' style scored
        assertThat(cfg.scoring().getActivityParams()).isNotEmpty();

        // --- BOOT-FIX LOCKS ---

        // Fix 1: simwrapper module removed.
        // The fixture config now contains a <module name="simwrapper"> block (mirroring the real
        // native config).  build() must strip it so no UnmaterializedConfigGroupChecker abort
        // occurs when the Controler is created.  Without the remove("simwrapper") call this
        // assertion would FAIL because the raw parsed config would still have the module.
        assertThat(cfg.getModules().containsKey("simwrapper"))
                .as("simwrapper config module must be removed by build() to avoid " +
                    "UnmaterializedConfigGroupChecker abort")
                .isFalse();

        // Fix 2: teleported pt router registered (DRT-only / null-rail path only).
        // The real clipped population contains legacy `pt` legs that PersonPrepareForSim re-routes
        // at iteration 0 start.  Without the addTeleportedModeParams(pt) call, no pt routing
        // module exists (transit is off) and MATSim throws UnknownModeException.  The assertion
        // verifies the entry is present; without the fix the map would not contain "pt" because
        // clearDefaultTeleportedModeParams=true in the fixture (matching the real native config)
        // means no default params survive loading.
        // NOTE: teleported-pt + pt-stripping are the DRT-only-path (null rail params) behaviour.
        //       The rail-on path (11-arg overload) does NOT add a teleported pt router — it routes
        //       pt via SwissRailRaptor using the real schedule.
        assertThat(cfg.routing().getTeleportedModeParams())
                .as("a teleported pt routing param must be registered by build() " +
                    "so legacy pt legs in the real population do not throw UnknownModeException")
                .containsKey(TransportMode.pt);

        // Fix 3: absolutise() turns relative paths into absolute ones.
        // Pass a bare filename (relative) and verify the resulting config field is absolute.
        // Without absolutise(), a relative path would be stored as-is and MATSim would later
        // resolve it against the config file's context directory — producing a doubled, non-existent
        // path.  The production code converts relative → absolute via Paths.get(p).toAbsolutePath().
        String relativeFleet = "fleet_rel.xml.gz";   // bare filename — relative by definition
        Config cfgRel = LausitzDrtConfigurator.build(
                baseConfig,
                "drt_network_rel.xml.gz",            // relative
                "drt_plans_rel.xml.gz",              // relative
                serviceShp, relativeFleet, outDir, "DRT_TEST_ABS", 0);
        assertThat(Path.of(cfgRel.network().getInputFile()).isAbsolute())
                .as("build() must absolutise the drtNetworkFile path (was relative)")
                .isTrue();
        assertThat(Path.of(cfgRel.plans().getInputFile()).isAbsolute())
                .as("build() must absolutise the plansFile path (was relative)")
                .isTrue();
        // fleet path is stored inside the DRT config group
        var drtRel = MultiModeDrtConfigGroup.get(cfgRel).getModalElements().iterator().next();
        assertThat(Path.of(drtRel.vehiclesFile).isAbsolute())
                .as("build() must absolutise the fleetFile path (was relative)")
                .isTrue();
    }

    @Test
    @DisplayName("build() with rail params enables transit PT, SwissRailRaptor and DRT intermodal access/egress")
    void buildEnablesRailPt(@TempDir Path tmp) {
        URL url = LausitzDrtConfiguratorTest.class.getClassLoader()
                .getResource("lausitz-rail-native-like.config.xml");
        assertThat(url).as("rail fixture config must be on the classpath").isNotNull();

        String railSchedule = tmp.resolve("rail-transitSchedule.xml.gz").toString();
        String railVehicles = tmp.resolve("rail-transitVehicles.xml.gz").toString();
        String vehicleTypes = tmp.resolve("lausitz-vehicle-types.xml").toString();

        Config cfg = LausitzDrtConfigurator.build(
                url.toString(),
                tmp.resolve("drt_network.xml.gz").toString(),
                tmp.resolve("plans.xml.gz").toString(),
                tmp.resolve("service-area.shp").toString(),
                tmp.resolve("fleet.xml.gz").toString(),
                railSchedule, railVehicles, vehicleTypes,
                tmp.resolve("matsim").toString(), "DRT_RAIL_TEST", 0);

        // transit on, pointing at the rail-filtered artifacts
        assertThat(cfg.transit().isUseTransit()).isTrue();
        assertThat(cfg.transit().getTransitScheduleFile()).isEqualTo(railSchedule);
        assertThat(cfg.transit().getVehiclesFile()).isEqualTo(railVehicles);

        // pt restored as a choice mode (alongside drt); no teleported pt router
        assertThat(List.of(cfg.subtourModeChoice().getModes())).contains("pt", "drt");
        assertThat(cfg.routing().getTeleportedModeParams()).doesNotContainKey("pt");

        // SwissRailRaptor intermodal access/egress configured with a drt access mode
        var srr = org.matsim.core.config.ConfigUtils.addOrGetModule(
                cfg, ch.sbb.matsim.config.SwissRailRaptorConfigGroup.class);
        assertThat(srr.isUseIntermodalAccessEgress()).isTrue();
        assertThat(srr.getIntermodalAccessEgressParameterSets().stream()
                .anyMatch(p -> "drt".equals(p.getMode())))
                .as("drt must be an intermodal access/egress mode").isTrue();

        // native vehicle-types restored
        var vehiclesCfg = org.matsim.core.config.ConfigUtils.addOrGetModule(
                cfg, org.matsim.core.config.groups.VehiclesConfigGroup.class);
        assertThat(vehiclesCfg.getVehiclesFile()).isEqualTo(vehicleTypes);
        assertThat(cfg.qsim().getVehiclesSource())
                .isEqualTo(org.matsim.core.config.groups.QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);
    }
}
