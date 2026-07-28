package hagrid.integrated.modular;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Modular tour KPI aggregation (Task 9, design §4). Freight stops are plain STAY-base tasks and
 * emit NO native MATSim events at all (design D7 — parcels never become agents, so the passenger
 * engine never sees them). {@link ModularTourEvent} is therefore the ONE source of truth for
 * every freight number this study publishes — the δ decomposition, tour completion rate, swap /
 * retooling cost, deadhead/service split, and the vehicle-hours withdrawn from passenger service.
 * There is no second event stream to cross-check against, which is why the conservation
 * identities below exist and why this class does not attempt anything beyond faithfully
 * accumulating what {@link ModularTourEventHandler} hands it.
 *
 * <p><b>Run-ID-prefixed output (1c bug {@code 89f1ee5} designed out):</b> a previous scenario
 * shipped its KPI CSV under a bare filename that its own extractor could not find once a run id
 * was set. Here the file is always addressed via {@code controlerIO.getOutputFilename(FILE_NAME)}
 * — the run-ID prefix mechanism — from the very first line, so Task 13's extractor can match
 * {@code <prefix>.modular_tour_stats.csv} exactly. Format is {@code metric;value}
 * (semicolon-separated, header row {@code metric;value}), matching the 1c extractor convention
 * ({@link hagrid.integrated.shareduse.SharedUseKpiHandler}).</p>
 *
 * <p><b>Per-iteration reset (1c {@code dd34b23} lesson):</b> this is a controller-scope singleton
 * bound once for the whole run, so without {@link #reset(int)} clearing the per-tour state, a
 * {@code maxIterations>1} run would silently accumulate every iteration's tours into one
 * cross-iteration CSV. {@link #reset(int)} clears {@link #byTour} so the CSV written at shutdown
 * reflects ONLY the final iteration — a real, previously-shipped bug, not a hypothetical.</p>
 *
 * <p><b>Deadhead / service split convention (carried forward from Task 6's review):</b>
 * {@code deadhead_km_planned} counts ONLY the approach leg (vehicle's current position → depot)
 * and the return leg (last stop → depot) of a freight excursion. Every inter-stop leg — INCLUDING
 * the depot→first-stop leg — counts as {@code service_km_planned}. This differs from the
 * freight-literature convention
 * where the depot→first-stop leg is often itself treated as deadhead; it is deliberate here
 * because {@link ModularTourScheduler#schedule} accumulates its {@code service} distance starting
 * from the depot link, and {@link ModularTourEvent#getDeadheadMeters()} /
 * {@link ModularTourEvent#getServiceMeters()} are populated directly from that same split at
 * dispatch time — see {@code ModularTourScheduler}'s {@code ScheduledExcursion} record. Stating it
 * here too (not just in the scheduler) is deliberate: this is the one file a reader of the
 * published CSV numbers will actually open.</p>
 *
 * <p><b>These are PLANNED kilometres, not travelled kilometres.</b> {@code deadhead_km_planned}
 * and {@code service_km_planned} are computed from dispatch-time routing (the DISPATCHED event's
 * payload), not reconstructed from mobsim travel events — there is no such event stream for
 * freight legs (D7). The {@code _planned} suffix on the metric name exists specifically to keep
 * this distinction visible to anyone consuming the CSV without reading this class.</p>
 *
 * <p><b>{@code freight_vehicle_hours} excludes incomplete excursions.</b> It is
 * Σ over tours that are BOTH DISPATCHED and COMPLETED of
 * {@code (completedAt − dispatchedAt) / 3600} — the "vehicle-hours withdrawn from passenger
 * service" ingredient of design §4. A tour that was dispatched but never reached COMPLETED (see
 * {@code tours_dispatched_incomplete}) has no completion timestamp to subtract from, so it is
 * excluded, NOT charged some estimated partial duration. This exclusion biases the metric
 * DOWNWARD exactly when the fleet is most saturated (many excursions stranded incomplete at day
 * end) — documented here rather than left for a reader to rediscover from the code. The
 * {@code dispatched} filter (Task 9 review, Finding 1) exists because {@code dispatchedAt} /
 * {@code completedAt} default to {@code Double.NaN}: a tour that somehow reached COMPLETED
 * without a prior DISPATCHED — an accounting anomaly, not something this class assumes cannot
 * happen — would otherwise poison the ENTIRE sum to {@code NaN} via {@code DoubleStream.sum()},
 * silently wiping out every other tour's real contribution, not just the offending one. The
 * deadhead/service sums use the same {@code dispatched} filter for the same reason.</p>
 *
 * <p><b>Conservation identities (design §4; assert in test, log — never throw — at shutdown):</b>
 * <ol>
 *   <li>{@code tours_planned == tours_expired_pending + tours_dispatched + tours_pending_eod}</li>
 *   <li>{@code tours_dispatched == tours_completed + tours_dispatched_incomplete}</li>
 *   <li>{@code parcels_planned == parcels_expired_pending + parcels_dispatched + parcels_pending_eod}</li>
 *   <li>{@code parcels_dispatched == parcels_served + parcels_dispatched_unserved}</li>
 *   <li>{@code delta_parcels == parcels_planned - parcels_served}</li>
 * </ol>
 * {@code tours_pending_eod} / {@code parcels_pending_eod} are DERIVED by subtraction (no event
 * ever fires for "still pending at end of day" — nothing observes that non-event), so identities
 * 1 and 3 hold by construction and cannot themselves fail; they are kept anyway as executable
 * documentation of the accounting contract, and as a tripwire against a future refactor that
 * decouples the derivation from what gets published. Identity 5 is likewise a restatement of
 * {@code delta_parcels}'s own definition, not an independent check. Identities 2 and 4, by
 * contrast, are DELIBERATELY NOT computed by subtraction here:
 * {@code tours_dispatched_incomplete} is counted independently as
 * {@code dispatched && !completed} per tour, and {@code parcels_dispatched_unserved} as a
 * per-tour, DISPATCHED-only clipped remainder — both give the textbook-identical number to the
 * subtraction form for a well-formed event stream, but unlike the subtraction form they can
 * actually go out of balance (and therefore actually get logged) if some future bug lets a tour
 * reach COMPLETED without DISPATCHED, or lets a STOP_SERVED accumulate against a tour that was
 * PLANNED but never DISPATCHED (see the dedicated test for exactly that scenario).</p>
 *
 * <p><b>Non-negativity of the two residuals (Task 9 review, Minor 2):</b> because identities 1
 * and 3 are tautological by construction (previous paragraph), a tour double-counted into more
 * than one mutually-exclusive bucket (e.g. flagged BOTH {@code expired} and {@code dispatched})
 * would silently drive {@code tours_pending_eod} / {@code parcels_pending_eod} NEGATIVE without
 * tripping any of the five stated identities — the one failure mode the identity set structurally
 * cannot see. {@link #logConservationViolationIfAny} therefore also logs (separately, loud but
 * non-fatal, same as everything else here) whenever either residual goes negative.</p>
 *
 * <p><b>A non-PLANNED phase with no preceding PLANNED (ambiguity #4; downgraded by Task 9
 * review, Finding 2):</b> the dispatcher guarantees PLANNED precedes every other phase for a
 * given tour id (Tasks 3–8 invariant). Originally this was enforced by throwing
 * {@link IllegalStateException} at the point of injection, on the reasoning that a blind
 * {@code computeIfAbsent} silently manufacturing a zero-{@code parcelsPlanned} accumulator would
 * corrupt every downstream identity with no trace of why. Review correctly identified that this
 * throw contradicts the class's own "loud but NON-FATAL" contract: Task 9 does not own the
 * PLANNED-precedes-everything precondition and cannot keep it true by itself, so throwing mid-run
 * for an upstream (Tasks 3–8) regression would convert "a wrong number with nothing to contradict
 * it" into "no CSV at all, mid-run, after hours of compute" — strictly worse. {@link #handleEvent}
 * now logs the anomaly ONCE per offending tour id (via {@link #unknownTourIdsLogged}, so a
 * systemic regression cannot flood the log for the rest of the run), drops the event, and lets the
 * shutdown-time conservation check make the resulting imbalance loud instead.</p>
 */
public final class ModularKpiHandler implements ModularTourEventHandler, ShutdownListener {

    private static final Logger LOG = LogManager.getLogger(ModularKpiHandler.class);

    static final String FILE_NAME = "modular_tour_stats.csv";

    /** Mutable per-tour accumulator, keyed by tourId (insertion-ordered for determinism). */
    private static final class TourStat {
        int parcelsPlanned;
        boolean dispatched;
        boolean expired;
        boolean completed;
        boolean completedLate;      // C8: COMPLETED arrived after Modular.DELIVERY_DAY_END_S
        int parcelsServed;
        int parcelsServedLate;      // C8: sum of STOP_SERVED parcels arriving after the threshold
        int swaps;
        double deadheadMeters;
        double serviceMeters;
        double dispatchedAt = Double.NaN;
        double completedAt = Double.NaN;
    }

    /** CLEARED on {@link #reset(int)} — the 1c {@code dd34b23} per-iteration lesson. */
    private final Map<String, TourStat> byTour = new LinkedHashMap<>();
    /**
     * Tour ids for which a non-PLANNED phase with no preceding PLANNED has already been logged
     * (Task 9 review, Finding 2) — logged ONCE per offending tour id, not once per event, so a
     * systemic regression cannot flood the log for the rest of the run. CLEARED on
     * {@link #reset(int)} along with {@link #byTour}.
     */
    private final Set<String> unknownTourIdsLogged = new LinkedHashSet<>();
    private final Path outputCsv;

    @Inject
    public ModularKpiHandler(OutputDirectoryHierarchy controlerIO) {
        this.outputCsv = Path.of(controlerIO.getOutputFilename(FILE_NAME));
    }

    @Override
    public void reset(int iteration) {
        byTour.clear();
        unknownTourIdsLogged.clear();
    }

    @Override
    public void handleEvent(ModularTourEvent event) {
        TourStat s;
        if (event.getPhase() == ModularTourEvent.Phase.PLANNED) {
            s = byTour.computeIfAbsent(event.getTourId(), k -> new TourStat());
        } else {
            s = byTour.get(event.getTourId());
            if (s == null) {
                // Downgraded from a throw to a logged anomaly (Task 9 review, Finding 2): this
                // class's own contract - and the brief's global constraint - is "loud but
                // NON-FATAL", precisely so a run that already spent hours computing still gets a
                // CSV even when the accounting looks wrong. Throwing here for a precondition this
                // class does not own and cannot itself keep true (the dispatcher's
                // PLANNED-precedes-everything guarantee, Tasks 3-8) would convert "a wrong number
                // with nothing to contradict it" into "no CSV at all, mid-run, after hours of
                // compute" - strictly worse. Logged ONCE per offending tour id (not once per
                // event, see #unknownTourIdsLogged) so a systemic regression cannot flood the log;
                // the event itself is dropped (nothing sensible to accumulate against), and the
                // shutdown-time conservation check is what makes the resulting imbalance loud.
                if (unknownTourIdsLogged.add(event.getTourId())) {
                    LOG.error("ModularTourEvent phase {} for tour '{}' arrived with no preceding"
                            + " PLANNED event - violates the dispatcher's"
                            + " PLANNED-precedes-everything guarantee (Tasks 3-8). Dropping this"
                            + " event; the shutdown conservation check will flag the resulting"
                            + " imbalance.", event.getPhase(), event.getTourId());
                }
                return;
            }
        }
        switch (event.getPhase()) {
            case PLANNED -> s.parcelsPlanned = event.getParcels();
            case EXPIRED -> s.expired = true;
            case DISPATCHED -> {
                s.dispatched = true;
                s.dispatchedAt = event.getTime();
                // Deadhead/service split convention - see class javadoc: deadheadMeters is
                // approach+return ONLY; serviceMeters already includes the depot->first-stop leg.
                // Both are PLANNED (dispatch-time routing) kilometres, not travelled ones (D7: no
                // travel-event stream exists for freight legs) - hence the "_planned" CSV names.
                s.deadheadMeters = event.getDeadheadMeters();
                s.serviceMeters = event.getServiceMeters();
            }
            case SWAP_DONE -> s.swaps++;
            case STOP_SERVED -> {
                s.parcelsServed += event.getParcels();
                if (event.getTime() > Modular.DELIVERY_DAY_END_S) {
                    s.parcelsServedLate += event.getParcels();   // C8: strictly AFTER 21:00
                }
            }
            case COMPLETED -> {
                s.completed = true;
                s.completedAt = event.getTime();
                s.completedLate = event.getTime() > Modular.DELIVERY_DAY_END_S;   // C8
            }
        }
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
        long toursPlanned = byTour.size();
        long toursExpired = count(s -> s.expired);
        long toursDispatched = count(s -> s.dispatched);
        long toursCompleted = count(s -> s.completed);
        // Independent count (NOT toursDispatched - toursCompleted, see class javadoc) so identity
        // 2 below retains the power to catch a tour that reached COMPLETED without DISPATCHED.
        long toursIncomplete = count(s -> s.dispatched && !s.completed);
        // No event ever fires for "still pending at EOD" (ambiguity #3) - subtraction is the only
        // option here; identity 1 below is therefore a tautology kept as documentation + tripwire.
        long toursPendingEod = toursPlanned - toursExpired - toursDispatched;

        long parcelsPlanned = sum(s -> true, s -> s.parcelsPlanned);
        long parcelsExpired = sum(s -> s.expired, s -> s.parcelsPlanned);
        long parcelsDispatched = sum(s -> s.dispatched, s -> s.parcelsPlanned);
        long parcelsServed = sum(s -> true, s -> s.parcelsServed);
        // Independent, per-tour, DISPATCHED-only clipped remainder (NOT parcelsDispatched -
        // parcelsServed as an aggregate, see class javadoc) so identity 4 below retains the power
        // to catch parcels served against a tour that was never actually DISPATCHED.
        long parcelsUnserved = sum(s -> s.dispatched, s -> Math.max(0, s.parcelsPlanned - s.parcelsServed));
        long parcelsPendingEod = parcelsPlanned - parcelsExpired - parcelsDispatched;   // ambiguity #3
        long deltaParcels = parcelsPlanned - parcelsServed;

        long swaps = sum(s -> true, s -> s.swaps);
        double retoolingHours = swaps * Modular.RETOOLING_S / 3600.0;
        double deadheadKmPlanned = sumD(s -> s.dispatched, s -> s.deadheadMeters) / 1000.0;
        double serviceKmPlanned = sumD(s -> s.dispatched, s -> s.serviceMeters) / 1000.0;
        // Excludes incomplete excursions by construction (filter s.completed) - see class javadoc
        // for why that biases the metric downward exactly when the fleet is most saturated.
        // ALSO filters s.dispatched (Task 9 review, Finding 1): dispatchedAt/completedAt default
        // to Double.NaN, so a tour that somehow reached COMPLETED without a prior DISPATCHED (an
        // accounting anomaly the conservation identities are built to flag, not something this
        // class assumes cannot happen) would otherwise yield NaN, and DoubleStream.sum()
        // propagates a single NaN term to the WHOLE-RUN value, silently wiping out every other
        // tour's real contribution. The deadhead/service sums above already guard on s.dispatched
        // for exactly this reason; this brings freight_vehicle_hours into line with them.
        double freightVehicleHours = byTour.values().stream()
                .filter(s -> s.completed && s.dispatched)
                .mapToDouble(s -> (s.completedAt - s.dispatchedAt) / 3600.0)
                .sum();
        long toursCompletedLate = count(s -> s.completedLate);
        long parcelsServedLate = sum(s -> true, s -> s.parcelsServedLate);

        logConservationViolationIfAny(toursPlanned, toursExpired, toursDispatched, toursCompleted,
                toursIncomplete, toursPendingEod, parcelsPlanned, parcelsExpired, parcelsDispatched,
                parcelsServed, parcelsUnserved, parcelsPendingEod, deltaParcels);

        List<String> lines = new ArrayList<>(List.of("metric;value",
                "tours_planned;" + toursPlanned,
                "tours_expired_pending;" + toursExpired,
                "tours_dispatched;" + toursDispatched,
                "tours_completed;" + toursCompleted,
                "tours_dispatched_incomplete;" + toursIncomplete,
                "tours_pending_eod;" + toursPendingEod,
                "parcels_planned;" + parcelsPlanned,
                "parcels_expired_pending;" + parcelsExpired,
                "parcels_dispatched;" + parcelsDispatched,
                "parcels_served;" + parcelsServed,
                "parcels_dispatched_unserved;" + parcelsUnserved,
                "parcels_pending_eod;" + parcelsPendingEod,
                "delta_parcels;" + deltaParcels,
                "swaps_completed;" + swaps,
                "retooling_hours;" + retoolingHours,
                "deadhead_km_planned;" + deadheadKmPlanned,
                "service_km_planned;" + serviceKmPlanned,
                "freight_vehicle_hours;" + freightVehicleHours,
                "tours_completed_late;" + toursCompletedLate,
                "parcels_served_late;" + parcelsServedLate));
        try {
            Path parent = outputCsv.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(outputCsv, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A run that already spent hours computing must still surface this loudly, but there
            // is nothing sensible left to do except fail - unlike the conservation check below,
            // an unwritable output path is not something the rest of the run can route around.
            throw new UncheckedIOException("Cannot write " + outputCsv, e);
        }
    }

    /**
     * Loud but NON-FATAL (design mandate): logs an error and returns, never throws. A run that
     * already spent hours computing must still write its CSV even if the accounting looks
     * suspect - see class javadoc for which of the five identities can actually fail vs. which
     * are tautological by construction.
     */
    private void logConservationViolationIfAny(long toursPlanned, long toursExpired,
            long toursDispatched, long toursCompleted, long toursIncomplete, long toursPendingEod,
            long parcelsPlanned, long parcelsExpired, long parcelsDispatched, long parcelsServed,
            long parcelsUnserved, long parcelsPendingEod, long deltaParcels) {
        boolean toursConserve = toursPlanned == toursExpired + toursDispatched + toursPendingEod;
        boolean toursDispatchConserves = toursDispatched == toursCompleted + toursIncomplete;
        boolean parcelsConserve = parcelsPlanned == parcelsExpired + parcelsDispatched + parcelsPendingEod;
        boolean parcelsDispatchConserves = parcelsDispatched == parcelsServed + parcelsUnserved;
        boolean deltaConserves = deltaParcels == parcelsPlanned - parcelsServed;
        if (!toursConserve || !toursDispatchConserves || !parcelsConserve
                || !parcelsDispatchConserves || !deltaConserves) {
            LOG.error("Modular KPI conservation identity VIOLATED - CSV is suspect. "
                    + "toursConserve={} toursDispatchConserves={} parcelsConserve={} "
                    + "parcelsDispatchConserves={} deltaConserves={}",
                    toursConserve, toursDispatchConserves, parcelsConserve,
                    parcelsDispatchConserves, deltaConserves);
        }
        // Minor 2 (Task 9 review): identities 1/3 above are tautological by construction (see
        // class javadoc) - a tour double-counted into more than one bucket (e.g. both EXPIRED and
        // DISPATCHED) would silently drive a *_pending_eod residual NEGATIVE without tripping any
        // of the five identities. This is the one hole the identity set structurally cannot see,
        // so it gets its own explicit, independent check.
        if (toursPendingEod < 0 || parcelsPendingEod < 0) {
            LOG.error("Modular KPI pending_eod residual is NEGATIVE - some tour (or its parcels)"
                    + " was counted in more than one mutually-exclusive bucket (e.g. both expired"
                    + " and dispatched). toursPendingEod={} parcelsPendingEod={}",
                    toursPendingEod, parcelsPendingEod);
        }
    }

    private long count(Predicate<TourStat> p) {
        return byTour.values().stream().filter(p).count();
    }

    private long sum(Predicate<TourStat> p, ToIntFunction<TourStat> f) {
        return byTour.values().stream().filter(p).mapToInt(f).sum();
    }

    private double sumD(Predicate<TourStat> p, ToDoubleFunction<TourStat> f) {
        return byTour.values().stream().filter(p).mapToDouble(f).sum();
    }
}
