package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NativeMemoryLifecycleTest {
    @Test
    void newWorkersRetireTerminatedRoutersEvenWhileThreadObjectsRemainReferenced() throws Exception {
        var costs=fixture();
        var reference=new AtomicReference<WeakReference<LeastCostPathCalculator>>();
        var ended=new Thread(()->reference.set(new WeakReference<>(costs.getRouter())));
        ended.start();ended.join(10000);assertThat(ended.isAlive()).isFalse();
        costs.getRouter(); // A new live worker retires the ended predecessor.
        for(int i=0;i<30 && reference.get().get()!=null;i++){System.gc();Thread.sleep(50);}
        assertThat(reference.get().get()).as("terminated thread object is not a reason to retain its router").isNull();
        java.lang.ref.Reference.reachabilityFence(ended);
        java.lang.ref.Reference.reachabilityFence(costs);
    }
    @Test
    void liveWorkerDoesNotRetainRouterOfAnAbandonedProvider() throws Exception {
        var references=abandonProviderOnCurrentThread();
        for(int i=0;i<30 && (references[0].get()!=null || references[1].get()!=null);i++) {
            System.gc();Thread.sleep(50);
        }
        assertThat(references[0].get()).as("provider itself is no longer owned").isNull();
        assertThat(references[1].get()).as("long-lived thread must not keep the abandoned provider's routing network").isNull();
    }
    private static WeakReference<?>[] abandonProviderOnCurrentThread() {
        var costs=fixture();var router=costs.getRouter();
        return new WeakReference<?>[]{new WeakReference<>(costs),new WeakReference<>(router)};
    }
    @Test
    void rebuildingWithChangedCostBoundsCannotReuseEarlierLandmarks() throws Exception {
        var builder=builder();
        var first=builder.build().getRouter();
        builder.setBaseTravelTimeAndDisutility((l,t,p,v)->l.getLength()/10,
            new org.matsim.core.router.util.TravelDisutility(){
                public double getLinkTravelDisutility(org.matsim.api.core.v01.network.Link l,double t,
                    org.matsim.api.core.v01.population.Person p,org.matsim.vehicles.Vehicle v){return l.getLength()*.001;}
                public double getLinkMinimumTravelDisutility(org.matsim.api.core.v01.network.Link l){return l.getLength()*.001;}
            });
        var second=builder.build().getRouter();
        assertThat(field(second,"astarData")).as("new cost bounds need their own admissible landmarks")
            .isNotSameAs(field(first,"astarData"));
    }
    @Test
    void workersSharePreparedGraphButNotMutablePathCalculators() throws Exception {
        var costs = fixture();
        var first = onFreshWorker(costs);
        var second = onFreshWorker(costs);
        assertThat(first).isNotSameAs(second);
        assertThat(field(first,"graph")).as("one prepared graph per native provider").isSameAs(field(second,"graph"));
        assertThat(field(first,"astarData")).as("one landmark preparation per native provider").isSameAs(field(second,"astarData"));
    }

    @Test
    void finishedWorkersDoNotRemainOwnedByLongLivedCostProvider() throws Exception {
        var costs = fixture();
        var references = new ArrayList<WeakReference<LeastCostPathCalculator>>();
        for(int i=0;i<8;i++) references.add(new WeakReference<>(onFreshWorker(costs)));
        for(int i=0;i<30 && references.stream().anyMatch(r -> r.get()!=null);i++) {
            costs.getRouter(); // A live provider performs maintenance when the next worker requests routing.
            System.gc(); Thread.sleep(50);
        }
        assertThat(references).allSatisfy(r -> assertThat(r.get()).as("finished worker router is reclaimable").isNull());
        // Keep the owner reachable throughout GC and prove it remains usable afterwards.
        assertThat(costs.getRouter()).isNotNull();
        java.lang.ref.Reference.reachabilityFence(costs);
    }

    private static LeastCostPathCalculator onFreshWorker(NetworkBasedTransportCosts costs) throws Exception {
        var value = new AtomicReference<LeastCostPathCalculator>();
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {try {value.set(costs.getRouter());} catch(Throwable t){failure.set(t);}});
        worker.start(); worker.join(10000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        return value.get();
    }

    private static Object field(Object owner,String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }

    private static NetworkBasedTransportCosts fixture() {
        return builder().build();
    }
    private static NetworkBasedTransportCosts.Builder builder() {
        var network=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));
        NetworkUtils.createAndAddLink(network,Id.createLinkId("ab"),a,b,100,10,1000,1);
        NetworkUtils.createAndAddLink(network,Id.createLinkId("ba"),b,a,100,10,1000,1);
        return NetworkBasedTransportCosts.Builder.newInstance(network);
    }
}
