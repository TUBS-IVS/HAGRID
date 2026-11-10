package hagrid.core.routing.sections;

import hagrid.core.routing.RoadLeg;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SectionRouteEvaluatorTest {
    @Test void chargesInternalRoadAndOriginalServiceAndWaitingOnce() {
        var carrier=CarriersUtils.createCarrier(Id.create("c",Carrier.class));
        for (var entry:Map.of("a",30.0,"b",40.0).entrySet()) {
            var s=CarrierService.Builder.newInstance(Id.create(entry.getKey(),CarrierService.class),Id.createLinkId(entry.getKey()))
                    .setCapacityDemand(1).setServiceDuration(entry.getValue())
                    .setServiceStartingTimeWindow(TimeWindow.newInstance(entry.getKey().equals("a")?20:0,1000)).build();
            CarriersUtils.addService(carrier,s);
        }
        var type=VehicleUtils.createVehicleType(Id.create("v",VehicleType.class),"car");
        type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(2.0).setCostsPerSecond(3.0);
        VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),10.0);
        VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),4.0);
        var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("v"),Id.createLinkId("d"),type)
                .setEarliestStart(0).setLatestEnd(1000).build();
        var section=new DeliverySection("s",List.of("a","b"),2,List.of(new SectionVariant(0,List.of("a","b"),List.of(Id.createLinkId("a"),Id.createLinkId("b")))));
        SectionRoadCosts roads=new SectionRoadCosts(){
            public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){return from.toString().equals("a")&&to.toString().equals("b")?new SectionLeg(100,10,0):new SectionLeg(0,0,0);}
            public RoadLeg materialize(Id<Link> from,Id<Link> to,double departure,Vehicle v){throw new UnsupportedOperationException();}
            public String fingerprint(){return "test";}
        };
        var result=new SectionRouteEvaluator(new SectionCatalog(carrier,List.of(section),"test"),roads,1000,1000,0).evaluate(List.of("s"),vehicle,0);
        assertThat(result.feasible()).isTrue();
        assertThat(result.endTime()).isEqualTo(100);
        assertThat(result.variableCost()).isEqualTo(710);
    }
    @Test void matchesExhaustiveOrientationsWithDifferentMemberWindows() {
        var carrier=CarriersUtils.createCarrier(Id.create("oracle",Carrier.class));
        var type=VehicleUtils.createVehicleType(Id.create("oracle-type",VehicleType.class),"car");
        type.getCapacity().setOther(20);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
        VehicleUtils.setCostsPerSecondWaiting(type.getCostInformation(),2.0);
        VehicleUtils.setCostsPerSecondInService(type.getCostInformation(),0.0);
        var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("oracle-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(120).build();
        var sections=new ArrayList<DeliverySection>();
        for(int i=0;i<8;i++){
            String job="j"+i;var service=CarrierService.Builder.newInstance(Id.create(job,CarrierService.class),Id.createLinkId("d"))
              .setCapacityDemand(1).setServiceDuration(2).setServiceStartingTimeWindow(TimeWindow.newInstance(i*7,80+i*5)).build();CarriersUtils.addService(carrier,service);
            sections.add(new DeliverySection("s"+i,List.of(job),1,List.of(
              new SectionVariant(0,List.of(job),List.of(Id.createLinkId(i+"a"))),new SectionVariant(1,List.of(job),List.of(Id.createLinkId(i+"b"))))));
        }
        SectionRoadCosts roads=new SectionRoadCosts(){
          public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){int h=Math.floorMod((from.toString()+">"+to).hashCode(),7);return new SectionLeg(1+h,1+(h*3)%6,0);}
          public RoadLeg materialize(Id<Link> a,Id<Link> b,double t,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "oracle";}
        };
        double bestCost=Double.POSITIVE_INFINITY,bestEnd=Double.POSITIVE_INFINITY;List<Integer> bestChoices=null;
        for(int mask=0;mask<256;mask++){
          double time=0,cost=0;Id<Link> at=vehicle.getLinkId();var choices=new ArrayList<Integer>();boolean valid=true;
          for(int i=0;i<8;i++){
            int choice=(mask>>(7-i))&1;choices.add(choice);var next=Id.<Link>createLinkId(i+(choice==0?"a":"b"));var leg=roads.leg(at,next,type);time+=leg.seconds();cost+=leg.meters();
            double start=Math.max(time,i*7);cost+=(start-time)*2;if(start>80+i*5){valid=false;break;}time=start+2;at=next;
          }
          if(!valid)continue;var home=roads.leg(at,vehicle.getLinkId(),type);time+=home.seconds();cost+=home.meters();if(time>120)continue;
          if(cost<bestCost||cost==bestCost&&time<bestEnd){bestCost=cost;bestEnd=time;bestChoices=choices;}
        }
        var actual=new SectionRouteEvaluator(new SectionCatalog(carrier,sections,"oracle"),roads,120,120,0).evaluate(sections.stream().map(DeliverySection::id).toList(),vehicle,0);
        assertThat(actual.feasible()).isTrue();assertThat(actual.variableCost()).isEqualTo(bestCost);assertThat(actual.endTime()).isEqualTo(bestEnd);
        assertThat(actual.visits().stream().map(SectionVisit::variant).toList()).isEqualTo(bestChoices);
    }
    @Test void choosesFasterCostlierOrientationWhenCheaperCannotReturnInShift() {
        var carrier=CarriersUtils.createCarrier(Id.create("shift",Carrier.class));
        var service=CarrierService.Builder.newInstance(Id.create("job",CarrierService.class),Id.createLinkId("a"))
          .setCapacityDemand(1).setServiceDuration(1).setServiceStartingTimeWindow(TimeWindow.newInstance(0,100)).build();CarriersUtils.addService(carrier,service);
        var type=VehicleUtils.createVehicleType(Id.create("shift-type",VehicleType.class),"car");type.getCapacity().setOther(2);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
        var v=CarrierVehicle.Builder.newInstance(Id.createVehicleId("shift-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(10).build();
        var section=new DeliverySection("s",List.of("job"),1,List.of(new SectionVariant(0,List.of("job"),List.of(Id.createLinkId("a"))),new SectionVariant(1,List.of("job"),List.of(Id.createLinkId("b")))));
        SectionRoadCosts roads=new SectionRoadCosts(){
          public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){if(to.toString().equals("a"))return new SectionLeg(1,20,0);if(to.toString().equals("b"))return new SectionLeg(5,3,0);return new SectionLeg(1,2,0);}
          public RoadLeg materialize(Id<Link> a,Id<Link> b,double t,Vehicle veh){throw new UnsupportedOperationException();}public String fingerprint(){return "shift";}
        };
        var evaluator=new SectionRouteEvaluator(new SectionCatalog(carrier,List.of(section),"shift"),roads,100,100,0);
        var result=evaluator.evaluate(List.of("s"),v,0);assertThat(result.feasible()).isTrue();assertThat(result.endTime()).isEqualTo(6);assertThat(result.variableCost()).isEqualTo(6);assertThat(result.visits().getFirst().variant()).isEqualTo(1);
        assertThat(evaluator.evaluate(List.of("s"),v,9).feasible()).isFalse();
    }

    @Test void stage2DifferentialPreservesInterleavedFrontierAndRoadCallOrder() {
        var carrier=CarriersUtils.createCarrier(Id.create("frontier",Carrier.class));
        for(String id:List.of("a","b"))CarriersUtils.addService(carrier,CarrierService.Builder.newInstance(Id.create(id,CarrierService.class),Id.createLinkId(id)).setCapacityDemand(1).setServiceDuration(0).setServiceStartingTimeWindow(TimeWindow.newInstance(0,100)).build());
        var type=VehicleUtils.createVehicleType(Id.create("frontier-type",VehicleType.class),"car");type.getCapacity().setOther(10);type.getCostInformation().setCostsPerMeter(1.0).setCostsPerSecond(0.0);
        var vehicle=CarrierVehicle.Builder.newInstance(Id.createVehicleId("frontier-v"),Id.createLinkId("d"),type).setEarliestStart(0).setLatestEnd(100).build();
        int[] ids={10,Integer.MAX_VALUE,9,9,8,7,Integer.MIN_VALUE,0,-1};String[] ends={"x","y","x","z","x","x","x","x","x"};
        var variants=new ArrayList<SectionVariant>();for(int i=0;i<ids.length;i++)variants.add(new SectionVariant(ids[i],List.of("a","b"),List.of(Id.createLinkId("p"+i),Id.createLinkId(ends[i]))));
        var section=new DeliverySection("s",List.of("a","b"),2,variants);var catalog=new SectionCatalog(carrier,List.of(section),"frontier");
        var calls=new ArrayList<String>();double[] seconds={10,2,9,2,8,7,7,10,6};double[] meters={1,1,2,1,3,4,1,10,5};
        SectionRoadCosts roads=new SectionRoadCosts(){
            public SectionLeg leg(Id<Link> from,Id<Link> to,VehicleType t){calls.add(from+">"+to);String target=to.toString();if(target.startsWith("p")){int i=Integer.parseInt(target.substring(1));return new SectionLeg(meters[i],seconds[i],0);}return new SectionLeg(0,0,0);}
            public RoadLeg materialize(Id<Link> a,Id<Link> b,double t,Vehicle v){throw new UnsupportedOperationException();}public String fingerprint(){return "frontier";}
        };
        var current=new SectionRouteEvaluator(catalog,roads,100,100,0);var reference=new SectionRouteEvaluatorStage2(catalog,roads,100,100,0);
        for(var order:List.of(List.of("s"),List.<String>of()))for(double departure:new double[]{0,99}){
            calls.clear();var actual=current.evaluate(order,vehicle,departure);var actualCalls=List.copyOf(calls);
            calls.clear();var expected=reference.evaluate(order,vehicle,departure);assertThat(actual).isEqualTo(expected);assertThat(actualCalls).isEqualTo(calls);
        }
    }
}
