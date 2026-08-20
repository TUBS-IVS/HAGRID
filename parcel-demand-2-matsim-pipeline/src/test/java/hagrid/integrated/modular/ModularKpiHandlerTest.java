package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

/**
 * Covers Task 9: {@link ModularKpiHandler} is the ONE place every freight number this study
 * publishes flows through (freight stops emit no native MATSim events at all - design D7), so a
 * miscount here is a wrong published number with nothing to contradict it. Besides the brief's
 * two named tests, this class adds several discriminating tests the brief's own literal
 * assertions do not cover (see task-9-report.md for the full reasoning):
 * <ul>
 *   <li>{@link #lateThresholdIsStrictlyGreaterThan} pins {@code >} vs {@code >=} at the exact
 *       {@link Modular#DELIVERY_DAY_END_S} boundary - the brief's own fixture never puts an
 *       event exactly ON the threshold, so a {@code >=} bug would pass it unnoticed.</li>
 *   <li>{@link #lateClassificationIsPerEventNotPerTour} pins that C8 lateness is judged per
 *       EVENT, not smeared across a tour from its own (possibly late) completion.</li>
 *   <li>{@link #nonPlannedPhaseForUnknownTourIsLoggedNotThrown} pins ambiguity #4's guard as
 *       downgraded by review Finding 2 (logged, never thrown).</li>
 *   <li>{@link #conservationViolationDoesNotPreventCsvWrite} pins BOTH the "loud but never
 *       fatal" shutdown contract AND that the independently-computed
 *       {@code parcels_dispatched_unserved} (not a plain aggregate subtraction) actually goes
 *       out of balance under a real anomaly a subtraction-only implementation could not detect.</li>
 *   <li>{@link #freightVehicleHoursExcludesCompletedWithoutDispatched} pins review Finding 1: a
 *       tour reaching COMPLETED without DISPATCHED must not poison the whole-run sum to NaN.</li>
 *   <li>{@link #noEventsWritesAllZeroCsv} pins review Minor 1: a legitimately freight-free run
 *       still gets a complete, well-formed, all-zero CSV.</li>
 *   <li>{@link #doubleCountedTourDrivesResidualNegative} pins review Minor 2: a tour counted in
 *       more than one mutually-exclusive bucket drives a {@code _pending_eod} residual negative,
 *       which none of the five stated identities can see, but which is now logged separately.</li>
 * </ul>
 */
@DisplayName("ModularKpiHandler")
class ModularKpiHandlerTest {

    private static final Id<DvrpVehicle> vehId = Id.create("drt_0", DvrpVehicle.class);
    private static final Id<DvrpVehicle> vehId2 = Id.create("drt_1", DvrpVehicle.class);

    @Test
    @DisplayName("delta decomposition + conservation identities from a mixed event sequence")
    void aggregatesAndConserves(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());
        // tour A: planned -> dispatched -> 2 stops (3+2 parcels) -> completed (both swaps)
        handler.handleEvent(ModularTourEvent.planned(100, "dhl_t0", 5));
        handler.handleEvent(ModularTourEvent.dispatched(200, "dhl_t0", vehId, 5, 2500.0, 4200.0, 1500.0, 1800.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "dhl_t0", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "dhl_t0", vehId, 3));
        handler.handleEvent(ModularTourEvent.stopServed(500, "dhl_t0", vehId, 2));
        handler.handleEvent(ModularTourEvent.swapDone(600, "dhl_t0", vehId));
        handler.handleEvent(ModularTourEvent.completed(600, "dhl_t0", vehId));
        // tour B: planned -> expired
        handler.handleEvent(ModularTourEvent.planned(100, "dhl_t1", 4));
        handler.handleEvent(ModularTourEvent.expired(700, "dhl_t1", 4));
        // tour C: planned -> dispatched -> 1 of 2 stops served, never completed (EOD)
        handler.handleEvent(ModularTourEvent.planned(100, "gls_t0", 7));
        handler.handleEvent(ModularTourEvent.dispatched(800, "gls_t0", vehId2, 7, 1000.0, 2000.0, 1500.0, 1800.0));
        handler.handleEvent(ModularTourEvent.swapDone(900, "gls_t0", vehId2));
        handler.handleEvent(ModularTourEvent.stopServed(950, "gls_t0", vehId2, 4));
        // tour D: planned, still pending at EOD
        handler.handleEvent(ModularTourEvent.planned(100, "gls_t1", 2));
        // tour E: completes LATE (after 21:00 = 75600) - C8 marker case
        handler.handleEvent(ModularTourEvent.planned(100, "hermes_t0", 2));
        handler.handleEvent(ModularTourEvent.dispatched(70000, "hermes_t0", vehId, 2, 500.0, 800.0, 1500.0, 1800.0));
        handler.handleEvent(ModularTourEvent.swapDone(70600, "hermes_t0", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(75900, "hermes_t0", vehId, 2));
        handler.handleEvent(ModularTourEvent.swapDone(76000, "hermes_t0", vehId));
        handler.handleEvent(ModularTourEvent.completed(76100, "hermes_t0", vehId));

        handler.notifyShutdown(fixtureShutdownEvent());

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("tours_planned")).isEqualTo(5);
        assertThat(csv.get("tours_dispatched")).isEqualTo(3);
        assertThat(csv.get("tours_completed")).isEqualTo(2);
        assertThat(csv.get("tours_dispatched_incomplete")).isEqualTo(1);
        assertThat(csv.get("tours_expired_pending")).isEqualTo(1);
        assertThat(csv.get("tours_pending_eod")).isEqualTo(1);
        assertThat(csv.get("parcels_planned")).isEqualTo(20);
        // Direct assertion on parcels_dispatched itself (the brief's own draft never asserts this
        // metric name directly - only the derived parcels_dispatched_unserved touches it, which
        // WOULD still catch a "summed parcelsServed instead of parcelsPlanned" bug indirectly,
        // but a metric with zero direct coverage among the 20 published names is a gap worth
        // closing on its own).
        assertThat(csv.get("parcels_dispatched")).isEqualTo(14);
        assertThat(csv.get("parcels_served")).isEqualTo(11);
        assertThat(csv.get("parcels_expired_pending")).isEqualTo(4);
        assertThat(csv.get("parcels_dispatched_unserved")).isEqualTo(3);
        assertThat(csv.get("parcels_pending_eod")).isEqualTo(2);
        assertThat(csv.get("delta_parcels")).isEqualTo(9);
        assertThat(csv.get("swaps_completed")).isEqualTo(5);
        assertThat(csv.get("retooling_hours")).isCloseTo(5 * 420.0 / 3600.0, within(1e-9));
        assertThat(csv.get("deadhead_km_planned")).isCloseTo(4.0, within(1e-9));
        assertThat(csv.get("service_km_planned")).isCloseTo(7.0, within(1e-9));
        // COMPLETED tours: A (200 -> 600) + E (70000 -> 76100)
        assertThat(csv.get("freight_vehicle_hours")).isCloseTo((400.0 + 6100.0) / 3600.0, within(1e-9));
        // C8: E completed at 76100 > 75600, its 2-parcel stop served at 75900 > 75600
        assertThat(csv.get("tours_completed_late")).isEqualTo(1);
        assertThat(csv.get("parcels_served_late")).isEqualTo(2);

