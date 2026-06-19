package hagrid.integrated.drt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetWriter;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Generates a DVRP fleet vehicles file for the DRT fleet. Vehicles are anchored
 * on network links round-robin over sorted link ids (reproducible, no RNG).
 */
public final class DrtFleetGenerator {

    private DrtFleetGenerator() {}

    public static void write(Network net, int fleetSize, int capacity,
                             double serviceBegin, double serviceEnd, Path out) {
        if (fleetSize < 1) {
            throw new IllegalArgumentException("fleetSize must be >= 1, got " + fleetSize);
        }
        List<Id<Link>> linkIds = new ArrayList<>(net.getLinks().keySet());
        if (linkIds.isEmpty()) {
            throw new IllegalArgumentException("cannot place a DRT fleet: network has no links");
        }
        linkIds.sort(Comparator.comparing(Id::toString));

        List<DvrpVehicleSpecification> specs = new ArrayList<>(fleetSize);
        for (int i = 0; i < fleetSize; i++) {
            Id<Link> startLink = linkIds.get(i % linkIds.size());
            specs.add(ImmutableDvrpVehicleSpecification.newBuilder()
                    .id(Id.create("drt_" + i, DvrpVehicle.class))
                    .startLinkId(startLink)
                    .capacity(capacity)
                    .serviceBeginTime(serviceBegin)
                    .serviceEndTime(serviceEnd)
                    .build());
        }
        new FleetWriter(Stream.of(specs.toArray(new DvrpVehicleSpecification[0]))).write(out.toString());
    }
}
