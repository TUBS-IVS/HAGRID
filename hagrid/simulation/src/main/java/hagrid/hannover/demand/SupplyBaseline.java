package hagrid.hannover.demand;

import hagrid.core.simulation.CarrierInputValidator;
import hagrid.core.util.Hub;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.freight.carriers.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;

/** Immutable, parcel-weighted supply channels. No arrival-time coupling is implied. */
public final class SupplyBaseline {
    public enum Flow { INBOUND, OUTBOUND }
    public record Lane(String hubId, String laneId, Flow flow, Id<Link> depotLink, long baselineUnits) {
        public Lane {
            if (hubId == null || hubId.isBlank() || laneId == null || laneId.isBlank() || baselineUnits < 0)
                throw new IllegalArgumentException("Invalid supply lane");
            Objects.requireNonNull(flow); Objects.requireNonNull(depotLink);
        }
    }
    static final String FLOW = "supplyFlow", LANE = "supplyLaneId", TARGET = "supplyHubId", ORIGIN = "supplyOriginHubId";
    private final List<Lane> lanes;
    private final Map<Lane,String> origins;

    private SupplyBaseline(Collection<Lane> lanes, Map<Lane,String> origins) {
        this.lanes = lanes.stream().sorted(Comparator.comparing(Lane::hubId)
                .thenComparing(Lane::flow).thenComparing(Lane::laneId)).toList();
        var keys = new HashSet<String>();
        for (Lane lane : this.lanes) {
            String key = hagrid.core.util.RunRandomStreams.key(lane.hubId(), lane.flow().name(), lane.laneId());
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate supply lane " + lane.laneId());
        }
        this.origins = Map.copyOf(origins);
    }
    static SupplyBaseline of(Collection<Lane> lanes) { return new SupplyBaseline(lanes, Map.of()); }
    public List<Lane> lanes() { return lanes; }
    String origin(Lane lane) { return origins.get(lane); }

