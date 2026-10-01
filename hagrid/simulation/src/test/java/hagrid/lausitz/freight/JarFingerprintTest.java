package hagrid.lausitz.freight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JarFingerprint")
class JarFingerprintTest {

    @TempDir
    Path tmp;

    private static Map<String, byte[]> entries() {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("hagrid/A.class", new byte[]{1, 2, 3});
        e.put("hagrid/B.class", new byte[]{4, 5});
        e.put("log4j2.xml", "<Configuration/>".getBytes(StandardCharsets.UTF_8));
        return e;
    }

    private static Path jar(Path file, long time, String manifest, String pomProperties,
                            Map<String, byte[]> entries, boolean reversed) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            put(z, "META-INF/MANIFEST.MF", manifest.getBytes(StandardCharsets.UTF_8), time);
            put(z, "META-INF/maven/g/a/pom.properties", pomProperties.getBytes(StandardCharsets.UTF_8), time);
            ZipEntry dir = new ZipEntry("hagrid/");
            dir.setTime(time);
            z.putNextEntry(dir);
            z.closeEntry();
            List<Map.Entry<String, byte[]>> list = new ArrayList<>(entries.entrySet());
            if (reversed) {
                Collections.reverse(list);
            }
            for (Map.Entry<String, byte[]> e : list) {
                put(z, e.getKey(), e.getValue(), time);
            }
        }
        return file;
    }

    private static void put(ZipOutputStream z, String name, byte[] content, long time) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        z.putNextEntry(e);
        z.write(content);
        z.closeEntry();
    }

    private static final String MANIFEST = "Manifest-Version: 1.0\r\nMain-Class: hagrid.Main\r\n";

    @Test
    void sameContentGivesTheSameFingerprintDespiteTimestampsAndEntryOrder() throws IOException {
        String a = JarFingerprint.ofJar(jar(tmp.resolve("a.jar"), 0L, MANIFEST, "#Created by Apache Maven", entries(), false));
        String b = JarFingerprint.ofJar(jar(tmp.resolve("b.jar"), 1_700_000_000_000L, MANIFEST, "#Created by Apache Maven",
                entries(), true));
        assertThat(b).isEqualTo(a);
    }

    @Test
    void aManifestThatSwitchesOnMultiReleaseGivesADifferentFingerprint() throws IOException {
        // Multi-Release decides whether Java loads the META-INF/versions/ classes of the shaded JAR
        String a = JarFingerprint.ofJar(jar(tmp.resolve("a.jar"), 0L, MANIFEST, "p", entries(), false));
        String b = JarFingerprint.ofJar(jar(tmp.resolve("b.jar"), 0L, MANIFEST + "Multi-Release: true\r\n", "p",
                entries(), false));
        assertThat(b).isNotEqualTo(a);
    }

    @Test
    void aChangedPomPropertiesGivesADifferentFingerprint() throws IOException {
        String a = JarFingerprint.ofJar(jar(tmp.resolve("a.jar"), 0L, MANIFEST, "version=1.0", entries(), false));
        String b = JarFingerprint.ofJar(jar(tmp.resolve("b.jar"), 0L, MANIFEST, "version=1.1", entries(), false));
        assertThat(b).isNotEqualTo(a);
    }

    @Test
    void oneChangedClassByteGivesADifferentFingerprint() throws IOException {
        String a = JarFingerprint.ofJar(jar(tmp.resolve("a.jar"), 0L, "m", "p", entries(), false));
        Map<String, byte[]> changed = entries();
        changed.put("hagrid/B.class", new byte[]{4, 6});
        String b = JarFingerprint.ofJar(jar(tmp.resolve("b.jar"), 0L, "m", "p", changed, false));
        assertThat(b).isNotEqualTo(a);
    }

    @Test
    void aChangedResourceGivesADifferentFingerprint() throws IOException {
        String a = JarFingerprint.ofJar(jar(tmp.resolve("a.jar"), 0L, "m", "p", entries(), false));
        Map<String, byte[]> changed = entries();
        changed.put("log4j2.xml", "<Configuration status=\"warn\"/>".getBytes(StandardCharsets.UTF_8));
        assertThat(JarFingerprint.ofJar(jar(tmp.resolve("b.jar"), 0L, "m", "p", changed, false))).isNotEqualTo(a);
    }

    @Test
    void movingBytesBetweenEntriesChangesTheFingerprint() throws IOException {
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put("x/A.class", new byte[]{1, 2});
        one.put("x/B.class", new byte[]{3});
        Map<String, byte[]> two = new LinkedHashMap<>();
        two.put("x/A.class", new byte[]{1});
        two.put("x/B.class", new byte[]{2, 3});
        assertThat(JarFingerprint.ofJar(jar(tmp.resolve("a.jar"), 0L, "m", "p", one, false)))
                .isNotEqualTo(JarFingerprint.ofJar(jar(tmp.resolve("b.jar"), 0L, "m", "p", two, false)));
    }

    @Test
    void aClassesDirectoryHasNoFingerprint() throws IOException {
        assertThat(JarFingerprint.of(tmp)).isEmpty();
    }

    @Test
    void inTheTestJvmTheRunningCodeIsAClassesDirectory() {
        assertThat(JarFingerprint.ofRunningCode()).isEmpty();
    }

    @Test
    void theTestOverrideWinsAndCanBeReset() {
        JarFingerprint.setOverrideForTests("forced");
        try {
            assertThat(JarFingerprint.ofRunningCode()).isEqualTo(Optional.of("forced"));
        } finally {
            JarFingerprint.setOverrideForTests(null);
        }
        assertThat(JarFingerprint.ofRunningCode()).isEmpty();
    }
}
