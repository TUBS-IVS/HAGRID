package hagrid.hannover;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigFile;
import hagrid.core.config.SectionSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class HagridV2RoutingRunnerTest {
    @TempDir Path root;

    @Test void buildsExactlyOneRoutingScenarioFromTheCentralProfile() throws Exception {
        Path file = Files.writeString(root.resolve("v2.properties"),
                "run.concept=basecase\nrun.date=2025-05-13\nrun.tag=test\n"
                + "routing.sectionHagridInputs=auto\nrouting.sectionMaxSpanMeters=400\n"
                + "routing.sectionAllowStreetCrossing=true\n"
                + "tourRules.uTurnPenaltyCost=0.05\noptimization.initialIterations=7\n");
        var scenario = HagridV2RoutingRunner.scenario(file);
        assertThat(scenario.getConcepts()).containsExactly("basecase");
        assertThat(scenario.getDates()).containsExactly(LocalDate.of(2025, 5, 13));
        assertThat(scenario.getTag()).isEqualTo("test");
        assertThat(scenario.getPipelineSettings().isRunRouting()).isTrue();
        assertThat(scenario.getPipelineSettings().isCachingEnabled()).isFalse();
        var config = new HagridConfig();
        var runner = new hagrid.hannover.pipeline.ScenarioRunner(scenario, root);
        var apply = runner.getClass().getDeclaredMethod("applyScenarioConfig", HagridConfig.class,
                String.class, LocalDate.class, hagrid.hannover.pipeline.CacheConfig.class);
        apply.setAccessible(true);
        apply.invoke(runner, config, "basecase", scenario.getDates().getFirst(), null);
        assertThat(config.routing().getJspritIterations()).isEqualTo(7);
        assertThat(config.routing().getUTurnPenaltyCost()).isEqualTo(0.05);
        var sections = SectionSettings.from(config.getAdditionalConfigurationValues());
        assertThat(sections.maxSpanMeters()).contains(400.0);
        assertThat(config.getAdditionalConfigurationValues()).containsEntry("routing.sectionAllowStreetCrossing", "true");
    }

    @Test void shippedProfileHasExplicitSearchAndTurnSettings() {
        var config = new HagridConfig();
        HagridConfigFile.load(Path.of("../../config/hagrid2-sections.properties")).applyTo(config);
        assertThat(config.routing().getJspritIterations()).isEqualTo(100);
        assertThat(config.routing().getUTurnPenaltyCost()).isEqualTo(0.1);
        assertThat(SectionSettings.from(config.getAdditionalConfigurationValues()).maxSpanMeters()).contains(200.0);
        assertThat(config.getAdditionalConfigurationValues()).containsEntry("routing.sectionAllowStreetCrossing", "false");
    }

    @Test void requiresExplicitDateAndSingleConcept() throws Exception {
        Path file = root.resolve("v2.properties");
        Files.writeString(file, "run.concept=basecase\n");
        assertThatThrownBy(() -> HagridV2RoutingRunner.scenario(file)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(file, "run.concept=basecase,other\nrun.date=2025-05-13\n");
        assertThatThrownBy(() -> HagridV2RoutingRunner.scenario(file)).isInstanceOf(IllegalArgumentException.class);
    }
}
