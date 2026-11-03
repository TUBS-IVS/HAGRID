package hagrid.core.routing;

import static org.assertj.core.api.Assertions.assertThat;

import hagrid.core.util.LogCapture;

import org.junit.jupiter.api.Test;
import org.matsim.core.network.NetworkUtils;

/**
 * Final review M1: the in-loop re-router builds one constraint per carrier per replanning
 * iteration, so the "active" INFO line may appear only once per penalty value and JVM.
 */
class UTurnSoftConstraintLoggingTest {

    @Test
    void infoOnlyOncePerPenaltyValue() {
        // value unique to this test, so other tests in the same JVM cannot have announced it
        double penalty = 7.0312517;
        try (LogCapture log = LogCapture.of(UTurnSoftConstraint.class)) {
            for (int i = 0; i < 5; i++) {
                new UTurnSoftConstraint(NetworkUtils.createNetwork(), penalty);
            }
            assertThat(log.infos()).filteredOn(m -> m.contains("penalty = " + penalty)).hasSize(1);
        }
        assertThat(UTurnSoftConstraint.announced(penalty)).isTrue();
        assertThat(new UTurnSoftConstraint(NetworkUtils.createNetwork(), penalty).penaltyCost()).isEqualTo(penalty);
    }
}
