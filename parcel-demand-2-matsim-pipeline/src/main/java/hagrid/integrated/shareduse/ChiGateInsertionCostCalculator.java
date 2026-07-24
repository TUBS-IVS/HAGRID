package hagrid.integrated.shareduse;

import org.matsim.contrib.drt.optimizer.insertion.InsertionCostCalculator;
import org.matsim.contrib.drt.optimizer.insertion.InsertionGenerator.Insertion;
import org.matsim.contrib.drt.optimizer.insertion.InsertionDetourTimeCalculator.DetourTimeInfo;
import org.matsim.contrib.drt.passenger.DrtRequest;

/**
 * Step-C static acceptance gate (spec §4.2): a PARCEL insertion whose RAW marginal
 * time loss — {@link DetourTimeInfo#getTotalTimeLoss()}, the seconds of additional
 * vehicle operating time the insertion adds — exceeds χ is declared infeasible.
 * The rejected parcel then goes to the {@link ParcelOnlyRetryQueue} and stays
 * "pending" until it can be inserted under χ or its delivery window expires.
 *
 * <p><b>Why gate on the raw {@code totalTimeLoss} and not the delegate's cost (M6).</b>
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
 */
public final class ChiGateInsertionCostCalculator implements InsertionCostCalculator {

    private final InsertionCostCalculator delegate;
    private final double chiThreshold;

    public ChiGateInsertionCostCalculator(InsertionCostCalculator delegate, double chiThreshold) {
        this.delegate = delegate;
        this.chiThreshold = chiThreshold;
    }

    /** A DRT request is a parcel iff any of its passenger ids is a parcel-person. */
    static boolean isParcel(DrtRequest request) {
        return request.getPassengerIds().stream()
                .anyMatch(id -> SharedUse.isParcelPerson(id.toString()));
    }

    @Override
    public double calculate(DrtRequest drtRequest, Insertion insertion, DetourTimeInfo detourTimeInfo) {
        double cost = delegate.calculate(drtRequest, insertion, detourTimeInfo);
        if (isParcel(drtRequest)
                && detourTimeInfo != null
                && detourTimeInfo.getTotalTimeLoss() > chiThreshold) {
            return INFEASIBLE_SOLUTION_COST;
        }
        return cost;
    }
}
