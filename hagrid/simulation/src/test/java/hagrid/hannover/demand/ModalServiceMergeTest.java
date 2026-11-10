package hagrid.hannover.demand;
import hagrid.core.util.Delivery;
import hagrid.hannover.pipeline.CarrierMergeLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.freight.carriers.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ModalServiceMergeTest {
    @TempDir Path directory;

    @Test void fullMergeCanUseBikeExclusiveLinksOutsideTheCarParcelGraph() throws Exception {
        var f=fixture();
        var c=f.carrier("hermes_12345_bike","bike","bike-home");
        add(c,"s1","bike-stop",new Coord(55,10),0,86400);
        add(c,"s2","bike-stop",new Coord(95,10),0,86400);
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices().values()).singleElement().satisfies(s->{
            assertThat(s.getServiceLinkId()).isEqualTo(Id.createLinkId("bike-stop"));
            assertThat(s.getCapacityDemand()).isEqualTo(2);
            assertThat(s.getAttributes().getAttribute("coord")).isNotNull();
        });
    }

    @Test void equalCoordinatesCannotSilentlyMoveAnExistingServiceToAnotherLink() throws Exception {
        var f=fixture(); var c=f.carrier("hermes_12345_bike","bike","bike-home");
        add(c,"s1","bike-home",new Coord(50,10),0,86400);
        add(c,"s2","bike-stop",new Coord(50,10),0,86400);
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,false);
        assertThat(c.getServices()).hasSize(2);
        assertThat(c.getServices().values().stream().map(CarrierService::getServiceLinkId)).containsExactlyInAnyOrder(Id.createLinkId("bike-home"),Id.createLinkId("bike-stop"));
    }

    @Test void disjointTimeWindowsCannotBeReplacedWithGlobalDefaults() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_car","car","car-home");
        add(c,"s1","car-stop",new Coord(75,0),100,200);
        add(c,"s2","car-stop",new Coord(75,0),300,400);
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices()).hasSize(2);
    }

    @Test void overlappingTimeWindowsAlsoReserveTheOtherOriginalJobsDuration() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_car","car","car-home");
        add(c,"s1","car-stop",new Coord(75,0),100,300);
        add(c,"s2","car-stop",new Coord(75,0),200,400);
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices().values()).singleElement().satisfies(s->{
            assertThat(s.getServiceStaringTimeWindow().getStart()).isEqualTo(200);
            assertThat(s.getServiceStaringTimeWindow().getEnd()).isEqualTo(270);
        });
    }

    @Test void smallCarriersWithDifferentModesCannotBeMergedTogether() throws Exception {
        var f=fixture(); var bike=f.carrier("hermes_12345_small","bike","bike-home");
        var car=f.carrier("hermes_12345_large","car","car-home");
        add(bike,"small","bike-stop",new Coord(75,10),0,86400);
        CarriersUtils.addService(car,DemandModalFixture.service("large","car-stop",4,new Coord(75,0),0,86400));
        var map=new LinkedHashMap<Id<Carrier>,Carrier>();map.put(bike.getId(),bike);map.put(car.getId(),car);
        var generator=new CarrierGenerator();
        DemandModalFixture.set(generator,"scenario",f.scenario);DemandModalFixture.set(generator,"hagridConfig",f.config);
        f.config.routing().setCarrierMergeThreshold(2);
        generator.mergeCarriersWithInsufficientServices(map,List.of(bike.getId()),600,new CarrierMergeLog());
        assertThat(map).hasSize(2);
        assertThat(car.getServices()).hasSize(1);
        assertThat(bike.getServices()).hasSize(1);
    }

    @Test void disconnectedStopsCannotBeTransferredToAnotherCarriersDepot() throws Exception {
        var f=fixture(); DemandModalFixture.cycle(f.scenario.getNetwork(),"island-","car",1000);
        var small=f.carrier("hermes_12345_small","car","island-home");
        var large=f.carrier("hermes_12345_large","car","car-home");
        add(small,"small","island-stop",new Coord(75,1000),0,86400);
        CarriersUtils.addService(large,DemandModalFixture.service("large","car-stop",4,new Coord(75,0),0,86400));
        var map=new LinkedHashMap<Id<Carrier>,Carrier>();map.put(small.getId(),small);map.put(large.getId(),large);
        var generator=new CarrierGenerator();
        DemandModalFixture.set(generator,"scenario",f.scenario);DemandModalFixture.set(generator,"hagridConfig",f.config);
        f.config.routing().setCarrierMergeThreshold(2);
        generator.mergeCarriersWithInsufficientServices(map,List.of(small.getId()),600,new CarrierMergeLog());
        assertThat(map).hasSize(2);
        assertThat(large.getServices()).hasSize(1);
    }

    @Test void mergedTravelBonusUsesTheActualFleetSpeedLimit() throws Exception {
        var f=fixture(); var c=f.carrier("hermes_12345_bike","bike","bike-home");
        c.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->v.getType().setMaximumVelocity(2.0));
        add(c,"s1","bike-stop",new Coord(0,10),0,86400);
        add(c,"s2","bike-stop",new Coord(100,10),0,86400);
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices().values()).singleElement().satisfies(s->
                assertThat(s.getServiceDuration()).isGreaterThanOrEqualTo(110));
    }
    @Test void actualBikeCapacityCapsEveryMergedService() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_bike","bike","bike-home");
        c.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->v.getType().getCapacity().setOther(2));
        for(int i=0;i<4;i++)add(c,"s"+i,"bike-stop",new Coord(75,10),0,86400);
        var carriers=new Carriers();carriers.addCarrier(c);CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices()).hasSize(2);
        assertThat(c.getServices().values()).allSatisfy(job->assertThat(job.getCapacityDemand()).isLessThanOrEqualTo(2));
    }
    @Test void existingMixedServicesRetainTheirParcelBreakdown() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_car","car","car-home");
        var mixed=DemandModalFixture.service("mixed","car-stop",3,new Coord(75,0),0,86400);
        mixed.getAttributes().putAttribute("type",Delivery.ParcelType.MIXED);mixed.getAttributes().putAttribute("b2b",1);mixed.getAttributes().putAttribute("b2c",2);
        CarriersUtils.addService(c,mixed);add(c,"single","car-stop",new Coord(75,0),0,86400);
        var carriers=new Carriers();carriers.addCarrier(c);CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices().values()).singleElement().satisfies(job->{
            assertThat(job.getCapacityDemand()).isEqualTo(4);assertThat(job.getAttributes().getAttribute("b2b")).isEqualTo(1);
            assertThat(job.getAttributes().getAttribute("b2c")).isEqualTo(3);
        });
    }
    @Test void aMergedStopCannotHideTheSecondOriginalJobsLateStart() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_car","car","car-home");
        for(int i=0;i<2;i++){
            var job=CarrierService.Builder.newInstance(org.matsim.api.core.v01.Id.create("s"+i,CarrierService.class),org.matsim.api.core.v01.Id.createLinkId("car-stop"))
                    .setCapacityDemand(1).setServiceDuration(60).setServiceStartingTimeWindow(TimeWindow.newInstance(100,150)).build();
            job.getAttributes().putAttribute("coord",new Coord(75,0));job.getAttributes().putAttribute("type",Delivery.ParcelType.B2C);
            job.getAttributes().putAttribute("carrierMode","HOME");job.getAttributes().putAttribute("skills","conventional");CarriersUtils.addService(c,job);
        }
        var carriers=new Carriers();carriers.addCarrier(c);CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices()).hasSize(2);
    }
    @Test void aLaterInvalidCarrierCannotPublishAnEarlierMerge() throws Exception {
        var f=fixture();var first=f.carrier("hermes_12345_first","car","car-home");
        add(first,"a","car-stop",new Coord(75,0),0,86400);add(first,"b","car-stop",new Coord(75,0),0,86400);
        var broken=f.carrier("hermes_12345_second","car","car-home");
        CarriersUtils.addService(broken,CarrierService.Builder.newInstance(org.matsim.api.core.v01.Id.create("broken",CarrierService.class),org.matsim.api.core.v01.Id.createLinkId("car-stop")).build());
        var carriers=new Carriers();carriers.addCarrier(first);carriers.addCarrier(broken);
        assertThatIllegalStateException().isThrownBy(()->CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true));
        assertThat(first.getServices().keySet()).containsExactlyInAnyOrder(org.matsim.api.core.v01.Id.create("a",CarrierService.class),org.matsim.api.core.v01.Id.create("b",CarrierService.class));
    }
    @Test void serviceMergeRemapsMissedEntriesWithoutLosingMultiplicity() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_car","car","car-home");
        add(c,"a","car-stop",new Coord(75,0),0,86400);
        add(c,"b","car-stop",new Coord(75,0),0,86400);
        c.getAttributes().putAttribute("missedParcels",2);
        c.getAttributes().putAttribute("missedParcelsAsList",new ArrayList<>(List.of(Id.create("a",CarrierService.class),Id.create("b",CarrierService.class))));
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices().values()).singleElement().satisfies(job->{
            assertThat((List<?>)c.getAttributes().getAttribute("missedParcelsAsList")).isEqualTo(List.of(job.getId(),job.getId()));
            assertThat(c.getAttributes().getAttribute("numberOfParcels")).isEqualTo(2);
            assertThat(c.getAttributes().getAttribute("missedParcels")).isEqualTo(2);
        });
    }
    @Test void splitServiceMergeKeepsEveryMissedParcelWithinItsNewJobsCapacity() throws Exception {
        var f=fixture();var c=f.carrier("hermes_12345_car","car","car-home");
        c.getCarrierCapabilities().getCarrierVehicles().values().forEach(v->v.getType().getCapacity().setOther(3));
        CarriersUtils.addService(c,DemandModalFixture.service("a","car-stop",4,new Coord(75,0),0,86400));
        CarriersUtils.addService(c,DemandModalFixture.service("b","car-stop",4,new Coord(75,0),0,86400));
        var entries=new ArrayList<Id<CarrierService>>(Collections.nCopies(4,Id.create("a",CarrierService.class)));
        entries.add(Id.create("b",CarrierService.class));
        c.getAttributes().putAttribute("missedParcels",5);c.getAttributes().putAttribute("missedParcelsAsList",entries);
        var carriers=new Carriers();carriers.addCarrier(c);
        CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true);
        assertThat(c.getServices()).hasSize(3);
        assertThat(c.getAttributes().getAttribute("numberOfParcels")).isEqualTo(8);
        var remapped=CarrierMetadata.missedEntries(c);
        assertThat(remapped).hasSize(5).doesNotContain(Id.create("a",CarrierService.class),Id.create("b",CarrierService.class));
        assertThat(c.getServices().values()).allSatisfy(job->{
            assertThat(Collections.frequency(remapped,job.getId())).isLessThanOrEqualTo(job.getCapacityDemand());
            assertThat(job.getAttributes().getAttribute("mergedMetadata").toString()).contains("missedParcels");
        });
    }
    @Test void commitFailureRollsBackEveryCarrierAndItsMissedMetadata() throws Exception {
        var f=fixture();var first=f.carrier("hermes_12345_first","car","car-home");
        var second=f.carrier("hermes_12345_second","car","car-home");
        for(Carrier c:List.of(first,second)){
            add(c,"a","car-stop",new Coord(75,0),0,86400);add(c,"b","car-stop",new Coord(75,0),0,86400);
            c.getAttributes().putAttribute("missedParcels",1);
            c.getAttributes().putAttribute("missedParcelsAsList",new ArrayList<>(List.of(Id.create("a",CarrierService.class))));
        }
        var jobs=new IdentityHashMap<Carrier,Map<Id<CarrierService>,CarrierService>>();
        var metadata=new IdentityHashMap<Carrier,Map<String,Object>>();
        for(Carrier c:List.of(first,second)){
            jobs.put(c,new LinkedHashMap<>(c.getServices()));metadata.put(c,new LinkedHashMap<>(c.getAttributes().getAsMap()));
        }
        var commits=new java.util.concurrent.atomic.AtomicInteger();
        var carriers=new Carriers();
        for(Carrier original:List.of(first,second)){
            Map<Id<CarrierService>,CarrierService> failing=new LinkedHashMap<>(original.getServices()){
                @Override public void putAll(Map<? extends Id<CarrierService>,? extends CarrierService> values){
                    super.putAll(values);
                    if(commits.incrementAndGet()==2)throw new IllegalStateException("deliberate second commit failure");
                }
            };
            Carrier proxy=(Carrier)java.lang.reflect.Proxy.newProxyInstance(Carrier.class.getClassLoader(),new Class<?>[]{Carrier.class},
                    (instance,method,args)->method.getName().equals("getServices")?failing:method.invoke(original,args));
            carriers.addCarrier(proxy);
        }
        assertThatThrownBy(()->CarrierServiceMerger.mergeServices(carriers,f.scenario,f.config,true))
                .isInstanceOf(IllegalStateException.class).hasMessage("deliberate second commit failure");
        for(Carrier original:List.of(first,second)){
            assertThat(carriers.getCarriers().get(original.getId()).getServices()).isEqualTo(jobs.get(original));
            assertThat(original.getAttributes().getAsMap()).isEqualTo(metadata.get(original));
        }
    }
    private DemandModalFixture fixture() throws Exception { return new DemandModalFixture(directory,"bike"); }
    private static void add(Carrier c,String id,String link,Coord coord,double start,double end) {
        CarriersUtils.addService(c,DemandModalFixture.service(id,link,1,coord,start,end));
    }
}
