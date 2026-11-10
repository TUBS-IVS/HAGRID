package com.graphhopper.jsprit.core.algorithm.recreate;
import com.graphhopper.jsprit.core.algorithm.state.StateManager;
import com.graphhopper.jsprit.core.problem.*;
import com.graphhopper.jsprit.core.problem.constraint.*;
import com.graphhopper.jsprit.core.problem.driver.DriverImpl;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceInsertionPruningTest {
 static final class Fixture {
  final Vehicle vehicle=VehicleImpl.Builder.newInstance("v").setStartLocation(Location.newInstance(0,0)).build();
  final Service old=Service.Builder.newInstance("old").setLocation(Location.newInstance(1,0)).build();
  final Service added=Service.Builder.newInstance("added").setLocation(Location.newInstance(2,0)).addTimeWindow(0,20).addTimeWindow(40,60).build();
  final VehicleRoutingProblem p=VehicleRoutingProblem.Builder.newInstance().addVehicle(vehicle).addJob(old).addJob(added).build();
  final ConstraintManager cm=new ConstraintManager(p,new StateManager(p));
  final VehicleRoute route=VehicleRoute.Builder.newInstance(vehicle).addService(old).build();
  int checked;
  Fixture() {cm.addConstraint((HardActivityConstraint)(c,a,b,d,t)->{checked++;return HardActivityConstraint.ConstraintsStatus.FULFILLED;},ConstraintManager.Priority.HIGH);}
  JobInsertionCostsCalculator calc(HagridServiceInsertionCalculator.InsertionCostLowerBound bound) {
   return HagridServiceInsertionCalculator.factory(false,bound).create(p,(c,a,b,d,t)->10+c.getActivityContext().getInsertionIndex(),p::getActivities,cm);
  }
  InsertionData best(HagridServiceInsertionCalculator.InsertionCostLowerBound bound,double limit) {
   return calc(bound).getInsertionData(route,added,vehicle,0,DriverImpl.noDriver(),limit);
  }
 }
 @Test void provenWorseCandidatesSkipHardChecksButKeepTheSameBest() {
  var f=new Fixture();var original=f.best(null,Double.MAX_VALUE);f.checked=0;
  var filtered=f.best((c,g)->10+g,Double.MAX_VALUE);
  assertEquals(original.getInsertionCost(),filtered.getInsertionCost());
  assertEquals(original.getDeliveryInsertionIndex(),filtered.getDeliveryInsertionIndex());
  assertEquals(2,f.checked,"Only the two time windows at the winning gap need hard checks");
 }
 @Test void negativeSoftCostsDisableTheBound() {
  var f=new Fixture();f.cm.addConstraint((SoftActivityConstraint)(c,a,b,d,t)->-100);
  var result=f.best((c,g)->10+g,-50);
  assertTrue(result.isFound());assertEquals(-90,result.getInsertionCost());assertEquals(4,f.checked);
 }
 @Test void rejectedCheapestGapDoesNotHideTheNextFeasibleGap() {
  var f=new Fixture();f.cm.addConstraint((HardActivityConstraint)(c,a,b,d,t)->c.getActivityContext().getInsertionIndex()==0?HardActivityConstraint.ConstraintsStatus.NOT_FULFILLED:HardActivityConstraint.ConstraintsStatus.FULFILLED,ConstraintManager.Priority.CRITICAL);
  var result=f.best((c,g)->10+g,Double.MAX_VALUE);assertTrue(result.isFound());assertEquals(1,result.getDeliveryInsertionIndex());assertEquals(11,result.getInsertionCost());
 }
 @Test void allPositionsRemainCompleteWithMultipleTimeWindows() {
  var f=new Fixture();var a=f.calc(null).getAllInsertionPositions(f.route,f.added,f.vehicle,0,DriverImpl.noDriver());
  var b=f.calc((c,g)->1e6).getAllInsertionPositions(f.route,f.added,f.vehicle,0,DriverImpl.noDriver());
  assertEquals(4,b.size());for(int i=0;i<a.size();i++){assertEquals(a.get(i).getInsertionCost(),b.get(i).getInsertionCost());assertEquals(a.get(i).getDeliveryInsertionIndex(),b.get(i).getDeliveryInsertionIndex());}
 }
 @Test void strictTieAndFiniteBestKnownThresholdRemainNative() {
  var f=new Fixture();assertFalse(f.best((c,g)->10+g,10).isFound());
  assertFalse(f.best(null,10).isFound());
 }
 @Test void nanBoundNeverRejectsAnOtherwiseValidInsertion() {
  var f=new Fixture();assertTrue(f.best((c,g)->Double.NaN,Double.MAX_VALUE).isFound());assertEquals(4,f.checked);
 }
 @Test void diagnosticsUseUnfilteredNativeCalculator() {
  var f=new Fixture();var calc=HagridServiceInsertionCalculator.factory(true,(ctx,g)->1e6)
    .create(f.p,(ctx,a,b,ins,t)->10+ctx.getActivityContext().getInsertionIndex(),f.p::getActivities,f.cm);
  var result=calc.getInsertionData(f.route,f.added,f.vehicle,0,DriverImpl.noDriver(),Double.MAX_VALUE);
  assertEquals(10,result.getInsertionCost());assertNotNull(result.getCostBreakdown());assertEquals(4,f.checked);
 }
 @Test void negativeRouteCostIsIncludedBeforeComparingWithIncumbent() {
  var f=new Fixture();f.cm.addConstraint((SoftRouteConstraint)ctx->-50);
  var result=f.best((ctx,g)->10+g,-30);assertTrue(result.isFound());assertEquals(-40,result.getInsertionCost());
 }
 @Test void unknownBoundFallsBackToFullEnumeration() {
  for(double bound:new double[]{Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NaN}) {
   var f=new Fixture();assertEquals(10,f.best((ctx,g)->bound,Double.MAX_VALUE).getInsertionCost());assertEquals(4,f.checked);
  }
 }
 @Test void skippingMiddleGapPreservesDepartureTimeAndLaterBreak() {
  var f=new Fixture();
  var b=Service.Builder.newInstance("b").setLocation(Location.newInstance(2,0)).setServiceTime(10).build();
  var c=Service.Builder.newInstance("c").setLocation(Location.newInstance(3,0)).build();
  var route=VehicleRoute.Builder.newInstance(f.vehicle).addService(f.old).addService(b).addService(c).build();
  var observed=new java.util.HashMap<Integer,Double>();
  f.cm.addConstraint((HardActivityConstraint)(ctx,prev,ins,next,t)->{
   int gap=ctx.getActivityContext().getInsertionIndex();observed.put(gap,t);
   return gap==2?HardActivityConstraint.ConstraintsStatus.NOT_FULFILLED_BREAK:HardActivityConstraint.ConstraintsStatus.FULFILLED;
  },ConstraintManager.Priority.CRITICAL);
  double[] costs={10,100,5,1};
  ActivityInsertionCostsCalculator costsCalc=(ctx,a,n,ins,t)->costs[ctx.getActivityContext().getInsertionIndex()];
  var plain=HagridServiceInsertionCalculator.factory(false,null).create(f.p,costsCalc,f.p::getActivities,f.cm);
  var original=plain.getInsertionData(route,f.added,f.vehicle,0,DriverImpl.noDriver(),Double.MAX_VALUE);
  double expected=observed.get(2);assertFalse(observed.containsKey(3));observed.clear();
  var filtered=HagridServiceInsertionCalculator.factory(false,(ctx,g)->costs[g]).create(f.p,costsCalc,f.p::getActivities,f.cm);
  var result=filtered.getInsertionData(route,f.added,f.vehicle,0,DriverImpl.noDriver(),Double.MAX_VALUE);
  assertFalse(observed.containsKey(1));assertFalse(observed.containsKey(3));
  assertEquals(expected,observed.get(2));assertEquals(original.getInsertionCost(),result.getInsertionCost());
  assertEquals(original.getDeliveryInsertionIndex(),result.getDeliveryInsertionIndex());
 }
}
