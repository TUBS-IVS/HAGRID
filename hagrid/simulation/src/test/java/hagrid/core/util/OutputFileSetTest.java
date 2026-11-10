package hagrid.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OutputFileSetTest {
    @TempDir Path directory;
    private OutputFileSet.Artifact output(String name,String value) {
        return new OutputFileSet.Artifact(directory.resolve(name), p->Files.writeString(p,value));
    }
    private List<String> names() throws Exception {
        try(var files=Files.list(directory)) {return files.map(p->p.getFileName().toString()).sorted().toList();}
    }

    @Test void allFilesArePreparedBeforeAnyOldOutputIsReplaced() throws Exception {
        Files.writeString(directory.resolve("first.xml"),"old");
        var second=new OutputFileSet.Artifact(directory.resolve("second.xml"),p->{throw new IOException("serialization failed");});
        assertThatThrownBy(()->OutputFileSet.publish(List.of(output("first.xml","new"),second)))
                .isInstanceOf(IOException.class).hasMessageContaining("serialization failed");
        assertThat(Files.readString(directory.resolve("first.xml"))).isEqualTo("old");
        assertThat(names()).containsExactly("first.xml");
    }

    @Test void aLaterReplacementFailureRestoresEarlierBytesAndLeavesMissingOutputsMissing() throws Exception {
        Files.writeString(directory.resolve("first.xml"),"first-old");Files.writeString(directory.resolve("second.csv"),"second-old");
        var moves=new AtomicInteger();
        assertThatThrownBy(()->OutputFileSet.publish(List.of(output("first.xml","first-new"),output("second.csv","second-new"),output("third.xml","third-new")),
                (from,to)->{if(moves.incrementAndGet()==2)throw new IOException("replacement failed");Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}))
                .isInstanceOf(IOException.class).hasMessageContaining("replacement failed");
        assertThat(Files.readString(directory.resolve("first.xml"))).isEqualTo("first-old");
        assertThat(Files.readString(directory.resolve("second.csv"))).isEqualTo("second-old");
        assertThat(names()).containsExactly("first.xml","second.csv");
    }

    @Test void aFailedRollbackRetainsTheBackupForRecovery() throws Exception {
        Files.writeString(directory.resolve("first.xml"),"original");
        var moves=new AtomicInteger();
        assertThatThrownBy(()->OutputFileSet.publish(List.of(output("first.xml","new"),output("second.xml","new")),
                (from,to)->{if(moves.incrementAndGet()>1)throw new IOException("storage failed");Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}))
                .isInstanceOf(IOException.class).satisfies(error->assertThat(error.getSuppressed()).anySatisfy(
                        suppressed->assertThat(suppressed.getMessage()).contains("backup retained at")));
        try(var files=Files.list(directory)) {
            var backups=files.filter(p->p.getFileName().toString().startsWith(".hagrid-backup-")).toList();
            assertThat(backups).hasSize(1);assertThat(Files.readString(backups.getFirst())).isEqualTo("original");
        }
        assertThat(Files.readString(directory.resolve("first.xml"))).isEqualTo("new");
    }
    @Test void anUncheckedRollbackFailurePreservesOriginalBytesAndThePrimaryFailure() throws Exception {
        Files.writeString(directory.resolve("first.xml"),"original");
        var moves=new AtomicInteger();
        assertThatThrownBy(()->OutputFileSet.publish(List.of(output("first.xml","new"),output("second.xml","new")),
                (from,to)->{
                    int attempt=moves.incrementAndGet();
                    if(attempt==2)throw new IOException("publication failed");
                    if(attempt==3)throw new SecurityException("restore denied");
                    Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }))
                .isInstanceOf(IOException.class).hasMessageContaining("publication failed")
                .satisfies(error->assertThat(error.getSuppressed()).anySatisfy(suppressed->{
                    assertThat(suppressed.getMessage()).contains("backup retained at");
                    assertThat(suppressed.getCause()).isInstanceOf(SecurityException.class);
                }));
        try(var files=Files.list(directory)) {
            var backups=files.filter(p->p.getFileName().toString().startsWith(".hagrid-backup-")).toList();
            assertThat(backups).hasSize(1);assertThat(Files.readString(backups.getFirst())).isEqualTo("original");
        }
    }
    @Test void anUncheckedRecoveryFailureDoesNotPreventRestoringTheOtherFiles() throws Exception {
        for(var name:List.of("first.xml","second.xml","third.xml"))Files.writeString(directory.resolve(name),name+"-old");
        var moves=new AtomicInteger();
        assertThatThrownBy(()->OutputFileSet.publish(List.of(output("first.xml","first-new"),output("second.xml","second-new"),output("third.xml","third-new")),
                (from,to)->{
                    int attempt=moves.incrementAndGet();
                    if(attempt==3)throw new IOException("third publication failed");
                    if(attempt==4)throw new SecurityException("second restore denied");
                    Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                })).isInstanceOf(IOException.class).hasMessageContaining("third publication failed");
        assertThat(Files.readString(directory.resolve("first.xml"))).isEqualTo("first.xml-old");
        assertThat(Files.readString(directory.resolve("second.xml"))).isEqualTo("second-new");
        assertThat(Files.readString(directory.resolve("third.xml"))).isEqualTo("third.xml-old");
        try(var files=Files.list(directory)) {
            var backups=files.filter(p->p.getFileName().toString().startsWith(".hagrid-backup-")).toList();
            assertThat(backups).hasSize(1);assertThat(Files.readString(backups.getFirst())).isEqualTo("second.xml-old");
        }
    }
    @Test void successfulReplacementRetainsOnlyTheFinalArtifacts() throws Exception {
        Files.writeString(directory.resolve("first.xml"),"old");
        OutputFileSet.publish(List.of(output("first.xml","new"),output("metrics.csv","csv")));
        assertThat(Files.readString(directory.resolve("first.xml"))).isEqualTo("new");
        assertThat(Files.readString(directory.resolve("metrics.csv"))).isEqualTo("csv");
        assertThat(names()).containsExactly("first.xml","metrics.csv");
    }

    @Test void foreignDirectoryOrDuplicateTargetsAreRejectedBeforeSerializing() throws Exception {
        Files.createDirectory(directory.resolve("foreign"));Files.writeString(directory.resolve("foreign/marker"),"keep");
        var calls=new AtomicInteger();
        var first=new OutputFileSet.Artifact(directory.resolve("first.xml"),p->{calls.incrementAndGet();Files.writeString(p,"new");});
        assertThatThrownBy(()->OutputFileSet.publish(List.of(first,output("foreign","bad")))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->OutputFileSet.publish(List.of(first,first))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);assertThat(Files.readString(directory.resolve("foreign/marker"))).isEqualTo("keep");
    }
}