package hagrid.integrated.modular;

import org.matsim.contrib.drt.optimizer.VehicleEntry;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;

/**
 * D2 strict lockout: from the moment a freight tour is spliced, the vehicle leaves the
 * passenger candidate set until the swap-back is performed. The capacity change alone does
 * NOT protect the approach/return legs (spike §3.2), and the drt-extensions predicate is too
 * narrow for multi-stop tours (spike §3.3) - hence the schedule-wide commitment predicate
 * ({@link Modular#hasUnperformedFreightTask}), shared verbatim with the tour dispatcher's own
 * idle-pool filter (Task 7) so both call sites stay in lockstep by construction.
 *
 * <p>VERIFY-SOURCE: {@code VehicleEntry.EntryFactory.create} returning {@code null} is the
 * sanctioned "exclude this vehicle from the passenger insertion candidate set" signal - the
 * drt-extensions {@code DrtServiceEntryFactory} is the precedent this decorator follows.
 */
public final class ModularEntryFactory implements VehicleEntry.EntryFactory {

    private final VehicleEntry.EntryFactory delegate;

    public ModularEntryFactory(VehicleEntry.EntryFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public VehicleEntry create(DvrpVehicle vehicle, double currentTime) {
        if (Modular.hasUnperformedFreightTask(vehicle.getSchedule())) {
            return null;
        }
        return delegate.create(vehicle, currentTime);
    }
}
