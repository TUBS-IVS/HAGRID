package hagrid.core.routing.sections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Typed adapter for the independently prepared halt catalog. */
public record PreparedHaltCatalog(String carrierId, Map<String, Road> links, List<Job> jobs, String depotLinkId, List<TurnEvidence> turnEvidence) {
 public record TurnEvidence(String fromLinkId,String toLinkId,String kind,String sourceSha256,String fromNodeId,String toNodeId,double fromLengthMeters,double toLengthMeters) {}
 public PreparedHaltCatalog(String carrierId,Map<String,Road> links,List<Job> jobs,String depotLinkId) {this(carrierId,links,jobs,depotLinkId,List.of());}
 public record Point(double x, double y) {}
 public record Road(String fromNodeId, String toNodeId, double lengthMeters, double geometryLengthMeters, List<Point> orientedXY) {
  public Road { orientedXY=List.copyOf(orientedXY); }
 }
 public record Approach(String linkId,double fraction) {}
 public record Job(String jobId,String rawLinkId,List<Approach> approaches,String serviceMode,String sourceStopId,String anchorLinkId,Point sourceXY,Point anchorXY,List<Point> sourceStreetXY,boolean fixedOriginalLink) {
  public Job(String jobId,String rawLinkId,List<Approach> approaches,String serviceMode,String sourceStopId) {this(jobId,rawLinkId,approaches,serviceMode,sourceStopId,rawLinkId,null,null,List.of(),false);}
  public Job(String jobId,String rawLinkId,List<Approach> approaches,String serviceMode,String sourceStopId,String anchorLinkId,Point sourceXY) {this(jobId,rawLinkId,approaches,serviceMode,sourceStopId,anchorLinkId,sourceXY,null,List.of(),false);}
  public Job(String jobId,String rawLinkId,List<Approach> approaches,String serviceMode,String sourceStopId,String anchorLinkId,Point sourceXY,Point anchorXY,List<Point> sourceStreetXY) {this(jobId,rawLinkId,approaches,serviceMode,sourceStopId,anchorLinkId,sourceXY,anchorXY,sourceStreetXY,false);}
  public Job {
   approaches=List.copyOf(approaches); if(anchorLinkId==null || anchorLinkId.isBlank()) anchorLinkId=rawLinkId;
   if(anchorXY==null)anchorXY=sourceXY; sourceStreetXY=List.copyOf(sourceStreetXY==null?List.of():sourceStreetXY);
   if(sourceXY!=null&&!finite(sourceXY) || anchorXY!=null&&!finite(anchorXY))throw new IllegalArgumentException("Invalid source or anchor point for "+jobId);
   if(!sourceStreetXY.isEmpty()) {if(sourceStreetXY.size()<2)throw new IllegalArgumentException("Invalid source street geometry for "+jobId);for(var p:sourceStreetXY)if(!finite(p))throw new IllegalArgumentException("Invalid source street vertex for "+jobId);}
   if(sourceXY!=null && anchorXY!=null && distance(sourceXY,anchorXY)>1e-5) {
    if(sourceStopId==null || sourceStopId.isBlank() || sourceStreetXY.size()<2 || lineDistance(sourceXY,sourceStreetXY)>0.03 || lineDistance(anchorXY,sourceStreetXY)>0.03)throw new IllegalArgumentException("Unverified source street anchor for "+jobId);
   }
  }
  private static boolean finite(Point p){return p!=null && Double.isFinite(p.x()) && Double.isFinite(p.y());}
  private static double distance(Point a,Point b){return Math.hypot(a.x()-b.x(),a.y()-b.y());}
  private static double lineDistance(Point p,List<Point> line){double best=Double.POSITIVE_INFINITY;for(int i=1;i<line.size();i++){var a=line.get(i-1);var b=line.get(i);double dx=b.x()-a.x(),dy=b.y()-a.y(),d=dx*dx+dy*dy;double t=d==0?0:Math.max(0,Math.min(1,((p.x()-a.x())*dx+(p.y()-a.y())*dy)/d));best=Math.min(best,Math.hypot(p.x()-(a.x()+t*dx),p.y()-(a.y()+t*dy)));}return best;}
 }
 public PreparedHaltCatalog { links=Map.copyOf(links); jobs=List.copyOf(jobs); turnEvidence=List.copyOf(turnEvidence); }
 private static Point parsePoint(JsonNode n){if(n.isMissingNode()||n.isNull())return null;if(!n.isArray()||n.size()!=2||!n.get(0).isNumber()||!n.get(1).isNumber())throw new IllegalArgumentException("Invalid point");return new Point(n.get(0).asDouble(),n.get(1).asDouble());}
 public static PreparedHaltCatalog read(Path path) throws IOException {
  JsonNode root=new ObjectMapper().readTree(path.toFile());
  if(root.path("schemaVersion").asInt()!=1) throw new IllegalArgumentException("Unsupported halt catalog schema");
  if(!root.path("links").isObject() || !root.path("jobs").isArray() || root.path("carrierId").asText().isBlank() || root.path("depot").path("linkId").asText().isBlank()) throw new IllegalArgumentException("Malformed halt catalog");
  var roads=new LinkedHashMap<String,Road>();
  root.path("links").fields().forEachRemaining(e->{var n=e.getValue(); var points=new ArrayList<Point>(); n.path("orientedXY").forEach(p->points.add(new Point(p.get(0).asDouble(),p.get(1).asDouble()))); roads.put(e.getKey(),new Road(n.path("fromNodeId").asText(),n.path("toNodeId").asText(),n.path("lengthMeters").asDouble(),n.path("geometryLengthMeters").asDouble(),points));});
  var jobs=new ArrayList<Job>();
  for(var j:root.path("jobs")){var approaches=new ArrayList<Approach>(); for(var a:j.path("approaches")) approaches.add(new Approach(a.path("linkId").asText(),a.path("fraction").asDouble(Double.NaN))); var source=parsePoint(j.path("sourceXY"));var anchor=parsePoint(j.path("anchorXY"));var street=j.path("sourceStreetXY");var vertices=new ArrayList<Point>();if(!street.isMissingNode()&&!street.isNull()){if(!street.isArray())throw new IllegalArgumentException("Invalid sourceStreetXY");for(var vertex:street)vertices.add(parsePoint(vertex));} jobs.add(new Job(j.path("jobId").asText(),j.path("rawLinkId").asText(),approaches,j.path("serviceMode").asText(),j.path("sourceStopId").asText(),j.path("anchorLinkId").asText(j.path("rawLinkId").asText()),source,anchor,vertices,j.path("fixedOriginalLink").asBoolean(false)));}
  var evidence=new ArrayList<TurnEvidence>();
  var entries=root.path("turnEvidence");if(!entries.isMissingNode()&&!entries.isArray())throw new IllegalArgumentException("turnEvidence must be an array");
  for(var e:entries)evidence.add(new TurnEvidence(e.path("fromLinkId").asText(),e.path("toLinkId").asText(),e.path("kind").asText(),e.path("sourceSha256").asText(),e.path("fromNodeId").asText(),e.path("toNodeId").asText(),e.path("fromLengthMeters").asDouble(Double.NaN),e.path("toLengthMeters").asDouble(Double.NaN)));
  return new PreparedHaltCatalog(root.path("carrierId").asText(),roads,jobs,root.path("depot").path("linkId").asText(),evidence);
 }
}
