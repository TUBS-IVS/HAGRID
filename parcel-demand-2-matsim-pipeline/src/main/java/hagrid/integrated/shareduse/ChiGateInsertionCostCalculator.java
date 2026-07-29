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
 * <p><b>Detour-only, not dwell-inclusive (rev. 2026-07-27).</b>
 * {@link DetourTimeInfo#getTotalTimeLoss()} = pickupTimeLoss + dropoffTimeLoss, where
 * EACH includes the newly inserted stop's full stop duration
 * ({@code InsertionDetourTimeCalculator}: pickupTimeLoss = toPickupTT + stopDuration
 * + fromPickupTT − replacedDriveTT; analogous for dropoff). For parcels those stop
 * durations are the request's OWN service time ({@link SharedUseStopDurationProvider}:
 * depot pickup {@link SharedUse#depotPickupSeconds(int)} + door dropoff
 * {@link SharedUse#segmentDwellSeconds(int)}, a dwell floor ≥ 150·n s for an n-parcel
 * segment) — gating on the raw total conflated detour tolerance with the parcel's own
 * service time and made segments with n ≥ 5 structurally uninsertable at χ=600.
 * The gate therefore subtracts the request's own dwell and gates on
 * {@code detourOnly = max(0, totalTimeLoss − ownDwell)}, with n read from the
 * request's {@link DvrpLoad} "parcels" dimension. The {@code max(0, …)} clamp is
 * deliberate: when the insertion piggybacks existing co-located stops, the actual
 * dwell contribution inside {@code totalTimeLoss} is SMALLER than {@code ownDwell}
 * ({@code ParallelStopTimeCalculator} max-semantics), so the subtraction can
 * overshoot; clamping to 0 keeps the gate conservative-permissive for piggyback
 * insertions (they read as zero detour, never as negative-and-rejected).
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
    private final ChiGateStats stats;

    public ChiGateInsertionCostCalculator(InsertionCostCalculator delegate, double chiThreshold,
                                          DvrpLoadType loadType, ChiGateStats stats) {
        this.delegate = delegate;
        this.chiThreshold = chiThreshold;
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
                                   DvrpLoadType loadType) {
        this(delegate, chiThreshold, loadType, new ChiGateStats());
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
        if (chiThreshold < 0) {
            stats.recordBlocked(parcelPerson);
            return INFEASIBLE_SOLUTION_COST; // hard-closed: no parcel ever boards
        }
        double detourOnly = Math.max(0.0,
                detourTimeInfo.getTotalTimeLoss() - ownDwellSeconds(drtRequest));
        if (detourOnly > chiThreshold) {
            stats.recordBlocked(parcelPerson);
            return INFEASIBLE_SOLUTION_COST;
        }
        return delegate.calculate(drtRequest, insertion, detourTimeInfo);
    }

    /**
     * The service time this request itself adds: scaled depot pickup + door dropoff for
     * its n parcels (mirrors {@link SharedUseStopDurationProvider}'s per-request dwell).
     * A missing load cannot happen in production ({@code DrtRequestCreator} always sets
     * one) — the 0.0 fallback degrades to the old dwell-inclusive (conservative) gate.
     */
    private double ownDwellSeconds(DrtRequest request) {
        DvrpLoad load = request.getLoad();
        if (load == null) {
            return 0.0;
        }
        int parcels = load.getElement(parcelDimensionIndex).intValue();
        if (parcels <= 0) {
            return 0.0;
        }
        return SharedUse.depotPickupSeconds(parcels) + SharedUse.segmentDwellSeconds(parcels);
    }
}
