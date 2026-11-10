import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
final class FastDirectionProblem {
 static final ObjectMapper JSON=new ObjectMapper();
 record Loc(String id,String link,double fraction,int group){}
 record Leg(List<String> links,double meters,int turns,double startFraction,double endFraction){}
 final boolean indexedInputs;final double[] activityCosts; final JsonNode input;final double penalty;final List<Loc> states=new ArrayList<>();final List<JsonNode> tasks=new ArrayList<>();final Map<String,Integer> jobs=new HashMap<>();
 final double[] depotXY;final int[][] options;final Leg[][] legs;final DirectionalDP dp;final double[][] pairMeters,pairCosts;
 static final double RATE=3.86430173292559E-4,FIXED=189.15,ACTIVITY=.008;
 FastDirectionProblem(Path root,double penalty,boolean indexedInputs)throws Exception{
  this.indexedInputs=indexedInputs;
  this.penalty=penalty;input=JSON.readTree(root.resolve("joint-inputs.json").toFile());var cache=JSON.readTree(root.resolve("matrix/path-cache.json").toFile());var links=JSON.readTree(root.resolve("matrix/used-links.json").toFile());
  var depotCoords=links.path(input.path("depot").asText()).path("coords").get(1);depotXY=new double[]{depotCoords.get(0).asDouble(),depotCoords.get(1).asDouble()};
  states.add(new Loc("depot",input.path("depot").asText(),1,0));tasks.add(null);jobs.put("depot",0);options=new int[input.path("tasks").size()+1][];options[0]=new int[]{0};
  for(var t:input.path("tasks")){int i=tasks.size();tasks.add(t);jobs.put(t.path("id").asText(),i);options[i]=new int[t.path("options").size()];int k=0;for(var o:t.path("options")){options[i][k++]=states.size();states.add(new Loc(t.path("id").asText(),o.path("link").asText(),o.path("fraction").asDouble(),t.path("group").asInt()));}}
  activityCosts=new double[tasks.size()];for(int j=1;j<tasks.size();j++)activityCosts[j]=tasks.get(j).path("duration").asDouble()*ACTIVITY;
  legs=new Leg[states.size()][states.size()];double[][]cost=new double[states.size()][states.size()];
  for(int a=0;a<states.size();a++)for(int b=0;b<states.size();b++){
   var from=states.get(a);var to=states.get(b);double al=links.path(from.link).path("length").asDouble(),bl=links.path(to.link).path("length").asDouble();Leg l;
   if(from.link.equals(to.link)&&to.fraction>=from.fraction-1e-12)l=new Leg(List.of(from.link),Math.max(0,(to.fraction-from.fraction)*al),0,from.fraction,to.fraction);
   else {var p=cache.path(penalty+"|"+from.link+"|"+to.link);if(p.isMissingNode())throw new AssertionError("Missing road pair");var ids=new ArrayList<String>();p.path("links").forEach(x->ids.add(x.asText()));l=new Leg(ids,p.path("meters").asDouble()+(1-from.fraction)*al-(1-to.fraction)*bl,p.path("turns").asInt(),from.fraction,to.fraction);}
   legs[a][b]=l;cost[a][b]=l.meters*RATE+l.turns*penalty;
  }
  dp=new DirectionalDP(options,cost);pairMeters=new double[options.length][options.length];pairCosts=new double[options.length][options.length];
  for(int a=0;a<options.length;a++)for(int b=0;b<options.length;b++){double m=Double.POSITIVE_INFINITY,c=Double.POSITIVE_INFINITY;for(int x:options[a])for(int y:options[b]){m=Math.min(m,legs[x][y].meters);c=Math.min(c,cost[x][y]);}pairMeters[a][b]=m;pairCosts[a][b]=c;}
 }
 double activity(int job){return indexedInputs?activityCosts[job]:tasks.get(job).path("duration").asDouble()*ACTIVITY;}
 double fullCost(int[] order){double c=order.length==0?0:FIXED+dp.value(order);for(int j:order)c+=activity(j);return c;}
 Map<String,Object> output(int[] order){
  int[] choice=dp.new Route(order).chosenStates();var result=new LinkedHashMap<String,Object>();var list=new ArrayList<Object>();double meters=0;int turns=0;
  for(int i=1;i<choice.length;i++){var l=legs[choice[i-1]][choice[i]];meters+=l.meters;turns+=l.turns;list.add(Map.of("from",states.get(choice[i-1]),"to",states.get(choice[i]),"path",l));}
  if(Math.abs(dp.value(order)-meters*RATE-turns*penalty)>1e-8)throw new AssertionError("Decoded objective differs");
  result.put("penalty",penalty);result.put("meters",meters);result.put("turns",turns);result.put("cost",meters*RATE+turns*penalty);result.put("fullSearchCost",fullCost(order));result.put("visits",order.length);result.put("legs",list);return result;
 }
}
