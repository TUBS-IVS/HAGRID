package hagrid.core.simulation;

import hagrid.core.routing.ReplayContextArchive;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedGraphArchiveTest {
    @Test void differentMeasurementProfilesShareOneCertifiedGraphAndRemainReadableAfterContextsClose() throws Exception {
        var before=roots();Path root;
        try(var f=new ReplanningCoordinatorTest.Fixture(2,Map.of("optimization.replanningIterations","1","optimization.carriersPerGroup","1"));
            var coordinator=new ReplanningCoordinator(f.config,42)) {
            coordinator.runGroup(25,f.carriers,()->f.context);
            var following=ReplanningCoordinatorTest.context(f,25,11);
            coordinator.runGroup(26,f.carriers,()->following);
            var created=roots();created.removeAll(before);assertEquals(1,created.size());root=created.iterator().next();
            java.util.List<Path> profiles;
            try(var files=Files.list(root.resolve(".contexts"))){profiles=files.toList();}
            assertEquals(2,profiles.size());
            for(var profile:profiles)try(var zip=new ZipFile(profile.toFile())) {
                assertNull(zip.getEntry("network.xml"),"Profiles must not duplicate the unchanged graph");
            }
            assertFalse(Files.exists(root.resolve(".graphs")),"An unchanged runtime graph needs no disk checkpoint");
            var measurements=new HashSet<Integer>();
            for(var profile:profiles)try(var restored=ReplayContextArchive.read(profile)) {
                measurements.add(restored.snapshot().measurementIteration());
            }
            assertEquals(Set.of(24,25),measurements);
            assertEquals(2,coordinator.finalMetrics().size());
        }
        assertFalse(Files.exists(root),"Owned shared graph archives must be removed with their coordinator");
    }
    private Set<Path> roots() throws Exception {
        try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return new HashSet<>(files.filter(Files::isDirectory).filter(p->p.getFileName().toString().startsWith("hagrid-replanning-contexts-")).toList());
        }
    }
}
