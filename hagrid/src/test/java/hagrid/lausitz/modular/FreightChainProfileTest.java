package hagrid.lausitz.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.core.controler.events.IterationEndsEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every expected number here is written out longhand rather than recomputed from the production
 * expression: a test that re-evaluates the formula agrees with the implementation by construction
 * and can only catch a crash, never a wrong rule (feedback_test_discrimination).
 *
 * <p>VERIFY-SOURCE: {@code IterationEndsEvent(MatsimServices, int, boolean)} stores its arguments
 * and nothing more ({@code AbstractIterationEvent} -&gt; {@code ControlerEvent}, matsim 2025.0
 * sources), so a {@code null} services reference is safe and lets the REAL listener entry point be
 * exercised rather than a package-private shortcut.
 */
@DisplayName("FreightChainProfile")
class FreightChainProfileTest {

    private static void endIteration(FreightChainProfile p, int iteration) {
        p.notifyIterationEnds(new IterationEndsEvent(null, iteration, false));
    }

    // ------------------------------------------------------------------ construction

    @Test
    @DisplayName("smoothing window k must be at least 1")
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new FreightChainProfile(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("k must be >= 1");
        assertThatThrownBy(() -> new FreightChainProfile(-3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FreightChainProfile(1).completedIterations()).isZero();
    }

    @Test
    @DisplayName("a routed duration must be positive and finite")
    void rejectsUnusableObservations() {
        FreightChainProfile p = new FreightChainProfile(3);
        assertThatThrownBy(() -> p.observe("t0", 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive and finite");
        assertThatThrownBy(() -> p.observe("t0", -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.observe("t0", Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------- unknown is NaN, never zero

    /**
     * THE load-bearing property of this class, and the one a natural implementation gets wrong.
     * A tour nobody has dispatched must read as "no information" so the dispatcher falls back to
     * its bootstrap. Zero would read as "this tour takes no time at all" — the most permissive
     * possible answer, which would keep every such tour pending until its real deadline had long
     * passed and then hand it to a splicer that refuses it. That is precisely the failure this
     * whole plan exists to remove, so it is pinned here rather than left to the caller.
     */
    @Test
    @DisplayName("an unobserved tour is NaN, never 0.0")
    void unobservedTourIsUnknownNotZero() {
        FreightChainProfile p = new FreightChainProfile(3);
        assertThat(p.estimate("never-seen")).isNaN();

        p.observe("t0", 1000.0);
        endIteration(p, 0);
        assertThat(p.estimate("t0")).isEqualTo(1000.0);
        assertThat(p.estimate("t1"))
                .as("a sibling tour in the same iteration is still unknown")
                .isNaN();
    }

    @Test
    @DisplayName("the in-progress iteration is NOT visible until it ends")
    void inProgressIterationIsExcluded() {
        FreightChainProfile p = new FreightChainProfile(3);
        p.observe("t0", 1000.0);
        assertThat(p.estimate("t0"))
                .as("feeding a tour its own within-mobsim measurement back to the gate that"
                        + " dispatched it would make the loop undamped within one iteration")
                .isNaN();
        assertThat(p.isBootstrapped()).isFalse();
        assertThat(p.currentIterationObservations()).isEqualTo(1);

        endIteration(p, 0);
        assertThat(p.isBootstrapped()).isTrue();
        assertThat(p.estimate("t0")).isEqualTo(1000.0);
        assertThat(p.currentIterationObservations()).isZero();
    }

    // ------------------------------------------------------------------ max, not mean

    /**
     * The estimator is biased on purpose. An estimate that is too small drops parcels; one that is
     * too large costs a single dispatch made earlier than strictly necessary. Longhand: the
     * observations are 1000 and 2000, the mean would be 1500, and the answer must be 2000.
     */
    @Test
    @DisplayName("across iterations the estimate is the MAX, not the mean")
    void takesTheMaximumAcrossIterations() {
        FreightChainProfile p = new FreightChainProfile(3);
        p.observe("t0", 1000.0);
        endIteration(p, 0);
        p.observe("t0", 2000.0);
        endIteration(p, 1);

        assertThat(p.estimate("t0")).isEqualTo(2000.0);

        // and the order of arrival must not matter: larger first, smaller second
        FreightChainProfile q = new FreightChainProfile(3);
        q.observe("t0", 2000.0);
        endIteration(q, 0);
        q.observe("t0", 1000.0);
        endIteration(q, 1);
        assertThat(q.estimate("t0")).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("within one iteration repeated observations keep the longest")
    void takesTheMaximumWithinAnIteration() {
        FreightChainProfile p = new FreightChainProfile(2);
        p.observe("t0", 900.0);
        p.observe("t0", 1700.0);
        p.observe("t0", 1200.0);
        endIteration(p, 0);
        assertThat(p.estimate("t0")).isEqualTo(1700.0);
    }

    // ------------------------------------------------------------------ the k-window

    /**
     * The window is what keeps one pathological iteration from inflating a tour's estimate for the
     * rest of the run. With {@code k=2} the 9000 s observation must age out after two further
     * iterations — longhand: after iterations 1 and 2 the buffered slots hold 1100 and 1200, so
     * the answer is 1200 and not 9000.
     */
    @Test
    @DisplayName("an outlier ages out of the k-iteration window")
    void outlierAgesOut() {
        FreightChainProfile p = new FreightChainProfile(2);
        p.observe("t0", 9000.0);
        endIteration(p, 0);
        assertThat(p.estimate("t0")).isEqualTo(9000.0);

        p.observe("t0", 1100.0);
        endIteration(p, 1);
        assertThat(p.estimate("t0"))
                .as("still inside the 2-iteration window")
                .isEqualTo(9000.0);

        p.observe("t0", 1200.0);
        endIteration(p, 2);
        assertThat(p.estimate("t0")).isEqualTo(1200.0);
    }

    /**
     * k empty iterations must return the tour to "unknown" rather than serving a stale estimate
     * indefinitely. The alternative — keeping the last value forever — would quietly pin a tour's
     * envelope to a measurement from a fleet configuration that no longer exists.
     */
    @Test
    @DisplayName("k iterations without observations evict the tour back to unknown")
    void emptyIterationsEvictKnowledge() {
        FreightChainProfile p = new FreightChainProfile(2);
        p.observe("t0", 1500.0);
        endIteration(p, 0);
        assertThat(p.estimate("t0")).isEqualTo(1500.0);

        endIteration(p, 1);
        assertThat(p.estimate("t0"))
                .as("one empty iteration is not enough - the value is still buffered")
                .isEqualTo(1500.0);

        endIteration(p, 2);
        assertThat(p.estimate("t0")).isNaN();
        assertThat(p.completedIterations())
                .as("an empty iteration still counts as completed and still occupies a slot")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the ring keeps exactly the last k iterations, in the right order")
    void ringKeepsTheLastKIterations() {
        FreightChainProfile p = new FreightChainProfile(3);
        // 400, 500, 600, 700 over four iterations; with k=3 the 400 must be gone and the answer
        // is max(500, 600, 700) = 700.
        double[] observations = {400.0, 500.0, 600.0, 700.0};
        for (int i = 0; i < observations.length; i++) {
            p.observe("t0", observations[i]);
            endIteration(p, i);
        }
        assertThat(p.estimate("t0")).isEqualTo(700.0);
        assertThat(p.completedIterations()).isEqualTo(4);

        // now the largest is the OLDEST kept: 700, 300, 200, 100 -> after four iterations the
        // buffered three are 300, 200, 100 and the answer is 300, not 700.
        FreightChainProfile q = new FreightChainProfile(3);
        double[] descending = {700.0, 300.0, 200.0, 100.0};
        for (int i = 0; i < descending.length; i++) {
            q.observe("t0", descending[i]);
            endIteration(q, i);
        }
        assertThat(q.estimate("t0")).isEqualTo(300.0);
    }

    // ------------------------------------------------------------------ several tours

    @Test
    @DisplayName("tours are tracked independently")
    void toursAreIndependent() {
        FreightChainProfile p = new FreightChainProfile(2);
        p.observe("dhl_t0", 1000.0);
        p.observe("gls_t0", 5000.0);
        endIteration(p, 0);

        assertThat(p.estimate("dhl_t0")).isEqualTo(1000.0);
        assertThat(p.estimate("gls_t0")).isEqualTo(5000.0);

        // a second iteration that only sees one of them must not touch the other
        p.observe("dhl_t0", 1400.0);
        endIteration(p, 1);
        assertThat(p.estimate("dhl_t0")).isEqualTo(1400.0);
        assertThat(p.estimate("gls_t0")).isEqualTo(5000.0);
    }

    /**
     * The rolled slot must be a SNAPSHOT. If the ring stored a live reference to the accumulator,
     * clearing it for the next iteration would empty the slot too and every estimate would fall
     * back to the bootstrap forever — a silent failure that looks exactly like "the feature is
     * off", which is the shape of bug this package has been bitten by before.
     */
    @Test
    @DisplayName("rolling an iteration snapshots it; the accumulator's reset does not empty the ring")
    void rolledIterationIsASnapshot() {
        FreightChainProfile p = new FreightChainProfile(2);
        p.observe("t0", 1234.0);
        endIteration(p, 0);
        assertThat(p.currentIterationObservations()).isZero();
        assertThat(p.estimate("t0")).isEqualTo(1234.0);

        p.observe("t1", 999.0);
        assertThat(p.estimate("t0"))
                .as("a later iteration's in-progress work must not disturb a rolled slot")
                .isEqualTo(1234.0);
    }
}
