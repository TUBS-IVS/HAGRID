package hagrid.integrated.drt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        // PT stripped
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
    }
}
