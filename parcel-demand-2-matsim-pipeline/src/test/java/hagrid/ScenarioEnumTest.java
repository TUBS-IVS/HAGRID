package hagrid;

import hagrid.HagridConfig.Scenario;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioEnumTest {
    @Test
    void lmdBaselineIsFreightButLausitzBound() {
        assertThat(Scenario.LMD_BASELINE.isDrt()).isFalse();
        assertThat(Scenario.LMD_BASELINE.requiresLausitz()).isTrue();
    }

    @Test
    void drtScenariosRequireLausitz() {
        assertThat(Scenario.DRT_BASELINE.requiresLausitz()).isTrue();
        assertThat(Scenario.DRT_SHAREDUSE.requiresLausitz()).isTrue();
        assertThat(Scenario.DRT_MODULAR.requiresLausitz()).isTrue();
    }

    @Test
    void hannoverConceptsDoNotRequireLausitz() {
        assertThat(Scenario.BASECASE.requiresLausitz()).isFalse();
        assertThat(Scenario.BASECASE.isDrt()).isFalse();
        assertThat(Scenario.WHITE_LABEL.requiresLausitz()).isFalse();
    }
}
