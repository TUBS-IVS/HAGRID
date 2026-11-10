package hagrid.core.routing;

import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.*;
import org.matsim.utils.objectattributes.attributable.*;
import java.util.*;

/** One detached graph per routing context. Public network, node, link and attribute views are read-only. */
final class RoutingNetworkSnapshot implements Network, TimeDependentNetwork {
    private final Network delegate;
    private final Map<Id<Node>,Node> nodes;
    private final Map<Id<Link>,Link> links;
    private final List<NetworkChangeEvent> events;
    private final Attributes attributes;
    private volatile String fingerprint;
    private final Map<Attributes,String> structuredAttributeHashes=new IdentityHashMap<>();

    RoutingNetworkSnapshot(Network source) { this(source,null); }
    RoutingNetworkSnapshot(Network source,String mode) {this(source,mode,true);}
    /** Mode networks are internal routing data; hash them only if a consumer actually requests it. */
    static RoutingNetworkSnapshot forMode(Network frozen,String mode) {
        if(!(frozen instanceof RoutingNetworkSnapshot))throw new IllegalArgumentException("Mode preparation requires an owned frozen graph");
        Objects.requireNonNull(mode);
        var expected=Set.of(mode);
        if(frozen.getLinks().values().stream().allMatch(link->link.getAllowedModes().equals(expected)))
            return (RoutingNetworkSnapshot)frozen;
        return new RoutingNetworkSnapshot(frozen,mode,false);
    }
    private RoutingNetworkSnapshot(Network source,String mode,boolean computeFingerprint) {
        List<NetworkChangeEvent> sourceEvents=source instanceof TimeDependentNetwork dynamic
                ? new ArrayList<>(dynamic.getNetworkChangeEvents()) : List.of();
        var config=new NetworkConfigGroup();
        config.setTimeVariantNetwork(!sourceEvents.isEmpty());
        delegate=NetworkUtils.createNetwork(config);
        delegate.setCapacityPeriod(source.getCapacityPeriod());
        delegate.setEffectiveCellSize(source.getEffectiveCellSize());
        delegate.setEffectiveLaneWidth(source.getEffectiveLaneWidth());
        delegate.setName(source.getName());
        copyAttributes(source.getAttributes(),delegate.getAttributes());
        for(Node node:source.getNodes().values()) {
            Node copy=delegate.getFactory().createNode(node.getId(),RoutingData.coord(node.getCoord()));
            copyAttributes(node.getAttributes(),copy.getAttributes()); delegate.addNode(copy);
        }
        for(Link link:source.getLinks().values()) {
            if(mode!=null && !link.getAllowedModes().contains(mode)) continue;
            Link copy=delegate.getFactory().createLink(link.getId(),delegate.getNodes().get(link.getFromNode().getId()),delegate.getNodes().get(link.getToNode().getId()));
            copy.setLength(link.getLength()); copy.setFreespeed(link.getFreespeed());
            copy.setCapacity(link.getCapacity()); copy.setNumberOfLanes(link.getNumberOfLanes()); copy.setAllowedModes(mode==null ? Set.copyOf(link.getAllowedModes()) : Set.of(mode));
            copyAttributes(link.getAttributes(),copy.getAttributes()); delegate.addLink(copy);
        }
        events=sourceEvents.stream().map(event -> {
            if(mode==null) return eventCopy(event,delegate.getLinks());
            var filtered=new NetworkChangeEvent(event.getStartTime());
            filtered.setFreespeedChange(copyChange(event.getFreespeedChange())); filtered.setFlowCapacityChange(copyChange(event.getFlowCapacityChange())); filtered.setLanesChange(copyChange(event.getLanesChange()));
            event.getLinks().stream().map(link->delegate.getLinks().get(link.getId())).filter(Objects::nonNull).forEach(filtered::addLink);
            return filtered;
        }).filter(event->!event.getLinks().isEmpty()).toList();
        NetworkUtils.setNetworkChangeEvents(delegate,events);
        var nodeViews=new LinkedHashMap<Id<Node>,Node>();
        var linkViews=new LinkedHashMap<Id<Link>,Link>();
        for(Node node:delegate.getNodes().values()) nodeViews.put(node.getId(),new NodeView(node));
        nodes=Collections.unmodifiableMap(nodeViews);
        for(Link link:delegate.getLinks().values()) linkViews.put(link.getId(),new LinkView(link));
        links=Collections.unmodifiableMap(linkViews);
        nodes.values().forEach(node -> ((NodeView) node).initializeAdjacency());
        attributes=new AttributeView(delegate.getAttributes());
        if(computeFingerprint)fingerprint=RoutingNetworkIdentity.fingerprint(delegate);
    }
    /** Conservative exact comparison: reordered events may miss reuse, changed data never reuse. */
    boolean matchesSource(Network source) {
        if(source==this)return true;
        if(source instanceof RoutingNetworkSnapshot frozen)return fingerprint().equals(frozen.fingerprint());
        if(!Objects.equals(getName(),source.getName())||Double.compare(getCapacityPeriod(),source.getCapacityPeriod())!=0
                ||Double.compare(getEffectiveCellSize(),source.getEffectiveCellSize())!=0||Double.compare(getEffectiveLaneWidth(),source.getEffectiveLaneWidth())!=0
                ||!sameAttributes(delegate.getAttributes(),source.getAttributes())||source.getNodes().size()!=nodes.size()||source.getLinks().size()!=links.size())return false;
        for(var n:delegate.getNodes().values()) {
            var other=source.getNodes().get(n.getId());if(other==null)return false;
            var a=n.getCoord();var b=other.getCoord();
            if(Double.compare(a.getX(),b.getX())!=0||Double.compare(a.getY(),b.getY())!=0||a.hasZ()!=b.hasZ()
                    ||a.hasZ()&&Double.compare(a.getZ(),b.getZ())!=0||!sameAttributes(n.getAttributes(),other.getAttributes()))return false;
        }
        for(var l:delegate.getLinks().values()) {
            var other=source.getLinks().get(l.getId());
            if(other==null||!l.getFromNode().getId().equals(other.getFromNode().getId())||!l.getToNode().getId().equals(other.getToNode().getId())
                    ||Double.compare(l.getLength(),other.getLength())!=0||Double.compare(l.getFreespeed(),other.getFreespeed())!=0
                    ||Double.compare(l.getCapacity(),other.getCapacity())!=0||Double.compare(l.getNumberOfLanes(),other.getNumberOfLanes())!=0
                    ||!l.getAllowedModes().equals(other.getAllowedModes())||!sameAttributes(l.getAttributes(),other.getAttributes()))return false;
        }
        var actual=source instanceof TimeDependentNetwork dynamic?dynamic.getNetworkChangeEvents():List.<NetworkChangeEvent>of();
        if(actual.size()!=events.size())return false;
        var iterator=actual.iterator();
        for(var event:events) {
            var other=iterator.next();
            if(Double.compare(event.getStartTime(),other.getStartTime())!=0||!sameChange(event.getFreespeedChange(),other.getFreespeedChange())
                    ||!sameChange(event.getFlowCapacityChange(),other.getFlowCapacityChange())||!sameChange(event.getLanesChange(),other.getLanesChange())
                    ||event.getLinks().size()!=other.getLinks().size())return false;
            var a=event.getLinks().iterator();var b=other.getLinks().iterator();
            while(a.hasNext())if(!a.next().getId().equals(b.next().getId()))return false;
        }
        return true;
    }
    private static boolean sameChange(NetworkChangeEvent.ChangeValue a,NetworkChangeEvent.ChangeValue b) {
        return a==null?b==null:b!=null&&a.getType()==b.getType()&&Double.compare(a.getValue(),b.getValue())==0;
    }
    private boolean sameAttributes(Attributes a,Attributes b) {
        var x=a.getAsMap();var y=b.getAsMap();
        var captured=structuredAttributeHashes.get(a);
        if(captured==null&&x.values().stream().allMatch(RoutingNetworkSnapshot::flatScalar)
                &&y.values().stream().allMatch(RoutingNetworkSnapshot::flatScalar))return x.equals(y);
        return (captured==null?RoutingData.hashValues(x):captured).equals(RoutingData.hashValues(y));
    }
    private static boolean flatScalar(Object value) {
        if(value==null)return true;
        var type=value.getClass();
        return type==String.class||type==Boolean.class||type==Character.class||type==Byte.class||type==Short.class
                ||type==Integer.class||type==Long.class||type==Float.class||type==Double.class
                ||type==java.math.BigInteger.class||type==java.math.BigDecimal.class
                ||type==java.time.LocalDate.class||type==java.time.LocalDateTime.class||type==java.time.Instant.class
                ||type==java.time.Duration.class||type==UUID.class;
    }
    synchronized String fingerprint() {
        if(fingerprint==null)fingerprint=RoutingNetworkIdentity.fingerprint(delegate);
        return fingerprint;
    }
    /** Native writer requires internal NodeImpl; expose only a stream, never the mutable delegate. */
    void writeNetwork(java.io.OutputStream stream) {
        new org.matsim.core.network.io.NetworkWriter(delegate).writeStreamV2(stream);
    }
    private static NetworkChangeEvent.ChangeValue copyChange(NetworkChangeEvent.ChangeValue value) {
        return value == null ? null : new NetworkChangeEvent.ChangeValue(value.getType(), value.getValue());
    }
    private static NetworkChangeEvent eventCopy(NetworkChangeEvent original,Map<Id<Link>,? extends Link> target) {
        var copy=new NetworkChangeEvent(original.getStartTime());
        copy.setFreespeedChange(copyChange(original.getFreespeedChange())); copy.setFlowCapacityChange(copyChange(original.getFlowCapacityChange())); copy.setLanesChange(copyChange(original.getLanesChange()));
        for(Link link:original.getLinks()) {
            Link mapped=target.get(link.getId()); if(mapped==null) throw new IllegalArgumentException("Network change event refers to missing link "+link.getId());
            copy.addLink(mapped);
        }
        return copy;
    }
    private void copyAttributes(Attributes source,Attributes target) {
        source.getAsMap().forEach((key,value)->target.putAttribute(key,RoutingData.copy(value)));
        if(!target.getAsMap().values().stream().allMatch(RoutingNetworkSnapshot::flatScalar))
            structuredAttributeHashes.put(target,RoutingData.hashValues(target.getAsMap()));
    }
    private static UnsupportedOperationException immutable() { return new UnsupportedOperationException("Routing network snapshot is read-only"); }

