package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.matsim.core.controler.events.IterationEndsEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Every expected number in this class is written out LONGHAND, with the arithmetic spelled out in
 * a comment. None of it is recomputed from {@code fleetSize - smoothed - h*fleetSize}: a test that
 * evaluates the production formula agrees with the implementation by construction and can only
 * catch a crash, never a wrong rule (feedback_test_discrimination).
 *
 * <p>VERIFY-SOURCE: {@code IterationEndsEvent(MatsimServices, int, boolean)} stores its arguments
 * and nothing more ({@code AbstractIterationEvent} -&gt; {@code ControlerEvent}, matsim 2025.0
 * sources), so a {@code null} services reference is safe here and lets the REAL listener entry
 * point be exercised rather than a package-private roll-up shortcut.
 */
@DisplayName("PassengerLoadProfile")
class PassengerLoadProfileTest {

    /** 08:00 = 28800 s; 28800 / 900 = 32. The bin the measured morning overhang lives in. */
    private static final int BIN_0800 = 32;
    /** 08:15 = 29700 s; the bin after {@link #BIN_0800}. */
    private static final int BIN_0815 = 33;

    private static void endIteration(PassengerLoadProfile profile, int iteration) {
        profile.notifyIterationEnds(new IterationEndsEvent(null, iteration, false));
    }

    // ------------------------------------------------------------------ bin arithmetic

    @Test
    @DisplayName("a tick exactly on a bin boundary belongs to the LATER bin")
    void binBoundaryGoesToTheLaterBin() {
        // Half-open bins [i*900, (i+1)*900). Pinning both sides of the boundary, because the
        // whole point of the convention is that Task 3's span loop and this class's accumulation
        // cannot disagree about which bin a commitment instant falls in.
        assertThat(PassengerLoadProfile.binOf(0.0)).isEqualTo(0);
        assertThat(PassengerLoadProfile.binOf(899.0)).isEqualTo(0);
        assertThat(PassengerLoadProfile.binOf(899.999)).isEqualTo(0);
        assertThat(PassengerLoadProfile.binOf(900.0)).isEqualTo(1);   // boundary -> LATER bin
        assertThat(PassengerLoadProfile.binOf(900.001)).isEqualTo(1);
        assertThat(PassengerLoadProfile.binOf(1799.0)).isEqualTo(1);
        assertThat(PassengerLoadProfile.binOf(1800.0)).isEqualTo(2);  // boundary -> LATER bin
        assertThat(PassengerLoadProfile.binOf(28800.0)).isEqualTo(BIN_0800);
        assertThat(PassengerLoadProfile.binOf(29700.0)).isEqualTo(BIN_0815);
    }

