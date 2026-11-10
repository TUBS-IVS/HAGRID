package hagrid.core.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotImportBoundaryTest {
    @TempDir Path root;

    @Test void decodedSnapshotDefersWholeGraphCompatibilityToContextCreation() throws Exception {
        try(var source=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->10,10,10,1000);
            var other=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->10,10,10,1000,
                    n->n.getNodes().values().iterator().next().setCoord(new Coord(900,900)))) {
            var bytes=new ByteArrayOutputStream();
            source.context().snapshot().write(new DataOutputStream(bytes));
            var decoded=assertDoesNotThrow(()->TravelTimeSnapshot.read(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),other.context().network()));
            assertEquals(10,decoded.forMode("car").getLinkTravelTime(other.destination(),0,null,null));
            var types=new CarrierVehicleTypes();
            var type=other.nativeVehicle().getType();types.getVehicleTypes().put(type.getId(),type);
            var failure=assertThrows(IllegalArgumentException.class,()->RoutingContext.create(
                    other.context().network(),types,decoded,other.context().config()));
            assertTrue(failure.getMessage().contains("Network geometry"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicArchiveReadersRejectAnIncorrectSnapshotNetworkIdentity(boolean portable) throws Exception {
        try(var fixture=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->10,10,10,1000);
            var owner=new SharedRoutingContextArchives()) {
            Path archive=portable?ReplayContextArchive.save(root,fixture.context()):owner.save(root,fixture.context());
            replaceSnapshotIdentity(archive);
            var failure=assertThrows(IllegalArgumentException.class,()->{
                try(var unexpected=ReplayContextArchive.read(archive)) {
                    fail("An archive with the wrong snapshot network identity was accepted");
                }
            });
            assertTrue(failure.getMessage().contains("Network geometry"));
            if(!portable)assertThrows(IllegalArgumentException.class,()->{
                try(var unexpected=owner.read(archive)) {fail("Owner accepted the incorrect network identity");}
            });
        }
    }

    @Test void malformedBinarySnapshotIsRejectedBeforeContextCreation() throws Exception {
        try(var fixture=RawDistanceTemporalRoutingTest.fixture((l,t,p,v)->10,10,10,1000)) {
            var bytes=new ByteArrayOutputStream();
            fixture.context().snapshot().write(new DataOutputStream(bytes));
            bytes.write(1);
            var failure=assertThrows(IOException.class,()->TravelTimeSnapshot.read(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),fixture.context().network()));
            assertEquals("Trailing snapshot payload",failure.getMessage());
        }
    }

    private static void replaceSnapshotIdentity(Path archive) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipFile(archive.toFile());var out=new ZipOutputStream(bytes)) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();out.putNextEntry(new ZipEntry(entry.getName()));
                try(var input=zip.getInputStream(entry)) {
                    if(entry.getName().equals("snapshot.bin")) {
                        var data=new DataInputStream(input);var encoded=new DataOutputStream(out);
                        encoded.writeUTF(data.readUTF());data.readUTF();encoded.writeUTF("0".repeat(64));
                        data.transferTo(out);
                    }else input.transferTo(out);
                }
                out.closeEntry();
            }
        }
        Files.write(archive,bytes.toByteArray());
    }
}
