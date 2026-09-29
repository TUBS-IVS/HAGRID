package hagrid.lausitz.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.drt.optimizer.rebalancing.mincostflow.MinCostFlowRebalancingStrategyParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DrtConfigComposer")
class DrtConfigComposerTest {

    @Test
    @DisplayName("composes a single full-simulation, service-area DRT mode")
    void composesDrtMode() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "input/drt/drt-service-area.shp", "out/fleet.xml");

        MultiModeDrtConfigGroup multi = MultiModeDrtConfigGroup.get(config);
        assertThat(multi.getModalElements()).hasSize(1);
        DrtConfigGroup drt = multi.getModalElements().iterator().next();
        assertThat(drt.getMode()).isEqualTo(TransportMode.drt);
        assertThat(drt.getSimulationType()).isEqualTo(DrtConfigGroup.SimulationType.fullSimulation);
        assertThat(drt.getOperationalScheme()).isEqualTo(DrtConfigGroup.OperationalScheme.serviceAreaBased);
        assertThat(drt.getStopDuration()).isEqualTo(60.0);
        assertThat(drt.getDrtServiceAreaShapeFile()).isEqualTo("input/drt/drt-service-area.shp");
        assertThat(drt.getVehiclesFile()).isEqualTo("out/fleet.xml");
    }

    @Test
    @DisplayName("registers dvrp network mode = drt")
    void dvrpNetworkMode() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        assertThat(DvrpConfigGroup.get(config).getNetworkModes()).containsExactly(TransportMode.drt);
    }

    @Test
    @DisplayName("adds drt to subtour mode choice")
    void drtInModeChoice() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        assertThat(Arrays.asList(config.subtourModeChoice().getModes())).contains(TransportMode.drt);
    }

    @Test
    @DisplayName("does NOT configure PT intermodality (DRT-only)")
    void noIntermodality() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        // SwissRailRaptor module must not have been added with intermodal access/egress
        assertThat(config.getModules()).doesNotContainKey("swissRailRaptor");
    }

    @Test
    @DisplayName("registers drt leg-mode scoring params so CharyparNagel can score DRT legs")
    void drtLegModeScoringParams() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        assertThat(config.scoring().getModes())
                .as("drt leg mode must have a ModeParams entry after composeConfig")
                .containsKey(TransportMode.drt);
    }

    @Test
    @DisplayName("configures demand-based MinCostFlow rebalancing with a square-grid zone system")
    void rebalancingAndZones() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        DrtConfigGroup drt = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();

        var rebal = drt.getRebalancingParams();
        assertThat(rebal).isPresent();
        assertThat(rebal.get().getInterval()).isEqualTo(1800);

        var strategy = rebal.get().getRebalancingStrategyParams();
        assertThat(strategy).isInstanceOf(MinCostFlowRebalancingStrategyParams.class);
        var mcf = (MinCostFlowRebalancingStrategyParams) strategy;
        assertThat(mcf.getRebalancingTargetCalculatorType())
                .isEqualTo(MinCostFlowRebalancingStrategyParams.RebalancingTargetCalculatorType.EstimatedDemand);
        assertThat(mcf.getZonalDemandEstimatorType())
                .isEqualTo(MinCostFlowRebalancingStrategyParams.ZonalDemandEstimatorType.PreviousIterationDemand);
        assertThat(mcf.getDemandEstimationPeriod()).isEqualTo(1800);

        // matsim 2025.0: the zone system + target-link selection live on RebalancingParams
        // (were a DrtZoneSystemParams param set on the DRT group in PR3552).
        assertThat(rebal.get().getTargetLinkSelection())
                .isEqualTo(org.matsim.contrib.drt.optimizer.rebalancing.RebalancingParams.TargetLinkSelection.mostCentral);
        assertThat(rebal.get().getZoneSystemParams())
                .isInstanceOf(org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams.class);
        assertThat(((org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams)
                rebal.get().getZoneSystemParams()).getCellSize()).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("a config that already carries a DRT mode is kept as is, and the skip is logged")
    void preexistingDrtModeIsKeptAndWarned() {
        Config config = ConfigUtils.createConfig();
        MultiModeDrtConfigGroup multi = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);
        DrtConfigGroup pre = new DrtConfigGroup();
        pre.setMode(TransportMode.drt);
        pre.setStopDuration(30.0);
        pre.addOrGetDrtOptimizationConstraintsParams();
        multi.addParameterSet(pre);

        try (hagrid.core.util.LogCapture log = hagrid.core.util.LogCapture.of(DrtConfigComposer.class)) {
            DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
            // the whole HAGRID composition (service area, fleet file, maxWaitTime,
            // rebalancing) is skipped here -- that must be visible in the log
            assertThat(log.warnings()).anyMatch(m -> m.contains("SKIPPED") && m.contains("drt"));
        }
        // behaviour unchanged: the pre-existing group is used, nothing of ours is written into it
        assertThat(multi.getModalElements()).hasSize(1);
        DrtConfigGroup drt = multi.getModalElements().iterator().next();
        assertThat(drt.getStopDuration()).isEqualTo(30.0);
        assertThat(drt.getVehiclesFile()).isNull();
    }

    @Test
    @DisplayName("the normal path (no DRT mode yet) composes without that warning")
    void normalPathIsQuiet() {
        Config config = ConfigUtils.createConfig();
        try (hagrid.core.util.LogCapture log = hagrid.core.util.LogCapture.of(DrtConfigComposer.class)) {
            DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
            assertThat(log.warnings()).noneMatch(m -> m.contains("SKIPPED"));
        }
    }
}
