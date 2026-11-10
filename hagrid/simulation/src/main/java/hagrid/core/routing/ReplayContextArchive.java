package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.*;
import org.matsim.core.network.io.*;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.vehicles.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** One certified graph/profile archive per immutable context, shared by sibling diagnostic cases. */
public final class ReplayContextArchive {
    private ReplayContextArchive() {}
    private static final ObjectMapper JSON = new ObjectMapper();
    // Bounded publication locks; never call a scoped owner while holding one.
    private static final Object[] PUBLICATION_LOCKS=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    /** Remove an owned archive after in-flight ZIP reads release their Windows handles. */
    public static void deleteArchive(Path archive)throws IOException {
        synchronized(publicationLock(archive)){Files.deleteIfExists(archive);}
    }
    static Object publicationLock(Path target) {
        return PUBLICATION_LOCKS[(target.toAbsolutePath().normalize().hashCode()&Integer.MAX_VALUE)%PUBLICATION_LOCKS.length];
    }
    private static final List<String> FILES = List.of("network.xml", "snapshot.bin", "context.json");
    record Profile(String id, String mode, double velocity, double fixed, double meter, double second) {}
    record Change(NetworkChangeEvent.ChangeType type, double value) {
        static Change of(NetworkChangeEvent.ChangeValue c) { return c == null ? null : new Change(c.getType(), c.getValue()); }
        NetworkChangeEvent.ChangeValue restore() { return new NetworkChangeEvent.ChangeValue(type, value); }
    }
    record Event(double start, List<String> links, Change speed, Change capacity, Change lanes) {
        static Event of(NetworkChangeEvent e) {
            return new Event(e.getStartTime(), e.getLinks().stream().map(l -> l.getId().toString()).sorted().toList(),
                    Change.of(e.getFreespeedChange()), Change.of(e.getFlowCapacityChange()), Change.of(e.getLanesChange()));
        }
        NetworkChangeEvent restore(Network n) {
            var e = new NetworkChangeEvent(start);
            for (String id : links) {
                var link = n.getLinks().get(Id.createLinkId(id));
                if (link == null) throw new IllegalArgumentException("Replay context event references missing link " + id);
                e.addLink(link);
            }
            e.setFreespeedChange(speed == null ? null : speed.restore());
            e.setFlowCapacityChange(capacity == null ? null : capacity.restore());
            e.setLanesChange(lanes == null ? null : lanes.restore());
            return e;
        }
    }
    record Metadata(String version, String fingerprint, ReplayConfiguration config, String name,
                    double capacityPeriod, double cellSize, double laneWidth, List<Profile> profiles, List<Event> events) {}

