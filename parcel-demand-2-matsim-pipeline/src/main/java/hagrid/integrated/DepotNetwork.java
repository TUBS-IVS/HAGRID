package hagrid.integrated;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordUtils;

import java.util.List;

/**
 * The set of parameterised depots in/around the study area. Depots serve as parcel-pickup origins
 * (Shared-Use) and capsule-swap points (Modular). Parcels/requests are assigned to the nearest depot.
 */
public final class DepotNetwork {

    /** A single depot location. */
    public record Depot(String id, Coord coord) { }

    private final List<Depot> depots;

    public DepotNetwork(List<Depot> depots) {
        if (depots == null || depots.isEmpty()) {
            throw new IllegalArgumentException("DepotNetwork requires at least one depot");
        }
        this.depots = List.copyOf(depots);
    }

    /** Immutable list of configured depots. */
    public List<Depot> depots() {
        return depots;
    }

    /** Returns the depot with the smallest Euclidean distance to {@code coord}. */
    public Depot nearestDepot(Coord coord) {
        Depot best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Depot d : depots) {
            double dist = CoordUtils.calcEuclideanDistance(coord, d.coord());
            if (dist < bestDist) {
                bestDist = dist;
                best = d;
            }
        }
        return best;
    }
}
