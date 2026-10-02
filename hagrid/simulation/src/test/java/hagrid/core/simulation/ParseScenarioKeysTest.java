package hagrid.core.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review 2026-10-02 #11: parseScenario put every token into a map and then read the keys it
 * knew. A misspelt key was therefore never read and the run took the default - 16 h on the wrong
 * value, logged but not stopped - and a key set twice silently kept its LAST value. One whole
 * comparison rested on a .bat whose BASE variable already carried fleetSize (RunMetadataWriter
 * comment). Both now fail at parse time, in seconds.
 */
@DisplayName("parseScenario: unknown and duplicate keys")
class ParseScenarioKeysTest {

    private static final String SPEC =
            "concept=drt_modular,date=2025-05-13,tag=keystest,fleetSize=130";

    @Test
    @DisplayName("a misspelt key aborts instead of running the default")
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(SPEC + ",budgetMdoe=selfref"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetMdoe")
                .hasMessageContaining("budgetMode");   // the allowed keys are listed
    }

    @Test
    @DisplayName("a key in the wrong case names the right spelling")
    void wrongCaseNamesTheKnownKey() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(SPEC + ",IdleThreshold=0.15"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did you mean 'idleThreshold'");
    }

    @Test
    @DisplayName("a key set twice aborts instead of keeping the last value")
    void duplicateKeyIsRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(SPEC + ",fleetSize=120"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fleetSize")
                .hasMessageContaining("130")
                .hasMessageContaining("120");
    }

    @Test
    @DisplayName("writeDashboard is read elsewhere but is a known key; multi-value keys still continue")
    void writeDashboardAndMultiValueKeysStillParse() {
        String spec = SPEC + ",writeDashboard=true,openDepots=hoy_sued,lauta,"
                + "freightWindows=07:00-13:00,16:45-18:30";
        assertThatCode(() -> SimulationRunnerUtils.parseScenario(spec)).doesNotThrowAnyException();
        assertThat(SimulationRunnerUtils.parseScenario(spec).getOpenDepots())
                .containsExactly("hoy_sued", "lauta");
        assertThat(SimulationRunnerUtils.extractDashboardFlags(new String[]{spec})).containsExactly(true);
    }
}