    /** Concurrent writers use private files and publish only verified complete archives atomically. */
    public static Path save(Path parent, RoutingContext context) throws IOException {
        Path shared = parent.resolve(".contexts");
        Files.createDirectories(shared);
        Path complete = shared.resolve(context.fingerprint() + ".zip");
        if (Files.exists(complete)) {
            try (var restored = read(complete)) {
                requireFingerprint(restored, context.fingerprint());
            }
            if(isCompleteArchive(complete))return complete;
            // A live temporary profile at this path must be upgraded to a portable export.
        }
        Path work = Files.createTempDirectory(shared, ".context-");
        Path archive = Files.createTempFile(shared, ".context-", ".pending");
        try (var lease = context.acquireQuery()) {
            Network n = context.network();
            try (var out = Files.newOutputStream(work.resolve("network.xml"))) { context.writeNetwork(out); }
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(work.resolve("snapshot.bin"))))) {
                context.snapshot().write(out);
            }
            var profiles = context.vehicleProfiles().values().stream()
                    .sorted(Comparator.comparing(p -> p.typeId().toString()))
                    .map(p -> new Profile(p.typeId().toString(), p.mode(), p.maximumVelocityMps(),
                            p.fixedCost(), p.costPerMeter(), p.costPerSecond())).toList();
            var events = n instanceof TimeDependentNetwork dynamic
                    ? dynamic.getNetworkChangeEvents().stream().map(Event::of).toList() : List.<Event>of();
            var metadata = new Metadata("hagrid-replay-context-v1", context.fingerprint(), ReplayConfiguration.of(context.config()),
                    n.getName(), n.getCapacityPeriod(), n.getEffectiveCellSize(), n.getEffectiveLaneWidth(), profiles, events);
            JSON.writeValue(work.resolve("context.json").toFile(), metadata);
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                for (String name : FILES) {
                    zip.putNextEntry(new ZipEntry(name)); Files.copy(work.resolve(name), zip); zip.closeEntry();
                }
            }
            try (var restored = read(archive)) { requireFingerprint(restored, context.fingerprint()); }
            synchronized(publicationLock(complete)) {
                Files.move(archive, complete, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return complete;
        } finally {
            Files.deleteIfExists(archive);
            for (String name : FILES) Files.deleteIfExists(work.resolve(name));
            Files.deleteIfExists(work);
        }
    }

    public static RoutingContext read(Path archive) throws IOException {
        if(!Files.isRegularFile(archive))throw new IOException("Missing replay context "+archive);
        return SharedRoutingContextArchives.readTemporary(archive);
    }
    static boolean isCompleteArchive(Path archive)throws IOException {
        synchronized(publicationLock(archive)) {
            try(var zip=new ZipFile(archive.toFile())){return zip.getEntry("network.xml")!=null;}
        }
    }
    static RoutingContext readComplete(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) throw new IOException("Missing replay context " + archive);
        Path work = Files.createTempDirectory(".read-context-");
        RoutingContext result = null;
        try {
            synchronized(publicationLock(archive)) {
            try (var zip = new ZipFile(archive.toFile())) {
                if (zip.size() != FILES.size()) throw new IOException("Incomplete replay context archive");
                for (String name : FILES) {
                    var entry = zip.getEntry(name);
                    if (entry == null || entry.isDirectory()) throw new IOException("Missing replay context payload " + name);
                    try (var in = zip.getInputStream(entry)) { Files.copy(in, work.resolve(name)); }
                }
            }
            }
            Metadata meta = JSON.readValue(work.resolve("context.json").toFile(), Metadata.class);
            if (!"hagrid-replay-context-v1".equals(meta.version())) throw new IOException("Unknown replay context version");
            var restoredConfig=meta.config().restore();
            var networkConfig = new NetworkConfigGroup(); networkConfig.setTimeVariantNetwork(!meta.events().isEmpty());
            Network n = NetworkUtils.createNetwork(networkConfig);
            new MatsimNetworkReader(n).readFile(work.resolve("network.xml").toString());
            n.setName(meta.name()); n.setCapacityPeriod(meta.capacityPeriod());
            n.setEffectiveCellSize(meta.cellSize()); n.setEffectiveLaneWidth(meta.laneWidth());
            NetworkUtils.setNetworkChangeEvents(n, meta.events().stream().map(e -> e.restore(n)).toList());
            var frozen=new RoutingNetworkSnapshot(n);
            TravelTimeSnapshot snapshot;
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(work.resolve("snapshot.bin"))))) {
                snapshot = TravelTimeSnapshot.read(in, frozen);
            }
            var types = new CarrierVehicleTypes();
            for (Profile p : meta.profiles()) {
                var type = VehicleUtils.createVehicleType(Id.create(p.id(), VehicleType.class), p.mode());
                type.setMaximumVelocity(p.velocity()); type.getCostInformation().setFixedCost(p.fixed());
                type.getCostInformation().setCostsPerMeter(p.meter()); type.getCostInformation().setCostsPerSecond(p.second());
                if (types.getVehicleTypes().putIfAbsent(type.getId(), type) != null) throw new IOException("Duplicate context profile " + p.id());
            }
            result = RoutingContext.create(frozen, types, snapshot, restoredConfig);
            requireFingerprint(result, meta.fingerprint());
            return result;
        } catch (IOException | RuntimeException e) {
            if (result != null) result.close();
            throw e;
        } finally {
            for (String name : FILES) Files.deleteIfExists(work.resolve(name));
            Files.deleteIfExists(work);
        }
    }
    static void requireFingerprint(RoutingContext context, String expected) throws IOException {
        if (!context.fingerprint().equals(expected)) throw new IOException("Replay context fingerprint mismatch");
    }
}
