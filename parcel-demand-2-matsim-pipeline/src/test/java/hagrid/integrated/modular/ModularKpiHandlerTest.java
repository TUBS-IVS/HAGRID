package hagrid.integrated.modular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Covers Task 9: {@link ModularKpiHandler} is the ONE place every freight number this study
 * publishes flows through (freight stops emit no native MATSim events at all - design D7), so a
 * miscount here is a wrong published number with nothing to contradict it. Besides the brief's
 * two named tests, this class adds four discriminating tests the brief's own literal assertions
 * do not cover (see task-9-report.md for the full reasoning):
 * <ul>
 *   <li>{@link #lateThresholdIsStrictlyGreaterThan} pins {@code >} vs {@code >=} at the exact
 *       {@link Modular#DELIVERY_DAY_END_S} boundary - the brief's own fixture never puts an
 *       event exactly ON the threshold, so a {@code >=} bug would pass it unnoticed.</li>
 *   <li>{@link #lateClassificationIsPerEventNotPerTour} pins that C8 lateness is judged per
 *       EVENT, not smeared across a tour from its own (possibly late) completion.</li>
 *   <li>{@link #nonPlannedPhaseForUnknownTourThrows} pins ambiguity #4's defensive guard.</li>
 *   <li>{@link #conservationViolationDoesNotPreventCsvWrite} pins BOTH the "loud but never
 *       fatal" shutdown contract AND that the independently-computed
 *       {@code parcels_dispatched_unserved} (not a plain aggregate subtraction) actually goes
 *       out of balance under a real anomaly a subtraction-only implementation could not detect.</li>
 * </ul>
 */
@DisplayName("ModularKpiHandler")
class ModularKpiHandlerTest {

    private static final Id<DvrpVehicle> vehId = Id.create("drt_0", DvrpVehicle.class);
    private static final Id<DvrpVehicle> vehId2 = Id.create("drt_1", DvrpVehicle.class);

    @Test
    @DisplayName("delta decomposition + conservation identities from a mixed event sequence")
    void aggregatesAndConserves(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));
        // tour A: planned -> dispatched -> 2 stops (3+2 parcels) -> completed (both swaps)
        handler.handleEvent(ModularTourEvent.planned(100, "dhl_t0", 5));
        handler.handleEvent(ModularTourEvent.dispatched(200, "dhl_t0", vehId, 5, 2500.0, 4200.0));
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
        handler.handleEvent(ModularTourEvent.dispatched(800, "gls_t0", vehId2, 7, 1000.0, 2000.0));
        handler.handleEvent(ModularTourEvent.swapDone(900, "gls_t0", vehId2));
        handler.handleEvent(ModularTourEvent.stopServed(950, "gls_t0", vehId2, 4));
        // tour D: planned, still pending at EOD
        handler.handleEvent(ModularTourEvent.planned(100, "gls_t1", 2));
        // tour E: completes LATE (after 21:00 = 75600) - C8 marker case
        handler.handleEvent(ModularTourEvent.planned(100, "hermes_t0", 2));
        handler.handleEvent(ModularTourEvent.dispatched(70000, "hermes_t0", vehId, 2, 500.0, 800.0));
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

        // Exactly the 20 metric names the brief mandates, no more, no less - Task 13's extractor
        // is written against this exact set.
        assertThat(csv).hasSize(20);
    }

    @Test
    @DisplayName("reset(iteration) clears per-iteration state - CSV reflects ONLY the final iteration")
    void resetClearsState(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));

        // iteration 0: tour A - planned, dispatched, one stop served, 2 swaps, completed.
        handler.handleEvent(ModularTourEvent.planned(100, "dhl_t0", 9));
        handler.handleEvent(ModularTourEvent.dispatched(200, "dhl_t0", vehId, 9, 500.0, 900.0));
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
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));
        handler.handleEvent(ModularTourEvent.planned(100, "t_boundary", 3));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_boundary", vehId, 3, 100.0, 200.0));
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
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));
        handler.handleEvent(ModularTourEvent.planned(100, "t_mixed", 5));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_mixed", vehId, 5, 100.0, 200.0));
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
    @DisplayName("ambiguity #4: a non-PLANNED phase for an unknown tour id throws")
    void nonPlannedPhaseForUnknownTourThrows(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));

        assertThatThrownBy(() -> handler.handleEvent(ModularTourEvent.stopServed(100, "ghost_tour", vehId, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost_tour");
    }

    @Test
    @DisplayName("conservation check is loud but non-fatal, and catches a stray STOP_SERVED "
            + "against a never-dispatched tour that ambiguity #4's guard alone cannot")
    void conservationViolationDoesNotPreventCsvWrite(@TempDir Path tmp) throws Exception {
        ModularKpiHandler handler = new ModularKpiHandler(fixtureControlerIO(tmp, "TESTRUN"));

        // t_ok: a perfectly ordinary, fully-conserving tour.
        handler.handleEvent(ModularTourEvent.planned(100, "t_ok", 3));
        handler.handleEvent(ModularTourEvent.dispatched(200, "t_ok", vehId, 3, 100.0, 200.0));
        handler.handleEvent(ModularTourEvent.swapDone(300, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.stopServed(400, "t_ok", vehId, 3));
        handler.handleEvent(ModularTourEvent.swapDone(500, "t_ok", vehId));
        handler.handleEvent(ModularTourEvent.completed(500, "t_ok", vehId));

        // t_stray: PLANNED (so it passes ambiguity #4's guard) but NEVER dispatched, yet a
        // STOP_SERVED arrives anyway - a real accounting anomaly the dispatcher is not supposed
        // to allow, but which the "no preceding PLANNED" guard alone cannot rule out. This is
        // exactly the case the conservation check (not the ambiguity-#4 throw) exists to flag.
        handler.handleEvent(ModularTourEvent.planned(100, "t_stray", 4));
        handler.handleEvent(ModularTourEvent.stopServed(600, "t_stray", vehId2, 4));

        assertThatCode(() -> handler.notifyShutdown(fixtureShutdownEvent()))
                .as("loud but non-fatal: a run that already spent hours computing must still write its CSV")
                .doesNotThrowAnyException();

        Map<String, Double> csv = readMetricCsv(tmp, "TESTRUN.modular_tour_stats.csv");
        assertThat(csv).as("CSV is still complete despite the anomaly").hasSize(20);

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
