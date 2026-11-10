package hagrid.core.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class RemovedOptimizationArchiveTest {
    @TempDir Path directory;
    private static final String FINGERPRINT="33eb813fa17d010481b182e1e438eed36cff48051563fa11b2faa10685177864";
    private Path fixture() throws Exception {
        Path file=directory.resolve("neutral.zip");
        try(var in=getClass().getResourceAsStream("neutral-v1-before-removal.zip")) { Files.copy(in,file); }
        return file;
    }
    @Test void preservesNeutralV1ArchiveAndRejectsCorruption() throws Exception {
        Path old=fixture();
        try(var context=ReplayContextArchive.read(old)) {
            assertThat(context.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(context.config().sections().fastRegret()).isFalse();
            assertThat(context.config().optimization().replanningIterations()).hasValue(2);
            Path roundtrip=ReplayContextArchive.save(directory.resolve("roundtrip"),context);
            try(var restored=ReplayContextArchive.read(roundtrip)) {
                assertThat(restored.fingerprint()).isEqualTo(FINGERPRINT);
                assertThat(restored.config()).isEqualTo(context.config());
            }
        }
        Path corrupt=alter(old,"corrupt",node->node.put("fingerprint","corrupt"));
        assertThatThrownBy(()->ReplayContextArchive.read(corrupt)).hasMessageContaining("fingerprint mismatch");
    }
    @Test void enabledHistoricalExperimentsRequireTheArchivedPackage() throws Exception {
        Path old=fixture();
        for(String field:new String[]{"zones","warmStart","adaptive"}) {
            Path enabled=alter(old,field,node->{
                var config=(ObjectNode)node.path("config");
                if(field.equals("adaptive"))config.putObject(field).put("minimumIterations",10).put("maximumIterations",30).put("plateauIterations",5);
                else config.put(field,true);
            });
            String option=field.equals("zones")?"routing.zoneBasedCachingEnabled":field.equals("warmStart")?"optimization.warmStartEnabled":"optimization.adaptiveSearchEnabled";
            assertThatThrownBy(()->ReplayContextArchive.read(enabled)).hasMessageContaining(option).hasMessageContaining("removed");
        }
    }
    private Path alter(Path old,String suffix,java.util.function.Consumer<ObjectNode> mutate) throws Exception {
        Path target=directory.resolve(suffix+".zip");var json=new ObjectMapper();
        try(var zip=new ZipFile(old.toFile());var out=new ZipOutputStream(Files.newOutputStream(target))) {
            var entries=zip.entries();while(entries.hasMoreElements()) {
                var entry=entries.nextElement();byte[] bytes;
                try(var in=zip.getInputStream(entry)){bytes=in.readAllBytes();}
                if(entry.getName().equals("context.json")){var node=(ObjectNode)json.readTree(bytes);mutate.accept(node);bytes=json.writeValueAsBytes(node);}
                out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        return target;
    }
}
