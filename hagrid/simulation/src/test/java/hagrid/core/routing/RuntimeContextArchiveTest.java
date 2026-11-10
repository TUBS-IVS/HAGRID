package hagrid.core.routing;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeContextArchiveTest {
    @TempDir Path root;
    private CarrierVehicleTypes types(RawDistanceTemporalRoutingTest.Fixture f) {
        var types=new CarrierVehicleTypes();
        f.carrier().getCarrierCapabilities().getCarrierVehicles().values().forEach(v->types.getVehicleTypes().put(v.getType().getId(),v.getType()));return types;
    }
    @Test void waitingForATemporaryOwnerDoesNotBlockAnUnrelatedCompleteExport()throws Exception {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->20,10,10,1000);
            var store=new SharedRoutingContextArchives()) {
            store.save(root,f.context());
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            var worker=new java.util.concurrent.atomic.AtomicReference<Thread>();
            try {
                synchronized(store) {
                    pool.submit(()->{worker.set(Thread.currentThread());return ReplayContextArchive.save(root,f.context());});
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                    while((worker.get()==null||worker.get().getState()!=Thread.State.BLOCKED)&&System.nanoTime()<deadline)Thread.sleep(5);
                    assertNotNull(worker.get());assertEquals(Thread.State.BLOCKED,worker.get().getState());
                    var independent=pool.submit(()->ReplayContextArchive.save(root.resolve("independent"),f.context()));
                    assertNotNull(independent.get(3,java.util.concurrent.TimeUnit.SECONDS));
                }
            }finally {pool.shutdown();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test void concurrentReadersAcceptAnAtomicUpgradeToACompleteArchive()throws Exception {
        Path profile;
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->20,10,10,1000);
            var store=new SharedRoutingContextArchives()) {
            profile=store.save(root,f.context());
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            var start=new java.util.concurrent.CountDownLatch(1);
            try {
                var reader=pool.submit(()->{start.await();for(int i=0;i<50;i++) {
                    try(var restored=ReplayContextArchive.read(profile)){assertEquals(f.context().fingerprint(),restored.fingerprint());}
                    try(var restored=store.read(profile)){assertEquals(f.context().fingerprint(),restored.fingerprint());}
                }return true;});
                var export=pool.submit(()->{start.await();return ReplayContextArchive.save(root,f.context());});
                start.countDown();assertTrue(reader.get(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(profile,export.get(10,java.util.concurrent.TimeUnit.SECONDS));
            }finally {pool.shutdown();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        }
        try(var restored=ReplayContextArchive.read(profile)){assertNotNull(restored.fingerprint());}
    }
    @Test void concurrentInitialRuntimeAndCompletePublicationCannotDowngradePortableExport()throws Exception {
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->20,10,10,1000)) {
            for(int iteration=0;iteration<12;iteration++) {
                var folder=root.resolve("race-"+iteration);Path portable;
                try(var store=new SharedRoutingContextArchives()) {
                    var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
                    var start=new java.util.concurrent.CountDownLatch(1);
                    try {
                        var runtime=pool.submit(()->{start.await();return store.save(folder,f.context());});
                        var complete=pool.submit(()->{start.await();return ReplayContextArchive.save(folder,f.context());});
                        start.countDown();portable=complete.get(10,java.util.concurrent.TimeUnit.SECONDS);
                        assertEquals(portable,runtime.get(10,java.util.concurrent.TimeUnit.SECONDS));
                    }finally {pool.shutdown();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
                }
                try(var restored=ReplayContextArchive.read(portable)){assertEquals(f.context().fingerprint(),restored.fingerprint());}
            }
        }
    }
    @Test void completeExportIntoTheSameDirectoryCannotReturnAnOwnerDependentProfile()throws Exception {
        Path portable;String fingerprint;
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,1000);
            var store=new SharedRoutingContextArchives()) {
            fingerprint=f.context().fingerprint();var profile=store.save(root,f.context());
            portable=store.exportComplete(profile,root);
            try(var restored=store.read(profile)){assertEquals(fingerprint,restored.fingerprint());}
        }
        try(var restored=ReplayContextArchive.read(portable)){assertEquals(fingerprint,restored.fingerprint());}
    }
    @Test void temporaryProfilesKeepExactMeasurementsAndPortableExportSurvivesOwnerClose() throws Exception {
        Path first,second,portable;String firstFingerprint;
        try(var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,1000);
            var store=new SharedRoutingContextArchives()) {
            firstFingerprint=f.context().fingerprint();first=store.save(root,f.context());
            var snapshot=TravelTimeSnapshot.capture(f.context().network(),Map.of("car",(l,t,p,v)->t<10?30:4),Map.of(),25,1000,10);
            try(var next=RoutingContext.create(f.context().network(),types(f),snapshot,f.context().config())) {
                second=store.save(root,next);
                assertNotEquals(first,second);assertFalse(Files.exists(root.resolve(".graphs")));
                try(var zip=new ZipFile(second.toFile())){assertEquals(2,zip.size());assertNull(zip.getEntry("network.xml"));}
            }
            f.close();
            try(var restored=ReplayContextArchive.read(first)){assertEquals(firstFingerprint,restored.fingerprint());}
            try(var restored=store.read(second)) {
                assertEquals(25,restored.snapshot().measurementIteration());
                var link=restored.network().getLinks().values().iterator().next();
                assertEquals(30,restored.snapshot().forMode("car").getLinkTravelTime(link,0,null,null));
            }
            portable=store.exportComplete(first,root.resolve("portable"));
        }
        assertThrows(java.io.IOException.class,()->ReplayContextArchive.read(first));
        try(var restored=ReplayContextArchive.read(portable)){assertEquals(firstFingerprint,restored.fingerprint());}
    }
    @Test void changedGraphIsCheckpointedAndCorruptionCannotUseAStaleInMemoryGraph()throws Exception {
        try(var a=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,1000);
            var b=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,1000,n->n.getLinks().values().iterator().next().setLength(101));
            var store=new SharedRoutingContextArchives()) {
            var first=store.save(root,a.context());var second=store.save(root,b.context());
            a.close();b.close();
            try(var restored=store.read(first)){assertEquals(24,restored.snapshot().measurementIteration());}
            try(var restored=store.read(second)){assertEquals(24,restored.snapshot().measurementIteration());}
            Path checkpoint;try(var files=Files.walk(root.resolve(".graphs"))){checkpoint=files.filter(p->p.toString().endsWith(".zip")).findFirst().orElseThrow();}
            Files.write(checkpoint,new byte[]{1},StandardOpenOption.APPEND);
            assertThrows(java.io.IOException.class,()->store.read(first));
        }
    }
    @Test void pruningAndCleanupPreserveAnExistingPortableCheckpoint()throws Exception {
        Path portable;
        try(var a=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->20,10,10,1000);
            var b=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->20,10,10,1000,n->n.getLinks().values().iterator().next().setLength(101));
            var store=new SharedRoutingContextArchives()) {
            var first=store.save(root,a.context());
            portable=store.exportComplete(first,root.resolve(".graphs"));
            store.save(root,b.context());Files.delete(first);
            store.save(root,b.context());store.deleteOwnedGraphs();
            assertTrue(Files.isRegularFile(portable));
        }
        try(var restored=ReplayContextArchive.read(portable)){assertNotNull(restored.fingerprint());}
    }
    @Test void closingOwnerRejectsReuseAndClosedContextsCannotBeSaved()throws Exception {
        var f=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->t<10?20:3,10,10,1000);
        try(var store=new SharedRoutingContextArchives()) {
            var path=store.save(root,f.context());f.close();
            assertThrows(IllegalStateException.class,()->store.save(root,f.context()));
            store.close();assertThrows(IllegalStateException.class,()->store.read(path));
        }
    }
}
