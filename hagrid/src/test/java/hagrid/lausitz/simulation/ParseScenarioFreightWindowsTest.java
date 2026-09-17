package hagrid.lausitz.simulation;

import hagrid.core.simulation.HAGRIDSimulationConfig;
import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.lausitz.modular.Modular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code freightWindows} became a second multi-value key on 2026-09-01, when arm B of the
 * short-tour ladder needed TWO dispatch windows in one scenario string. Before that the
 * tokenizer only continued {@code openDepots}, so {@code freightWindows=07:00-13:00,16:45-18:30}
 * died with {@code "Invalid token: 16:45-18:30"} - loudly, but it died.
 *
 * <p>The point of these tests is not that two windows parse; it is that widening the
 * continuation rule did NOT widen it to everything else. A bare token after {@code tag=} must
 * still fail, and a leading bare token (lastKey still null) must still produce the ordinary
 * message rather than an NPE out of {@code Set.of(...).contains(null)}.
 */
@DisplayName("parseScenario: freightWindows as a second multi-value key (2026-09-01)")
class ParseScenarioFreightWindowsTest {

    private static final String BASE =
            "concept=DRT_MODULAR,date=2025-05-13,maxIter=1,jspritIter=1,tag=win,";

    @Test
    @DisplayName("omitted freightWindows means always-open, i.e. the empty list")
    void defaultsToAlwaysOpen() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + "fleetSize=130")
                .getFreightWindows()).isEmpty();
    }

    @Test
    @DisplayName("a single window parses to one half-open interval in seconds after midnight")
    void singleWindow() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + "freightWindows=07:00-13:00")
                .getFreightWindows())
                .containsExactly(new Modular.DispatchWindow(7 * 3600.0, 13 * 3600.0));
    }

    @Test
    @DisplayName("TWO windows survive the comma that also delimits scenario tokens")
    void twoWindowsAcrossTheTokenDelimiter() {
        assertThat(SimulationRunnerUtils.parseScenario(
                        BASE + "freightWindows=07:00-13:00,16:45-18:30,fleetSize=130")
                        .getFreightWindows())
                .containsExactly(
                        new Modular.DispatchWindow(7 * 3600.0, 13 * 3600.0),
                        new Modular.DispatchWindow(16 * 3600.0 + 45 * 60.0, 18 * 3600.0 + 30 * 60.0));
    }

    @Test
    @DisplayName("the key AFTER a two-window value is still parsed as a key, not swallowed")
    void continuationDoesNotSwallowTheFollowingKey() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                BASE + "freightWindows=07:00-13:00,16:45-18:30,fleetSize=130,maxConcurrentFreight=25");
        assertThat(cfg.getFleetSize()).isEqualTo(130);
        assertThat(cfg.getMaxConcurrentFreight()).isEqualTo(25);
        assertThat(cfg.getFreightWindows()).hasSize(2);
    }

    @Test
    @DisplayName("a bare token after an UNRELATED key still fails loudly")
    void bareTokenAfterUnrelatedKeyStillRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(
                "concept=DRT_MODULAR,date=2025-05-13,tag=win,16:45-18:30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid token");
    }

    @Test
    @DisplayName("a LEADING bare token reports Invalid token, not an NPE from Set.contains(null)")
    void leadingBareTokenIsNotAnNpe() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario("16:45-18:30,concept=DRT_MODULAR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid token");
    }
}
