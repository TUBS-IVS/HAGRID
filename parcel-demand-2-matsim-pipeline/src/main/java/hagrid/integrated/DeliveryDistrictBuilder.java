package hagrid.integrated;

import hagrid.utils.demand.Delivery;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns provider-separated deliveries into district-based delivery units for the INTEGRATED
 * scenarios (1c/1d). Three levels, in this order:
 *
 * <ol>
 *   <li><b>Pool</b> every delivery at the same segment coordinate into ONE {@link PooledStop} —
 *       the 892 Lausitz segments are served by 3.37 providers on average, so this removes ~2,231
 *       duplicate stops (and, for jsprit, duplicate jobs).</li>
 *   <li><b>Catchment</b>: each stop goes to its nearest OPEN depot. This ordering is what
 *       guarantees the depot is the nearest loading point for every stop it serves — assigning
 *       depots to freely-clustered districts does not.</li>
 *   <li><b>Split</b> oversized catchments at the job ceiling: if a depot's demand exceeds
 *       maxJobsPerDistrict, it is partitioned deterministically into sub-districts using a
 *       median-strip split along the longer bounding-box axis. Each sub-district gets a suffixed
 *       id (e.g., west#0, west#1) and the same parent depot.</li>
 * </ol>
 *
 * <p>The Baseline keeps one depot per provider and does NOT use this class
 * (spec 2026-08-17 D2).
 */
public final class DeliveryDistrictBuilder {

    private static final Logger LOG = LogManager.getLogger(DeliveryDistrictBuilder.class);

    /** One physical stop: all parcels of all providers at one demand segment. */
    public record PooledStop(Coord coord, int totalParcels, List<Delivery> parts) { }

    /** One district: the jsprit optimisation unit / pickup group, anchored at one depot. */
    public record District(String id, DepotNetwork.Depot depot, List<PooledStop> stops) { }

    private DeliveryDistrictBuilder() {}

    public static List<District> build(Collection<Delivery> deliveries,
                                       List<DepotNetwork.Depot> openDepots,
                                       int maxJobsPerDistrict) {
        if (openDepots == null || openDepots.isEmpty()) {
            throw new IllegalArgumentException("at least one open depot is required");
        }
        if (maxJobsPerDistrict < 1) {
            throw new IllegalArgumentException("maxJobsPerDistrict must be >= 1: " + maxJobsPerDistrict);
        }
        DepotNetwork network = new DepotNetwork(openDepots);

        // LinkedHashMap everywhere: district ids and service ids derive from this order.
        Map<String, List<Delivery>> bySegment = new LinkedHashMap<>();
        for (Delivery d : deliveries) {
            bySegment.computeIfAbsent(segmentKey(d.getCoordinate()), k -> new ArrayList<>()).add(d);
        }

        Map<String, List<PooledStop>> byDepot = new LinkedHashMap<>();
        for (List<Delivery> parts : bySegment.values()) {
            Coord coord = parts.get(0).getCoordinate();
            int total = parts.stream().mapToInt(Delivery::getAmount).sum();
            DepotNetwork.Depot depot = network.nearestDepot(coord);
            byDepot.computeIfAbsent(depot.id(), k -> new ArrayList<>())
                    .add(new PooledStop(coord, total, List.copyOf(parts)));
        }

        List<District> districts = new ArrayList<>();
        for (DepotNetwork.Depot depot : openDepots) {
            List<PooledStop> stops = byDepot.get(depot.id());
            if (stops == null || stops.isEmpty()) {
                LOG.info("depot {} has no demand in its catchment - no district built", depot.id());
                continue;
            }
            int parts = (int) Math.ceil(stops.size() / (double) maxJobsPerDistrict);
            if (parts <= 1) {
                districts.add(new District(depot.id(), depot, List.copyOf(stops)));
            } else {
                List<List<PooledStop>> chunks = splitEvenly(stops, parts);
                for (int i = 0; i < chunks.size(); i++) {
                    districts.add(new District(depot.id() + "#" + i, depot, List.copyOf(chunks.get(i))));
                }
                LOG.info("catchment {} has {} stops > ceiling {} -> split into {} districts",
                        depot.id(), stops.size(), maxJobsPerDistrict, chunks.size());
            }
        }
        LOG.info("DeliveryDistrictBuilder: {} deliveries -> {} pooled stops in {} district(s)",
                deliveries.size(), bySegment.size(), districts.size());
        return List.copyOf(districts);
    }

    /** Segments are identified by their exact coordinate (PANDA emits one point per segment). */
    private static String segmentKey(Coord c) {
        return c.getX() + "|" + c.getY();
    }

    /**
     * Deterministic compact split: sort along the wider bounding-box axis, then cut into {@code parts}
     * contiguous blocks of near-equal size. Chosen over {@code SameSizeKMeans} because level 2 only
     * needs a reproducible equal-size cut and the ELKI path is seeded randomly (see plan Task 3).
     */
    private static List<List<PooledStop>> splitEvenly(List<PooledStop> stops, int parts) {
        double minX = stops.stream().mapToDouble(s -> s.coord().getX()).min().orElseThrow();
        double maxX = stops.stream().mapToDouble(s -> s.coord().getX()).max().orElseThrow();
        double minY = stops.stream().mapToDouble(s -> s.coord().getY()).min().orElseThrow();
        double maxY = stops.stream().mapToDouble(s -> s.coord().getY()).max().orElseThrow();
        boolean byX = (maxX - minX) >= (maxY - minY);

        List<PooledStop> sorted = new ArrayList<>(stops);
        // Tie-break on the other axis so the order is total and reproducible.
        sorted.sort(byX
                ? java.util.Comparator.comparingDouble((PooledStop s) -> s.coord().getX())
                        .thenComparingDouble(s -> s.coord().getY())
                : java.util.Comparator.comparingDouble((PooledStop s) -> s.coord().getY())
                        .thenComparingDouble(s -> s.coord().getX()));

        List<List<PooledStop>> chunks = new ArrayList<>();
        int n = sorted.size();
        int base = n / parts;
        int remainder = n % parts;
        int from = 0;
        for (int i = 0; i < parts; i++) {
            int size = base + (i < remainder ? 1 : 0);
            chunks.add(new ArrayList<>(sorted.subList(from, from + size)));
            from += size;
        }
        return chunks;
    }

    /**
     * Resolves the {@code openDepots} config selection against the depot CSV. {@code null}/empty
     * means every depot stays open. Order follows the CSV so district ids are stable across runs.
     *
     * @throws IllegalArgumentException if a named depot is absent from the CSV — a typo would
     *                                  otherwise silently shrink the depot network and move every KPI
     */
    public static List<DepotNetwork.Depot> selectOpenDepots(Map<String, Coord> depotCoords,
                                                            List<String> openDepots) {
        if (openDepots == null || openDepots.isEmpty()) {
            return depotCoords.entrySet().stream()
                    .map(e -> new DepotNetwork.Depot(e.getKey(), e.getValue())).toList();
        }
        List<String> wanted = openDepots.stream().map(s -> s.trim().toLowerCase()).toList();
        for (String w : wanted) {
            if (!depotCoords.containsKey(w)) {
                throw new IllegalArgumentException("openDepots names an unknown depot '" + w
                        + "'. Available: " + depotCoords.keySet());
            }
        }
        return depotCoords.entrySet().stream()
                .filter(e -> wanted.contains(e.getKey()))
                .map(e -> new DepotNetwork.Depot(e.getKey(), e.getValue()))
                .toList();
    }
}