    public Map<Id<Node>,? extends Node> getNodes() { return nodes; }
    public Map<Id<Link>,? extends Link> getLinks() { return links; }
    public Attributes getAttributes() { return attributes; }
    public NetworkFactory getFactory() { throw immutable(); }
    public double getCapacityPeriod() { return delegate.getCapacityPeriod(); }
    public double getEffectiveLaneWidth() { return delegate.getEffectiveLaneWidth(); }
    public double getEffectiveCellSize() { return delegate.getEffectiveCellSize(); }
    public String getName() { return delegate.getName(); }
    public void addNode(Node node) { throw immutable(); }
    public void addLink(Link link) { throw immutable(); }
    public Node removeNode(Id<Node> id) { throw immutable(); }
    public Link removeLink(Id<Link> id) { throw immutable(); }
    public void setCapacityPeriod(double value) { throw immutable(); }
    public void setEffectiveLaneWidth(double value) { throw immutable(); }
    public void setEffectiveCellSize(double value) { throw immutable(); }
    public void setName(String value) { throw immutable(); }
    public Queue<NetworkChangeEvent> getNetworkChangeEvents() {
        var result=new PriorityQueue<NetworkChangeEvent>(Comparator.comparingDouble(NetworkChangeEvent::getStartTime));
        events.forEach(event -> result.add(eventCopy(event,links))); return result;
    }
    public void addNetworkChangeEvent(NetworkChangeEvent event) { throw immutable(); }
    public void setNetworkChangeEvents(List<NetworkChangeEvent> events) { throw immutable(); }

