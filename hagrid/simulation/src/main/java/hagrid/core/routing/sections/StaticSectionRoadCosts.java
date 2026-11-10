package hagrid.core.routing.sections;
import hagrid.core.routing.*;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Static link-state shortest paths. Only scalar rows are cached; paths are reconstructed on demand. */
public final class StaticSectionRoadCosts implements SectionRoadCosts {
 private final TurnPolicy turnPolicy;
 private final SplitRoadNetwork split;private final org.matsim.core.router.util.TravelTime travelTime;private final double penalty;private final String fingerprint;private final String topologyFingerprint;
 private final Map<Id<Link>,Id<Link>> raw;private final Map<Id<Link>,Set<Id<Link>>> reverse,unknown;
 private final Set<Id<Link>> endpoints=new HashSet<>();private final Map<Id<VehicleType>,VehicleType> profiles=new HashMap<>();private final Map<Id<VehicleType>,Profile> signatures=new HashMap<>();
 private final Map<Id<VehicleType>,ConcurrentHashMap<Id<Link>,Row>> rows;private final java.util.concurrent.atomic.LongAdder settledLinks=new java.util.concurrent.atomic.LongAdder();
 private final Map<Id<Link>,Integer> endpointIndex=new HashMap<>();
 private final List<Link> links;private final Map<Id<Link>,Integer> linkIndex;private final int[][] outgoing;
 private final ThreadLocal<Buffers> buffers;
 private static final class Buffers { final double[] cost,time,meters;final int[] turns,uncertain,previous;final boolean[] unknown;Buffers(int n){cost=new double[n];time=new double[n];meters=new double[n];turns=new int[n];uncertain=new int[n];previous=new int[n];unknown=new boolean[n];} }
 private record QueueEntry(int index,double cost,boolean unknown,double seconds) {}
 private record Row(double[] meters,double[] seconds,int[] turns,int[] uncertain,boolean[] unknown) {}
 private record State(Id<Link> link,double cost,double seconds,double meters,int turns,boolean unknown,State previous) {}
 private StaticSectionRoadCosts(Topology topology,SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,org.matsim.core.router.util.TravelTime travelTime,double penalty,TurnPolicy turnPolicy){
  this.turnPolicy=Objects.requireNonNull(turnPolicy);this.split=split;this.travelTime=travelTime;this.penalty=penalty;this.topologyFingerprint=topology.fingerprint;
  if(!topology.views.contains(split))throw new IllegalArgumentException("Road view was not included in shared topology");
  this.raw=topology.raw;this.reverse=topology.reverse;this.unknown=topology.unknown;this.links=topology.links;this.linkIndex=topology.linkIndex;this.outgoing=topology.outgoing;
  buffers=ThreadLocal.withInitial(()->new Buffers(links.size()));
  split.jobApproaches().values().forEach(as->as.forEach(a->endpoints.add(a.haltLinkId())));
  for(var v:vehicles){var type=v.getType();if(Double.isNaN(type.getMaximumVelocity())||type.getMaximumVelocity()<=0||!Double.isFinite(type.getCostInformation().getCostsPerMeter())||type.getCostInformation().getCostsPerMeter()<0||!Double.isFinite(type.getCostInformation().getCostsPerSecond())||type.getCostInformation().getCostsPerSecond()<0)throw new IllegalArgumentException("Invalid static routing profile " + type.getId());Profile signature=profileSignature(type);var previous=signatures.putIfAbsent(type.getId(),signature);if(previous!=null&&!previous.equals(signature))throw new IllegalArgumentException("Conflicting vehicle profiles " + type.getId());profiles.putIfAbsent(type.getId(),type);endpoints.add(resolveOriginalLink(v.getLinkId()));}
  var partitions=new HashMap<Id<VehicleType>,ConcurrentHashMap<Id<Link>,Row>>();for(var id:signatures.keySet())partitions.put(id,new ConcurrentHashMap<>());rows=Map.copyOf(partitions);
  var orderedEndpoints=endpoints.stream().sorted(Comparator.comparing(Object::toString)).toList();for(int i=0;i<orderedEndpoints.size();i++)endpointIndex.put(orderedEndpoints.get(i),i);
  for(var l:split.network().getLinks().values()){var restrictions=NetworkUtils.getDisallowedNextLinks(l);if(restrictions!=null) restrictions.getAsMap().forEach((m,seqs)->seqs.forEach(s->{if(s.size()!=1)throw new IllegalArgumentException("Unsupported multi-link turn restriction " + l.getId());}));}
  fingerprint=networkFingerprint();
 }
 /** Run-owned immutable topology shared by scoped per-carrier road tables. */
 public static final class Topology {
  private final Set<SplitRoadNetwork> views=Collections.newSetFromMap(new IdentityHashMap<>());
  private final Map<Id<Link>,Id<Link>> raw=new HashMap<>();
  private final Map<Id<Link>,Set<Id<Link>>> reverse=new HashMap<>(),unknown=new HashMap<>();
  private final List<Link> links;private final Map<Id<Link>,Integer> linkIndex=new HashMap<>();private final int[][] outgoing;private final String fingerprint;
  private Topology(Collection<SplitRoadNetwork> roads){
   if(roads==null||roads.isEmpty())throw new IllegalArgumentException("No road views");
   views.addAll(roads);if(views.contains(null))throw new IllegalArgumentException("Null road view");
   var split=roads.iterator().next();
   for(var view:views)if(view.network()!=split.network()||!view.children().equals(split.children())||!view.turnEvidence().equals(split.turnEvidence()))throw new IllegalArgumentException("Road views do not share topology and evidence");
  split.children().forEach((id,kids)->kids.forEach(k->raw.put(k,id)));
  var adjacency=new HashMap<Id<Node>,List<Link>>();
  split.network().getNodes().values().forEach(n->adjacency.put(n.getId(),new ArrayList<Link>(n.getOutLinks().values().stream().sorted(Comparator.comparing(l->l.getId().toString())).toList())));
  links=new ArrayList<Link>(split.network().getLinks().values().stream().sorted(Comparator.comparing(l->l.getId().toString())).toList());
  for(int i=0;i<links.size();i++)linkIndex.put(links.get(i).getId(),i);
  outgoing=new int[links.size()][];for(int i=0;i<links.size();i++)outgoing[i]=adjacency.get(links.get(i).getToNode().getId()).stream().mapToInt(l->linkIndex.get(l.getId())).toArray();
  // Catalog approach pairs carry upstream geometric verification; transit pairs require
  // unique, matching OSM way identity. Missing or ambiguous identity stays unknown.
  var byJunctions=new HashMap<String,List<Id<Link>>>();
  var ways=new HashMap<Id<Link>,String>();
  for(var e:split.children().entrySet()){
   var first=split.network().getLinks().get(e.getValue().getFirst());var last=split.network().getLinks().get(e.getValue().getLast());
   byJunctions.computeIfAbsent(first.getFromNode().getId()+"\u0000"+last.getToNode().getId(),k->new ArrayList<>()).add(e.getKey());
   var value=first.getAttributes().getAttribute("osm:way:id");if(value!=null&&!value.toString().isBlank())ways.put(e.getKey(),value.toString());
  }
  var verified=new HashMap<Id<Link>,Set<Id<Link>>>();
  for(var view:views)view.jobApproaches().values().forEach(as->{for(var a:as)for(var b:as)if(!a.originalLinkId().equals(b.originalLinkId()))verified.computeIfAbsent(a.originalLinkId(),k->new HashSet<>()).add(b.originalLinkId());});
  for(var e:split.children().entrySet()){
   var first=split.network().getLinks().get(e.getValue().getFirst());var last=split.network().getLinks().get(e.getValue().getLast());
   var candidates=byJunctions.getOrDefault(last.getToNode().getId()+"\u0000"+first.getFromNode().getId(),List.of());
   var sameWay=candidates.stream().filter(id->!id.equals(e.getKey())&&ways.containsKey(e.getKey())&&Objects.equals(ways.get(e.getKey()),ways.get(id))).toList();
   for(var other:candidates){if(other.equals(e.getKey()))continue;
    boolean trusted=verified.getOrDefault(e.getKey(),Set.of()).contains(other);
    String way=ways.get(e.getKey()),otherWay=ways.get(other);
    if(!trusted&&way!=null&&way.equals(otherWay)&&sameWay.size()==1){
     var back=byJunctions.getOrDefault(first.getFromNode().getId()+"\u0000"+last.getToNode().getId(),List.of());
     trusted=back.stream().filter(id->!id.equals(other)&&way.equals(ways.get(id))).count()==1;
    }
    if(trusted)reverse.computeIfAbsent(e.getKey(),k->new HashSet<>()).add(other);
    else if(way==null||otherWay==null||way.equals(otherWay))unknown.computeIfAbsent(e.getKey(),k->new HashSet<>()).add(other);
   }
  }
  // Prepared, source-bound evidence overrides only uncertain/heuristic transit pairs.
  var classifications=new HashMap<String,String>();
  for(var proof:split.turnEvidence()){
   var a=Id.createLinkId(proof.fromLinkId());var b=Id.createLinkId(proof.toLinkId());
   var ac=split.children().get(a);var bc=split.children().get(b);
   if(ac==null||bc==null||a.equals(b)||!proof.sourceSha256().matches("[0-9a-fA-F]{64}")||!Set.of("TURN","NOT_TURN").contains(proof.kind()))throw new IllegalArgumentException("Invalid prepared turn evidence " + a + " -> " + b);
   var af=split.network().getLinks().get(ac.getFirst());var al=split.network().getLinks().get(ac.getLast());var bf=split.network().getLinks().get(bc.getFirst());var bl=split.network().getLinks().get(bc.getLast());
   double alength=ac.stream().map(split.network().getLinks()::get).mapToDouble(Link::getLength).sum(),blength=bc.stream().map(split.network().getLinks()::get).mapToDouble(Link::getLength).sum();
   if(!af.getFromNode().getId().toString().equals(proof.fromNodeId())||!al.getToNode().getId().toString().equals(proof.toNodeId())||!bf.getFromNode().getId().toString().equals(proof.toNodeId())||!bl.getToNode().getId().toString().equals(proof.fromNodeId())||!sameLength(alength,proof.fromLengthMeters())||!sameLength(blength,proof.toLengthMeters()))throw new IllegalArgumentException("Prepared turn evidence disagrees with network " + a + " -> " + b);
   String key=a+"|"+b;var previous=classifications.putIfAbsent(key,proof.kind());
   if(previous!=null&&!previous.equals(proof.kind()))throw new IllegalArgumentException("Conflicting prepared turn evidence " + key);
   if(proof.kind().equals("NOT_TURN")&&(verified.getOrDefault(a,Set.of()).contains(b)||verified.getOrDefault(b,Set.of()).contains(a)))throw new IllegalArgumentException("Turn evidence contradicts verified halt approaches " + key);
   setEvidence(a,b,proof.kind());
  }
  fingerprint=graphFingerprint();
  }
  private String graphFingerprint(){
   try{
    var digest=java.security.MessageDigest.getInstance("SHA-256");
    java.util.function.Consumer<String> add=value->{byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);};
    views.stream().map(SplitRoadNetwork::fingerprint).sorted().forEach(add);
    for(var link:links){add.accept(link.getId()+"|"+link.getFromNode().getId()+"|"+link.getToNode().getId()+"|"+link.getLength()+"|"+link.getFreespeed()+"|"+new TreeSet<>(link.getAllowedModes())+"|"+String.valueOf(link.getAttributes().getAttribute("osm:way:id")));var restrictions=NetworkUtils.getDisallowedNextLinks(link);if(restrictions!=null)add.accept(new TreeMap<>(restrictions.getAsMap()).toString());}
    return HexFormat.of().formatHex(digest.digest());
   }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
  }
  private void setEvidence(Id<Link> a,Id<Link> b,String kind){var r=reverse.get(a);if(r!=null)r.remove(b);var u=unknown.get(a);if(u!=null)u.remove(b);if(kind.equals("TURN"))reverse.computeIfAbsent(a,k->new HashSet<>()).add(b);}
 }
 public static Topology prepareTopology(Collection<SplitRoadNetwork> views){return new Topology(views);}
 private static boolean sameLength(double a,double b){return Double.isFinite(b)&&b>=0&&Math.abs(a-b)<=Math.max(1e-6,Math.max(a,b)*1e-8);}
 private String networkFingerprint(){
  return SplitRoadNetworkBuilder.hash(topologyFingerprint+"|"+split.fingerprint()+"|"+penalty+"|"+turnPolicy+"|"+profiles.values().stream().map(t->t.getId()+":"+t.getNetworkMode()+":"+t.getMaximumVelocity()+":"+t.getCostInformation().getCostsPerMeter()+":"+t.getCostInformation().getCostsPerSecond()).sorted().toList());
 } public static StaticSectionRoadCosts prepare(SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,VRPTransportCosts source,double penalty){return prepare(split,vehicles,source,penalty,TurnPolicy.STRICT);}
 public static StaticSectionRoadCosts prepare(SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,VRPTransportCosts source,double penalty,TurnPolicy policy){
  Objects.requireNonNull(split);Objects.requireNonNull(vehicles);Objects.requireNonNull(source);
  if(!NativeModalTransportCosts.isCertifiedStatic(source))throw new IllegalArgumentException("Section routing requires a certified static transport profile");
  if(!Double.isFinite(penalty)||penalty<0||vehicles.isEmpty())throw new IllegalArgumentException("Invalid tariff or empty fleet");
  return prepare(prepareTopology(List.of(split)),split,vehicles,source,penalty,policy);
 }
 public static final class AmbiguousRoadTurnException extends IllegalStateException { public AmbiguousRoadTurnException(String message){super(message);} }
 public static StaticSectionRoadCosts prepare(Topology topology,SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,VRPTransportCosts source,double penalty){return prepare(topology,split,vehicles,source,penalty,TurnPolicy.STRICT);}
 public static StaticSectionRoadCosts prepare(Topology topology,SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,VRPTransportCosts source,double penalty,TurnPolicy policy){
  Objects.requireNonNull(topology);Objects.requireNonNull(split);Objects.requireNonNull(vehicles);Objects.requireNonNull(source);
  if(!NativeModalTransportCosts.isCertifiedStatic(source))throw new IllegalArgumentException("Section routing requires a certified static transport profile");
  if(!Double.isFinite(penalty)||penalty<0||vehicles.isEmpty())throw new IllegalArgumentException("Invalid tariff or empty fleet");
  return new StaticSectionRoadCosts(topology,split,vehicles,source.getTravelTime(),penalty,policy);
 }
 public static StaticSectionRoadCosts prepareFreeFlow(Topology topology,SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,double penalty){return prepareFreeFlow(topology,split,vehicles,penalty,TurnPolicy.STRICT);}
 public static StaticSectionRoadCosts prepareFreeFlow(Topology topology,SplitRoadNetwork split,Collection<CarrierVehicle> vehicles,double penalty,TurnPolicy policy){
  Objects.requireNonNull(topology);Objects.requireNonNull(split);Objects.requireNonNull(vehicles);
  if(split.network() instanceof org.matsim.core.network.TimeDependentNetwork dynamic&&!dynamic.getNetworkChangeEvents().isEmpty())throw new IllegalArgumentException("Time-dependent network events are unsupported in section mode");
  if(!Double.isFinite(penalty)||penalty<0||vehicles.isEmpty())throw new IllegalArgumentException("Invalid tariff or empty fleet");
  org.matsim.core.router.util.TravelTime freeFlow=(link,time,person,vehicle)->{double speed=Math.min(vehicle.getType().getMaximumVelocity(),link.getFreespeed());if(!(speed>0))throw new IllegalArgumentException("Invalid road speed " + link.getId());return link.getLength()/speed;};
  return new StaticSectionRoadCosts(topology,split,vehicles,freeFlow,penalty,policy);
 }
 @Override public TurnClassification classifyTransition(Id<Link> from,Id<Link> to){
  var a=raw.get(from);var b=raw.get(to);if(a==null||b==null)throw new IllegalArgumentException("Unknown split road link");
  if(a.equals(b))return TurnClassification.NOT_TURN;
  if(reverse.getOrDefault(a,Set.of()).contains(b))return TurnClassification.TURN;
  if(unknown.getOrDefault(a,Set.of()).contains(b))return TurnClassification.UNKNOWN;
  return TurnClassification.NOT_TURN;
 }
 public long unknownTransitionCount(){return unknown.values().stream().mapToLong(Set::size).sum();}
 @Override public TurnPolicy turnPolicy(){return turnPolicy;}
 public double uTurnPenalty(){return penalty;}
 public int cachedRowCount(){return rows.values().stream().mapToInt(Map::size).sum();}
 public long cachedAmbiguousODCount(){long n=0;for(var partition:rows.values())for(var row:partition.values())for(boolean value:row.unknown())if(value)n++;return n;}
 public long settledLinkCount(){return settledLinks.sum();}
 @Override public Id<Link> resolveOriginalLink(Id<Link> id){var children=split.children().get(id);return children==null?id:children.getLast();}
 @Override public String fingerprint(){return fingerprint;}
 @Override public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType type){
  requireType(type);from=resolveOriginalLink(from);to=resolveOriginalLink(to);
  Integer index=endpointIndex.get(to);if(index==null)throw new IllegalArgumentException("Not a prepared halt or depot " + to);
  if(!endpointIndex.containsKey(from))throw new IllegalArgumentException("Not a prepared halt or depot " + from);
  var typeRows=rows.get(type.getId());var values=typeRows.get(from);
  if(values==null)values=typeRows.computeIfAbsent(from,origin->row(search(origin,null,type,0).scalars));
  if(turnPolicy==TurnPolicy.STRICT && values.unknown()[index])throw new AmbiguousRoadTurnException("Uncertifiable road turn on optimal path from " + from + " to " + to);
  return new SectionLeg(values.meters()[index],values.seconds()[index],values.turns()[index],values.uncertain()[index]);
 }
 @Override public RoadLeg materialize(Id<Link> from,Id<Link> to,double departure,Vehicle vehicle){
  requireType(vehicle.getType());from=resolveOriginalLink(from);to=resolveOriginalLink(to);var result=search(from,to,vehicle.getType(),departure);var end=result.target;
  if(end==null)throw new NoRouteException("No road path from " + from + " to " + to);
  if(turnPolicy==TurnPolicy.STRICT && end.unknown()){
   for(var step=end;step.previous()!=null;step=step.previous())if(step.unknown()&&!step.previous().unknown())throw new AmbiguousRoadTurnException("Uncertifiable road turn " + step.previous().link() + " -> " + step.link() + " (raw " + raw.get(step.previous().link()) + " -> " + raw.get(step.link()) + ")");
   throw new AmbiguousRoadTurnException("Uncertifiable road turn from " + from + " to " + to);
  }
  var links=new ArrayList<Id<Link>>();for(var p=end;p.previous()!=null;p=p.previous())links.add(p.link());Collections.reverse(links);
  var route=RouteUtils.createLinkNetworkRouteImpl(from,to);route.setLinkIds(from,links.isEmpty()?List.of():links.subList(0,links.size()-1),to);route.setDistance(end.meters());route.setTravelTime(end.seconds());route.setVehicleId(vehicle.getId());
  return new RoadLeg(route,end.seconds(),end.meters(),end.cost());
 }
 private Row row(Map<Id<Link>,Scalar> values){
  var meters=new double[endpoints.size()];var seconds=new double[endpoints.size()];var turns=new int[endpoints.size()];var uncertain=new int[endpoints.size()];var unknownCells=new boolean[endpoints.size()];
  Arrays.fill(meters,Double.POSITIVE_INFINITY);Arrays.fill(seconds,Double.POSITIVE_INFINITY);
  values.forEach((id,value)->{int i=endpointIndex.get(id);meters[i]=value.leg().meters();seconds[i]=value.leg().seconds();turns[i]=value.leg().uTurns();uncertain[i]=value.leg().uncertainReversals();unknownCells[i]=value.unknown();});
  return new Row(meters,seconds,turns,uncertain,unknownCells);
 }
 private record Profile(String mode,double speed,double perMeter,double perSecond) {
  boolean matches(VehicleType type){var rates=type.getCostInformation();return Objects.equals(mode,type.getNetworkMode())&&Double.compare(speed,type.getMaximumVelocity())==0&&Double.compare(perMeter,rates.getCostsPerMeter())==0&&Double.compare(perSecond,rates.getCostsPerSecond())==0;}
 }
 private static Profile profileSignature(VehicleType type){var rates=type.getCostInformation();return new Profile(type.getNetworkMode(),type.getMaximumVelocity(),rates.getCostsPerMeter(),rates.getCostsPerSecond());}
 private void requireType(VehicleType type){var profile=signatures.get(type.getId());if(profile==null||!profile.matches(type))throw new IllegalArgumentException("Unknown or changed vehicle profile " + type.getId());}
 private record Scalar(SectionLeg leg,boolean unknown) {}
 private record Result(Map<Id<Link>,Scalar> scalars,State target) {}
 private Result search(Id<Link> from,Id<Link> target,VehicleType type,double departure){
  Integer origin=linkIndex.get(from);if(origin==null||!links.get(origin).getAllowedModes().contains(type.getNetworkMode()))throw new IllegalArgumentException("Invalid road origin " + from);
  var vehicle=VehicleUtils.createVehicle(Id.createVehicleId("section-profile:"+type.getId()),type);var rates=type.getCostInformation();
  var b=buffers.get();Arrays.fill(b.cost,Double.POSITIVE_INFINITY);Arrays.fill(b.time,Double.POSITIVE_INFINITY);Arrays.fill(b.previous,-1);
  b.cost[origin]=0;b.time[origin]=0;b.meters[origin]=0;b.turns[origin]=0;b.uncertain[origin]=0;b.unknown[origin]=false;
  // In conservative mode uncertainty already has a monetary tariff. Prefer time
  // before the boolean flag: a later unknown edge can erase a known-prefix tie.
  Comparator<QueueEntry> priority=Comparator.comparingDouble(QueueEntry::cost);
  priority=turnPolicy==TurnPolicy.NETWORK_REVERSAL
    ? priority.thenComparingDouble(QueueEntry::seconds).thenComparing(QueueEntry::unknown)
    : priority.thenComparing(QueueEntry::unknown).thenComparingDouble(QueueEntry::seconds);
  var queue=new PriorityQueue<QueueEntry>(priority.thenComparingInt(QueueEntry::index));
  queue.add(new QueueEntry(origin,0,false,0));Integer destination=target==null?null:linkIndex.get(target);int remaining=endpointIndex.size();
  while(!queue.isEmpty()){
   var current=queue.remove();int currentIndex=current.index();if(current.cost()!=b.cost[currentIndex]||current.unknown()!=b.unknown[currentIndex]||current.seconds()!=b.time[currentIndex])continue;
   settledLinks.increment();
   if(destination!=null&&currentIndex==destination)break;
   if(destination==null&&endpointIndex.containsKey(links.get(currentIndex).getId())&&--remaining==0)break;
   var link=links.get(currentIndex);var restrictions=NetworkUtils.getDisallowedNextLinks(link);
   for(int nextIndex:outgoing[currentIndex]){
    var next=links.get(nextIndex);if(!next.getAllowedModes().contains(type.getNetworkMode()))continue;
    if(restrictions!=null&&restrictions.getDisallowedLinkSequences(type.getNetworkMode()).contains(List.of(next.getId())))continue;
    double seconds=travelTime.getLinkTravelTime(next,departure+b.time[currentIndex],null,vehicle);if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid static travel time " + next.getId());
    var classification=classifyTransition(link.getId(),next.getId());boolean turn=classification==TurnClassification.TURN;boolean isUnknown=classification==TurnClassification.UNKNOWN;boolean uncertain=b.unknown[currentIndex]||isUnknown;
    boolean charged=turn || turnPolicy==TurnPolicy.NETWORK_REVERSAL && isUnknown;
    double cost=b.cost[currentIndex]+next.getLength()*rates.getCostsPerMeter()+seconds*rates.getCostsPerSecond()+(charged?penalty:0),time=b.time[currentIndex]+seconds;
    boolean betterTie=turnPolicy==TurnPolicy.NETWORK_REVERSAL
      ? time<b.time[nextIndex] || time==b.time[nextIndex]&&!uncertain&&b.unknown[nextIndex]
      : !uncertain&&b.unknown[nextIndex] || uncertain==b.unknown[nextIndex]&&time<b.time[nextIndex];
    if(cost<b.cost[nextIndex]||cost==b.cost[nextIndex]&&betterTie){
     b.cost[nextIndex]=cost;b.time[nextIndex]=time;b.meters[nextIndex]=b.meters[currentIndex]+next.getLength();b.turns[nextIndex]=b.turns[currentIndex]+(turn?1:0);b.uncertain[nextIndex]=b.uncertain[currentIndex]+(isUnknown?1:0);b.previous[nextIndex]=currentIndex;b.unknown[nextIndex]=uncertain;
     queue.add(new QueueEntry(nextIndex,cost,uncertain,time));
    }
   }
  }
  var scalars=new HashMap<Id<Link>,Scalar>();for(var id:endpoints){Integer i=linkIndex.get(id);if(i!=null&&Double.isFinite(b.cost[i]))scalars.put(id,new Scalar(new SectionLeg(b.meters[i],b.time[i],b.turns[i],b.uncertain[i]),b.unknown[i]));}
  State result=null;if(destination!=null&&Double.isFinite(b.cost[destination])){
   var path=new ArrayList<Integer>();for(int i=destination;i!=-1;i=b.previous[i])path.add(i);Collections.reverse(path);
   for(int i:path)result=new State(links.get(i).getId(),b.cost[i],b.time[i],b.meters[i],b.turns[i],b.unknown[i],result);
  }
  return new Result(Map.copyOf(scalars),result);
 }
}















