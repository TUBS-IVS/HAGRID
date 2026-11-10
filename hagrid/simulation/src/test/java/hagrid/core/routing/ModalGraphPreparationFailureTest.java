package hagrid.core.routing;

import hagrid.core.HagridConfig;
import hagrid.core.config.HagridConfigResolver;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.core.router.speedy.SpeedyGraphBuilder;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModalGraphPreparationFailureTest {
    @Test void failedFirstGraphPublishesNothingAndRetryReturnsTheOnlyAdmissionPermit() {
        var config=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(Map.of("routing.maximumConcurrentRouters","1")),Map.of());
        try(var f=new OptimizationFixture(config)) {
            var attempts=new AtomicInteger();
            var engine=new ModalRoutingEngine(f.context,(network,mode)-> {
                if(attempts.incrementAndGet()==1)throw new IllegalStateException("first actual graph construction fails");
                return SpeedyGraphBuilder.build(network,mode);
            });
            try {
                var profile=f.context.vehicleProfiles().get(f.type.getId());
                assertThrows(IllegalStateException.class,()->engine.roadLeg(profile,Id.createLinkId("depot"),Id.createLinkId("stop"),0));
                assertEquals(0,engine.statistics().preparedGraphs());assertEquals(0,engine.statistics().createdRouters());
                assertEquals(0,engine.statistics().activeRouters());
                var road=assertTimeoutPreemptively(Duration.ofSeconds(2),()->engine.roadLeg(profile,Id.createLinkId("depot"),Id.createLinkId("stop"),0));
                assertEquals(20,road.travelTimeSeconds(),1e-9);assertEquals(200,road.distanceMeters(),1e-9);
                assertEquals(2,attempts.get());assertEquals(1,engine.statistics().preparedGraphs());
                assertEquals(1,engine.statistics().createdRouters());assertEquals(0,engine.statistics().activeRouters());
                engine.roadLeg(profile,Id.createLinkId("depot"),Id.createLinkId("stop"),0);
                assertEquals(2,attempts.get());assertEquals(1,engine.statistics().createdRouters());
            } finally {engine.close();}
        }
    }
}