    @Test
    @DisplayName("the boundary convention is visible in the accumulated profile, not just in binOf")
    void observationOnABoundaryLandsInTheLaterBin() {
        PassengerLoadProfile profile = new PassengerLoadProfile(1);
        profile.observe(900.0, 40);          // exactly on the bin 0 / bin 1 boundary
        endIteration(profile, 0);

        assertThat(profile.smoothedPassengerBusy(1)).isEqualTo(40.0);
        // bin 0 never received anything -> unknown, NOT 0.0 and NOT 40.0
        assertThat(profile.smoothedPassengerBusy(0)).isNaN();
        assertThat(profile.budget(0, 100, 0.0)).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    @DisplayName("times outside the 36 h horizon are clamped, not rejected")
    void outOfRangeTimesAreClamped() {
        assertThat(PassengerLoadProfile.binOf(-1.0)).isEqualTo(0);
        assertThat(PassengerLoadProfile.binOf(PassengerLoadProfile.BIN_COUNT
                * PassengerLoadProfile.BIN_S)).isEqualTo(PassengerLoadProfile.BIN_COUNT - 1);
        assertThat(PassengerLoadProfile.binOf(999_999.0))
                .isEqualTo(PassengerLoadProfile.BIN_COUNT - 1);
    }

    // ------------------------------------------------------------------ smoothing arithmetic

    @Test
    @DisplayName("smoothing: mean of the per-iteration means, hand-computed")
    void smoothingArithmeticAgainstHandComputedValues() {
        PassengerLoadProfile profile = new PassengerLoadProfile(3);

        // iteration 1: two simsteps in bin 32 -> (30 + 34) / 2 = 32
        profile.observe(28800.0, 30);
        profile.observe(29000.0, 34);
        endIteration(profile, 0);
        // iteration 2: (20 + 24) / 2 = 22
        profile.observe(28800.0, 20);
        profile.observe(29000.0, 24);
        endIteration(profile, 1);
        // iteration 3: (12 + 18) / 2 = 15
        profile.observe(28800.0, 12);
        profile.observe(29000.0, 18);
        endIteration(profile, 2);

        // smoothed = (32 + 22 + 15) / 3 = 69 / 3 = 23
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(23.0, within(1e-9));

        // budget = 100 vehicles - 23 busy - 15 reserved (0.15 of 100) = 62
        assertThat(profile.budget(BIN_0800, 100, 0.15)).isCloseTo(62.0, within(1e-9));
        // and with no reserve at all: 100 - 23 - 0 = 77
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isCloseTo(77.0, within(1e-9));
    }

    @Test
    @DisplayName("within a bin, repeated simsteps are averaged, not summed or last-wins")
    void repeatedObservationsInOneBinAreAveraged() {
        PassengerLoadProfile profile = new PassengerLoadProfile(1);
        // four simsteps inside bin 32: (10 + 20 + 30 + 40) / 4 = 100 / 4 = 25
        profile.observe(28800.0, 10);
        profile.observe(28801.0, 20);
        profile.observe(29500.0, 30);
        profile.observe(29699.0, 40);
        assertThat(profile.currentIterationObservations(BIN_0800)).isEqualTo(4);
        endIteration(profile, 0);

        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(25.0, within(1e-9));
        // a sum would give 100 (budget 0), a last-wins would give 40 (budget 60): 100 - 25 = 75
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isCloseTo(75.0, within(1e-9));
        // the accumulator is cleared by the roll-up
        assertThat(profile.currentIterationObservations(BIN_0800)).isEqualTo(0);
    }

    // ------------------------------------------------------------------ bootstrap

    @Test
    @DisplayName("bootstrap: before any iteration ends the budget is unbounded")
    void bootstrapIsUnbounded() {
        PassengerLoadProfile profile = new PassengerLoadProfile(5);
        assertThat(profile.isBootstrapped()).isFalse();
        assertThat(profile.completedIterations()).isZero();
        assertThat(profile.budget(BIN_0800, 100, 0.15)).isEqualTo(Double.POSITIVE_INFINITY);

        // observing does NOT bootstrap it: iteration 0's own measurements are not available to
        // iteration 0's dispatcher, which is the whole meaning of "from iteration N-1".
        profile.observe(28800.0, 30);
        profile.observe(29000.0, 30);
        assertThat(profile.isBootstrapped()).isFalse();
        assertThat(profile.budget(BIN_0800, 100, 0.15)).isEqualTo(Double.POSITIVE_INFINITY);

        endIteration(profile, 0);
        assertThat(profile.isBootstrapped()).isTrue();
        assertThat(profile.completedIterations()).isEqualTo(1);
        // now it speaks: 100 - 30 - 15 = 55
        assertThat(profile.budget(BIN_0800, 100, 0.15)).isCloseTo(55.0, within(1e-9));
    }

    @Test
    @DisplayName("an iteration that observed nothing still counts as completed, but says nothing")
    void emptyIterationBootstrapsWithoutAssertingZeroLoad() {
        PassengerLoadProfile profile = new PassengerLoadProfile(2);
        endIteration(profile, 0);   // mobsim ran, dispatcher never ticked this profile

        assertThat(profile.isBootstrapped()).isTrue();
        // "bootstrapped" must not be confused with "knows something": still unbounded, NOT
        // fleetSize - 0 - 0 = 100, which is what a zero-filled array would have produced.
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isNaN();
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isEqualTo(Double.POSITIVE_INFINITY);
    }

    // ------------------------------------------------------------------ window semantics

    @Test
    @DisplayName("k=1 degenerates to 'previous iteration only'")
    void kOfOneUsesOnlyThePreviousIteration() {
        PassengerLoadProfile profile = new PassengerLoadProfile(1);

        profile.observe(28800.0, 40);
        endIteration(profile, 0);
        profile.observe(28800.0, 10);
        endIteration(profile, 1);

        // 10, not (40 + 10) / 2 = 25
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(10.0, within(1e-9));
        // 100 - 10 - 0 = 90   (a mean over both iterations would give 100 - 25 = 75)
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isCloseTo(90.0, within(1e-9));
    }

    @Test
    @DisplayName("ring buffer: iteration k+1 evicts iteration 1")
    void ringBufferEvictsTheOldestIteration() {
        PassengerLoadProfile profile = new PassengerLoadProfile(3);

        profile.observe(28800.0, 60);   // iteration 1 - must be gone at the end
        endIteration(profile, 0);
        profile.observe(28800.0, 30);   // iteration 2
        endIteration(profile, 1);
        profile.observe(28800.0, 12);   // iteration 3
        endIteration(profile, 2);

        // after three iterations the window is exactly full: (60 + 30 + 12) / 3 = 102 / 3 = 34
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(34.0, within(1e-9));

        profile.observe(28800.0, 6);    // iteration 4 - evicts iteration 1
        endIteration(profile, 3);

        // (30 + 12 + 6) / 3 = 48 / 3 = 16
        // if iteration 1 had survived: (60 + 30 + 12 + 6) / 4 = 27  -> budget 73, not 84
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(16.0, within(1e-9));
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isCloseTo(84.0, within(1e-9));
        assertThat(profile.completedIterations()).isEqualTo(4);
    }

    @Test
    @DisplayName("the in-progress iteration is NOT part of the smoothed profile")
    void inProgressIterationIsExcludedFromSmoothing() {
        PassengerLoadProfile profile = new PassengerLoadProfile(2);

        profile.observe(28800.0, 30);
        endIteration(profile, 0);

        // the current mobsim is running and reporting an idle fleet in the same bin
        profile.observe(28800.0, 0);
        profile.observe(29000.0, 0);

        // still 30: the live samples belong to an iteration that has not ended.
        // If they were folded in, the mean would be (30 + 0) / 2 = 15 -> budget 85, not 70.
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(30.0, within(1e-9));
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isCloseTo(70.0, within(1e-9));
    }

    // ------------------------------------------------------------------ unknown != zero

    @Test
    @DisplayName("a bin no iteration ever observed reads as unknown, not as zero load")
    void unobservedBinIsUnknownNotZero() {
        PassengerLoadProfile profile = new PassengerLoadProfile(3);
        profile.observe(28800.0, 30);       // bin 32 only
        endIteration(profile, 0);

        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(30.0, within(1e-9));
        // bin 33 was never observed. Zero load would publish a budget of 100 - 0 - 0 = 100,
        // i.e. "the entire fleet is free at 08:15" - the most permissive and most dangerous
        // possible wrong answer.
        assertThat(profile.smoothedPassengerBusy(BIN_0815)).isNaN();
        assertThat(profile.budget(BIN_0815, 100, 0.0)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(profile.budget(BIN_0815, 100, 0.0)).isNotEqualTo(100.0);
    }

    @Test
    @DisplayName("a bin observed in only SOME buffered iterations is not diluted by the others")
    void partiallyObservedBinAveragesOnlyOverObservingIterations() {
        PassengerLoadProfile profile = new PassengerLoadProfile(2);

        profile.observe(28800.0, 30);       // iteration 1 saw bin 32
        endIteration(profile, 0);
        profile.observe(36000.0, 8);        // iteration 2 saw bin 40 (10:00) but NOT bin 32
        endIteration(profile, 1);

        // 30, not (30 + 0) / 2 = 15: iteration 2's silence about bin 32 is ignorance, not a
        // measurement of zero. Dividing by the number of silent iterations understates the
        // passenger load, and it understates it in the permissive direction.
        assertThat(profile.smoothedPassengerBusy(BIN_0800)).isCloseTo(30.0, within(1e-9));
        assertThat(profile.budget(BIN_0800, 100, 0.0)).isCloseTo(70.0, within(1e-9));
        // bin 40 likewise: 8, not 4
        assertThat(profile.smoothedPassengerBusy(40)).isCloseTo(8.0, within(1e-9));
    }

    // ------------------------------------------------------------------ headroom

    @Test
    @DisplayName("headroom enters as a SHARE of fleet size, not as an absolute vehicle count")
    void headroomIsAShareOfFleetSize() {
        PassengerLoadProfile profile = new PassengerLoadProfile(1);
        profile.observe(28800.0, 30);
        endIteration(profile, 0);

        // fleet 100, h = 0.10  ->  100 - 30 - 10 = 60
        assertThat(profile.budget(BIN_0800, 100, 0.10)).isCloseTo(60.0, within(1e-9));
        // fleet 200, h = 0.10  ->  200 - 30 - 20 = 150
        // an ABSOLUTE reading of h would give 200 - 30 - 0.1 = 169.9 here, and would also make
        // the 100-vehicle case above 69.9 - the two fleet sizes together pin the share reading.
        assertThat(profile.budget(BIN_0800, 200, 0.10)).isCloseTo(150.0, within(1e-9));
        // fleet 200, h = 0.20  ->  200 - 30 - 40 = 130
        assertThat(profile.budget(BIN_0800, 200, 0.20)).isCloseTo(130.0, within(1e-9));
        // h = 0 is the pure "fleet minus passengers" budget: 200 - 30 = 170
        assertThat(profile.budget(BIN_0800, 200, 0.0)).isCloseTo(170.0, within(1e-9));
    }

    @Test
    @DisplayName("the budget may go negative and is not clamped")
    void budgetIsNotClampedAtZero() {
        PassengerLoadProfile profile = new PassengerLoadProfile(1);
        profile.observe(28800.0, 95);
        endIteration(profile, 0);

        // 100 - 95 - 15 = -10: the overhang's magnitude is the quantity this study measures,
        // so it must not be flattened to 0 here.
        assertThat(profile.budget(BIN_0800, 100, 0.15)).isCloseTo(-10.0, within(1e-9));
    }

    // ------------------------------------------------------------------ argument validation

    @Test
    @DisplayName("constructor rejects k < 1")
    void constructorRejectsNonPositiveSmoothingWindow() {
        assertThatThrownBy(() -> new PassengerLoadProfile(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1")
                .hasMessageContaining("0");
        assertThatThrownBy(() -> new PassengerLoadProfile(-3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
        // k = 1 is legal - it is the degenerate "previous iteration only" setting, not an error
        assertThat(new PassengerLoadProfile(1).isBootstrapped()).isFalse();
    }

    @Test
    @DisplayName("loud rejection of nonsense arguments rather than a silent wrong number")
    void argumentsAreValidated() {
        PassengerLoadProfile profile = new PassengerLoadProfile(2);

        assertThatThrownBy(() -> profile.observe(28800.0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passengerBusy");
        assertThatThrownBy(() -> profile.budget(-1, 100, 0.15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bin index");
        assertThatThrownBy(() -> profile.budget(PassengerLoadProfile.BIN_COUNT, 100, 0.15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bin index");
        assertThatThrownBy(() -> profile.budget(BIN_0800, 0, 0.15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fleetSize");
        assertThatThrownBy(() -> profile.budget(BIN_0800, 100, 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHARE");
        assertThatThrownBy(() -> profile.budget(BIN_0800, 100, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHARE");
    }
}
