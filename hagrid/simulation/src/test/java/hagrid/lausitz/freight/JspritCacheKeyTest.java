package hagrid.lausitz.freight;

import hagrid.core.routing.HAGRIDRouterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JspritCacheKey")
class JspritCacheKeyTest {

    @TempDir
    Path tmp;

    /** Dummy input files - the key only hashes bytes, so they need not be valid shapefiles. */
    static Fixture stage(Path dir) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("demand.shp"), "shp");
        write(dir.resolve("demand.dbf"), "dbf");
        write(dir.resolve("demand.shx"), "shx");
        write(dir.resolve("demand.prj"), "prj");
        write(dir.resolve("area.shp"), "area-shp");
        write(dir.resolve("area.dbf"), "area-dbf");
        write(dir.resolve("depots.csv"), "provider;x;y\n");
        write(dir.resolve("net.xml.gz"), "net");
        write(dir.resolve("vans.xml"), "vans");
        return new Fixture(dir);
    }

    static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    record Fixture(Path dir) {
        String s(String name) { return dir.resolve(name).toString(); }
        Path p(String name) { return dir.resolve(name); }
        LmdPreprocessInputs baseline() {
            return LmdPreprocessInputs.baseline(s("demand.shp"), s("depots.csv"), s("net.xml.gz"),
                    s("vans.xml"), 100, s("area.shp"));
        }
        LmdPreprocessInputs modular() {
            return LmdPreprocessInputs.modular(s("demand.shp"), s("depots.csv"), s("net.xml.gz"),
                    s("vans.xml"), 100, s("area.shp"), 10800, List.of("a", "b"), 300);
        }
    }

    static JspritCacheKey key(LmdPreprocessInputs in) throws IOException {
        return JspritCacheKey.of(in, "fp-1", p -> null, "java-1");
    }

    @FunctionalInterface
    interface KeyStep { JspritCacheKey apply(Fixture f) throws IOException; }

    /** before is evaluated first, then after (which may change files) - the two keys must differ. */
    record Case(String name, KeyStep before, KeyStep after) {
        @Override public String toString() { return name; }
    }

    static Case fileChange(String name, String file, boolean modular) {
        KeyStep k = f -> key(modular ? f.modular() : f.baseline());
        return new Case(name, k, f -> { write(f.p(file), "changed"); return k.apply(f); });
    }

    static Stream<Case> everyComponentChangesTheKey() {
        return Stream.of(
                fileChange("demand .shp", "demand.shp", false),
                fileChange("demand .dbf sidecar", "demand.dbf", false),
                fileChange("demand .prj sidecar", "demand.prj", false),
                fileChange("service-area .shp", "area.shp", false),
                fileChange("service-area .dbf sidecar", "area.dbf", false),
                fileChange("depot csv", "depots.csv", false),
                fileChange("network", "net.xml.gz", false),
                fileChange("vehicle types", "vans.xml", false),
                fileChange("modular network", "net.xml.gz", true),
                new Case("variant", f -> key(f.baseline()), f -> key(f.modular())),
                new Case("jspritIterations", f -> key(f.baseline()), f -> key(
                        LmdPreprocessInputs.baseline(f.s("demand.shp"), f.s("depots.csv"), f.s("net.xml.gz"),
                                f.s("vans.xml"), 101, f.s("area.shp")))),
                new Case("service area dropped", f -> key(f.baseline()), f -> key(
                        LmdPreprocessInputs.baseline(f.s("demand.shp"), f.s("depots.csv"), f.s("net.xml.gz"),
                                f.s("vans.xml"), 100, null))),
                new Case("maxTourDurationSeconds", f -> key(f.modular()), f -> key(
                        LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"), f.s("net.xml.gz"),
                                f.s("vans.xml"), 100, f.s("area.shp"), 12600, List.of("a", "b"), 300))),
                new Case("openDepots order", f -> key(f.modular()), f -> key(
                        LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"), f.s("net.xml.gz"),
                                f.s("vans.xml"), 100, f.s("area.shp"), 10800, List.of("b", "a"), 300))),
                new Case("maxJobsPerDistrict", f -> key(f.modular()), f -> key(
                        LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"), f.s("net.xml.gz"),
                                f.s("vans.xml"), 100, f.s("area.shp"), 10800, List.of("a", "b"), 299))),
                new Case("jsprit seed property", f -> key(f.baseline()), f -> JspritCacheKey.of(f.baseline(),
                        "fp-1", p -> p.equals(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY) ? "42" : null, "java-1")),
                new Case("code fingerprint", f -> key(f.baseline()),
                        f -> JspritCacheKey.of(f.baseline(), "fp-2", p -> null, "java-1")),
                new Case("java runtime", f -> key(f.baseline()),
                        f -> JspritCacheKey.of(f.baseline(), "fp-1", p -> null, "java-2")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void everyComponentChangesTheKey(Case c) throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        JspritCacheKey before = c.before().apply(f);
        JspritCacheKey after = c.after().apply(f);
        assertThat(after.fullHash()).as(c.name()).isNotEqualTo(before.fullHash());
    }

    @Test
    void sameContentInAnotherFolderGivesTheSameKey() throws IOException {
        Fixture a = stage(tmp.resolve("a"));
        Fixture b = stage(tmp.resolve("b").resolve("deeper"));
        assertThat(key(b.baseline()).fullHash()).isEqualTo(key(a.baseline()).fullHash());
    }

    @Test
    void neighbourWithALongerBaseNameIsNotPartOfTheFamily() throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        String before = key(f.baseline()).fullHash();
        write(f.p("demand-with-ruhland.dbf"), "neighbour");
        write(f.p("area-with-ruhland.shp"), "neighbour");
        assertThat(key(f.baseline()).fullHash()).isEqualTo(before);
    }

    @Test
    void uppercaseExtensionsBelongToTheFamily() throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        String before = key(f.baseline()).fullHash();
        write(f.p("demand.CPG"), "UTF-8");
        assertThat(key(f.baseline()).fullHash()).as("new .CPG sidecar").isNotEqualTo(before);

        Path upper = tmp.resolve("upper");
        Files.createDirectories(upper);
        write(upper.resolve("DEMAND.SHP"), "shp");
        write(upper.resolve("DEMAND.DBF"), "dbf");
        LmdPreprocessInputs in = LmdPreprocessInputs.baseline(upper.resolve("DEMAND.SHP").toString(),
                f.s("depots.csv"), f.s("net.xml.gz"), f.s("vans.xml"), 100, null);
        assertThat(key(in).components().get("demand_shp")).startsWith("dbf:").contains(",shp:");
    }

    @Test
    void everyRecordComponentAppearsInTheManifest() throws IOException {
        JspritCacheKey k = key(stage(tmp.resolve("in")).modular());
        for (RecordComponent rc : LmdPreprocessInputs.class.getRecordComponents()) {
            assertThat(k.components()).containsKey(JspritCacheKey.snakeCase(rc.getName()));
        }
        assertThat(k.components()).containsKeys("prop." + HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY, "code", "java");
    }

    @Test
    void baselineHasNoModularParametersAndAnUnsetSeed() throws IOException {
        JspritCacheKey k = key(stage(tmp.resolve("in")).baseline());
        assertThat(k.components().get("max_tour_duration_seconds")).isEqualTo(JspritCacheKey.NONE);
        assertThat(k.components().get("open_depots")).isEqualTo(JspritCacheKey.NONE);
        assertThat(k.components().get("prop.hagrid.jsprit.seed")).isEqualTo(JspritCacheKey.UNSET);
    }

    @Test
    void nullAndEmptyOpenDepotsAreTheSameKey() throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        LmdPreprocessInputs withNull = LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"),
                f.s("net.xml.gz"), f.s("vans.xml"), 100, f.s("area.shp"), 10800, null, 300);
        LmdPreprocessInputs withEmpty = LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"),
                f.s("net.xml.gz"), f.s("vans.xml"), 100, f.s("area.shp"), 10800, List.of(), 300);
        assertThat(key(withNull).fullHash()).isEqualTo(key(withEmpty).fullHash());
    }

    /** M6: a comma inside one depot name must not read as two depots. */
    @Test
    void listEncodingIsUnambiguous() throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        LmdPreprocessInputs one = LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"),
                f.s("net.xml.gz"), f.s("vans.xml"), 100, f.s("area.shp"), 10800, List.of("a,b"), 300);
        LmdPreprocessInputs two = LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"),
                f.s("net.xml.gz"), f.s("vans.xml"), 100, f.s("area.shp"), 10800, List.of("a", "b"), 300);
        assertThat(key(one).fullHash()).isNotEqualTo(key(two).fullHash());
        assertThat(key(one).components().get("open_depots")).isEqualTo("[\"a,b\"]");
        assertThat(key(two).components().get("open_depots")).isEqualTo("[\"a\",\"b\"]");
        LmdPreprocessInputs none = LmdPreprocessInputs.modular(f.s("demand.shp"), f.s("depots.csv"),
                f.s("net.xml.gz"), f.s("vans.xml"), 100, f.s("area.shp"), 10800, List.of(), 300);
        assertThat(key(none).components().get("open_depots")).as("the empty list stays []").isEqualTo("[]");
    }

    @Test
    void dirNameIsTheVariantPlusSixteenHexCharacters() throws IOException {
        assertThat(key(stage(tmp.resolve("in")).baseline()).dirName()).matches("baseline-[0-9a-f]{16}");
    }

    @Test
    void changedComponentsNamesExactlyTheDifferences() throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        JspritCacheKey before = key(f.baseline());
        write(f.p("net.xml.gz"), "changed");
        JspritCacheKey after = JspritCacheKey.of(f.baseline(), "fp-2", p -> null, "java-1");
        assertThat(after.changedComponents(before.components())).containsExactly("network", "code");
    }

    @Test
    void missingInputFileFails() throws IOException {
        Fixture f = stage(tmp.resolve("in"));
        Files.delete(f.p("vans.xml"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> key(f.baseline()))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
    }
}
