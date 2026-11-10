package hagrid.core.routing;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Immutable mode/link travel times sampled at bin starts on [0, horizon).
 * Sources must provide mode-level values with null person/vehicle. Vehicle speed
 * limits are applied at lookup using captured link lengths. NaN explicitly means
 * no observation and requests the supplied fallback; other invalid values fail.
 * This piecewise-constant sampling does not assert FIFO or preserve interpolation.
 */
public final class TravelTimeSnapshot {
    private final Map<String, Map<Id<Link>, Profile>> profilesByMode;
    private final Map<String, TravelTime> accessByMode;
    private final int measurementIteration;
    private final double horizonSeconds;
    private final int binSizeSeconds;
    private final String fingerprint;
    private final String networkFingerprint;
    private final StorageStats storageStats;
    private final Set<String> constantModes;

    private TravelTimeSnapshot(Map<String, Map<Id<Link>, Profile>> profiles, int iteration, double horizon, int binSize, String networkFingerprint) {
        this.networkFingerprint = networkFingerprint;
        profilesByMode = Map.copyOf(profiles);
        constantModes=profilesByMode.entrySet().stream().filter(e->e.getValue().values().stream().allMatch(p->p.values.length==1))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        measurementIteration = iteration;
        horizonSeconds = horizon;
        binSizeSeconds = binSize;
        var access = new HashMap<String, TravelTime>();
        for (String mode : profilesByMode.keySet()) {
            access.put(mode, (link, time, person, vehicle) -> {
                if (!Double.isFinite(time) || time < 0 || time >= horizonSeconds) {
                    throw new RangeException("Travel time outside captured range [0, " + horizonSeconds + "): " + time);
                }
                Profile profile = profilesByMode.get(mode).get(link.getId());
                if (profile == null) throw new IllegalArgumentException("No captured link " + link.getId() + " for mode " + mode);
                double value = profile.valueAt((int) Math.floor(time / binSizeSeconds));
                if (vehicle != null) {
                    if (vehicle.getType() == null) throw new IllegalArgumentException("Vehicle has no type for mode " + mode);
                    if (vehicle.getType().hasNetworkMode() && !mode.equals(vehicle.getType().getNetworkMode())) {
                        throw new IllegalArgumentException("Vehicle mode " + vehicle.getType().getNetworkMode() + " disagrees with " + mode);
                    }
                    double velocity = vehicle.getType().getMaximumVelocity();
                    if (Double.isNaN(velocity) || velocity <= 0) throw new IllegalArgumentException("Invalid vehicle maximum velocity: " + velocity);
                    value = Math.max(value, profile.lengthMeters / velocity);
                }
                return value;
            });
        }
        accessByMode = Map.copyOf(access);
        fingerprint = calculateFingerprint();
        long count = 0, values = 0, bytes = 0;
        for (var modeProfiles : profilesByMode.values()) {
            for (Profile profile : modeProfiles.values()) {
                count++;
                values += profile.values.length;
                bytes += Double.BYTES + (long) profile.values.length * (Double.BYTES + Integer.BYTES);
            }
        }
        storageStats = new StorageStats(count, values, bytes);
    }

