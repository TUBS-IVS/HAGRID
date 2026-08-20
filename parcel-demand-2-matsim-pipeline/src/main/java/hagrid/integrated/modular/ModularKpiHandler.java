package hagrid.integrated.modular;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * payload), not reconstructed from mobsim travel events. Freight DRIVES are real DVRP drives that
 * DO emit {@code LinkEnterEvent}s (review I3); what does not exist is (a) native passenger-engine
 * events for freight STOPS and (b) a per-task distance breakdown in {@code drt_vehicle_stats}. The
 * {@code _planned} suffix on the metric name exists because these two metrics stay dispatch-time
 * routing BY DECISION, not for lack of a travel-event stream (METHODS-LOG 2.14).</p>
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
 * <p><b>C8 late convention.</b> "Late" always means: event time (dwell END for {@code
 * STOP_SERVED}, swap-back end for {@code COMPLETED}) strictly after {@code
 * Modular.DELIVERY_DAY_END_S} (21:00) - the same convention for both {@code
 * tours_completed_late} and {@code parcels_served_late} below.</p>
 *
 * <p><b>⚠ The km metrics and {@code freight_vehicle_hours} are over DIFFERENT TOUR SETS</b>
 * (review Minor 6). {@code deadhead_km_planned} / {@code service_km_planned} filter on
 * {@code dispatched} alone; {@code freight_vehicle_hours} filters on
 * {@code completed && dispatched} — for the NaN reason just given, but the effect is that every
 * dispatched-but-incomplete excursion contributes its kilometres and none of its hours. Any
 * km-per-freight-hour ratio formed from these three numbers is therefore INFLATED, the more so
 * the more saturated the arm. Each filter is individually documented above; this paragraph
 * exists because the mismatch BETWEEN them is what a reader dividing one by the other trips
 * over, and neither metric's own note can show it.</p>
 *
 * <p><b>{@code tours_rejected_at_splice} is a diagnostic, not a bucket</b> (review Finding 3).
 * It counts tours the splicer refused at least once — see {@link ModularTourDispatcher} on why
 * that is a different failure from pending expiry (jsprit's car-network {@code plannedDuration}
 * vs. the actual DRT-routed completion plus approach leg). It is deliberately OUTSIDE the five
 * conservation identities and overlaps every one of their buckets: a rejected tour may be
 * dispatched later, may expire, or may sit pending at EOD. Read it against
 * {@code tours_dispatched}: a tour counted here that never reached DISPATCHED had the gate open
 * for it and still did not fit.</p>
 *
 * <p><b>Task 10 per-site swap peaks (spec 2026-08-17, "make the idealisations measurable"):</b>
 * {@code peak_concurrent_swaps} is ONE global number, computed by the pre-existing (untouched)
 * {@link #peakConcurrentSwaps()} below. The upcoming depot-count sweep makes that number stop
 * describing any one site — one open depot concentrates every swap on a single yard, several
 * open depots hide which one actually saturates. {@link #peakConcurrentSwapsBySite()} reuses the
 * identical sweep-line ({@link #maxConcurrentSwaps}) grouped by the PHYSICAL SITE {@link #siteOf}
 * derives from {@link ModularPlanStats#districtByTourId()} — NOT the raw district id itself
 * (fix round 1: a district id can be a {@code maxJobsPerDistrict} split sub-district, e.g. {@code
 * hoy_sued#0}/{@code hoy_sued#1}, that shares one physical yard with its siblings; the metric
 * must aggregate those back together or it understates exactly the concurrency it exists to
 * measure). {@link #notifyShutdown} appends one {@code peak_concurrent_swaps_<site>} row per
 * physical site that ever recorded a swap, after the twenty-six pre-existing metric names. See
 * {@link #siteOf}'s and {@link #swapEndTimesBySite()}'s javadoc for the full reasoning.</p>
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
        boolean rejectedAtSplice;   // review Finding 3: refused by the splicer at least once
        boolean completed;
        boolean completedLate;      // C8: COMPLETED arrived after Modular.DELIVERY_DAY_END_S
        int parcelsServed;
        int parcelsServedLate;      // C8: sum of STOP_SERVED parcels arriving after the threshold
        int swaps;
        double deadheadMeters;
        double serviceMeters;
        double dispatchedAt = Double.NaN;
        double completedAt = Double.NaN;
        // Task 1 (review F7, peak_concurrent_swaps): every SWAP_DONE's event time, appended in
        // handleEvent's SWAP_DONE case alongside the plain swaps++ counter. A swap occupies
        // [time - Modular.RETOOLING_S, time] (SWAP_DONE fires at the swap's END, not its start).
        final List<Double> swapTimes = new ArrayList<>();
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
    /**
     * Task 1 (defensive, ambiguity resolution): tour ids whose SWAP_DONE arrived with no depot in
     * {@link #planStats}'s {@code depotByTourId} map already logged once, so a systemic mismatch
     * (e.g. the map built against a different tour list) cannot flood the log. Their swaps are
     * still counted, grouped under the synthetic key {@code "unknown"} - never dropped, never a
     * crash. CLEARED on {@link #reset(int)} along with {@link #byTour}.
     */
    private final Set<String> unknownDepotTourIdsLogged = new LinkedHashSet<>();
    /**
     * Task 10 (per-site swap peaks): mirrors {@link #unknownDepotTourIdsLogged} but for the
     * DISTRICT/site grouping {@link #swapEndTimesBySite()} uses - a SWAP_DONE whose tour id has
     * no entry in {@link ModularPlanStats#districtByTourId()} is grouped under the synthetic
     * site key {@code "unknown"} and logged ONCE, never dropped. Independent of {@link
     * #unknownDepotTourIdsLogged} because the two groupings key off two different maps and can
     * disagree about which tour ids are "unknown". CLEARED on {@link #reset(int)}.
     */
    private final Set<String> unknownSiteTourIdsLogged = new LinkedHashSet<>();
    private final Path outputCsv;
    /** Task 1: plan-time accounting (demand/unassigned/missed/max-load/depot-by-tour), computed
     *  once by {@link ModularTourConverter#planStats} right after {@code convert} and handed in by
     *  {@link ModularDispatchModule} - this handler never touches a {@code Carriers} object. */
    private final ModularPlanStats planStats;

    public ModularKpiHandler(OutputDirectoryHierarchy controlerIO, ModularPlanStats planStats) {
        this.outputCsv = Path.of(controlerIO.getOutputFilename(FILE_NAME));
        this.planStats = planStats;
    }

    @Override
    public void reset(int iteration) {
        byTour.clear();
        unknownTourIdsLogged.clear();
        unknownDepotTourIdsLogged.clear();
        unknownSiteTourIdsLogged.clear();
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
            // NOT mutually exclusive with any other flag, and deliberately outside the
            // conservation identities: a rejected tour may still be dispatched later (a nearer
            // vehicle turns up), or expire, or sit pending at EOD. It is a DIAGNOSTIC over the
            // same tours, not a fourth bucket - see the class javadoc's metric note.
            case SPLICE_REJECTED -> s.rejectedAtSplice = true;
            case DISPATCHED -> {
                s.dispatched = true;
                s.dispatchedAt = event.getTime();
                // Deadhead/service split convention - see class javadoc: deadheadMeters is
                // approach+return ONLY; serviceMeters already includes the depot->first-stop leg.
                // Both are PLANNED (dispatch-time routing) kilometres, not travelled ones - freight
                // DRIVES do emit LinkEnterEvents (review I3), but these two stay dispatch-time
                // routing BY DECISION (METHODS-LOG 2.14) - hence the "_planned" CSV names.
                s.deadheadMeters = event.getDeadheadMeters();
                s.serviceMeters = event.getServiceMeters();
            }
            case SWAP_DONE -> {
                s.swaps++;
                s.swapTimes.add(event.getTime());
            }
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
        long toursRejectedAtSplice = count(s -> s.rejectedAtSplice);

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
                "parcels_served_late;" + parcelsServedLate,
                // APPENDED, never inserted (review Finding 3): the twenty names above and their
                // order are a published contract Task 13's extractor and its tests read.
                "tours_rejected_at_splice;" + toursRejectedAtSplice,
                // Task 1 (review F1/F3/F5/F7, METHODS-LOG 2.16): five MORE metrics appended after
                // tours_rejected_at_splice, in this exact order - the twenty-one names above are
                // untouched and keep their positions; nothing here may ever be inserted earlier.
                "parcels_demand;" + planStats.parcelsDemand(),
                "parcels_unassigned_jsprit;" + planStats.parcelsUnassignedJsprit(),
                "parcels_missed_overlay;" + planStats.parcelsMissedOverlay(),
                "max_parcels_per_tour;" + planStats.maxParcelsPerTour(),
                "peak_concurrent_swaps;" + peakConcurrentSwaps()));
        // Task 10 (spec 2026-08-17, "make the idealisations measurable"): one MORE row per site
        // that ever recorded a swap, APPENDED after the twenty-six names above - same append-only
        // discipline as everything else in this list, except this final block's row COUNT is not
        // fixed (it is exactly the number of distinct sites {@link #swapEndTimesBySite()} ever
        // saw). peakConcurrentSwaps() just above is completely untouched by this addition - same
        // method, same map (planStats.depotByTourId()), same arithmetic - so the pre-existing
        // global figure cannot regress; see ModularKpiHandlerTest for the pinning assertion.
        peakConcurrentSwapsBySite().forEach((site, peak) ->
                lines.add("peak_concurrent_swaps_" + site + ";" + peak));
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

    /**
     * Task 1 (review F7): {@code peak_concurrent_swaps} = the max, over depots, of the max
     * number of swaps simultaneously "in progress" at that depot at any instant of the run. A
     * SWAP_DONE fires at the swap's END (design D-none: there is no separate SWAP_STARTED event),
     * so swap {@code k} ending at {@code t} is taken to occupy {@code [t - RETOOLING_S, t]}.
     * Depot is resolved per tour via {@link #planStats}'s {@code depotByTourId} - a tour id that
     * map does not know (defensive; should not happen for any tour this handler's own PLANNED
     * event created) is grouped under the synthetic key {@code "unknown"} and logged ONCE, never
     * dropped and never a crash (ambiguity resolution, mirroring
     * {@link #unknownTourIdsLogged}'s flood-guard).
     */
    private long peakConcurrentSwaps() {
        Map<String, List<Double>> swapEndTimesByDepot = new LinkedHashMap<>();
        for (Map.Entry<String, TourStat> entry : byTour.entrySet()) {
            TourStat s = entry.getValue();
            if (s.swapTimes.isEmpty()) {
                continue;
            }
            String tourId = entry.getKey();
            Id<Link> depot = planStats.depotByTourId().get(tourId);
            String depotKey;
            if (depot != null) {
                depotKey = depot.toString();
            } else {
                depotKey = "unknown";
                if (unknownDepotTourIdsLogged.add(tourId)) {
                    LOG.warn("SWAP_DONE for tour '{}' has no depot in planStats.depotByTourId() -"
                            + " counting its {} swap(s) under the synthetic depot key 'unknown'"
                            + " for peak_concurrent_swaps.", tourId, s.swapTimes.size());
                }
            }
            swapEndTimesByDepot.computeIfAbsent(depotKey, k -> new ArrayList<>()).addAll(s.swapTimes);
        }
        long peak = 0;
        for (List<Double> endTimes : swapEndTimesByDepot.values()) {
            peak = Math.max(peak, maxConcurrentSwaps(endTimes));
        }
        return peak;
    }

    /**
     * Task 10 (spec 2026-08-17, "make the idealisations measurable"): {@code
     * peak_concurrent_swaps} above is ONE global number. With the upcoming depot-count sweep
     * that stops describing any particular site: at one open depot every swap concentrates on a
     * single yard the global figure cannot single out, and at several open depots the global
     * max hides which one is actually saturated. This groups the SAME swap-end times {@link
     * #peakConcurrentSwaps()} sees, by the PHYSICAL SITE {@link #siteOf} derives from {@link
     * ModularPlanStats#districtByTourId()} - see {@link #siteOf}'s javadoc for why a site is NOT
     * simply the district id, and {@link ModularPlanStats#districtByTourId()}'s javadoc for why
     * the two groupings (this one and the pre-existing depot-link one) are kept separate rather
     * than reusing {@link #peakConcurrentSwaps()}'s map. {@link #peakConcurrentSwaps()} itself
     * is untouched: same map, same key, same arithmetic, so the pre-existing global figure
     * cannot regress from this addition.
     *
     * <p>A tour id absent from {@code districtByTourId()} (defensive; should not happen for any
     * tour this handler's own PLANNED event created) is grouped under the synthetic site key
     * {@code "unknown"} and logged ONCE - never dropped, never a crash, mirroring {@link
     * #peakConcurrentSwaps()}'s own "unknown" depot fallback via the independent {@link
     * #unknownSiteTourIdsLogged} flood-guard.
     */
    private Map<String, List<Double>> swapEndTimesBySite() {
        Map<String, List<Double>> bySite = new LinkedHashMap<>();
        for (Map.Entry<String, TourStat> entry : byTour.entrySet()) {
            TourStat s = entry.getValue();
            if (s.swapTimes.isEmpty()) {
                continue;
            }
            String tourId = entry.getKey();
            String district = planStats.districtByTourId().get(tourId);
            String siteKey;
            if (district != null) {
                siteKey = siteOf(district);
            } else {
                siteKey = "unknown";
                if (unknownSiteTourIdsLogged.add(tourId)) {
                    LOG.warn("SWAP_DONE for tour '{}' has no district in"
                            + " planStats.districtByTourId() - counting its {} swap(s) under the"
                            + " synthetic site key 'unknown' for the per-site"
                            + " peak_concurrent_swaps breakdown.", tourId, s.swapTimes.size());
                }
            }
            bySite.computeIfAbsent(siteKey, k -> new ArrayList<>()).addAll(s.swapTimes);
        }
        return bySite;
    }

    /**
     * Fix round 1 (coordinator review): the per-site swap metric exists to answer ONE question -
     * how many capsule swaps happen simultaneously at ONE PHYSICAL YARD, because the model has
     * NO swap capacity limit and that concurrency figure IS the infrastructure the scenario
     * silently assumes. A district id is NOT the same thing as a physical site: {@code
     * DeliveryDistrictBuilder} splits an oversized catchment into sub-districts {@code
     * <depotId>#0}, {@code <depotId>#1}, ... that all share the SAME physical depot ({@code
     * maxJobsPerDistrict}, spec 2026-08-17) - keying the swap-concurrency metric on the raw
     * district id would report {@code hoy_sued#0}/{@code hoy_sued#1}/{@code hoy_sued#2} as three
     * tidy separate peaks when the sweep's single-depot stage puts all three at ONE yard, wrongly
     * understating concurrency by up to 3x exactly on the stage this metric exists to police
     * (the one most likely to otherwise win the sweep on unpriced infrastructure). Stripping the
     * {@code #<n>} suffix - a district id with none IS its own site unchanged - re-aggregates
     * every split sub-district back onto the one physical yard they actually share, matching the
     * question the metric is meant to answer.
     */
    private static String siteOf(String districtId) {
        int hash = districtId.indexOf('#');
        return hash < 0 ? districtId : districtId.substring(0, hash);
    }

    /**
     * Task 10: peak concurrent swaps at ONE physical site - {@code 0} if that site never appears
     * (an id nothing ever swapped under, or one this run never planned a tour for at all). {@code
     * site} is a PHYSICAL yard id ({@link #siteOf}'s output, i.e. a district id with any {@code
     * maxJobsPerDistrict} split suffix stripped) - passing a raw, still-suffixed district id
     * (e.g. {@code "hoy_sued#0"}) looks up nothing and returns 0, since {@link
     * #swapEndTimesBySite()} never stores a suffixed key. Uses the exact same sweep-line as the
     * global figure ({@link #maxConcurrentSwaps}), just grouped by a different key ({@link
     * #swapEndTimesBySite()} instead of the depot-keyed map {@link #peakConcurrentSwaps()}
     * builds). Package-private: exercised directly by {@link ModularKpiHandlerTest}; the {@code
     * peak_concurrent_swaps_<site>} CSV rows below are the only production consumer.
     */
    long peakConcurrentSwaps(String site) {
        List<Double> endTimes = swapEndTimesBySite().get(site);
        return endTimes == null ? 0 : maxConcurrentSwaps(endTimes);
    }

    /**
     * Task 10: every site's peak, for the {@code peak_concurrent_swaps_<site>} CSV rows written
     * in {@link #notifyShutdown}. {@code TreeMap} - never a {@code HashMap} (determinism, global
     * constraint): the site set and its insertion order here are a function of simstep event
     * arrival, not something that should leak into published CSV row order; sorting by site id
     * gives a byte-reproducible row order independent of that arrival order.
     */
    private Map<String, Long> peakConcurrentSwapsBySite() {
        Map<String, Long> peaks = new TreeMap<>();
        swapEndTimesBySite().forEach((site, endTimes) -> peaks.put(site, maxConcurrentSwaps(endTimes)));
        return peaks;
    }

    /**
     * Classic sweep-line over interval endpoints for ONE depot's swap-end times. Each swap ending
     * at {@code t} occupies {@code [t - RETOOLING_S, t]}; at a tie timestamp an interval END is
     * processed BEFORE an interval START (achieved by sorting the -1 delta ahead of the +1 delta
     * at equal times), so two back-to-back swaps - one's end exactly equal to the next's start -
     * do NOT count as concurrent.
     */
    private static long maxConcurrentSwaps(List<Double> swapEndTimes) {
        List<double[]> deltas = new ArrayList<>();
        for (double end : swapEndTimes) {
            deltas.add(new double[] {end - Modular.RETOOLING_S, 1});
            deltas.add(new double[] {end, -1});
        }
        deltas.sort(Comparator.<double[]>comparingDouble(d -> d[0]).thenComparingDouble(d -> d[1]));
        long running = 0;
        long peak = 0;
        for (double[] delta : deltas) {
            running += (long) delta[1];
            peak = Math.max(peak, running);
        }
        return peak;
    }
}
