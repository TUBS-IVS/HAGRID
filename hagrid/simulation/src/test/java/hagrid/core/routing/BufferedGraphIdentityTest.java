package hagrid.core.routing;

import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.*;
import org.matsim.api.core.v01.*;
import static org.junit.jupiter.api.Assertions.*;

class BufferedGraphIdentityTest {
    @Test void fingerprintDoesNotConstructAnotherDigestForEveryNetworkChangeEvent()throws Exception {
        var cfg=new NetworkConfigGroup();cfg.setTimeVariantNetwork(true);var network=NetworkUtils.createNetwork(cfg);
        var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(1,0));
        var link=NetworkUtils.createAndAddLink(network,Id.createLinkId("road"),a,b,100,10,1000,1);link.setAllowedModes(Set.of("car"));
        var events=new ArrayList<NetworkChangeEvent>();
        for(int i=0;i<8;i++) {
            var event=new NetworkChangeEvent(i*10);event.addLink(link);
            event.setFreespeedChange(new NetworkChangeEvent.ChangeValue(NetworkChangeEvent.ChangeType.FACTOR,.5));events.add(event);
        }
        NetworkUtils.setNetworkChangeEvents(network,events);
        // Compare the buffered child digest against the unchanged legacy byte writer.
        var constructor=Class.forName("hagrid.core.routing.RoutingNetworkIdentity$EventDigester").getDeclaredConstructor();
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        var buffered=(java.util.function.Function<NetworkChangeEvent,String>)constructor.newInstance();
        for(var event:events)assertEquals(RoutingNetworkIdentity.eventFingerprint(event),buffered.apply(event));
        String expected=RoutingNetworkIdentity.fingerprint(network);
        var base=MessageDigest.getInstance("SHA-256").getProvider();var count=new AtomicInteger();
        var observer=new Provider("HagridDigestAllocationObserver","1.0","Test-local SHA factory observer") {{
        putService(new Provider.Service(this,"MessageDigest","SHA-256",Forward.class.getName(),List.of(),Map.of()) {
            public Object newInstance(Object parameter)throws NoSuchAlgorithmException {
                count.incrementAndGet();return new Forward(MessageDigest.getInstance("SHA-256",base));
            }
        }); }};
        Security.insertProviderAt(observer,1);
        try {
            assertEquals(expected,RoutingNetworkIdentity.fingerprint(network));
            assertTrue(count.get()<=2,"Graph hashing must reuse the child digest; constructed "+count.get());
        }finally {Security.removeProvider(observer.getName());}
    }
    private static final class Forward extends MessageDigestSpi {
        final MessageDigest delegate;Forward(MessageDigest delegate){this.delegate=delegate;}
        protected void engineUpdate(byte b){delegate.update(b);}
        protected void engineUpdate(byte[] b,int offset,int length){delegate.update(b,offset,length);}
        protected byte[] engineDigest(){return delegate.digest();}
        protected void engineReset(){delegate.reset();}
    }
}
