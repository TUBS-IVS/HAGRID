package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.passenger.DrtRequest;
import org.matsim.contrib.dvrp.load.DvrpLoad;
import org.matsim.contrib.dvrp.load.DvrpLoadType;

/**
 * Step-C static acceptance gate (spec §4.2): a PARCEL insertion whose DETOUR-ONLY
 * marginal time loss exceeds χ is declared infeasible. The rejected parcel then goes
 * to the {@link ParcelOnlyRetryQueue} and stays "pending" until it can be inserted
 * under χ or its delivery window expires.
 *
 * <p><b>Detour-only, not dwell-inclusive (rev. 2026-07-27), and per LEG (rev. 2026-08-13).</b>
 * {@link DetourTimeInfo#getTotalTimeLoss()} = pickupTimeLoss + dropoffTimeLoss, where
 * EACH leg includes the newly inserted stop's own stop duration
 * ({@code InsertionDetourTimeCalculator}: pickupTimeLoss = toPickupTT + stopDuration
 * + fromPickupTT − replacedDriveTT; analogous for dropoff). For parcels those stop
 * durations are the request's OWN service time ({@link SharedUseStopDurationProvider}:
 * depot pickup {@link SharedUse#depotPickupSeconds(int)} on the PICKUP leg, door dwell
 * {@link SharedUse#segmentDwellSeconds(int)} on the DROPOFF leg, together a dwell floor
 * ≥ 150·n s for an n-parcel segment) — gating on the raw total conflated detour tolerance
 * with the parcel's own service time and made segments with n ≥ 5 structurally
 * uninsertable at χ=600. The gate therefore subtracts the request's own dwell, but leg by
 * leg, and gates on
 * <pre>
 *   detourOnly = max(0, pickupTimeLoss  − max(depotPickupSeconds(n),  stopDurationFloor))
 *              + max(0, dropoffTimeLoss − max(segmentDwellSeconds(n), stopDurationFloor))
 * </pre>
 * with n read from the request's {@link DvrpLoad} "parcels" dimension and
 * {@code stopDurationFloor} the same {@code drtCfg.getStopDuration()} that
 * {@link SharedUseModule} hands {@code MinimumStopDurationAdapter} — no stop in the schedule
 * is shorter than that, so subtracting the unfloored 30 s of a single-parcel depot load would
 * leave 30 s of the request's OWN dwell in the residual.
 *
 * <p><b>Why per leg and not on the sum (rev. 2026-08-13, METHODS-LOG 2.35).</b> The
 * previous form {@code max(0, totalTimeLoss − ownDwell)} subtracted the dwell of BOTH
 * stops from the SUM and clamped once. When one leg piggybacks a co-located stop, its real
 * dwell contribution is smaller than nominal — {@code ParallelStopTimeCalculator}
 * max-semantics, and the same-link branches of {@code InsertionDetourTimeCalculator}
 * return only the ADDITIONAL stop duration — so the over-subtraction on that leg was
 * credited against genuine driving detour on the OTHER leg: up to {@code ownDwell(n)}
 * seconds of real detour could read as zero. Every parcel segment starts at
 * {@link SharedUse#ACT_DEPOT}, so a piggybacked depot pickup is the normal case rather
 * than a coincidence — in {@code chid600det} 1096 of 3104 segments (35 %) reported exactly
 * 0. Subtracting each leg's own stop duration from that leg leaves the pure drive detour
 * in every branch: for a NEW stop {@code stopDuration} is exactly this request's own
 * duration, so the subtraction cancels it; for a SAME-LINK stop the leg carries only the
 * additional duration and no drive terms at all, so the clamp correctly yields 0. A
 * piggyback credit can no longer cancel detour elsewhere. The per-leg {@code max(0, …)}
 * mostly does not bind — in the new-stop branches a leg is ≥ its own stop duration by
 * construction — it is there for the same-link branches, where an insertion into an
 * already-running stop can report a NEGATIVE additional duration; clamping keeps that
 * schedule shortening from being credited against real driving on the other leg.
 *
 * <p>This changed the gate's DECISION, not only the instrument: 1c runs from before
 * 2026-08-13 ({@code chid600w21}, {@code chid600det}) admitted parcels under the summed
 * form and are not comparable with later ones.
 *
 * <p><b>Hard-closed mode: χ &lt; 0.</b> Under detour-only semantics χ=0 no longer
 * guarantees zero deliveries (a zero-detour piggyback insertion passes), so the
 * Task-10 leakage probe needs an explicit closed mode: any negative χ (canonically
 * −1) rejects EVERY parcel insertion unconditionally, before the delegate is even
 * consulted.
 *
 * <p><b>Why gate on the raw time loss and not the delegate's cost (M6).</b>
 * {@code DrtModeOptimizerQSimModule} binds either
 * {@code CostCalculationStrategy.RejectSoftConstraintViolations} (cost == raw
 * {@code totalTimeLoss}) OR {@code DiscourageSoftConstraintViolations} (cost ==
 * {@code totalTimeLoss} PLUS wait/travel/ride/diversion penalties), depending on
 * {@code rejectRequestIfMaxWaitOrTravelTimeViolated}. Gating on the returned cost
 * would therefore be strategy-dependent and could reject a parcel whose true added
 * vehicle time is under χ. Reading the raw {@code totalTimeLoss} from
 * {@link DetourTimeInfo} keeps χ a clean "seconds of added vehicle time" threshold
 * regardless of the bound strategy.
 *
 * <p>Passenger requests pass through untouched (never gated); a kept parcel insertion
 * returns the delegate's own cost unchanged so the insertion search still ranks it.
 *
 * <p><b>Instrumentation.</b> Every block is reported to {@link ChiGateStats}. Without it
 * a blocked parcel leaves no trace anywhere: it goes back to the retry queue and, if it never
 * gets in, is dropped past its window with no rejection event, so it is indistinguishable in
 * the KPIs from a segment that simply found no vehicle. See {@link ChiGateStats} for why
 * {@code segments_rejected_final == 0} does NOT mean the gate is inactive.
 */
