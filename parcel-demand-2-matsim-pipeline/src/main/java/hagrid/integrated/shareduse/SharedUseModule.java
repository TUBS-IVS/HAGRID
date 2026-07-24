package hagrid.integrated.shareduse;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.stops.MinimumStopDurationAdapter;
import org.matsim.contrib.drt.stops.ParallelStopTimeCalculator;
import org.matsim.contrib.drt.stops.PassengerStopDurationProvider;
import org.matsim.contrib.drt.stops.StopTimeCalculator;
import org.matsim.contrib.dvrp.load.DvrpLoadFromFleet;
import org.matsim.contrib.dvrp.load.DvrpLoadType;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared-Use (cargo-hitching) composition for a DRT mode. This is the
 * CONTROLLER-scope half: per-request stop dwell and the 2D fleet capacity.
 *
 * <p>It overrides three bindings that {@code DrtModeModule}/{@code FleetModule}
 * install by default, so it MUST be added via {@code controler.addOverridingModule}
 * AFTER {@code DrtConfigComposer.installModules} (the same override-ordering
 * mechanism {@code PtAndDrtFareModule} relies on):
 * <ul>
 *   <li>{@link PassengerStopDurationProvider} — replaced by a
 *       {@link SharedUseStopDurationProvider} snapshotting parcel load + dwell;</li>
 *   <li>{@link StopTimeCalculator} — a {@link ParallelStopTimeCalculator} (prices a
 *       shared pax+parcel stop as {@code max(durations)}) wrapped by a
 *       {@link MinimumStopDurationAdapter} floor, mirroring the native prebooking
 *       branch of {@code DrtModeModule};</li>
 *   <li>{@link DvrpLoadFromFleet} — gives every vehicle {@link SharedUse#PARCEL_SLOTS}
 *       parcel slots in addition to the fleet-XML seat scalar (the default fleet
 *       loader would leave {@code "parcels"} at 0).</li>
 * </ul>
 *
 * <p>The QSim overrides (the χ-gate on parcel insertion + the parcel-only retry
 * queue) are installed via {@code installOverridingQSimModule} in Task 5.
 */
public final class SharedUseModule extends AbstractDvrpModeModule {

    private final DrtConfigGroup drtCfg;
    /** χ-gate threshold; consumed by the QSim half installed in Task 5. */
    private final double chiThreshold;

    public SharedUseModule(DrtConfigGroup drtCfg, double chiThreshold) {
        super(drtCfg.getMode());
        this.drtCfg = drtCfg;
        this.chiThreshold = chiThreshold;
    }

    @Override
    public void install() {
        // Per-request dwell. Snapshot parcel load (for scaled depot pickup) and door dwell
        // (dropoff) from the population; parcel-persons are static, so the snapshot stays valid.
        bindModal(PassengerStopDurationProvider.class).toProvider(modalProvider(getter -> {
            Population population = getter.get(Population.class);
            Map<Id<Person>, Integer> loadById = new HashMap<>();
            Map<Id<Person>, Double> dwellById = new HashMap<>();
            population.getPersons().values().stream()
                    .filter(p -> SharedUse.isParcelPerson(p.getId().toString()))
                    .forEach(p -> {
                        Object load = p.getAttributes().getAttribute(SharedUse.LOAD_ATTRIBUTE);
                        if (load instanceof Number n) {
                            loadById.put(p.getId(), n.intValue());
                        }
                        Object dwell = p.getAttributes().getAttribute(SharedUse.DWELL_ATTRIBUTE);
                        if (dwell instanceof Number n) {
                            dwellById.put(p.getId(), n.doubleValue());
                        }
                    });
            return new SharedUseStopDurationProvider(drtCfg.getStopDuration(), loadById, dwellById);
        })).asEagerSingleton();

        bindModal(StopTimeCalculator.class).toProvider(modalProvider(getter ->
                new MinimumStopDurationAdapter(
                        new ParallelStopTimeCalculator(getter.getModal(PassengerStopDurationProvider.class)),
                        drtCfg.getStopDuration()))).asEagerSingleton();

        // 2D fleet capacity: fleet XML scalar -> seats ("passengers"), plus fixed parcel slots.
        bindModal(DvrpLoadFromFleet.class).toProvider(modalProvider(getter -> {
            DvrpLoadType loadType = getter.getModal(DvrpLoadType.class);
            return (capacity, vehicleId) -> loadType.fromMap(Map.<String, Number>of(
                    "passengers", capacity,                 // fleet XML scalar = seats
                    "parcels", SharedUse.PARCEL_SLOTS));     // repurposed back-bench volume
        })).asEagerSingleton();

        // QSim half installed in Task 5
    }
}
