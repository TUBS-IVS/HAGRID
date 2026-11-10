package hagrid.core.routing.sections;

import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.cost.VehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import java.util.*;

/** Builds native macro services with independent vehicle types and geometry-only ruin distances. */
public final class SectionProblemFactory {
 private SectionProblemFactory() {}
 public record Problem(VehicleRoutingProblem problem, Map<String,DeliverySection> sections) {public Problem {sections=Map.copyOf(sections);}}
 public static Problem build(SectionCatalog catalog,Network network,SectionRoadCosts roads) {
  catalog.requireUnchanged();
  // A second detached snapshot ensures native conversion cannot mutate the catalog's vehicle types.
  Carrier macro=new SectionCatalog(catalog.originalCarrier(),catalog.sections(),catalog.fingerprint()).originalCarrier();
  var fleet=CarrierCapabilities.Builder.newInstance().setFleetSize(macro.getCarrierCapabilities().getFleetSize());
  for(var v:macro.getCarrierCapabilities().getCarrierVehicles().values())fleet.addVehicle(CarrierVehicle.Builder.newInstance(v.getId(),roads.resolveOriginalLink(v.getLinkId()),v.getType()).setEarliestStart(v.getEarliestStartTime()).setLatestEnd(v.getLatestEndTime()).build());
  macro.setCarrierCapabilities(fleet.build());macro.getServices().clear();
  var lookup=new LinkedHashMap<String,DeliverySection>();
  for(var s:catalog.sections()) {
   if(lookup.put(s.id(),s)!=null)throw new IllegalArgumentException("Duplicate section "+s.id());
   var halt=s.variants().getFirst().orderedHaltLinks().getFirst();
   var job=CarrierService.Builder.newInstance(Id.create(s.id(),CarrierService.class),halt,s.demand()).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,Double.MAX_VALUE)).build();
   var skills=new TreeSet<String>();for(var id:s.memberIds())skills.addAll(CarriersUtils.getSkills(catalog.originalCarrier().getServices().get(Id.create(id,CarrierService.class))));
   CarriersUtils.setSkills(job,skills);CarriersUtils.addService(macro,job);
  }
  var builder=MatsimJspritFactory.createRoutingProblemBuilder(macro,network);builder.setRoutingCost(new GeometryCosts());
  return new Problem(builder.build(),lookup);
 }
 /** Proxy is static and dimensionless; only native neighborhoods and access/egress use it. */
 public static final class GeometryCosts implements VehicleRoutingTransportCosts {
  private double distance(Location a,Location b){var x=a.getCoordinate();var y=b.getCoordinate();if(x==null||y==null)throw new IllegalArgumentException("Section proxy requires coordinates");return Math.hypot(x.getX()-y.getX(),x.getY()-y.getY());}
  public double getTransportCost(Location a,Location b,double time,Driver d,Vehicle v){return distance(a,b);}
  public double getBackwardTransportCost(Location a,Location b,double time,Driver d,Vehicle v){return distance(a,b);}
  public double getTransportTime(Location a,Location b,double time,Driver d,Vehicle v){return 0;}
  public double getBackwardTransportTime(Location a,Location b,double time,Driver d,Vehicle v){return 0;}
  public double getDistance(Location a,Location b,double time,Vehicle v){return distance(a,b);}
 }
}
