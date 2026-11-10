package hagrid.core.simulation;

import java.net.URL;
import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkChangeEventsParser;
import org.matsim.core.scenario.ScenarioUtils;

/** Lossless native event batching: every original link occurrence and timestamp is retained. */
public final class CompactNetworkChangeEvents {
    private static final int MAXIMUM_BUFFERED_LINKS = 100_000;
    private CompactNetworkChangeEvents() {}

    public record Statistics(long inputEvents, long outputEvents) {}

    /**
     * Native parser append sink. Only disjoint updates with equal full signatures share an event.
     * A repeated link flushes the whole bucket, preserving native duplicate-slot failure behavior.
     * Input events are never modified. Nonfinite, empty and internally duplicate inputs pass through.
     */
    static final class Collector extends AbstractList<NetworkChangeEvent> {
        private record Signature(double time, NetworkChangeEvent.ChangeValue speed,
                                 NetworkChangeEvent.ChangeValue capacity, NetworkChangeEvent.ChangeValue lanes) {}
        private final int limit;
        private final List<NetworkChangeEvent> completed = new ArrayList<>();
        private final Map<Signature, NetworkChangeEvent> pending = new LinkedHashMap<>();
        private final Set<Link> links = new HashSet<>();
        private long inputs;
        private double time;

        Collector(int limit) {
            if (limit < 1) throw new IllegalArgumentException("Positive event buffer limit required");
            this.limit = limit;
        }

        @Override public boolean add(NetworkChangeEvent event) {
            inputs++;
            if (event == null || !Double.isFinite(event.getStartTime())
                    || !finite(event.getFreespeedChange()) || !finite(event.getFlowCapacityChange())
                    || !finite(event.getLanesChange()) || event.getLinks().isEmpty()
                    || event.getLinks().size() > limit || hasRepeatedLinks(event)) {
                flush(); completed.add(event); return true;
            }
            if (!pending.isEmpty() && (Double.compare(time, event.getStartTime()) != 0
                    || event.getLinks().size() > limit - links.size()
                    || event.getLinks().stream().anyMatch(links::contains))) flush();
            time = event.getStartTime();
            var key = new Signature(time, event.getFreespeedChange(), event.getFlowCapacityChange(), event.getLanesChange());
            var merged = pending.get(key);
            if (merged == null) {
                merged = new NetworkChangeEvent(time);
                merged.setFreespeedChange(event.getFreespeedChange());
                merged.setFlowCapacityChange(event.getFlowCapacityChange());
                merged.setLanesChange(event.getLanesChange());
                pending.put(key, merged);
            }
            merged.addLinks(event.getLinks());
            links.addAll(event.getLinks());
            return true;
        }

        private static boolean finite(NetworkChangeEvent.ChangeValue value) {
            return value == null || (value.getType() != null && Double.isFinite(value.getValue()));
        }
        private static boolean hasRepeatedLinks(NetworkChangeEvent event) {
            return event.getLinks().size() > 1 && new HashSet<>(event.getLinks()).size() != event.getLinks().size();
        }
        private void flush() {
            completed.addAll(pending.values());pending.clear();links.clear();
        }
        List<NetworkChangeEvent> finish() { flush(); return completed; }
        long inputEvents() { return inputs; }
        @Override public int size() { return completed.size() + pending.size(); }
        @Override public NetworkChangeEvent get(int index) { return finish().get(index); }
    }

    public static Statistics load(Network network, URL input) {
        var collector = new Collector(MAXIMUM_BUFFERED_LINKS);
        new NetworkChangeEventsParser(network, collector).parse(input);
        return publish(network, collector);
    }

    /** For legacy modal inputs, merge their original event structures before calling this method. */
    public static Statistics compact(Network network) {
        var collector = new Collector(MAXIMUM_BUFFERED_LINKS);
        NetworkUtils.getNetworkChangeEvents(network).forEach(collector::add);
        return publish(network, collector);
    }

    private static Statistics publish(Network network, Collector collector) {
        var events = collector.finish();
        NetworkUtils.setNetworkChangeEvents(network, events);
        var statistics = new Statistics(collector.inputEvents(), events.size());
        LogManager.getLogger(CompactNetworkChangeEvents.class).info(
                "Native network changes compacted: {} input events, {} retained events; all link occurrences preserved",
                statistics.inputEvents(), statistics.outputEvents());
        return statistics;
    }

    /** Keeps normal scenario loading/route factories and native XML validation; restores config on failures. */
    public static void loadScenario(Scenario scenario) {
        var config = scenario.getConfig();
        var network = config.network();
        String input = network.getChangeEventsInputFile();
        if (!network.isTimeVariantNetwork() || input == null) {
            ScenarioUtils.loadScenario(scenario); return;
        }
        URL url = network.getChangeEventsInputFileUrl(config.getContext());
        network.setChangeEventsInputFile(null);
        try { ScenarioUtils.loadScenario(scenario); }
        finally { network.setChangeEventsInputFile(input); }
        load(scenario.getNetwork(), url);
    }
}