    /** Exact integer allocation, including very large inputs; ties use the stable lane ID. */
    public Map<Lane,Long> allocate(String hubId, Flow flow, long units) {
        if (units < 0) throw new IllegalArgumentException("Negative supply allocation");
        var selected = lanes.stream().filter(l -> l.hubId().equals(hubId) && l.flow() == flow).toList();
        BigInteger total = selected.stream().map(l -> BigInteger.valueOf(l.baselineUnits())).reduce(BigInteger.ZERO, BigInteger::add);
        if (units > 0 && total.signum() == 0) throw new IllegalArgumentException("Positive supply demand with zero baseline for " + hubId + "/" + flow);
        var allocations = new LinkedHashMap<Lane,Long>();
        var remainders = new HashMap<Lane,BigInteger>();
        long assigned = 0;
        for (Lane lane : selected) {
            BigInteger[] parts = total.signum() == 0 ? new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO}
                    : BigInteger.valueOf(units).multiply(BigInteger.valueOf(lane.baselineUnits())).divideAndRemainder(total);
            long count = parts[0].longValueExact();
            allocations.put(lane, count); remainders.put(lane, parts[1]); assigned = Math.addExact(assigned, count);
        }
        var byRemainder = selected.stream().sorted(Comparator.<Lane,BigInteger>comparing(remainders::get).reversed()
                .thenComparing(Lane::laneId)).toList();
        long remaining = units - assigned;
        for (int i = 0; i < remaining; i++) allocations.compute(byRemainder.get(i), (key,count) -> count + 1);
        return Collections.unmodifiableMap(allocations);
    }

    /** A central inbound channel also carries the demand of its recorded outbound destinations. */
    long requiredUnits(String hubId, Flow flow, Map<Id<Hub>,Hub> hubs) {
        long units = requireHub(hubId, hubs).getAssignedSupplyDemand();
        if (flow == Flow.INBOUND) {
            var receivers = new TreeSet<String>();
            origins.forEach((lane,origin) -> { if (origin.equals(hubId) && lane.flow() == Flow.OUTBOUND) receivers.add(lane.hubId()); });
            for (String receiver : receivers) if (!receiver.equals(hubId))
                units = Math.addExact(units, requireHub(receiver,hubs).getAssignedSupplyDemand());
        }
        return units;
    }

    public static SupplyBaseline read(Path file, CarrierVehicleTypes types, Network network, Map<Id<Hub>,Hub> hubs) {
        var carriers = new Carriers();
        new CarrierPlanXmlReader(carriers, types).readFile(file.toString());
        CarrierInputValidator.validate(carriers, types, network);
        var result = new ArrayList<Lane>();
        var origins = new HashMap<Lane,String>();
        var access = new CarrierGenerationAccess(network, network);
        for (Carrier carrier : carriers.getCarriers().values()) {
            if (!carrier.getShipments().isEmpty()) throw new IllegalArgumentException("Supply baseline requires service jobs: " + carrier.getId());
            String owner = attribute(carrier, "hubId");
            if (owner == null) {
                var matches = hubs.values().stream().filter(h -> carrier.getId().toString().equals("supply_" + h.getId().toString().replace("/", "_"))
                        || carrier.getId().toString().startsWith("supply_" + h.getId().toString().replace("/", "_") + "_")).toList();
                if (matches.size() != 1) throw new IllegalArgumentException("Missing unambiguous supply hub for " + carrier.getId());
                owner = matches.get(0).getId().toString();
            }
            requireHub(owner,hubs);
            String depotValue = attribute(carrier,"supplyLink");
            if (depotValue == null) throw new IllegalArgumentException("Missing baseline supplyLink for " + carrier.getId());
            Id<Link> depot = Id.createLinkId(depotValue);
            if (!network.getLinks().containsKey(depot) || carrier.getCarrierCapabilities().getCarrierVehicles().values().stream()
                    .anyMatch(v -> !depot.equals(v.getLinkId()))) throw new IllegalArgumentException("Invalid supply depot for " + carrier.getId());
            String flowValue = attribute(carrier,FLOW);
            Flow flow = flowValue == null ? (carrier.getId().toString().contains("from_dhl_anderten") ? Flow.OUTBOUND : Flow.INBOUND)
                    : Flow.valueOf(flowValue);
            String laneId = attribute(carrier,LANE);
            if (laneId == null) laneId = carrier.getId().toString();
            String origin = flow == Flow.OUTBOUND ? Optional.ofNullable(attribute(carrier,ORIGIN)).orElse(owner) : null;
            var counts = new TreeMap<String,Long>();
            for (CarrierService service : carrier.getServices().values()) {
                String target = Objects.toString(service.getAttributes().getAttribute(TARGET),null);
                if (target == null && flow == Flow.INBOUND) target = owner;
                if (target == null) {
                    var matches = hubs.values().stream().filter(h -> access.depotLink(h,CarriersUtils.getCarrierMode(carrier)).equals(service.getServiceLinkId())).toList();
                    if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous supply destination for " + service.getId());
                    target = matches.get(0).getId().toString();
                }
                Hub targetHub = requireHub(target,hubs);
                if (!access.depotLink(targetHub,CarriersUtils.getCarrierMode(carrier)).equals(service.getServiceLinkId()))
                    throw new IllegalArgumentException("Supply destination disagrees with job link: " + service.getId());
                counts.merge(target,(long)service.getCapacityDemand(), Math::addExact);
            }
            // Legacy production included empty DHL placeholders; they are not active lanes.
            if (counts.isEmpty()) continue;
            for (var entry : counts.entrySet()) {
                var lane = new Lane(entry.getKey(),laneId,flow,depot,entry.getValue());
                result.add(lane);
                if (origin != null) { requireHub(origin,hubs); origins.put(lane,origin); }
            }
        }
        return new SupplyBaseline(result,origins);
    }
    private static String attribute(Carrier carrier, String name) { return Objects.toString(carrier.getAttributes().getAttribute(name),null); }
    private static Hub requireHub(String id, Map<Id<Hub>,Hub> hubs) {
        Hub hub = hubs.get(Id.create(id,Hub.class));
        if (hub == null) throw new IllegalArgumentException("Unknown supply hub " + id);
        return hub;
    }
}