        // Conservation identities (design §4), literally as stated - asserted directly against
        // the published numbers so a reader of this test sees the accounting contract spelled out.
        assertThat(csv.get("tours_planned")).isEqualTo(
                csv.get("tours_expired_pending") + csv.get("tours_dispatched") + csv.get("tours_pending_eod"));
        assertThat(csv.get("tours_dispatched")).isEqualTo(
                csv.get("tours_completed") + csv.get("tours_dispatched_incomplete"));
        assertThat(csv.get("parcels_planned")).isEqualTo(
                csv.get("parcels_expired_pending") + csv.get("parcels_dispatched") + csv.get("parcels_pending_eod"));
        assertThat(csv.get("parcels_dispatched")).isEqualTo(
                csv.get("parcels_served") + csv.get("parcels_dispatched_unserved"));
        assertThat(csv.get("delta_parcels")).isEqualTo(
                csv.get("parcels_planned") - csv.get("parcels_served"));

        // No splicer rejection anywhere in this sequence - the diagnostic must be 0, not
        // silently absent (a missing key would read as 0.0 downstream and hide a regression).
        assertThat(csv).containsKey("tours_rejected_at_splice");
        assertThat(csv.get("tours_rejected_at_splice")).isZero();

        // Exactly the metric names the brief mandates, no more, no less - Task 13's extractor is
        // written against this exact set. 20 originally + tours_rejected_at_splice APPENDED by
        // the whole-branch review (Finding 3), + 5 more APPENDED by Task 1 (paper-readiness
        // review F1/F3/F5/F7): 26 total. The twenty-one and their order are unchanged; position
        // 20 is still tours_rejected_at_splice, since the five new ones land AFTER it. Task 10
        // adds ONE more: this fixture's zeroPlanStats() carries an empty districtByTourId, so
        // dhl_t0's + hermes_t0's four swaps all land in the synthetic "unknown" site bucket,
        // producing exactly one peak_concurrent_swaps_unknown row -> 27 total.
        assertThat(csv).hasSize(27);
        assertThat(List.copyOf(csv.keySet()).get(20))
                .as("appended LAST (before Task 1) so no existing column position shifts")
                .isEqualTo("tours_rejected_at_splice");
    }

    /**
     * Review Finding 3. A splicer rejection used to leave no trace at all — no event, no log, no
     * counter — so the tour later tripped pending expiry and was published as
     * {@code tours_expired_pending}. That attributes to "the gate was too tight" what was really
     * "the tour never fit", and the two call for opposite responses on the θ sweep.
     *
     * <p>The two tours here separate the diagnostic from the bucket it used to be swallowed by:
     * {@code t_rejected} was refused by the splicer AND then expired (so it appears in BOTH), while
     * {@code t_gated} only ever expired (so it must NOT appear in the rejection count). A handler
     * that simply aliased the new metric onto {@code expired} would pass an assertion on
     * {@code t_rejected} alone and fail here.
     */
    @Test
    @DisplayName("Finding 3: tours_rejected_at_splice counts splicer refusals, not pending expiries")
    void spliceRejectionIsCountedSeparatelyFromExpiry(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        handler.handleEvent(ModularTourEvent.planned(100, "t_rejected", 5));
        handler.handleEvent(ModularTourEvent.spliceRejected(200, "t_rejected", vehId, 5));
        handler.handleEvent(ModularTourEvent.expired(300, "t_rejected", 5));
        // gate-starved only: never offered to the splicer, just ran out of envelope
        handler.handleEvent(ModularTourEvent.planned(100, "t_gated", 4));
        handler.handleEvent(ModularTourEvent.expired(300, "t_gated", 4));
        // rejected once, then a nearer vehicle turned up: rejected AND dispatched, no contradiction
        handler.handleEvent(ModularTourEvent.planned(100, "t_late_fit", 3));
        handler.handleEvent(ModularTourEvent.spliceRejected(200, "t_late_fit", vehId, 3));
        handler.handleEvent(ModularTourEvent.dispatched(400, "t_late_fit", vehId2, 3, 10.0, 20.0, 0.0, 0.0));

        handler.notifyShutdown(fixtureShutdownEvent());

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("tours_rejected_at_splice")).isEqualTo(2);   // t_rejected + t_late_fit
        assertThat(csv.get("tours_expired_pending")).isEqualTo(2);      // t_rejected + t_gated
        assertThat(csv.get("tours_dispatched")).isEqualTo(1);           // t_late_fit
        // The diagnostic overlaps the buckets instead of being one - so the identities that
        // partition the tours must be untouched by it.
        assertThat(csv.get("tours_planned")).isEqualTo(
                csv.get("tours_expired_pending") + csv.get("tours_dispatched")
                        + csv.get("tours_pending_eod"));
    }

    @Test
    @DisplayName("reset(iteration) clears per-iteration state - CSV reflects ONLY the final iteration")
    void resetClearsState(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        // iteration 0: tour A - planned, dispatched, one stop served, 2 swaps, completed.
        handler.handleEvent(ModularTourEvent.planned(100, "dhl_t0", 9));
        handler.handleEvent(ModularTourEvent.dispatched(200, "dhl_t0", vehId, 9, 500.0, 900.0, 0.0, 0.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "dhl_t0", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "dhl_t0", vehId, 9));
        handler.handleEvent(ModularTourEvent.swapDone(500, "dhl_t0", vehId));
        handler.handleEvent(ModularTourEvent.completed(500, "dhl_t0", vehId));

        handler.reset(1); // QSim/iteration rebuilt - MUST clear tour A's accounting entirely

        // iteration 1 (final): a DIFFERENT, deliberately-distinguishable tour B - planned only,
        // nothing else - chosen so any leaked iteration-0 numbers (9 parcels, 1 dispatch, 2
        // swaps, 1 completion, 1 served parcel) could not reappear by coincidence.
        handler.handleEvent(ModularTourEvent.planned(100, "gls_t9", 6));

        handler.notifyShutdown(fixtureShutdownEvent());
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");

        assertThat(csv.get("tours_planned")).as("iter-0 tour A must not leak").isEqualTo(1);
        assertThat(csv.get("parcels_planned")).as("6 (tour B) only, not 9 (A) nor 15 (both)").isEqualTo(6);
        assertThat(csv.get("tours_dispatched")).as("iter-0's dispatch must not leak").isEqualTo(0);
        assertThat(csv.get("tours_completed")).as("iter-0's completion must not leak").isEqualTo(0);
        assertThat(csv.get("swaps_completed")).as("iter-0's 2 swaps must not leak").isEqualTo(0);
        assertThat(csv.get("parcels_served")).as("iter-0's served parcel must not leak").isEqualTo(0);
        assertThat(csv.get("tours_pending_eod")).as("tour B is planned-only -> pending at EOD").isEqualTo(1);
    }

    @Test
    @DisplayName("C8 boundary: exactly DELIVERY_DAY_END_S is ON TIME, not late (strict >, not >=)")
    void lateThresholdIsStrictlyGreaterThan(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());
        handler.handleEvent(ModularTourEvent.planned(100, "t_boundary", 3));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_boundary", vehId, 3, 100.0, 200.0, 0.0, 0.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "t_boundary", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(Modular.DELIVERY_DAY_END_S, "t_boundary", vehId, 3));
        handler.handleEvent(ModularTourEvent.swapDone(Modular.DELIVERY_DAY_END_S, "t_boundary", vehId));
        handler.handleEvent(ModularTourEvent.completed(Modular.DELIVERY_DAY_END_S, "t_boundary", vehId));

        handler.notifyShutdown(fixtureShutdownEvent());
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");

        assertThat(csv.get("tours_completed_late")).as("time == threshold is on-time, not late").isEqualTo(0);
        assertThat(csv.get("parcels_served_late")).as("time == threshold is on-time, not late").isEqualTo(0);
    }

    @Test
    @DisplayName("C8 lateness is judged per-EVENT, not smeared from the tour's own late completion")
    void lateClassificationIsPerEventNotPerTour(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());
        handler.handleEvent(ModularTourEvent.planned(100, "t_mixed", 5));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_mixed", vehId, 5, 100.0, 200.0, 0.0, 0.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "t_mixed", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "t_mixed", vehId, 5));    // well on-time
        handler.handleEvent(ModularTourEvent.swapDone(76200, "t_mixed", vehId));
        handler.handleEvent(ModularTourEvent.completed(76200, "t_mixed", vehId));      // tour completes LATE

        handler.notifyShutdown(fixtureShutdownEvent());
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");

        assertThat(csv.get("tours_completed_late")).as("tour DID complete after 21:00").isEqualTo(1);
        assertThat(csv.get("parcels_served_late"))
                .as("the stop itself was served on-time; a per-tour 'completed late -> mark "
                        + "everything late' bug would wrongly report this as 5")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Review Finding 2: a non-PLANNED phase for an unknown tour id is logged, not "
            + "thrown, and does not corrupt any other tour's counts")
    void nonPlannedPhaseForUnknownTourIsLoggedNotThrown(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        // ghost_tour never had a PLANNED - originally this threw IllegalStateException. Review
        // Finding 2: that throw contradicted the class's own "loud but non-fatal" contract (Task
        // 9 does not own the dispatcher's PLANNED-precedes-everything guarantee and cannot itself
        // keep it true), so it is now a logged, dropped event instead.
        assertThatCode(() -> handler.handleEvent(ModularTourEvent.stopServed(100, "ghost_tour", vehId, 2)))
                .as("loud but non-fatal: the shutdown-time conservation check surfaces this, not a mid-run throw")
                .doesNotThrowAnyException();

        // A second event for the SAME ghost tour must also not throw (logged once per tour id,
        // not once per event - not directly observable here without a log-capture appender, but
        // it must at minimum not throw or corrupt any state).
        assertThatCode(() -> handler.handleEvent(ModularTourEvent.stopServed(150, "ghost_tour", vehId, 1)))
                .doesNotThrowAnyException();

        // A perfectly ordinary tour fed afterward must be entirely unaffected.
        handler.handleEvent(ModularTourEvent.planned(100, "t_ok", 3));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_ok", vehId, 3, 100.0, 200.0, 0.0, 0.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "t_ok", vehId, 3));
        handler.handleEvent(ModularTourEvent.swapDone(500, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.completed(500, "t_ok", vehId));

        handler.notifyShutdown(fixtureShutdownEvent());
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");

        // ghost_tour never got a PLANNED, so it must never appear as a counted tour at all - the
        // dropped events must not manufacture an accumulator entry.
        assertThat(csv.get("tours_planned")).as("ghost_tour must not be counted - it never had a PLANNED").isEqualTo(1);
        assertThat(csv.get("parcels_served")).as("ghost_tour's stray served parcels must not leak in").isEqualTo(3);
    }

    @Test
    @DisplayName("conservation check is loud but non-fatal, and catches a stray STOP_SERVED "
            + "against a never-dispatched tour that ambiguity #4's guard alone cannot")
    void conservationViolationDoesNotPreventCsvWrite(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        // t_ok: a perfectly ordinary, fully-conserving tour.
        handler.handleEvent(ModularTourEvent.planned(100, "t_ok", 3));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_ok", vehId, 3, 100.0, 200.0, 0.0, 0.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "t_ok", vehId, 3));
        handler.handleEvent(ModularTourEvent.swapDone(500, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.completed(500, "t_ok", vehId));

        // t_stray: PLANNED (so it passes ambiguity #4's "no preceding PLANNED" check without even
        // logging) but NEVER dispatched, yet a STOP_SERVED arrives anyway - a real accounting
        // anomaly the dispatcher is not supposed to allow, but which that check alone cannot rule
        // out (it only ever sees a completely UNKNOWN tour id). This is exactly the case the
        // shutdown-time conservation check exists to flag instead.
        handler.handleEvent(ModularTourEvent.planned(100, "t_stray", 4));
        handler.handleEvent(ModularTourEvent.stopServed(600, "t_stray", vehId2, 4));

        assertThatCode(() -> handler.notifyShutdown(fixtureShutdownEvent()))
                .as("loud but non-fatal: a run that already spent hours computing must still write its CSV")
                .doesNotThrowAnyException();

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        // 26 pre-Task-10 metrics + one Task 10 row: t_ok's two swaps land in the synthetic
        // "unknown" site bucket (zeroPlanStats()'s districtByTourId is empty).
        assertThat(csv).as("CSV is still complete despite the anomaly").hasSize(27);

        double dispatched = csv.get("parcels_dispatched");
        double served = csv.get("parcels_served");
        double unserved = csv.get("parcels_dispatched_unserved");
        // parcels_served is a GLOBAL sum (picks up t_stray's stray 4), while
        // parcels_dispatched_unserved is computed per DISPATCHED tour only (t_stray is excluded,
        // since it was never dispatched) - so the two do NOT compensate, and identity 4 really is
        // violated here. A plain aggregate-subtraction implementation of
        // parcels_dispatched_unserved (= parcels_dispatched - parcels_served) could never produce
        // this mismatch, because it would be defined into balance regardless of the anomaly.
        assertThat(served + unserved)
                .as("stray STOP_SERVED against a never-dispatched tour breaks the identity - this"
                        + " is exactly what the shutdown-time conservation log flags")
                .isNotEqualTo(dispatched);
    }

    @Test
    @DisplayName("Review Finding 1: freight_vehicle_hours excludes a tour that reached COMPLETED "
            + "without DISPATCHED, instead of poisoning the whole-run sum with NaN")
    void freightVehicleHoursExcludesCompletedWithoutDispatched(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        // t_ok: a normal, fully-dispatched-and-completed tour with real vehicle-hours (500 -> 200
        // = 300s) to withdraw from passenger service.
        handler.handleEvent(ModularTourEvent.planned(100, "t_ok", 3));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_ok", vehId, 3, 100.0, 200.0, 0.0, 0.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "t_ok", vehId, 3));
        handler.handleEvent(ModularTourEvent.swapDone(500, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.completed(500, "t_ok", vehId));

        // t_anomaly: PLANNED then COMPLETED directly - no DISPATCHED ever arrives. This is NOT
        // blocked by the ambiguity-#4 check (PLANNED DID happen), so dispatchedAt stays
        // Double.NaN. Before the fix, (completedAt - NaN) / 3600 = NaN, and DoubleStream.sum()
        // propagates that single NaN term to the WHOLE-RUN freight_vehicle_hours, wiping out
        // t_ok's real 300s contribution too - not just t_anomaly's own (absent) one.
        handler.handleEvent(ModularTourEvent.planned(100, "t_anomaly", 5));
        handler.handleEvent(ModularTourEvent.completed(9999, "t_anomaly", vehId2));

        assertThatCode(() -> handler.notifyShutdown(fixtureShutdownEvent())).doesNotThrowAnyException();

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("freight_vehicle_hours"))
                .as("t_anomaly must be excluded (dispatched=false) - t_ok's real 300s must survive, not become NaN")
                .isCloseTo(300.0 / 3600.0, within(1e-9));
    }

    @Test
    @DisplayName("Minor 1: no events at all still writes a complete, well-formed, all-zero CSV")
    void noEventsWritesAllZeroCsv(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        handler.notifyShutdown(fixtureShutdownEvent());

        Path path = tmp.resolve("TESTRUN.modular_tour_stats.csv");
        assertThat(Files.exists(path)).as("CSV must be written even for a legitimately freight-free run").isTrue();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        assertThat(lines).as("header + all 26 metrics").hasSize(27);

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv).hasSize(26);
        csv.values().forEach(v -> assertThat(v).isEqualTo(0.0));
    }

    @Test
    @DisplayName("Minor 2: a tour counted as BOTH expired and dispatched drives a pending_eod "
            + "residual negative - the one hole the five identities structurally cannot see")
    void doubleCountedTourDrivesResidualNegative(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), zeroPlanStats());

        // t_double: PLANNED, then BOTH expired AND dispatched - an accounting anomaly no single
        // event-phase check can rule out (nothing enforces mutual exclusivity between the two
        // flags in this handler; that is the dispatcher's job upstream, Tasks 3-8). This drives
        // tours_pending_eod / parcels_pending_eod NEGATIVE without violating any of the five
        // stated identities (1 == 1 + 1 + (-1) still "holds" arithmetically).
        handler.handleEvent(ModularTourEvent.planned(100, "t_double", 5));
        handler.handleEvent(ModularTourEvent.expired(200, "t_double", 5));
        handler.handleEvent(ModularTourEvent.dispatched(300, "t_double", vehId, 5, 10.0, 20.0, 0.0, 0.0));

        assertThatCode(() -> handler.notifyShutdown(fixtureShutdownEvent()))
                .as("loud but non-fatal - a negative residual must still not prevent the CSV write")
                .doesNotThrowAnyException();

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("tours_pending_eod")).as("1 planned - 1 expired - 1 dispatched = -1").isEqualTo(-1);
        assertThat(csv.get("parcels_pending_eod")).as("5 planned - 5 expired - 5 dispatched = -5").isEqualTo(-5);
    }

    /**
     * Task 1 (paper-readiness review F1/F3/F5/F7, METHODS-LOG 2.16): {@link ModularKpiHandler}'s
     * ctor now also takes a {@link ModularPlanStats}, so the five new CSV metrics must be present
     * for every run - including ones that never touch plan-time accounting. This test pins their
     * VALUES and their exact append position, both directly (the brief's literal
     * {@code containsSubsequence} sketch) and via a full-arithmetic reconstruction of the
     * pre-existing 21 lines (append-only pin: the original metrics' own computation must be
     * byte-for-byte untouched by this change, not merely equal after floating-point rounding).
     *
     * <p>The two SWAP_DONE events on "dhl_t0" at t=30000/30200 occupy
     * {@code [29580,30000]}/{@code [29780,30200]} at the SAME depot - they overlap (29780 &lt;
     * 30000) so {@code peak_concurrent_swaps} must be 2, not 1 (which a same-tour-only or
     * no-overlap-detection implementation would wrongly produce) and not 3 (which counting the
     * second depot's independent swap into the same bucket would wrongly produce - the max is
     * taken PER DEPOT, then maxed across depots, never summed across depots).
     */
    @Test
    @DisplayName("Task 1: parcels_demand/unassigned/missed/max_parcels_per_tour/peak_concurrent_swaps "
            + "appended after tours_rejected_at_splice, in order, without disturbing the original 21")
    void planTimeMetricsAppendedAfterToursRejectedAtSplice(@TempDir Path tmp) throws Exception {
        Id<Link> depotA = Id.createLinkId("depotA");
        Id<Link> depotB = Id.createLinkId("depotB");
        // Task 10: districtByTourId (6th arg) left empty - this test is about the pre-existing
        // depot-keyed global figure and the five Task-1 rows, not the new per-site rows, so both
        // tours' swaps fall into the site-grouping's synthetic "unknown" bucket (see below).
        ModularPlanStats stats = new ModularPlanStats(15, 2, 1, 8,
                Map.of("dhl_t0", depotA, "gls_t0", depotB), Map.of());
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), stats);

        // dhl_t0 (depotA): two swaps 200s apart - overlapping intervals, peak 2 at this depot.
        handler.handleEvent(ModularTourEvent.planned(100, "dhl_t0", 5));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "dhl_t0", vehId));
        handler.handleEvent(ModularTourEvent.swapDone(30200, "dhl_t0", vehId));
        // gls_t0 (depotB): one swap alone - peak 1 at this depot.
        handler.handleEvent(ModularTourEvent.planned(100, "gls_t0", 3));
        handler.handleEvent(ModularTourEvent.swapDone(50000, "gls_t0", vehId2));

        handler.notifyShutdown(fixtureShutdownEvent());
        List<String> lines = Files.readAllLines(
                tmp.resolve("TESTRUN.modular_tour_stats.csv"), StandardCharsets.UTF_8);

        // Brief's literal sketch: the five new metrics appear, IN ORDER, after the append-only
        // twenty-first metric.
        assertThat(lines).containsSubsequence(
                "tours_rejected_at_splice;0",
                "parcels_demand;15",
                "parcels_unassigned_jsprit;2",
                "parcels_missed_overlay;1",
                "max_parcels_per_tour;8",
                "peak_concurrent_swaps;2");

        // Append-only pin, strengthened beyond containsSubsequence: the 21 pre-existing lines are
        // reconstructed here from the SAME arithmetic ModularKpiHandler uses (not hand-typed
        // literals, to sidestep floating-point string-representation guesswork) and compared
        // line-for-line - a reordering, an inserted metric, or a changed formula for any of the
        // original 21 would be caught here even if it happened to still contain the five new
        // lines somewhere in the file.
        long swaps = 3;    // 2 (dhl_t0) + 1 (gls_t0)
        List<String> expectedOriginal21 = List.of(
                "metric;value",
                "tours_planned;" + 2,
                "tours_expired_pending;" + 0,
                "tours_dispatched;" + 0,
                "tours_completed;" + 0,
                "tours_dispatched_incomplete;" + 0,
                "tours_pending_eod;" + 2,
                "parcels_planned;" + 8,
                "parcels_expired_pending;" + 0,
                "parcels_dispatched;" + 0,
                "parcels_served;" + 0,
                "parcels_dispatched_unserved;" + 0,
                "parcels_pending_eod;" + 8,
                "delta_parcels;" + 8,
                "swaps_completed;" + swaps,
                "retooling_hours;" + (swaps * Modular.RETOOLING_S / 3600.0),
                "deadhead_km_planned;" + 0.0,
                "service_km_planned;" + 0.0,
                "freight_vehicle_hours;" + 0.0,
                "tours_completed_late;" + 0,
                "parcels_served_late;" + 0,
                "tours_rejected_at_splice;" + 0);
        assertThat(lines.subList(0, 22))
                .as("the 21 pre-existing metrics (plus header) must be byte-identical to before")
                .isEqualTo(expectedOriginal21);
        assertThat(lines.subList(22, lines.size()))
                .as("exactly the five Task-1 metrics plus Task 10's one per-site row (both tours'"
                        + " swaps fall into the synthetic 'unknown' site bucket, districtByTourId"
                        + " being empty here), nothing else, in the mandated order")
                .containsExactly(
                        "parcels_demand;15",
                        "parcels_unassigned_jsprit;2",
                        "parcels_missed_overlay;1",
                        "max_parcels_per_tour;8",
                        "peak_concurrent_swaps;2",
                        // Task 10: dhl_t0 (2 swaps, peak 2) + gls_t0 (1 swap, peak 1) combined
                        // into "unknown" -> end times {30000,30200,50000}; the first two overlap
                        // (peak 2), the third is isolated -> site peak = 2.
                        "peak_concurrent_swaps_unknown;2");
    }

    /**
     * Self-review finding: the main test above ({@link #planTimeMetricsAppendedAfterToursRejectedAtSplice})
     * cannot actually tell "grouped per depot, then maxed" apart from "one global bucket over every
     * swap regardless of depot" - its two depots' swap windows never overlap in time, so both
     * implementations happen to produce the same peak=2. This test forces the two apart: depotA and
     * depotB each get exactly ONE swap, but both swaps end at the SAME instant, so their intervals
     * are IDENTICAL and DO overlap in absolute time. Per-depot grouping (correct) gives peak 1 at
     * EACH depot, so the max across depots is 1; a global-bucket bug that ignores depot separation
     * would see two overlapping intervals and wrongly report 2.
     */
    @Test
    @DisplayName("Task 1: peak_concurrent_swaps is maxed PER DEPOT, not over one global bucket "
            + "across depots (two different depots' simultaneous swaps must not combine)")
    void peakConcurrentSwapsIsPerDepotNotGlobal(@TempDir Path tmp) throws Exception {
        Id<Link> depotA = Id.createLinkId("depotA");
        Id<Link> depotB = Id.createLinkId("depotB");
        ModularPlanStats stats = new ModularPlanStats(0, 0, 0, 0,
                Map.of("a_t0", depotA, "b_t0", depotB), Map.of());
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), stats);

        // Same absolute swap-end time at TWO DIFFERENT depots - a global (depot-blind) sweep would
        // see these as two overlapping intervals (peak 2); per-depot grouping keeps them apart
        // (peak 1 at depotA, peak 1 at depotB, max across depots = 1).
        handler.handleEvent(ModularTourEvent.planned(100, "a_t0", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "a_t0", vehId));
        handler.handleEvent(ModularTourEvent.planned(100, "b_t0", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "b_t0", vehId2));

        handler.notifyShutdown(fixtureShutdownEvent());
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("peak_concurrent_swaps"))
                .as("two DIFFERENT depots' simultaneous swaps must NOT combine into one bucket")
                .isEqualTo(1);
    }

    /**
     * Self-review finding: no existing test puts a swap interval's END exactly on another's START,
     * the one instant the tie-break rule (process interval-END before interval-START at equal
     * timestamps) actually matters. A flipped tie-break (START before END) would wrongly report 2
     * here instead of 1.
     */
    @Test
    @DisplayName("Task 1: back-to-back swaps whose intervals touch (one's end == the next's start) "
            + "do NOT count as concurrent (tie-break: END processed before START)")
    void peakConcurrentSwapsTieBreakEndBeforeStart(@TempDir Path tmp) throws Exception {
        Id<Link> depot = Id.createLinkId("depot");
        ModularPlanStats stats = new ModularPlanStats(0, 0, 0, 0, Map.of("t_touch", depot), Map.of());
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), stats);

        // swap 1 occupies [30000 - 420, 30000] = [29580, 30000]; swap 2 occupies
        // [30420 - 420, 30420] = [30000, 30420] - swap 1's END is exactly swap 2's START.
        handler.handleEvent(ModularTourEvent.planned(100, "t_touch", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "t_touch", vehId));
        handler.handleEvent(ModularTourEvent.swapDone(30000 + Modular.RETOOLING_S, "t_touch", vehId));

        handler.notifyShutdown(fixtureShutdownEvent());
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("peak_concurrent_swaps"))
                .as("touching (not overlapping) intervals must NOT count as concurrent")
                .isEqualTo(1);
    }

    /**
     * Ambiguity resolution (defensive): a SWAP_DONE for a tourId absent from
     * {@code planStats.depotByTourId()} must not crash or drop the swap - it is grouped under the
     * synthetic depot key {@code "unknown"} instead, so it still contributes to
     * {@code peak_concurrent_swaps}.
     */
    @Test
    @DisplayName("Task 1: SWAP_DONE for a tourId with no depot in planStats is grouped under "
            + "'unknown' and counted, never crashes")
    void swapDoneForUnmappedTourIdCountsUnderSyntheticUnknownDepot(@TempDir Path tmp) throws Exception {
        ModularPlanStats emptyDepotMap = new ModularPlanStats(0, 0, 0, 0, Map.of(), Map.of());
        ModularKpiHandler handler =
                new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), emptyDepotMap);

        handler.handleEvent(ModularTourEvent.planned(100, "t_no_depot", 3));
        handler.handleEvent(ModularTourEvent.swapDone(200, "t_no_depot", vehId));

        assertThatCode(() -> handler.notifyShutdown(fixtureShutdownEvent()))
                .doesNotThrowAnyException();
        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("peak_concurrent_swaps"))
                .as("the lone swap still counts, grouped under the synthetic 'unknown' depot")
                .isEqualTo(1);
    }

    /**
     * Task 10 (spec 2026-08-17, "make the idealisations measurable"): the brief's own illustrative
     * sketch calls a {@code newHandlerWithSites(...)} factory and a {@code swap(handler, site,
     * start, end)} helper that do not exist on this class - adapted here to the REAL construction
     * (ctor takes a {@link ModularPlanStats}) and the REAL swap mechanic (a {@code SWAP_DONE}
     * fires at the swap's END only, occupying a FIXED {@code [end - RETOOLING_S, end]} window -
     * there is no separate start/end pair to pass in, and a tour must be PLANNED before any other
     * event for it is accepted at all).
     *
     * <p>Three tours share depot {@code depotHoySued} / district {@code "hoy_sued"} with three
     * mutually overlapping swaps (peak 3); one tour sits alone at depot {@code depotWittichenau} /
     * district {@code "wittichenau"} (peak 1). Neither district here is ever split by {@code
     * maxJobsPerDistrict}, so stripping a (non-existent) {@code #<n>} suffix is a no-op and the
     * site id equals the district id - {@link
     * #peakConcurrentSwapsAggregatesSplitSubDistrictsAtOnePhysicalSite} is the one that exercises
     * the suffix-stripping aggregation itself. This test's job is the brief's literal claims: a
     * per-site accessor exists, the CSV carries one row per site, and the global row is unchanged.
     */
    @Test
    @DisplayName("Task 10: peak_concurrent_swaps is also reported PER SITE (district id), and the "
            + "pre-existing global row is unchanged")
    void peakConcurrentSwapsIsReportedPerSite(@TempDir Path tmp) throws Exception {
        Id<Link> depotHoySued = Id.createLinkId("depotHoySued");
        Id<Link> depotWittichenau = Id.createLinkId("depotWittichenau");
        Map<String, Id<Link>> depotByTourId = new LinkedHashMap<>();
        depotByTourId.put("hoy_sued_t0", depotHoySued);
        depotByTourId.put("hoy_sued_t1", depotHoySued);
        depotByTourId.put("hoy_sued_t2", depotHoySued);
        depotByTourId.put("wittichenau_t0", depotWittichenau);
        Map<String, String> districtByTourId = new LinkedHashMap<>();
        districtByTourId.put("hoy_sued_t0", "hoy_sued");
        districtByTourId.put("hoy_sued_t1", "hoy_sued");
        districtByTourId.put("hoy_sued_t2", "hoy_sued");
        districtByTourId.put("wittichenau_t0", "wittichenau");
        ModularPlanStats stats = new ModularPlanStats(0, 0, 0, 0, depotByTourId, districtByTourId);
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), stats);

        // hoy_sued: three swaps 100s apart - [29580,30000]/[29680,30100]/[29780,30200] all overlap
        // at [29780,30000] -> peak 3.
        handler.handleEvent(ModularTourEvent.planned(100, "hoy_sued_t0", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "hoy_sued_t0", vehId));
        handler.handleEvent(ModularTourEvent.planned(100, "hoy_sued_t1", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30100, "hoy_sued_t1", vehId));
        handler.handleEvent(ModularTourEvent.planned(100, "hoy_sued_t2", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30200, "hoy_sued_t2", vehId2));
        // wittichenau: one isolated swap, far away in time -> peak 1, and never overlapping
        // hoy_sued's swaps (so the depot-keyed global figure also comes out to 3, not something
        // bigger from an accidental cross-site merge).
        handler.handleEvent(ModularTourEvent.planned(100, "wittichenau_t0", 1));
        handler.handleEvent(ModularTourEvent.swapDone(50000, "wittichenau_t0", vehId2));

        handler.notifyShutdown(fixtureShutdownEvent());

        // Package-private accessor, exercised directly (same package): the site IS the district
        // id, not the depot link string.
        assertThat(handler.peakConcurrentSwaps("hoy_sued")).isEqualTo(3);
        assertThat(handler.peakConcurrentSwaps("wittichenau")).isEqualTo(1);
        assertThat(handler.peakConcurrentSwaps("never_seen_site"))
                .as("a site with no recorded swaps returns 0, not a lookup failure").isEqualTo(0);

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("peak_concurrent_swaps"))
                .as("the global figure - still depot-keyed, untouched code path - must stay 3")
                .isEqualTo(3);
        assertThat(csv.get("peak_concurrent_swaps_hoy_sued")).isEqualTo(3);
        assertThat(csv.get("peak_concurrent_swaps_wittichenau")).isEqualTo(1);

        // Determinism (global constraint): TreeMap ordering, never insertion/HashMap order - the
        // two site rows must appear alphabetically, right after the (untouched) 26-metric block.
        List<String> lines = Files.readAllLines(
                tmp.resolve("TESTRUN.modular_tour_stats.csv"), StandardCharsets.UTF_8);
        assertThat(lines.subList(lines.size() - 2, lines.size()))
                .as("sites sorted alphabetically, appended after every pre-existing row")
                .containsExactly("peak_concurrent_swaps_hoy_sued;3", "peak_concurrent_swaps_wittichenau;1");
    }

    /**
     * Fix round 1 (coordinator review, replaces an earlier wrong-by-design test): the per-site
     * swap metric answers ONE question - how many capsule swaps happen simultaneously at ONE
     * PHYSICAL YARD, because the model has no swap capacity limit at all. A prior version of this
     * class keyed the per-site grouping on the raw district id, so a catchment split by {@code
     * maxJobsPerDistrict} into {@code hoy_sued#0}/{@code hoy_sued#1} (sharing ONE physical depot
     * link) was reported as two tidy separate peaks of 1 - understating the real concurrency at
     * that yard by half, and by up to 3x on the sweep's single-depot stage where THREE
     * sub-districts land on one yard. This test pins the FIX: {@code peakConcurrentSwaps} is
     * queried by the physical SITE id (the {@code #<n>} suffix stripped), and the two
     * sub-districts' overlapping swaps must be counted TOGETHER under that one site, matching the
     * depot-keyed global figure - not summed from the parts, but read as one aggregated value the
     * same sweep-line produces.
     *
     * <p>Verified BEFORE the fix landed (coordinator instruction): run against the raw
     * district-keyed grouping, {@code peakConcurrentSwaps("hoy_sued")} returned 0 (no bucket was
     * ever stored under the unsuffixed key at all) and {@code peak_concurrent_swaps_hoy_sued} was
     * absent from the CSV (only the suffixed {@code _hoy_sued#0}/{@code _hoy_sued#1} rows
     * existed) - both assertions below failed under that implementation.
     */
    @Test
    @DisplayName("Task 10 fix: per-site peak is keyed by PHYSICAL SITE (suffix stripped) - two "
            + "split sub-districts of the same physical depot are counted TOGETHER")
    void peakConcurrentSwapsAggregatesSplitSubDistrictsAtOnePhysicalSite(@TempDir Path tmp)
            throws Exception {
        Id<Link> sharedDepot = Id.createLinkId("depotHoySued");
        Map<String, Id<Link>> depotByTourId = Map.of(
                "hoy_sued#0_t0", sharedDepot, "hoy_sued#1_t0", sharedDepot);
        Map<String, String> districtByTourId = Map.of(
                "hoy_sued#0_t0", "hoy_sued#0", "hoy_sued#1_t0", "hoy_sued#1");
        ModularPlanStats stats = new ModularPlanStats(0, 0, 0, 0, depotByTourId, districtByTourId);
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"), stats);

        // Same instant at BOTH sub-districts -> identical, overlapping intervals. A yard with no
        // swap capacity limit would see 2 capsule swaps happening at once here.
        handler.handleEvent(ModularTourEvent.planned(100, "hoy_sued#0_t0", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "hoy_sued#0_t0", vehId));
        handler.handleEvent(ModularTourEvent.planned(100, "hoy_sued#1_t0", 1));
        handler.handleEvent(ModularTourEvent.swapDone(30000, "hoy_sued#1_t0", vehId2));

        handler.notifyShutdown(fixtureShutdownEvent());

        // THE assertion the fix is about: aggregated by SITE, not summed from the parts by hand -
        // both sub-districts' swaps must land in the SAME bucket the sweep-line maxes over.
        assertThat(handler.peakConcurrentSwaps("hoy_sued"))
                .as("both sub-districts share ONE physical yard - their overlapping swaps must be "
                        + "counted TOGETHER, not as two separate peaks of 1")
                .isEqualTo(2);
        assertThat(handler.peakConcurrentSwaps("hoy_sued#0"))
                .as("a raw, still-suffixed district id is not a site - it must look up nothing")
                .isEqualTo(0);
        assertThat(handler.peakConcurrentSwaps("hoy_sued#1"))
                .as("a raw, still-suffixed district id is not a site - it must look up nothing")
                .isEqualTo(0);

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv.get("peak_concurrent_swaps"))
                .as("the depot-keyed GLOBAL figure still pools both sub-districts at their shared"
                        + " physical yard - unchanged behaviour, not a regression")
                .isEqualTo(2);
        assertThat(csv.get("peak_concurrent_swaps_hoy_sued"))
                .as("ONE aggregated row for the physical site, matching the global figure")
                .isEqualTo(2);
        assertThat(csv)
                .as("no separate per-sub-district rows - the raw district id is not a site")
                .doesNotContainKey("peak_concurrent_swaps_hoy_sued#0")
                .doesNotContainKey("peak_concurrent_swaps_hoy_sued#1");
    }

    /** Task 1: a zero-valued fixture for tests that exercise the ORIGINAL 21 metrics only and do
     *  not care about plan-time accounting - keeps every pre-existing test's all-zero-append
     *  invariant intact without repeating this literal at every call site. Task 10: the sixth arg
     *  (districtByTourId) is likewise empty for these tests, so any swap they fire falls into the
     *  synthetic "unknown" site bucket - the same pre-existing fallback these tests already rely
     *  on for the depot-keyed map (5th arg), just mirrored for the site-keyed one. */
    private static ModularPlanStats zeroPlanStats() {
        return new ModularPlanStats(0, 0, 0, 0, Map.of(), Map.of());
    }

    // -------------------------------------------------------------------------

    private static OutputDirectoryHierarchy fixtureControlerIO(Path dir, String runId) {
        return new OutputDirectoryHierarchy(dir.toAbsolutePath().toString(), runId,
                OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles,
                ControllerConfigGroup.CompressionType.gzip);
    }

    private static ShutdownEvent fixtureShutdownEvent() {
        return new ShutdownEvent(null, false, 0);
    }

    private static Map<String, Double> readMetricCsv(Path dir, String fileName) throws IOException {
        Path path = dir.resolve(fileName);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo("metric;value");
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(";", 2);
            map.put(parts[0], Double.parseDouble(parts[1]));
        }
        return map;
    }
}