    private final class NodeView implements Node {
        private final Node node; private final Attributes attributes; private final Coord coordinate;
        private Map<Id<Link>,Link> inLinks, outLinks;
        NodeView(Node node) { this.node=node; coordinate=node.getCoord(); attributes=new AttributeView(node.getAttributes()); }
        public Id<Node> getId() { return node.getId(); }
        public Coord getCoord() { return coordinate; }
        public Attributes getAttributes() { return attributes; }
        void initializeAdjacency() { inLinks=linkView(node.getInLinks()); outLinks=linkView(node.getOutLinks()); }
        public Map<Id<Link>,? extends Link> getInLinks() { return inLinks; }
        public Map<Id<Link>,? extends Link> getOutLinks() { return outLinks; }
        public boolean addInLink(Link link) { throw immutable(); }
        public boolean addOutLink(Link link) { throw immutable(); }
        public Link removeInLink(Id<Link> id) { throw immutable(); }
        public Link removeOutLink(Id<Link> id) { throw immutable(); }
        public void setCoord(Coord coord) { throw immutable(); }
    }
    private Map<Id<Link>,Link> linkView(Map<Id<Link>,? extends Link> source) {
        var result=new LinkedHashMap<Id<Link>,Link>(); source.keySet().forEach(id -> result.put(id,links.get(id))); return Collections.unmodifiableMap(result);
    }
    private final class LinkView implements Link {
        private final Link link; private final Attributes attributes; private final Coord coordinate;
        LinkView(Link link) { this.link=link; coordinate=link.getCoord(); attributes=new AttributeView(link.getAttributes()); }
        public Id<Link> getId() { return link.getId(); }
        public Coord getCoord() { return coordinate; }
        public Node getFromNode() { return nodes.get(link.getFromNode().getId()); }
        public Node getToNode() { return nodes.get(link.getToNode().getId()); }
        public double getLength() { return link.getLength(); }
        public double getFreespeed() { return link.getFreespeed(); }
        public double getFreespeed(double time) { return link.getFreespeed(time); }
        public double getCapacity() { return link.getCapacity(); }
        public double getCapacity(double time) { return link.getCapacity(time); }
        public double getNumberOfLanes() { return link.getNumberOfLanes(); }
        public double getNumberOfLanes(double time) { return link.getNumberOfLanes(time); }
        public double getCapacityPeriod() { return link.getCapacityPeriod(); }
        public Set<String> getAllowedModes() { return link.getAllowedModes(); }
        public Attributes getAttributes() { return attributes; }
        public boolean setFromNode(Node node) { throw immutable(); }
        public boolean setToNode(Node node) { throw immutable(); }
        public void setLength(double value) { throw immutable(); }
        public void setFreespeed(double value) { throw immutable(); }
        public void setCapacity(double value) { throw immutable(); }
        public void setNumberOfLanes(double value) { throw immutable(); }
        public void setAllowedModes(Set<String> modes) { throw immutable(); }
    }
    private static final class AttributeView implements Attributes {
        private final Attributes attributes;
        AttributeView(Attributes attributes) { this.attributes=attributes; }
        public Object getAttribute(String name) { return RoutingData.copy(attributes.getAttribute(name)); }
        public Map<String,Object> getAsMap() {
            var result=new LinkedHashMap<String,Object>(); attributes.getAsMap().forEach((key,value) -> result.put(key,RoutingData.copy(value))); return Collections.unmodifiableMap(result);
        }
        public int size() { return attributes.size(); }
        public boolean isEmpty() { return attributes.isEmpty(); }
        public Object putAttribute(String name,Object value) { throw immutable(); }
        public Object removeAttribute(String name) { throw immutable(); }
        public void clear() { throw immutable(); }
    }
}