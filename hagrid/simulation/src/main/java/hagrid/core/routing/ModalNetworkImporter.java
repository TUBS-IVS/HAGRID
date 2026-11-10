package hagrid.core.routing;

import hagrid.core.config.ResolvedHagridConfig;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.nio.file.Path;
import java.util.*;

/**
 * Loads a common network or explicitly merges compatible modal inputs.
 * Source graphs are not modified. Shared physical links must have compatible
 * scalars and attributes; their allowed modes and mode-specific turn rules are
 * combined. This importer does not remap stops or infer a bike/cargobike alias.
 */
public final class ModalNetworkImporter {
    private ModalNetworkImporter() {}

    public static Network load(ResolvedHagridConfig config) {
        Objects.requireNonNull(config, "config");
        return load(Path.of(config.routing().networkFile()), Optional.empty(), Map.of());
    }

    /** Optional second input is never opened for a car-only invocation. */
    public static Network load(Path commonFile, Optional<Path> additionalModalFile, Map<String, String> aliases) {
        Objects.requireNonNull(commonFile, "commonFile");
        Objects.requireNonNull(additionalModalFile, "additionalModalFile");
        var checked = checkedAliases(aliases);
        if (additionalModalFile.isEmpty() && checked.isEmpty()) return read(commonFile);
        var sources = new ArrayList<Network>();
        sources.add(read(commonFile));
        additionalModalFile.ifPresent(file -> sources.add(read(file)));
        return merge(sources, checked);
    }

    private static Network read(Path file) {
        var settings = new NetworkConfigGroup();
        settings.setTimeVariantNetwork(true);
        var network = NetworkUtils.createNetwork(settings);
        new MatsimNetworkReader(network).readFile(file.toAbsolutePath().toString());
        RoutingNetworkValidator.validate(network);
        return network;
    }

    /** Includes already attached NetworkChangeEvents; file-event loading belongs to the scenario loader. */
    public static Network merge(Collection<? extends Network> inputs, Map<String, String> aliases) {
        Objects.requireNonNull(inputs, "inputs");
        var modes = checkedAliases(aliases);
        var sources = List.copyOf(inputs);
        if (sources.isEmpty()) throw new IllegalArgumentException("At least one modal network is required");
        sources.forEach(RoutingNetworkValidator::validate);
        var settings = new NetworkConfigGroup();
        settings.setTimeVariantNetwork(true);
        var result = NetworkUtils.createNetwork(settings);
        var first = sources.getFirst();
        result.setName(first.getName());
        result.setCapacityPeriod(first.getCapacityPeriod());
        result.setEffectiveCellSize(first.getEffectiveCellSize());
        result.setEffectiveLaneWidth(first.getEffectiveLaneWidth());
        for (var source : sources) {
            requireEqual("network", "capacity period", result.getCapacityPeriod(), source.getCapacityPeriod());
            requireEqual("network", "cell size", result.getEffectiveCellSize(), source.getEffectiveCellSize());
            requireEqual("network", "lane width", result.getEffectiveLaneWidth(), source.getEffectiveLaneWidth());
            mergeAttributes(source.getAttributes(), result.getAttributes(), modes, "network");
            for (var node : source.getNodes().values()) {
                var existing = result.getNodes().get(node.getId());
                if (existing == null) {
                    existing = result.getFactory().createNode(node.getId(), RoutingData.coord(node.getCoord()));
                    result.addNode(existing);
                } else if (!RoutingData.hashValues(existing.getCoord()).equals(RoutingData.hashValues(node.getCoord()))) {
                    throw conflict("node " + node.getId(), "coordinates");
                }
                mergeAttributes(node.getAttributes(), existing.getAttributes(), modes, "node " + node.getId());
            }
            for (var link : source.getLinks().values()) {
                String entity = "link " + link.getId();
                var existing = result.getLinks().get(link.getId());
                if (existing == null) {
                    var from = result.getNodes().get(link.getFromNode().getId());
                    var to = result.getNodes().get(link.getToNode().getId());
                    if (from == null || to == null) throw conflict(entity, "missing endpoint");
                    existing = result.getFactory().createLink(link.getId(), from, to);
                    existing.setLength(link.getLength());
                    existing.setFreespeed(link.getFreespeed());
                    existing.setCapacity(link.getCapacity());
                    existing.setNumberOfLanes(link.getNumberOfLanes());
                    existing.setAllowedModes(normalize(link.getAllowedModes(), modes));
                    result.addLink(existing);
                } else {
                    if (!existing.getFromNode().getId().equals(link.getFromNode().getId())
                            || !existing.getToNode().getId().equals(link.getToNode().getId())) {
                        throw conflict(entity, "direction/endpoints");
                    }
                    requireEqual(entity, "length", existing.getLength(), link.getLength());
                    requireEqual(entity, "freespeed", existing.getFreespeed(), link.getFreespeed());
                    requireEqual(entity, "capacity", existing.getCapacity(), link.getCapacity());
                    requireEqual(entity, "lanes", existing.getNumberOfLanes(), link.getNumberOfLanes());
                    var allowed = new TreeSet<>(existing.getAllowedModes());
                    allowed.addAll(normalize(link.getAllowedModes(), modes));
                    existing.setAllowedModes(Set.copyOf(allowed));
                }
                mergeAttributes(link.getAttributes(), existing.getAttributes(), modes, entity);
            }
        }
        copyEvents(sources, result);
        return result;
    }