public final class ChiGateInsertionCostCalculator implements InsertionCostCalculator {

    /** Name of the parcel dimension in the mode's {@link DvrpLoadType}
     *  (the suffix of {@link SharedUse#LOAD_ATTRIBUTE}). */
    static final String PARCEL_DIMENSION = "parcels";

    private final InsertionCostCalculator delegate;
    private final double chiThreshold;
    private final int parcelDimensionIndex;
    private final double minStopDurationSeconds;
    private final ChiGateStats stats;

    /**
     * @param minStopDurationSeconds the floor {@code MinimumStopDurationAdapter} applies to every
     *        stop — MUST be the value {@link SharedUseModule} hands that adapter
     *        ({@code drtCfg.getStopDuration()}), or the gate subtracts a dwell the schedule never
     *        contained. It matters only where a request's own duration is BELOW the floor, i.e.
     *        the depot pickup of a single-parcel segment (30 s against a 60 s floor).
     */
    public ChiGateInsertionCostCalculator(InsertionCostCalculator delegate, double chiThreshold,
                                          DvrpLoadType loadType, double minStopDurationSeconds,
                                          ChiGateStats stats) {
        this.delegate = delegate;
        this.chiThreshold = chiThreshold;
        this.minStopDurationSeconds = minStopDurationSeconds;
        this.stats = stats;
        this.parcelDimensionIndex = loadType.getDimensions().indexOf(PARCEL_DIMENSION);
        if (this.parcelDimensionIndex < 0) {
            throw new IllegalArgumentException("DvrpLoadType has no '" + PARCEL_DIMENSION
                    + "' dimension (got " + loadType.getDimensions() + "). The χ-gate needs it to"
                    + " subtract a parcel request's own dwell — is the Shared-Use dvrp load"
                    + " config (DrtConfigComposer) missing?");
        }
    }

    /**
     * Convenience constructor for tests that do not assert on the counters; production wiring
     * ({@link SharedUseModule}) MUST pass the shared controller-scope {@link ChiGateStats}, or
     * the χ counters read 0 on every run while the gate is in fact firing.
     */
    ChiGateInsertionCostCalculator(InsertionCostCalculator delegate, double chiThreshold,
                                   DvrpLoadType loadType, double minStopDurationSeconds) {
        this(delegate, chiThreshold, loadType, minStopDurationSeconds, new ChiGateStats());
    }

    /** A DRT request is a parcel iff any of its passenger ids is a parcel-person. */
    static boolean isParcel(DrtRequest request) {
        return firstParcelPerson(request) != null;
    }

    /**
     * The request's parcel-person id, or null for a pax request. Parcel requests are
     * single-person, so the first match is THE segment's person — the key the counters and
     * {@link SharedUseKpiHandler}'s expired-bucket attribution both use.
     */
    private static Id<Person> firstParcelPerson(DrtRequest request) {
        for (Id<Person> id : request.getPassengerIds()) {
            if (SharedUse.isParcelPerson(id.toString())) {
                return id;
            }
        }
        return null;
    }