    public static TravelTimeSnapshot capture(Network network, Map<String, TravelTime> measuredByMode,
                                             Map<String, TravelTime> fallbackByMode, int measurementIteration,
                                             double horizonSeconds, int binSizeSeconds) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(measuredByMode, "measuredByMode");
        Objects.requireNonNull(fallbackByMode, "fallbackByMode");
        if (measurementIteration < 0) throw new IllegalArgumentException("measurement iteration must be non-negative");
        if (binSizeSeconds <= 0) throw new IllegalArgumentException("bin size must be positive");
        if (!Double.isFinite(horizonSeconds) || horizonSeconds <= 0) throw new IllegalArgumentException("horizon must be finite and positive");
        double binCount = Math.ceil(horizonSeconds / binSizeSeconds);
        if (binCount > Integer.MAX_VALUE) throw new IllegalArgumentException("bin count exceeds supported range");
        int bins = (int) binCount;
        Set<String> modes = new TreeSet<>();
        addModes(modes, measuredByMode);
        addModes(modes, fallbackByMode);
        if (modes.isEmpty()) throw new IllegalArgumentException("At least one measured or fallback mode is required");
        String networkFingerprint = network instanceof RoutingNetworkSnapshot frozenGraph?frozenGraph.fingerprint():RoutingNetworkIdentity.fingerprint(network);
        var captured = new HashMap<String, Map<Id<Link>, Profile>>();
        for (String mode : modes) {
            var links = new HashMap<Id<Link>, Profile>();
            TravelTime measured = measuredByMode.get(mode);
            TravelTime fallback = fallbackByMode.get(mode);
            for (Link link : network.getLinks().values()) {
                if (!link.getAllowedModes().contains(mode)) continue;
                double length = link.getLength();
                if (!Double.isFinite(length) || length < 0) throw new IllegalArgumentException("Invalid length for link " + link.getId());
                var profile = new ProfileBuilder();
                for (int bin = 0; bin < bins; bin++) {
                    double time = (double) bin * binSizeSeconds;
                    double value = measured == null ? Double.NaN : measured.getLinkTravelTime(link, time, null, null);
                    if (Double.isNaN(value)) {
                        if (fallback == null) throw new IllegalArgumentException("Missing observation and fallback for mode " + mode + ", link " + link.getId());
                        value = fallback.getLinkTravelTime(link, time, null, null);
                    }
                    if (!Double.isFinite(value) || value < 0 || (length > 0 && value == 0)) {
                        throw new IllegalArgumentException("Invalid travel time for mode " + mode + ", link " + link.getId() + ", bin " + bin + ": " + value);
                    }
                    profile.append(bin, value);
                }
                links.put(link.getId(), profile.build(length,links.size()));
            }
            captured.put(mode, Map.copyOf(links));
        }
        String actualFingerprint = network instanceof RoutingNetworkSnapshot frozen
                ? frozen.fingerprint() : RoutingNetworkIdentity.fingerprint(network);
        if (!networkFingerprint.equals(actualFingerprint)) {
            throw new IllegalArgumentException("Network changed during travel-time snapshot capture");
        }
        return new TravelTimeSnapshot(captured, measurementIteration, horizonSeconds, binSizeSeconds, networkFingerprint);
    }

    private static void addModes(Set<String> modes, Map<String, TravelTime> sources) {
        for (var entry : sources.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("Mode names and travel-time providers must be specified");
            }
            modes.add(entry.getKey());
        }
    }

    /** Check compatibility once at context creation, never on each routing query. */
    void validateNetwork(Network network) {
        for (var modeEntry : profilesByMode.entrySet()) {
            String mode = modeEntry.getKey();
            var captured = modeEntry.getValue();
            long actualCount = network.getLinks().values().stream().filter(link -> link.getAllowedModes().contains(mode)).count();
            if (actualCount != captured.size()) {
                throw new IllegalArgumentException("Network link domain disagrees with travel-time snapshot for mode " + mode);
            }
            for (var entry : captured.entrySet()) {
                Link link = network.getLinks().get(entry.getKey());
                if (link == null || !link.getAllowedModes().contains(mode)
                        || Double.compare(link.getLength(), entry.getValue().lengthMeters) != 0) {
                    throw new IllegalArgumentException("Network link " + entry.getKey() + " disagrees with travel-time snapshot for mode " + mode);
                }
            }
        }
        String actualFingerprint = network instanceof RoutingNetworkSnapshot frozen
                ? frozen.fingerprint() : RoutingNetworkIdentity.fingerprint(network);
        if (!networkFingerprint.equals(actualFingerprint)) {
            throw new IllegalArgumentException("Network geometry, attributes or change events disagree with travel-time snapshot");
        }
    }
    public TravelTime forMode(String mode) {
        TravelTime access = accessByMode.get(mode);
        if (access == null) throw new IllegalArgumentException("No captured travel times for mode " + mode);
        return access;
    }

    void validateTime(double time) {
        if(!Double.isFinite(time) || time<0 || time>=horizonSeconds) throw new RangeException("Travel time outside captured range [0, "+horizonSeconds+"): "+time);
    }
    static final class RangeException extends IllegalArgumentException {
        RangeException(String message){super(message);}
    }
    List<RoutingProfileCompatibility.Violation> fifoViolations(String mode) {
        forMode(mode);var violations=new ArrayList<RoutingProfileCompatibility.Violation>();
        for(var id:profilesByMode.get(mode).keySet().stream().sorted(Comparator.comparing(Id::toString)).toList()) {
            var profile=profilesByMode.get(mode).get(id);
            for(int i=1;i<profile.values.length;i++)if(profile.values[i]<profile.values[i-1])
                violations.add(new RoutingProfileCompatibility.Violation(mode,id.toString(),profile.changeBins[i]*binSizeSeconds,
                        "Non-FIFO piecewise-constant profile"));
        }
        return List.copyOf(violations);
    }
    void validateFifo(String mode) {
        var violations=fifoViolations(mode);
        if(!violations.isEmpty()) {
            var first=violations.getFirst();throw new IllegalArgumentException(first.rule()+": mode "+mode+
                    ", link "+first.linkId()+", boundary "+first.boundarySeconds()+" s");
        }
    }
    boolean isConstant(String mode) {
        forMode(mode);
        return constantModes.contains(mode);
    }
    public String fingerprint() { return fingerprint; }
    public int measurementIteration() { return measurementIteration; }
    public double horizonSeconds() { return horizonSeconds; }
    public int binSizeSeconds() { return binSizeSeconds; }
    public Set<String> modes() { return profilesByMode.keySet(); }
    public StorageStats storageStats() { return storageStats; }

    /** Numeric payload only; excludes JVM objects, maps and array headers. Not a heap/working-set measurement. */
    public record StorageStats(long profileCount, long retainedValueCount, long numericPayloadBytes) {}

    /** Stream the compressed numeric profiles; no dense links-by-bins export is constructed. */
    void write(java.io.DataOutputStream out) throws IOException {
        out.writeUTF("hagrid-snapshot-export-v1");
        out.writeUTF(networkFingerprint);
        out.writeInt(measurementIteration);
        out.writeDouble(horizonSeconds);
        out.writeInt(binSizeSeconds);
        out.writeInt(profilesByMode.size());
        for (String mode : new TreeSet<>(profilesByMode.keySet())) {
            out.writeUTF(mode);
            var links = profilesByMode.get(mode);
            out.writeInt(links.size());
            for (var id : links.keySet().stream().sorted(Comparator.comparing(Id::toString)).toList()) {
                Profile profile = links.get(id);
                out.writeUTF(id.toString());
                out.writeDouble(profile.lengthMeters);
                out.writeInt(profile.values.length);
                for (int i = 0; i < profile.values.length; i++) {
                    out.writeInt(profile.changeBins[i]);
                    out.writeDouble(profile.values[i]);
                }
            }
        }
    }

    static TravelTimeSnapshot read(java.io.DataInputStream in, Network network) throws IOException {
        if (!"hagrid-snapshot-export-v1".equals(in.readUTF())) throw new IOException("Unknown snapshot version");
        String networkFingerprint = in.readUTF();
        int iteration = in.readInt();
        double horizon = in.readDouble();
        int binSize = in.readInt();
        if (iteration < 0 || !Double.isFinite(horizon) || horizon <= 0 || binSize <= 0
                || Math.ceil(horizon / binSize) > Integer.MAX_VALUE) throw new IOException("Invalid snapshot range");
        int bins = (int) Math.ceil(horizon / binSize);
        var networkModes = new HashSet<String>();
        network.getLinks().values().forEach(link -> networkModes.addAll(link.getAllowedModes()));
        int modes = in.readInt();
        if (modes < 1 || modes > networkModes.size()) throw new IOException("Invalid snapshot mode count");
        var captured = new HashMap<String, Map<Id<Link>, Profile>>();
        for (int m = 0; m < modes; m++) {
            String mode = in.readUTF();
            if (!networkModes.contains(mode) || captured.containsKey(mode)) throw new IOException("Invalid snapshot mode " + mode);
            int count = in.readInt();
            long expected = network.getLinks().values().stream().filter(link -> link.getAllowedModes().contains(mode)).count();
            if (count != expected) throw new IOException("Snapshot link count disagrees with network");
            var links = new HashMap<Id<Link>, Profile>();
            for (int l = 0; l < count; l++) {
                var id = Id.createLinkId(in.readUTF());
                Link link = network.getLinks().get(id);
                double length = in.readDouble();
                int changes = in.readInt();
                if (link == null || !link.getAllowedModes().contains(mode) || links.containsKey(id)
                        || Double.compare(length, link.getLength()) != 0 || changes < 1 || changes > bins)
                    throw new IOException("Invalid snapshot link profile " + id);
                var builder = new ProfileBuilder();
                int previous = -1;
                for (int c = 0; c < changes; c++) {
                    int bin = in.readInt();
                    double value = in.readDouble();
                    if ((c == 0 && bin != 0) || bin <= previous || bin >= bins || !Double.isFinite(value)
                            || value < 0 || (length > 0 && value == 0)) throw new IOException("Invalid snapshot transition " + id);
                    builder.append(bin, value); previous = bin;
                }
                links.put(id, builder.build(length,links.size()));
            }
            captured.put(mode, Map.copyOf(links));
        }
        if (in.read() != -1) throw new IOException("Trailing snapshot payload");
        var snapshot = new TravelTimeSnapshot(captured, iteration, horizon, binSize, networkFingerprint);
        // Both archive readers validate the decoded snapshot when creating their RoutingContext.
        return snapshot;
    }
    private String calculateFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                out.writeUTF("hagrid-travel-time-snapshot-v2-bin-start");
                out.writeUTF(networkFingerprint);
                out.writeInt(measurementIteration);
                out.writeDouble(horizonSeconds);
                out.writeInt(binSizeSeconds);
                out.writeInt(profilesByMode.size());
                for (String mode : new TreeSet<>(profilesByMode.keySet())) {
                    out.writeUTF(mode);
                    var links = profilesByMode.get(mode);
                    out.writeInt(links.size());
                    for (Id<Link> id : links.keySet().stream().sorted(Comparator.comparing(Id::toString)).toList()) {
                        Profile profile = links.get(id);
                        out.writeUTF(id.toString());
                        out.writeDouble(profile.lengthMeters);
                        out.writeInt(profile.values.length);
                        for (int i = 0; i < profile.values.length; i++) {
                            out.writeInt(profile.changeBins[i]);
                            out.writeDouble(profile.values[i]);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Cannot fingerprint captured travel times", impossible);
        }
    }

    /** Slot numbers belong to one frozen mode. Archive order and identifiers stay independent of them. */
    final class IndexedMode {
        private final Map<Id<Link>,Profile> byId;
        private final Profile[] profiles;
        private final Id<Link>[] ids;

        @SuppressWarnings("unchecked")
        IndexedMode(String mode) {
            byId=profilesByMode.get(mode);
            profiles=new Profile[byId.size()];
            ids=(Id<Link>[])new Id<?>[byId.size()];
            byId.forEach((id,profile)->{
                profiles[profile.slot]=profile;
                ids[profile.slot]=id;
            });
        }

        final class TimedPath {
            private final int[] slots;

            TimedPath(List<Id<Link>> middle,Id<Link> from,Id<Link> to) {
                slots=new int[from.equals(to)?0:middle.size()+1];
                if(slots.length>0) {
                    for(int i=0;i<middle.size();i++)slots[i]=byId.get(middle.get(i)).slot;
                    slots[slots.length-1]=byId.get(to).slot;
                }
            }

            double travelSeconds(double departure,double speed) {
                // The adapter validates the vehicle/mode once. Time validity still depends on each link entry.
                if(Double.isNaN(speed)||speed<=0)throw new IllegalArgumentException("Invalid vehicle maximum velocity: "+speed);
                double entry=departure;
                for(int slot:slots) {
                    validateTime(entry);
                    var profile=profiles[slot];
                    double measured=profile.valueAt((int)Math.floor(entry/binSizeSeconds));
                    entry+=Math.max(measured,profile.lengthMeters/speed);
                }
                return entry-departure;
            }

            List<Id<Link>> intermediateLinks() {
                if(slots.length<=1)return List.of();
                var links=new ArrayList<Id<Link>>(slots.length-1);
                for(int i=0;i<slots.length-1;i++)links.add(ids[slots[i]]);
                return links;
            }
        }
    }

    private record Profile(int slot, double lengthMeters, int[] changeBins, double[] values) {
        double valueAt(int bin) {
            int position = Arrays.binarySearch(changeBins, bin);
            if (position < 0) position = -position - 2;
            return values[position];
        }
    }

    /** Holds only transitions, avoiding a dense links x bins x vehicle table. */
    private static final class ProfileBuilder {
        private int[] bins = new int[1];
        private double[] values = new double[1];
        private int count;
        void append(int bin, double value) {
            if (count > 0 && values[count - 1] == value) return;
            if (count == values.length) {
                bins = Arrays.copyOf(bins, count * 2);
                values = Arrays.copyOf(values, count * 2);
            }
            bins[count] = bin;
            values[count++] = value;
        }
        Profile build(double length,int slot) {
            return new Profile(slot,length, Arrays.copyOf(bins, count), Arrays.copyOf(values, count));
        }
    }
}