    private static Map<String, String> checkedAliases(Map<String, String> aliases) {
        Objects.requireNonNull(aliases, "aliases");
        for (var entry : aliases.entrySet()) {
            requireMode(entry.getKey());
            requireMode(entry.getValue());
            String next = aliases.get(entry.getValue());
            if (next != null && !next.equals(entry.getValue())) {
                throw new IllegalArgumentException("Mode aliases must point directly to final modes: " + entry.getKey());
            }
        }
        return Map.copyOf(aliases);
    }

    private static Set<String> normalize(Set<String> modes, Map<String, String> aliases) {
        var result = new TreeSet<String>();
        for (String mode : modes) {
            requireMode(mode);
            result.add(aliases.getOrDefault(mode, mode));
        }
        return Set.copyOf(result);
    }

    private static void requireMode(String mode) {
        if (mode == null || mode.isBlank()) throw new IllegalArgumentException("Network mode must be specified");
    }

    private static void mergeAttributes(Attributes source, Attributes target, Map<String, String> aliases, String entity) {
        for (var entry : source.getAsMap().entrySet()) {
            Object value = entry.getValue();
            boolean present = target.getAsMap().containsKey(entry.getKey());
            Object existing = target.getAttribute(entry.getKey());
            if (value instanceof DisallowedNextLinks incoming) {
                if (present && !(existing instanceof DisallowedNextLinks)) throw conflict(entity, "attribute " + entry.getKey());
                var merged = present ? ((DisallowedNextLinks) existing).copy() : new DisallowedNextLinks();
                incoming.getAsMap().forEach((mode, sequences) -> {
                    requireMode(mode);
                    String normalized = aliases.getOrDefault(mode, mode);
                    sequences.forEach(sequence -> merged.addDisallowedLinkSequence(normalized, List.copyOf(sequence)));
                });
                target.putAttribute(entry.getKey(), merged);
            } else if (!present) {
                target.putAttribute(entry.getKey(), RoutingData.copy(value));
            } else if (!RoutingData.hashValues(existing).equals(RoutingData.hashValues(value))) {
                throw conflict(entity, "attribute " + entry.getKey());
            }
        }
    }

    private static void copyEvents(List<? extends Network> sources, Network target) {
        var events = new LinkedHashMap<String, NetworkChangeEvent>();
        var occupied = new HashSet<EventSlot>();
        for (var source : sources) {
            if (!(source instanceof TimeDependentNetwork dynamic)) continue;
            var seenInSource = new HashSet<String>();
            for (var original : dynamic.getNetworkChangeEvents()) {
                if (!Double.isFinite(original.getStartTime()) || original.getStartTime() < 0
                        || original.getLinks().isEmpty()) throw new IllegalArgumentException("Invalid network change event");
                String signature = RoutingNetworkIdentity.eventFingerprint(original);
                if (!seenInSource.add(signature)) {
                    throw new IllegalArgumentException("Repeated identical network change event within one input");
                }
                if (events.containsKey(signature)) continue; // Same full event from multiple modal files: apply once.
                var copy = new NetworkChangeEvent(original.getStartTime());
                copy.setFreespeedChange(copyChange(original.getFreespeedChange()));
                copy.setFlowCapacityChange(copyChange(original.getFlowCapacityChange()));
                copy.setLanesChange(copyChange(original.getLanesChange()));
                for (var link : original.getLinks()) {
                    var mapped = target.getLinks().get(link.getId());
                    if (mapped == null) throw conflict("event link " + link.getId(), "missing link");
                    checkSlot(occupied, original, link.getId(), "freespeed", original.getFreespeedChange());
                    checkSlot(occupied, original, link.getId(), "capacity", original.getFlowCapacityChange());
                    checkSlot(occupied, original, link.getId(), "lanes", original.getLanesChange());
                    copy.addLink(mapped);
                }
                events.put(signature, copy);
            }
        }
        NetworkUtils.setNetworkChangeEvents(target, new ArrayList<>(events.values()));
    }

    private static void checkSlot(Set<EventSlot> occupied, NetworkChangeEvent event, Id<Link> link,
                                  String property, NetworkChangeEvent.ChangeValue change) {
        if (change == null) return;
        if (change.getType() == null || !Double.isFinite(change.getValue())) {
            throw conflict("event link " + link, "invalid " + property + " change");
        }
        if (!occupied.add(new EventSlot(event.getStartTime(), link, property))) {
            throw conflict("event link " + link, "overlapping " + property + " changes at " + event.getStartTime());
        }
    }

    private record EventSlot(double time, Id<Link> link, String property) {}

    private static NetworkChangeEvent.ChangeValue copyChange(NetworkChangeEvent.ChangeValue value) {
        return value == null ? null : new NetworkChangeEvent.ChangeValue(value.getType(), value.getValue());
    }

    private static void requireEqual(String entity, String field, double first, double second) {
        if (!Double.isFinite(first) || !Double.isFinite(second) || Double.compare(first, second) != 0) {
            throw conflict(entity, field);
        }
    }

    private static IllegalArgumentException conflict(String entity, String field) {
        return new IllegalArgumentException("Conflicting modal network " + entity + ": " + field);
    }
}

