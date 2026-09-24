package hagrid.lausitz.simulation;

import hagrid.core.simulation.SimulationRunnerUtils;
import hagrid.lausitz.modular.Modular;
import hagrid.core.simulation.HAGRIDSimulationConfig;
import hagrid.core.util.StudyArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code budgetMode} / {@code budgetSmoothing} / {@code budgetHeadroom} - the three scenario keys
 * of the self-referential capacity budget (plan 2026-09-04, Task 4).
 *
 * <p>What these tests are FOR, beyond "the keys parse":</p>
 * <ul>
 *   <li><b>The defaults are the contract.</b> {@code off} is what keeps every pre-2026-09-04 run
 *       bit-identical, and {@code budgetHeadroom = 0.15} is not a taste parameter: it equals theta,
 *       which is what makes the first budget arm a ONE-FACTOR experiment (see
 *       {@link Modular#DEFAULT_BUDGET_HEADROOM}). Changing either silently would invalidate the
 *       comparison the whole arm exists to make, so both are pinned by value here.</li>
 *   <li><b>An unknown mode must be loud.</b> A typo (say {@code budgetMode=selfrev}) that fell back
 *       to {@code off} would produce a 16 h run that looks like it used the feature and did not -
 *       exactly the silent-wrong-result class this study keeps tripping over. The message must
 *       name the offending value AND the allowed set, because "No enum constant ...BudgetMode.X"
 *       tells the user what they typed but not what to type instead.</li>
 *   <li><b>Both validation belts exist.</b> The parse helpers reject first, but a caller
 *       constructing {@link HAGRIDSimulationConfig} directly (every test and every future runner
 *       path does) must fail at WIRING time too, not on the first tick of a 16 h mobsim - so the
 *       constructor is asserted separately from {@code parseScenario}.</li>
 * </ul>
 */
@DisplayName("parseScenario: self-referential capacity-budget keys (plan 2026-09-04)")
class ParseScenarioBudgetTest {

    private static final String BASE =
            "concept=DRT_MODULAR,date=2025-05-13,maxIter=1,jspritIter=1,tag=bud,fleetSize=130";

    // ------------------------------------------------------------------ defaults

    @Test
    @DisplayName("all three keys absent: off / 5 / 0.15 (the pre-2026-09-04 behaviour)")
    void defaultsWhenAbsent() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(BASE);
        assertThat(cfg.getBudgetMode()).isEqualTo(Modular.BudgetMode.OFF);
        assertThat(cfg.getBudgetSmoothing()).isEqualTo(5);
        // 0.15 == theta. See the class javadoc: this is the one-factor-experiment default.
        assertThat(cfg.getBudgetHeadroom()).isEqualTo(0.15, within(1e-12));
    }

    @Test
    @DisplayName("the defaults are exactly the Modular constants, not a second hard-coded copy")
    void defaultsComeFromTheModularConstants() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(BASE);
        assertThat(cfg.getBudgetMode()).isEqualTo(Modular.DEFAULT_BUDGET_MODE);
        assertThat(cfg.getBudgetSmoothing()).isEqualTo(Modular.DEFAULT_BUDGET_SMOOTHING);
        assertThat(cfg.getBudgetHeadroom()).isEqualTo(Modular.DEFAULT_BUDGET_HEADROOM);
    }

    // ------------------------------------------------------------------ each key parses

    @Test
    @DisplayName("budgetMode=selfref switches the budget on; budgetMode=off is accepted explicitly")
    void budgetModeParses() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetMode=selfref").getBudgetMode())
                .isEqualTo(Modular.BudgetMode.SELFREF);
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetMode=off").getBudgetMode())
                .isEqualTo(Modular.BudgetMode.OFF);
    }

    @Test
    @DisplayName("budgetMode is case-insensitive, the studyArea convention")
    void budgetModeIsCaseInsensitive() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetMode=SELFREF").getBudgetMode())
                .isEqualTo(Modular.BudgetMode.SELFREF);
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetMode=SelfRef").getBudgetMode())
                .isEqualTo(Modular.BudgetMode.SELFREF);
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetMode=OFF").getBudgetMode())
                .isEqualTo(Modular.BudgetMode.OFF);
    }

    @Test
    @DisplayName("budgetSmoothing parses; k=1 (the most responsive, most oscillation-prone) is legal")
    void budgetSmoothingParses() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetSmoothing=3")
                .getBudgetSmoothing()).isEqualTo(3);
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetSmoothing=1")
                .getBudgetSmoothing()).isEqualTo(1);
    }

    @Test
    @DisplayName("budgetHeadroom parses, including both closed endpoints 0.0 and 1.0")
    void budgetHeadroomParses() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetHeadroom=0.20")
                .getBudgetHeadroom()).isEqualTo(0.20, within(1e-12));
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetHeadroom=0.0")
                .getBudgetHeadroom()).isEqualTo(0.0);
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetHeadroom=1.0")
                .getBudgetHeadroom()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("all three together, and the key AFTER them is still parsed as a key")
    void allThreeTogetherWithoutSwallowingTheNextKey() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(
                BASE + ",budgetMode=selfref,budgetSmoothing=7,budgetHeadroom=0.10,idleThreshold=0.15");
        assertThat(cfg.getBudgetMode()).isEqualTo(Modular.BudgetMode.SELFREF);
        assertThat(cfg.getBudgetSmoothing()).isEqualTo(7);
        assertThat(cfg.getBudgetHeadroom()).isEqualTo(0.10, within(1e-12));
        assertThat(cfg.getIdleThreshold()).isEqualTo(0.15, within(1e-12));
    }

    // ------------------------------------------------------------------ loud rejection

    @Test
    @DisplayName("an unknown budgetMode names the bad value AND the allowed set - it never falls back")
    void unknownBudgetModeIsRejectedLoudly() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetMode=selfrev"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetMode")
                .hasMessageContaining("selfrev")     // what the user typed
                .hasMessageContaining("off")         // ... and what they could have typed
                .hasMessageContaining("selfref");
    }

    @Test
    @DisplayName("budgetSmoothing < 1 is rejected (k=0 would divide by an empty window)")
    void budgetSmoothingBelowOneRejected() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetSmoothing=0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetSmoothing");
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetSmoothing=-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetSmoothing");
    }

    @Test
    @DisplayName("budgetHeadroom outside [0,1] is rejected - it is a SHARE, not a vehicle count")
    void budgetHeadroomOutsideUnitIntervalRejected() {
        // The most likely real mistake: reading "headroom = 15 vehicles" and typing the count.
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetHeadroom=15"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetHeadroom");
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetHeadroom=1.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetHeadroom");
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetHeadroom=-0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetHeadroom");
    }

    // ------------------------------------------------------------------ the constructor belt

    /**
     * The parse helpers reject first, so the tests above cannot see the CONSTRUCTOR's own checks.
     * These call it directly - the path every test fixture and every future runner takes - and are
     * what guarantee a bad value fails at wiring time rather than on the first tick of a 16 h run.
     */
    @Test
    @DisplayName("the constructor validates independently of parseScenario (wiring-time failure)")
    void constructorValidatesToo() {
        assertThatCode(() -> config(Modular.BudgetMode.SELFREF, 5, 0.15)).doesNotThrowAnyException();

        assertThatThrownBy(() -> config(Modular.BudgetMode.SELFREF, 0, 0.15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetSmoothing");
        assertThatThrownBy(() -> config(Modular.BudgetMode.SELFREF, 5, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetHeadroom");
        assertThatThrownBy(() -> config(Modular.BudgetMode.SELFREF, 5, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetHeadroom");
        // Deliberately also with the budget OFF: a spec saying budgetSmoothing=0 is a typo whether
        // or not the budget is on, and silently accepting it in OFF runs would make the same spec
        // valid or invalid depending on an unrelated key.
        assertThatThrownBy(() -> config(Modular.BudgetMode.OFF, 0, 0.15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetSmoothing");
    }

    @Test
    @DisplayName("a shorter constructor still defaults the budget to off / 5 / 0.15")
    void shorterConstructorsKeepDefaulting() {
        HAGRIDSimulationConfig cfg = new HAGRIDSimulationConfig("DRT_MODULAR",
                LocalDate.of(2025, 5, 13), 1, 1, false, 0.0, 1.0, "budctor",
                StudyArea.LAUSITZ_HOYERSWERDA, 130, true, false, 600.0, false, 1337L,
                0.15, Modular.DEFAULT_MAX_TOUR_DURATION_S, List.of(), 300,
                0, List.of());
        assertThat(cfg.getBudgetMode()).isEqualTo(Modular.BudgetMode.OFF);
        assertThat(cfg.getBudgetSmoothing()).isEqualTo(5);
        assertThat(cfg.getBudgetHeadroom()).isEqualTo(0.15, within(1e-12));
    }

    // ------------------------------------------- budgetUrgencyLeadS (plan 2026-09-05, Fix 2)

    @Test
    @DisplayName("budgetUrgencyLeadS absent: 3600 s, the Modular constant and not a second copy")
    void urgencyLeadDefaultsToTheModularConstant() {
        HAGRIDSimulationConfig cfg = SimulationRunnerUtils.parseScenario(BASE);
        assertThat(cfg.getBudgetUrgencyLeadS()).isEqualTo(3600.0, within(1e-12));
        assertThat(cfg.getBudgetUrgencyLeadS())
                .isEqualTo(Modular.DEFAULT_BUDGET_URGENCY_LEAD_S, within(1e-12));
    }

    @Test
    @DisplayName("budgetUrgencyLeadS is parsed and reaches the config")
    void urgencyLeadIsParsed() {
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetUrgencyLeadS=1800")
                .getBudgetUrgencyLeadS()).isEqualTo(1800.0, within(1e-12));
        assertThat(SimulationRunnerUtils.parseScenario(BASE + ",budgetUrgencyLeadS=5400.5")
                .getBudgetUrgencyLeadS()).isEqualTo(5400.5, within(1e-12));
    }

    /**
     * Zero is the dangerous value and must be REJECTED rather than clamped: it divides by zero in
     * the ramp and, worse, silently restores the binary override the ramp exists to replace - the
     * mechanism that expired 31 of 46 tours in {@code d1d_f130_bud}. A run configured that way
     * would look configured, log a lead of 0, and behave like the version that failed.
     */
    @Test
    @DisplayName("budgetUrgencyLeadS: zero, negative and non-numeric are rejected by name")
    void urgencyLeadRejectsUnusableValues() {
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetUrgencyLeadS=0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetUrgencyLeadS");
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetUrgencyLeadS=-60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetUrgencyLeadS");
        assertThatThrownBy(() -> SimulationRunnerUtils.parseScenario(BASE + ",budgetUrgencyLeadS=soon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetUrgencyLeadS");
    }

    /** The second validation belt: a caller building the config directly must fail too. */
    @Test
    @DisplayName("budgetUrgencyLeadS: the constructor rejects it independently of the parser")
    void urgencyLeadIsValidatedInTheConstructor() {
        assertThatThrownBy(() -> new HAGRIDSimulationConfig("DRT_MODULAR",
                LocalDate.of(2025, 5, 13), 1, 1, false, 0.0, 1.0, "budctor",
                StudyArea.LAUSITZ_HOYERSWERDA, 130, true, false, 600.0, false, 1337L,
                0.15, Modular.DEFAULT_MAX_TOUR_DURATION_S, List.of(), 300, 0, List.of(),
                Modular.BudgetMode.SELFREF, 5, 0.15, /*budgetUrgencyLeadS*/ 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetUrgencyLeadS");
    }

    private static HAGRIDSimulationConfig config(Modular.BudgetMode mode, int smoothing,
                                                 double headroom) {
        return new HAGRIDSimulationConfig("DRT_MODULAR", LocalDate.of(2025, 5, 13), 1, 1,
                false, 0.0, 1.0, "budctor", StudyArea.LAUSITZ_HOYERSWERDA, 130, true, false,
                600.0, false, 1337L, 0.15, Modular.DEFAULT_MAX_TOUR_DURATION_S, List.of(), 300,
                0, List.of(), mode, smoothing, headroom);
    }
}