    @Override
    public double calculate(DrtRequest drtRequest, Insertion insertion, DetourTimeInfo detourTimeInfo) {
        Id<Person> parcelPerson = firstParcelPerson(drtRequest);
        if (parcelPerson == null) {
            return delegate.calculate(drtRequest, insertion, detourTimeInfo);
        }
        int parcels = parcelCount(drtRequest);
        // Recorded for EVERY parcel evaluation — kept AND blocked, and also under the
        // hard-closed mode below. The saturating block counters cannot tell whether χ binds
        // (METHODS-LOG 2.31); the distribution of the smallest achievable detour per segment
        // can, and in the χ<0 arm it doubles as the unperturbed reference (no parcel ever
        // boards, so the vehicle trajectory is the pure pax one).
        // See ChiGateStats#recordEvaluation for what this does and does not prove.
        double detourOnly = detourOnlySeconds(detourTimeInfo, parcels);
        stats.recordEvaluation(parcelPerson, parcels, detourOnly);
        if (chiThreshold < 0) {
            stats.recordBlocked(parcelPerson);
            return INFEASIBLE_SOLUTION_COST; // hard-closed: no parcel ever boards
        }
        if (detourOnly > chiThreshold) {
            stats.recordBlocked(parcelPerson);
            return INFEASIBLE_SOLUTION_COST;
        }
        return delegate.calculate(drtRequest, insertion, detourTimeInfo);
    }

    /**
     * Parcels in this request's segment, read from the {@code "parcels"} {@link DvrpLoad}
     * dimension. A missing load cannot happen in production ({@code DrtRequestCreator} always
     * sets one); 0 subtracts no dwell at all, so the gate falls back to the full (dwell-inclusive)
     * time loss — strictly conservative, never permissive.
     */
    private int parcelCount(DrtRequest request) {
        DvrpLoad load = request.getLoad();
        if (load == null) {
            return 0;
        }
        return Math.max(0, load.getElement(parcelDimensionIndex).intValue());
    }

    /**
     * The DRIVE-only detour this insertion adds: each leg's time loss minus THAT leg's own stop
     * duration, clamped per leg and then summed. See the class doc for why the subtraction must
     * not happen on {@link DetourTimeInfo#getTotalTimeLoss()} — a piggybacked stop on one leg
     * would otherwise pay for real driving on the other.
     *
     * <p>Package-private so a test can assert the arithmetic directly, without routing it through
     * the accept/reject verdict (a fixture that puts the whole time loss on one leg cannot tell
     * the two forms apart — which is why the summed form's own test suite stayed green).
     */
    double detourOnlySeconds(DetourTimeInfo detourTimeInfo, int parcels) {
        double pickupDetour = detourTimeInfo.pickupDetourInfo.pickupTimeLoss
                - ownPickupSeconds(parcels);
        double dropoffDetour = detourTimeInfo.dropoffDetourInfo.dropoffTimeLoss
                - ownDropoffSeconds(parcels);
        return Math.max(0.0, pickupDetour) + Math.max(0.0, dropoffDetour);
    }

    /**
     * Depot bulk-load time this request adds on the PICKUP leg, mirroring
     * {@link SharedUseStopDurationProvider#calcPickupDuration} — but floored like the schedule
     * itself, because {@code MinimumStopDurationAdapter} wraps the stop-time calculator and no
     * stop is ever shorter than {@code minStopDurationSeconds}. Without the floor a single-parcel
     * segment (30 s own load, 60 s in the schedule) would carry 30 s of its own dwell into the
     * residual and read it as detour — irrelevant at χ=600, half the threshold at χ=60. n=0 (no
     * load, cannot happen in production) subtracts nothing at all, floor included, which keeps
     * that fallback conservative.
     */
    private double ownPickupSeconds(int parcels) {
        return parcels <= 0 ? 0.0
                : Math.max(SharedUse.depotPickupSeconds(parcels), minStopDurationSeconds);
    }

    /**
     * Door dwell this request adds on the DROPOFF leg, mirroring
     * {@link SharedUseStopDurationProvider#calcDropoffDuration} (which reads the
     * {@link SharedUse#DWELL_ATTRIBUTE} that {@code ParcelAgentGenerator} fills with exactly
     * {@link SharedUse#segmentDwellSeconds(int)} of the same sub-load, so this is the same
     * number the stop-time calculator used), under the same floor as the pickup leg. The floor
     * never binds here in the current parameterisation — {@code segmentDwellSeconds(1)} is already
     * 120 s — but reading it from the same source keeps the two from drifting apart.
     */
    private double ownDropoffSeconds(int parcels) {
        return parcels <= 0 ? 0.0
                : Math.max(SharedUse.segmentDwellSeconds(parcels), minStopDurationSeconds);
    }
}
