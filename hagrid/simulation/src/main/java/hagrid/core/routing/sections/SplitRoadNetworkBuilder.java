package hagrid.core.routing.sections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.network.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.core.network.turnRestrictions.DisallowedNextLinks;

public final class SplitRoadNetworkBuilder {
 private SplitRoadNetworkBuilder() {}
 /** Split once across all prepared carriers; each returned view keeps its own job namespace. */
 public static Map<String,SplitRoadNetwork> buildUnion(Network original, Collection<PreparedHaltCatalog> catalogs) {
  if(catalogs==null || catalogs.isEmpty())throw new IllegalArgumentException("No halt catalogs");
  var ordered=new TreeMap<String,PreparedHaltCatalog>();
  for(var c:catalogs)if(c==null || c.carrierId()==null || c.carrierId().isBlank() || ordered.putIfAbsent(c.carrierId(),c)!=null)throw new IllegalArgumentException("Duplicate or invalid carrier catalog");
  var roads=new LinkedHashMap<String,PreparedHaltCatalog.Road>();var allJobs=new ArrayList<PreparedHaltCatalog.Job>();
  var ids=new LinkedHashMap<String,Map<String,String>>();var evidence=new TreeMap<String,PreparedHaltCatalog.TurnEvidence>();int serial=0;
  for(var entry:ordered.entrySet()) {
   var c=entry.getValue();var local=new LinkedHashMap<String,String>();ids.put(entry.getKey(),local);
   for(var e:c.turnEvidence()){String key=e.fromLinkId().length()+":"+e.fromLinkId()+e.toLinkId().length()+":"+e.toLinkId();var prior=evidence.putIfAbsent(key,e);if(prior!=null&&!prior.equals(e))throw new IllegalArgumentException("Conflicting turn evidence "+key);}
   c.links().forEach((key,value)->{var old=roads.putIfAbsent(key,value);if(old!=null&&!old.equals(value))throw new IllegalArgumentException("Inconsistent geometry for "+key);});
   for(var job:c.jobs()) {
    String internal="union-job:"+(serial++);
    if(local.putIfAbsent(job.jobId(),internal)!=null)throw new IllegalArgumentException("Duplicate job "+job.jobId()+" in "+entry.getKey());
    allJobs.add(new PreparedHaltCatalog.Job(internal,job.rawLinkId(),job.approaches(),job.serviceMode(),job.sourceStopId(),job.anchorLinkId(),job.sourceXY(),job.anchorXY(),job.sourceStreetXY(),job.fixedOriginalLink()));
   }
  }
  var merged=build(original,new PreparedHaltCatalog("section-union",roads,allJobs,ordered.firstEntry().getValue().depotLinkId(),List.copyOf(evidence.values())));
  var network=merged.network();network.getAttributes().removeAttribute("section.carrierId");
  var views=new LinkedHashMap<String,SplitRoadNetwork>();
  for(var entry:ordered.entrySet()) {
   String carrier=entry.getKey();network.getAttributes().putAttribute(carrierKey(carrier),true);
   var jobs=new LinkedHashMap<String,List<HaltApproach>>();
   for(var job:entry.getValue().jobs()) {
    String internal=ids.get(carrier).get(job.jobId());jobs.put(job.jobId(),merged.jobApproaches().get(internal));
    network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"raw"),job.rawLinkId());
    network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"fixed"),job.fixedOriginalLink());
    network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"anchor"),job.anchorLinkId());
    network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"sourceAnchorDiff"),(job.sourceXY()!=null && job.anchorXY()!=null && Math.hypot(job.sourceXY().x()-job.anchorXY().x(),job.sourceXY().y()-job.anchorXY().y())>1e-5));
    if(job.sourceXY()!=null){network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"x"),job.sourceXY().x());network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"y"),job.sourceXY().y());}
    network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"mode"),job.serviceMode());
    network.getAttributes().putAttribute(jobKey(carrier,job.jobId(),"stop"),job.sourceStopId());
   }
   views.put(carrier,new SplitRoadNetwork(network,merged.children(),jobs,hash(merged.fingerprint()+"|"+carrier),merged.turnEvidence()));
  }
  return Collections.unmodifiableMap(views);
 }
 static String carrierKey(String carrier) {return "section.carrier."+hash(carrier);}
 static String jobKey(String carrier,String job,String field) {return "section.member."+hash(carrier.length()+":"+carrier+job.length()+":"+job)+"."+field;}
 public static SplitRoadNetwork build(Network original, PreparedHaltCatalog halts) {
  if(original==null || halts==null) throw new IllegalArgumentException("network and halt catalog are required");
  if(original instanceof TimeDependentNetwork dynamic && !dynamic.getNetworkChangeEvents().isEmpty()) throw new IllegalArgumentException("Time-dependent network events are unsupported in section mode");
  var fractions=new TreeMap<String,SortedSet<Double>>(); var seen=new HashSet<String>();
  for(var job:halts.jobs()) {
   if(!seen.add(job.jobId()) || job.approaches().isEmpty()) throw new IllegalArgumentException("Invalid job " + job.jobId());
   var rawLink=original.getLinks().get(Id.createLinkId(job.rawLinkId()));
   if(job.fixedOriginalLink()) {
    if(rawLink==null || !rawLink.getAllowedModes().contains("car") || !job.rawLinkId().equals(job.anchorLinkId()) || !Set.of("PARCEL_LOCKER","PARCEL_LOCKER_EXISTING").contains(job.serviceMode()) || job.sourceStopId()==null || job.sourceStopId().isBlank() || job.sourceXY()==null || !job.sourceStreetXY().isEmpty() || job.approaches().size()!=1 || !job.approaches().getFirst().linkId().equals(job.rawLinkId()) || Double.compare(job.approaches().getFirst().fraction(),1d)!=0)throw new IllegalArgumentException("Invalid fixed out-of-home binding for "+job.jobId());
    continue;
   }
   var anchorLink=original.getLinks().get(Id.createLinkId(job.anchorLinkId()));
   var anchorRoad=halts.links().get(job.anchorLinkId());
   if(rawLink==null || anchorLink==null || anchorRoad==null || !anchorRoad.fromNodeId().equals(anchorLink.getFromNode().getId().toString()) || !anchorRoad.toNodeId().equals(anchorLink.getToNode().getId().toString())) throw new IllegalArgumentException("Missing assigned or anchor road for job "+job.jobId());
   validateRoad(anchorRoad,anchorLink,job.jobId());
   boolean remapped=!job.rawLinkId().equals(job.anchorLinkId()) || (job.sourceXY()!=null && job.anchorXY()!=null && Math.hypot(job.sourceXY().x()-job.anchorXY().x(),job.sourceXY().y()-job.anchorXY().y())>1e-5);
   if(remapped && (job.sourceStopId()==null || job.sourceStopId().isBlank() || job.sourceXY()==null || !Double.isFinite(job.sourceXY().x()) || !Double.isFinite(job.sourceXY().y()))) throw new IllegalArgumentException("Missing remapped source identity for job "+job.jobId());
   for(var a:job.approaches()) {
    var id=Id.createLinkId(a.linkId()); var link=original.getLinks().get(id); var road=halts.links().get(a.linkId());
    if(link==null || road==null || !Double.isFinite(a.fraction()) || a.fraction()<0 || a.fraction()>1 || road.orientedXY().size()<2 || !road.fromNodeId().equals(link.getFromNode().getId().toString()) || !road.toNodeId().equals(link.getToNode().getId().toString())) throw new IllegalArgumentException("Invalid raw binding for job " + job.jobId()+" link "+a.linkId());
    validateRoad(road,link,job.jobId());
    if(remapped){var projected=coordinate(road,a.fraction()); if(Math.hypot(projected.getX()-job.anchorXY().x(),projected.getY()-job.anchorXY().y())>0.03)throw new IllegalArgumentException("Remapped source point does not match anchor for job "+job.jobId());}
    fractions.computeIfAbsent(a.linkId(),k->new TreeSet<>()).add(a.fraction());
   }
   if(job.approaches().stream().noneMatch(a->a.linkId().equals(job.anchorLinkId()))) {
    if(job.approaches().size()!=1)throw new IllegalArgumentException("Invalid reverse-only approach for job "+job.jobId());
    var reverse=halts.links().get(job.approaches().getFirst().linkId());
    if(!anchorRoad.fromNodeId().equals(reverse.toNodeId()) || !anchorRoad.toNodeId().equals(reverse.fromNodeId())) throw new IllegalArgumentException("Invalid reverse-only road for job "+job.jobId());
    validateReverseGeometry(anchorRoad,reverse,job.jobId());
   }
   if(job.approaches().size()>2)throw new IllegalArgumentException("Too many approaches for job "+job.jobId());
   if(job.approaches().size()==2){var a=job.approaches().getFirst(); var b=job.approaches().getLast(); var ar=halts.links().get(a.linkId()); var br=halts.links().get(b.linkId()); if(a.linkId().equals(b.linkId()) || !ar.fromNodeId().equals(br.toNodeId()) || !ar.toNodeId().equals(br.fromNodeId()) || Math.abs(a.fraction()+b.fraction()-1)>1e-8)throw new IllegalArgumentException("Invalid reverse approach for job "+job.jobId());validateReverseGeometry(ar,br,job.jobId());}
  }
  // Multi-link restrictions need history through stops, which this adapter cannot certify.
  for(var link:original.getLinks().values()) { var r=NetworkUtils.getDisallowedNextLinks(link); if(r!=null) r.getAsMap().forEach((m,seqs)->seqs.forEach(s->{if(s.size()!=1) throw new IllegalArgumentException("Unsupported multi-link turn restriction at "+link.getId());})); }
  Network out=NetworkUtils.createNetwork(); out.setName(original.getName()); out.setCapacityPeriod(original.getCapacityPeriod()); out.setEffectiveCellSize(original.getEffectiveCellSize()); out.setEffectiveLaneWidth(original.getEffectiveLaneWidth()); original.getAttributes().getAsMap().forEach(out.getAttributes()::putAttribute);
  out.getAttributes().putAttribute("section.carrierId",halts.carrierId()); out.getAttributes().putAttribute("section.depotLinkId",halts.depotLinkId());
  for(var e:halts.links().entrySet())out.getAttributes().putAttribute("section.geometryLength."+e.getKey(),e.getValue().geometryLengthMeters());
  for(var j:halts.jobs()){out.getAttributes().putAttribute("section.job."+j.jobId()+".raw",j.rawLinkId()); out.getAttributes().putAttribute("section.job."+j.jobId()+".fixed",j.fixedOriginalLink()); out.getAttributes().putAttribute("section.job."+j.jobId()+".anchor",j.anchorLinkId()); out.getAttributes().putAttribute("section.job."+j.jobId()+".sourceAnchorDiff",(j.sourceXY()!=null && j.anchorXY()!=null && Math.hypot(j.sourceXY().x()-j.anchorXY().x(),j.sourceXY().y()-j.anchorXY().y())>1e-5)); if(j.sourceXY()!=null){out.getAttributes().putAttribute("section.job."+j.jobId()+".x",j.sourceXY().x());out.getAttributes().putAttribute("section.job."+j.jobId()+".y",j.sourceXY().y());} out.getAttributes().putAttribute("section.job."+j.jobId()+".mode",j.serviceMode()); out.getAttributes().putAttribute("section.job."+j.jobId()+".stop",j.sourceStopId());}
  var factory=out.getFactory();
  for(var n:original.getNodes().values()){ var copy=factory.createNode(n.getId(),n.getCoord()); n.getAttributes().getAsMap().forEach(copy.getAttributes()::putAttribute); out.addNode(copy); }
  var children=new LinkedHashMap<Id<Link>,List<Id<Link>>>(); var endAt=new HashMap<String,Map<Double,Id<Link>>>();
  for(var raw:original.getLinks().values()) {
   var points=fractions.get(raw.getId().toString()); var ids=new ArrayList<Id<Link>>(); children.put(raw.getId(),ids);
   if(points==null) {var l=copy(factory,raw,raw.getId(),out.getNodes().get(raw.getFromNode().getId()),out.getNodes().get(raw.getToNode().getId()),raw.getLength()); out.addLink(l); ids.add(l.getId()); continue;}
   var bindings=new HashMap<Double,Id<Link>>(); endAt.put(raw.getId().toString(),bindings); Node from=out.getNodes().get(raw.getFromNode().getId());
   if(points.contains(0d)) {Node markerNode=factory.createNode(Id.createNodeId("section-node:"+raw.getId()+":0"),from.getCoord()); out.addNode(markerNode); Id<Link> id=Id.createLinkId("section-link:"+raw.getId()+":0"); var marker=copy(factory,raw,id,from,markerNode,0); out.addLink(marker); ids.add(id); bindings.put(0d,id); from=markerNode;}
   var cuts=new TreeSet<Double>(points); cuts.remove(0d); cuts.add(1d); double previous=0; int index=1;
   for(double f:cuts){Node to;if(f==1d) to=out.getNodes().get(raw.getToNode().getId()); else {to=factory.createNode(Id.createNodeId("section-node:"+raw.getId()+":"+index),coordinate(halts.links().get(raw.getId().toString()),f)); out.addNode(to);} Id<Link> id=Id.createLinkId("section-link:"+raw.getId()+":"+index++); var child=copy(factory,raw,id,from,to,raw.getLength()*(f-previous)); out.addLink(child); ids.add(id); bindings.put(f,id); previous=f; from=to;}
  }
  // Endpoint identity is semantic only for roads with multiple directed children.
  for(var raw:original.getLinks().values()) { var ids=children.get(raw.getId()); if(ids.size()>1) for(var id:ids) { var child=out.getLinks().get(id); child.getAttributes().putAttribute("sectionOriginalFromNodeId",raw.getFromNode().getId().toString()); child.getAttributes().putAttribute("sectionOriginalToNodeId",raw.getToNode().getId().toString()); } }
  for(var raw:original.getLinks().values()){var r=NetworkUtils.getDisallowedNextLinks(raw); if(r==null) continue; var mapped=new DisallowedNextLinks(); r.getAsMap().forEach((mode,seqs)->seqs.forEach(s->{var target=children.get(s.getFirst()); if(target==null) throw new IllegalArgumentException("Missing restricted link "+s.getFirst()); mapped.addDisallowedLinkSequence(mode,List.of(target.getFirst()));})); var last=out.getLinks().get(children.get(raw.getId()).getLast()); NetworkUtils.setDisallowedNextLinks(last,mapped);}
  var jobs=new LinkedHashMap<String,List<HaltApproach>>(); for(var j:halts.jobs()){var a=new ArrayList<HaltApproach>(); for(var p:j.approaches()) a.add(new HaltApproach(Id.createLinkId(p.linkId()),p.fraction(),j.fixedOriginalLink()?children.get(Id.createLinkId(p.linkId())).getLast():endAt.get(p.linkId()).get(p.fraction()))); jobs.put(j.jobId(),a);}
  return new SplitRoadNetwork(out,children,jobs,networkFingerprint(original,halts,fractions),halts.turnEvidence());
 }
 /** Full geometry must reverse; swapped endpoints alone also match parallel loops. */
 private static void validateReverseGeometry(PreparedHaltCatalog.Road a,PreparedHaltCatalog.Road b,String job) {
  var forward=a.orientedXY();var reverse=b.orientedXY();
  // The internal producer emits the same vertices in reverse order. Do not guess
  // equivalence for independently simplified polylines here.
  if(forward.size()!=reverse.size())throw new IllegalArgumentException("Unverified reverse geometry for job "+job);
  for(int i=0;i<forward.size();i++) {var p=forward.get(i);var q=reverse.get(reverse.size()-1-i);
   if(Math.hypot(p.x()-q.x(),p.y()-q.y())>1e-6)throw new IllegalArgumentException("Different road geometries in reverse approaches for job "+job);
  }
 }
 private static void validateRoad(PreparedHaltCatalog.Road road,Link link,String jobId) {
  if(!Double.isFinite(road.lengthMeters()) || road.lengthMeters()<=0 || !Double.isFinite(road.geometryLengthMeters()) || road.geometryLengthMeters()<=0 || !Double.isFinite(link.getLength()) || link.getLength()<=0 || Math.abs(road.lengthMeters()-link.getLength())>Math.max(1e-6,link.getLength()*1e-8)) throw new IllegalArgumentException("Invalid road length for job "+jobId+" link "+link.getId());
  double length=0; var points=road.orientedXY();
  for(int i=0;i<points.size();i++){var p=points.get(i);if(!Double.isFinite(p.x())||!Double.isFinite(p.y()))throw new IllegalArgumentException("Invalid geometry for job "+jobId+" link "+link.getId()); if(i>0){var prev=points.get(i-1);length+=Math.hypot(p.x()-prev.x(),p.y()-prev.y());}}
  if(!(length>0) || Math.abs(length-road.geometryLengthMeters())>Math.max(1e-5,length*1e-7))throw new IllegalArgumentException("Invalid geometry length for job "+jobId+" link "+link.getId());
 }
 private static Link copy(NetworkFactory f,Link raw,Id<Link> id,Node from,Node to,double length){var l=f.createLink(id,from,to); l.setLength(length); l.setFreespeed(raw.getFreespeed()); l.setCapacity(raw.getCapacity()); l.setNumberOfLanes(raw.getNumberOfLanes()); l.setAllowedModes(raw.getAllowedModes()); raw.getAttributes().getAsMap().forEach((k,v)->{if(!(v instanceof DisallowedNextLinks)) l.getAttributes().putAttribute(k,v);}); l.getAttributes().removeAttribute("sectionOriginalFromNodeId"); l.getAttributes().removeAttribute("sectionOriginalToNodeId"); l.getAttributes().putAttribute("sectionOriginalLinkId",raw.getId().toString()); return l;}
 private static Coord coordinate(PreparedHaltCatalog.Road road,double fraction){var p=road.orientedXY(); double total=0; for(int i=1;i<p.size();i++) total+=Math.hypot(p.get(i).x()-p.get(i-1).x(),p.get(i).y()-p.get(i-1).y()); double target=total*fraction, soFar=0; for(int i=1;i<p.size();i++){var a=p.get(i-1); var b=p.get(i); double d=Math.hypot(b.x()-a.x(),b.y()-a.y()); if(soFar+d>=target && d>0){double t=(target-soFar)/d; return new Coord(a.x()+t*(b.x()-a.x()),a.y()+t*(b.y()-a.y()));} soFar+=d;} var last=p.getLast();return new Coord(last.x(),last.y());}
 private static String networkFingerprint(Network network,PreparedHaltCatalog halts,Map<String,SortedSet<Double>> fractions) {
  try {var digest=MessageDigest.getInstance("SHA-256");
   java.util.function.Consumer<String> add=s->digest.update((s+"\n").getBytes(StandardCharsets.UTF_8));
   add.accept("section-network-v1|"+network.getCapacityPeriod()+"|"+network.getEffectiveCellSize()+"|"+network.getEffectiveLaneWidth()+"|"+canonical(network.getAttributes().getAsMap()));
   network.getNodes().values().stream().sorted(Comparator.comparing(n->n.getId().toString())).forEach(n->add.accept(n.getId()+"|"+n.getCoord()+"|"+canonical(n.getAttributes().getAsMap())));
   network.getLinks().values().stream().sorted(Comparator.comparing(l->l.getId().toString())).forEach(l->{var restriction=NetworkUtils.getDisallowedNextLinks(l);add.accept(l.getId()+"|"+l.getFromNode().getId()+"|"+l.getToNode().getId()+"|"+l.getLength()+"|"+l.getFreespeed()+"|"+l.getCapacity()+"|"+l.getNumberOfLanes()+"|"+new TreeSet<>(l.getAllowedModes())+"|"+(restriction==null?"":canonical(restriction.getAsMap()))+"|"+canonical(l.getAttributes().getAsMap().entrySet().stream().filter(e->!(e.getValue() instanceof DisallowedNextLinks)).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue))));});
   add.accept(halts.carrierId()+"|"+halts.depotLinkId());
   halts.turnEvidence().stream().map(Object::toString).sorted().forEach(add);
   for(var entry:fractions.entrySet())add.accept(entry.getKey()+"|"+entry.getValue()+"|"+halts.links().get(entry.getKey()));
   halts.jobs().stream().sorted(Comparator.comparing(PreparedHaltCatalog.Job::jobId)).forEach(j->add.accept(j.toString()));
   return HexFormat.of().formatHex(digest.digest());
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 static String canonical(Object value) {
  if(value==null)return "null";
  if(value instanceof hagrid.core.util.Hub h && value.getClass()==hagrid.core.util.Hub.class)return "hub:"+canonical(Arrays.asList(h.getId(),h.getProvider(),h.getAddress(),h.getType(),h.getCoord(),h.getLink(),h.getAssignedSupplyDemand(),h.getCapacityLimit(),h.isCapacityEnabled(),h.getWLSupplyDemandByProvider(),h.getAttributes().getAsMap()));
  if(value.getClass().isArray()){int n=java.lang.reflect.Array.getLength(value);var items=new ArrayList<String>(n);for(int i=0;i<n;i++)items.add(canonical(java.lang.reflect.Array.get(value,i)));return value.getClass().getName()+":"+items;}
  if(value instanceof Map<?,?> map){var entries=new TreeMap<String,String>(); map.forEach((k,v)->entries.put(String.valueOf(k),canonical(v)));return entries.toString();}
  if(value instanceof Set<?> set)return set.stream().map(SplitRoadNetworkBuilder::canonical).sorted().toList().toString();
  if(value instanceof Collection<?> collection)return collection.stream().map(SplitRoadNetworkBuilder::canonical).toList().toString();
  return value.getClass().getName()+":"+value;
 }
 static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
