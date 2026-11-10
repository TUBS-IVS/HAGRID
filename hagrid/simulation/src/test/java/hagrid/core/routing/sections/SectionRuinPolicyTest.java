package hagrid.core.routing.sections;
import com.graphhopper.jsprit.core.algorithm.module.RuinAndRecreateModule;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.vehicle.*;
import com.graphhopper.jsprit.core.util.Coordinate;
import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SectionRuinPolicyTest {
 @Test void actualRuinCountsUseFixedSharesAcrossThresholds(){
  for(int n:List.of(250,251,500,501)){
   var type=VehicleTypeImpl.Builder.newInstance("v").addCapacityDimension(0,n+1).build();
   var depot=Location.Builder.newInstance().setId("d").setCoordinate(Coordinate.newInstance(0,0)).build();
   var vehicle=VehicleImpl.Builder.newInstance("v").setType(type).setStartLocation(depot).build();
   var builder=VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).setRoutingCost(new SectionProblemFactory.GeometryCosts());
   var jobs=new ArrayList<Service>();for(int i=0;i<n;i++){var loc=Location.Builder.newInstance().setId("j"+i).setCoordinate(Coordinate.newInstance(i+1,0)).build();var j=Service.Builder.newInstance("j"+i).setLocation(loc).addSizeDimension(0,1).build();jobs.add(j);builder.addJob(j);}
   var p=builder.build();var carrier=CarriersUtils.createCarrier(Id.create("empty",Carrier.class));var catalog=new SectionCatalog(carrier,List.of(),"test");
   SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,org.matsim.vehicles.Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "test";}};
   var algorithm=SectionInsertionAdapter.configure(p,new SectionSearchState(catalog,roads,10000,10000,0),4711,new SectionSearchPolicy(1,.25,.4));
   var rb=VehicleRoute.Builder.newInstance(p.getVehicles().iterator().next());for(var job:jobs)rb.addService(job);var route=rb.build();
   boolean radial=false,random=false;
   for(var strategy:algorithm.getSearchStrategyManager().getStrategies())for(var module:strategy.getSearchStrategyModules())if(module instanceof RuinAndRecreateModule r){String name=strategy.getId().toLowerCase(Locale.ROOT);if(!radial&&name.contains("radial")){assertThat(r.getRuin().ruin(new ArrayList<>(List.of(VehicleRoute.copyOf(route))))).hasSize(Math.max(1,(int)(n*.25)));radial=true;}if(!random&&name.contains("random")){assertThat(r.getRuin().ruin(new ArrayList<>(List.of(VehicleRoute.copyOf(route))))).hasSize(Math.max(1,(int)(n*.4)));random=true;}}
   assertThat(radial).as("radial strategy for %s",n).isTrue();assertThat(random).as("random strategy for %s",n).isTrue();
  }
 }
 @Test void normalRuinCountsKeepSizeThresholdsWithAndWithoutFast(){
  for(boolean fast:List.of(false,true))for(int n:List.of(250,251,500,501)){
   var type=VehicleTypeImpl.Builder.newInstance("v").addCapacityDimension(0,n+1).build();
   var depot=Location.Builder.newInstance().setId("d").setCoordinate(Coordinate.newInstance(0,0)).build();
   var vehicle=VehicleImpl.Builder.newInstance("v").setType(type).setStartLocation(depot).build();
   var builder=VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).setRoutingCost(new SectionProblemFactory.GeometryCosts()).setFleetSize(VehicleRoutingProblem.FleetSize.INFINITE);
   var jobs=new ArrayList<Service>();for(int i=0;i<n;i++){var loc=Location.Builder.newInstance().setId("j"+i).setCoordinate(Coordinate.newInstance(i+1,0)).build();var j=Service.Builder.newInstance("j"+i).setLocation(loc).addSizeDimension(0,1).build();jobs.add(j);builder.addJob(j);}
   var p=builder.build();var carrier=CarriersUtils.createCarrier(Id.create("empty",Carrier.class));var catalog=new SectionCatalog(carrier,List.of(),"test");
   SectionRoadCosts roads=new SectionRoadCosts(){public SectionLeg leg(Id<Link>a,Id<Link>b,VehicleType t){return new SectionLeg(0,0,0);}public RoadLeg materialize(Id<Link>a,Id<Link>b,double x,org.matsim.vehicles.Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "test";}};
   var algorithm=SectionInsertionAdapter.configureNormal(p,new SectionSearchState(catalog,roads,10000,10000,0),4711,1,1,null,fast);
   var rb=VehicleRoute.Builder.newInstance(p.getVehicles().iterator().next());for(var job:jobs)rb.addService(job);var route=rb.build();
   boolean radial=false,random=false;
   for(var strategy:algorithm.getSearchStrategyManager().getStrategies())for(var module:strategy.getSearchStrategyModules())if(module instanceof RuinAndRecreateModule r){String name=strategy.getId().toLowerCase(Locale.ROOT);if(!radial&&name.contains("radial")){assertThat(r.getRuin().ruin(new ArrayList<>(List.of(VehicleRoute.copyOf(route))))).hasSize(Math.max(1,(int)(n*(n>500?.05:n>250?.12:.25))));radial=true;}if(!random&&name.contains("random")){assertThat(r.getRuin().ruin(new ArrayList<>(List.of(VehicleRoute.copyOf(route))))).hasSize(Math.max(1,(int)(n*(n>500?.10:n>250?.20:.40))));random=true;}}
   assertThat(radial).as("radial strategy for %s",n).isTrue();assertThat(random).as("random strategy for %s",n).isTrue();
  }
 }
}
