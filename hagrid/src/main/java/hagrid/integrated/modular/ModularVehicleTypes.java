package hagrid.integrated.modular;

import com.google.common.base.Preconditions;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.CarrierVehicleTypeReader;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.Comparator;

/**
 * The U-Shift cargo capsule as a jsprit/carrier vehicle type (design D4: 7 depot groups,
 * ONLY the vehicle type swapped). Cost parameters are cloned from the largest van in the
 * existing LMD vehicle-types file so the jsprit objective stays comparable with the LMD
 * baseline instead of inventing new cost numbers; capacity is the 216-parcel capsule (D8).
 */
public final class ModularVehicleTypes {

    private ModularVehicleTypes() {}

    /** Returns a CarrierVehicleTypes holding EXACTLY one type: the cargo capsule. */
    public static CarrierVehicleTypes createCapsuleTypes(String vanTypesFile) {
        CarrierVehicleTypes vanTypes = new CarrierVehicleTypes();
        new CarrierVehicleTypeReader(vanTypes).readFile(vanTypesFile);
        // Cost donor = the largest-capacity van (VERIFY-SOURCE: VehicleCapacity.getOther() defaults
        // to Double.POSITIVE_INFINITY and is only ever set via the primitive setOther(double), so it
        // is never null here). Tie-break by type id keeps the pick total/deterministic even if two
        // vans somehow tied on capacity.
        VehicleType donor = vanTypes.getVehicleTypes().values().stream()
                .max(Comparator.<VehicleType>comparingDouble(t -> t.getCapacity().getOther())
                        .thenComparing(t -> t.getId().toString()))
                .orElseThrow(() -> new IllegalStateException(
                        "No van vehicle types in " + vanTypesFile));
        // review J-F7: VehicleCapacity.getOther() defaults to Double.POSITIVE_INFINITY for a van
        // type whose XML never sets an 'other' (parcel) capacity at all - such a van silently WINS
        // the max() above against every properly-specified van (a finite number never beats
        // infinity), making it the cost donor even though it isn't really "the largest van", it is
        // a data-entry omission. Cloning cost parameters from that accidental donor would be a
        // silent wrong result with no trace of why; naming the file here converts it into a loud,
        // actionable failure instead.
        Preconditions.checkState(Double.isFinite(donor.getCapacity().getOther()),
                "Van type '%s' in %s has no finite 'other' (parcel) capacity set - it won the"
                        + " cost-donor selection only because an unset capacity defaults to"
                        + " +Infinity, not because it is genuinely the largest van. Set an explicit"
                        + " capacity for every van in that file.",
                donor.getId(), vanTypesFile);

        VehicleType capsule = VehicleUtils.createVehicleType(
                Id.create(Modular.CARGO_CAPSULE_TYPE_ID, VehicleType.class));
        capsule.getCapacity().setOther((double) Modular.CARGO_CAPACITY_PARCELS);
        capsule.setNetworkMode(donor.getNetworkMode());
        // VERIFY-SOURCE: getMaximumVelocity() returns a primitive double (default
        // Double.POSITIVE_INFINITY), never null - copied as-is.
        capsule.setMaximumVelocity(donor.getMaximumVelocity());
        capsule.getCostInformation()
                .setCostsPerMeter(donor.getCostInformation().getCostsPerMeter())
                .setCostsPerSecond(donor.getCostInformation().getCostsPerSecond())
                .setFixedCost(donor.getCostInformation().getFixedCosts());

        CarrierVehicleTypes out = new CarrierVehicleTypes();
        out.getVehicleTypes().put(capsule.getId(), capsule);
        return out;
    }
}
