package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.TransportMode;
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
        assertThat(drt.mode).isEqualTo(TransportMode.drt);
        assertThat(drt.simulationType).isEqualTo(DrtConfigGroup.SimulationType.fullSimulation);
        assertThat(drt.operationalScheme).isEqualTo(DrtConfigGroup.OperationalScheme.serviceAreaBased);
        assertThat(drt.stopDuration).isEqualTo(60.0);
        assertThat(drt.drtServiceAreaShapeFile).isEqualTo("input/drt/drt-service-area.shp");
        assertThat(drt.vehiclesFile).isEqualTo("out/fleet.xml");
    }

    @Test
    @DisplayName("registers dvrp network mode = drt")
    void dvrpNetworkMode() {
        Config config = ConfigUtils.createConfig();
        DrtConfigComposer.composeConfig(config, "a.shp", "f.xml");
        assertThat(DvrpConfigGroup.get(config).networkModes).containsExactly(TransportMode.drt);
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
}
