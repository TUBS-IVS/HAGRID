package hagrid.core.config;

import hagrid.core.HagridConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class UnifiedRouteDurationTest {
    @Test void demandAndSolverHaveTheSameSevenHourDefault() {
        var config = new HagridConfig();
        assertThat(config.getMaxRouteDuration()).isEqualTo(25200);
        assertThat(config.routing().getSolverMaxRouteDurationSeconds()).isEqualTo(config.getMaxRouteDuration());
    }
    @Test void changingSolverDurationAlsoChangesDemandDuration() {
        var config = new HagridConfig();
        config.routing().setSolverMaxRouteDurationSeconds(14400);
        assertThat(config.getMaxRouteDuration()).isEqualTo(14400);
    }
    @Test void oldDemandSetterChangesTheSameSetting() {
        var config = new HagridConfig();
        config.routing().setMaxRouteDurationSeconds(10800);
        assertThat(config.routing().getSolverMaxRouteDurationSeconds()).isEqualTo(10800);
    }
    @Test void invalidOldSetterCannotBypassValidation() {
        assertThatThrownBy(() -> new HagridConfig().routing().setMaxRouteDurationSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
