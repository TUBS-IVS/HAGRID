package hagrid.integrated.drt;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.optimizer.rebalancing.demandestimator.ZonalDemandEstimator;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.DemandEstimatorAsTargetCalculator;
import org.matsim.contrib.drt.optimizer.rebalancing.targetcalculator.RebalancingTargetCalculator;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeQSimModule;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rebinds the DRT mode's {@link RebalancingTargetCalculator} to a
 * {@link ReturnToDepotTargetCalculator}: demand-based by day, depot-targeting in
 * the final window. Installed AFTER MultiModeDrtModule so it overrides the
 * default target-calculator binding.
 */
public final class ReturnToDepotRebalancingModule extends AbstractDvrpModeModule {

    private final List<Coord> depotCoords;
    private final double returnStart;
    private final double perDepotCapacity;
    private final double demandEstimationPeriod;

    public ReturnToDepotRebalancingModule(String mode, List<Coord> depotCoords,
                                          double returnStart, double perDepotCapacity,
                                          double demandEstimationPeriod) {
        super(mode);
        this.depotCoords = depotCoords;
        this.returnStart = returnStart;
        this.perDepotCapacity = perDepotCapacity;
        this.demandEstimationPeriod = demandEstimationPeriod;
    }

    @Override
    public void install() {
        // RebalancingTargetCalculator is bound inside a QSim child injector by
        // DrtModeMinCostFlowRebalancingModule (AbstractDvrpModeQSimModule scope).
        // To override it we must also bind inside the QSim scope via
        // installOverridingQSimModule — a controller-scope bindModal would create
        // a second, conflicting binding (BindingAlreadySet).
        installOverridingQSimModule(new AbstractDvrpModeQSimModule(getMode()) {
            @Override
            protected void configureQSim() {
                bindModal(RebalancingTargetCalculator.class).toProvider(modalProvider(getter -> {
                    ZoneSystem zones = getter.getModal(ZoneSystem.class);
                    ZonalDemandEstimator estimator = getter.getModal(ZonalDemandEstimator.class);
                    RebalancingTargetCalculator daytime =
                            new DemandEstimatorAsTargetCalculator(estimator, demandEstimationPeriod);
                    return new ReturnToDepotTargetCalculator(daytime,
                            buildDepotCapacities(zones, depotCoords, perDepotCapacity), returnStart);
                })).asEagerSingleton();
            }
        });
    }

    /**
     * Maps each depot to its zone and accumulates capacities: two depots sharing a 2km grid
     * cell yield one zone entry with 2x the per-depot capacity (a plain Set would silently
     * halve the return pull toward that depot pair).
     */
    static Map<Zone, Double> buildDepotCapacities(ZoneSystem zones, List<Coord> depotCoords,
                                                  double perDepotCapacity) {
        GeometryFactory gf = new GeometryFactory();
        Map<Zone, Double> capacities = new LinkedHashMap<>();
        for (Coord c : depotCoords) {
            zoneForCoord(zones, c, gf).ifPresent(z ->
                    capacities.merge(z, perDepotCapacity, Double::sum));
        }
        return capacities;
    }

    /**
     * ZoneSystem has no coord lookup: find the zone containing the coord (point-in-polygon),
     * else the zone with the nearest centroid.
     */
    private static Optional<Zone> zoneForCoord(ZoneSystem zones, Coord c, GeometryFactory gf) {
        Point p = gf.createPoint(new Coordinate(c.getX(), c.getY()));
        Zone nearest = null;
        double best = Double.MAX_VALUE;
        for (Zone z : zones.getZones().values()) {
            if (z.getPreparedGeometry() != null && z.getPreparedGeometry().contains(p)) {
                return Optional.of(z);
            }
            double d = CoordUtils.calcEuclideanDistance(c, z.getCentroid());
            if (d < best) { best = d; nearest = z; }
        }
        return Optional.ofNullable(nearest);
    }
}
