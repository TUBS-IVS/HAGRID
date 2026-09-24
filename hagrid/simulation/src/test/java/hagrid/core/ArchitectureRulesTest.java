package hagrid.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Architekturregel des Repos in zehn Zeilen (Spec 2026-09-17-repo-restructure-design.md §4.1):
 * <ol>
 *   <li>{@code hagrid.hannover} und {@code hagrid.lausitz} referenzieren einander nie.</li>
 *   <li>Aus {@code hagrid.core} zeigen nur die Schaltzentralen-Klassen in die Studienpakete.</li>
 * </ol>
 * Gescannt wird der gesamte Quelltext ohne Kommentare, nicht nur {@code import}-Zeilen,
 * damit voll qualifizierte Referenzen (z. B. in {@code HagridPaths}) mitzählen.
 */
@DisplayName("Architecture rules: core / hannover / lausitz")
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src", "main", "java", "hagrid");
    // Keyed on the path relative to src/main/java/hagrid, NOT on the simple file name:
    // two classes in different packages may share a name, and a name-keyed allowlist
    // would wave both of them through.
    private static final Set<String> CORE_SWITCHBOARD = Set.of(
            "core/HagridPaths.java",
            "core/simulation/HAGRIDScenarioBuilder.java",
            "core/simulation/HAGRIDSimulationConfig.java",
            "core/simulation/SimulationRunnerUtils.java");
    // Heuristic, not a parser: string literals are not understood, so a "//" inside a
    // non-URL string would still be stripped as a comment. Acceptable here because this
    // scan is a guard — Step 4 additionally runs git grep over resources.
    private static final Pattern COMMENTS = Pattern.compile("(?<!:)//.*?$|/\\*.*?\\*/", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Map<String, Pattern> STUDY_TOKENS = Map.of(
            "hannover", Pattern.compile("hagrid\\.hannover\\."),
            "lausitz", Pattern.compile("hagrid\\.lausitz\\."));

    private static Stream<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList().stream();
        }
    }

    private static String rootOf(Path file) {
        return MAIN.relativize(file).getName(0).toString();
    }

    /** Allowlist key: path relative to {@code src/main/java/hagrid}, always with '/'. */
    private static String keyOf(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    private static String codeWithoutComments(Path file) throws IOException {
        return COMMENTS.matcher(Files.readString(file)).replaceAll("");
    }

    @Test
    @DisplayName("every main source lives under core, hannover or lausitz")
    void onlyThreeRoots() throws IOException {
        List<Path> strays = mainSources().filter(p -> !Set.of("core", "hannover", "lausitz").contains(rootOf(p))).toList();
        assertThat(strays).isEmpty();
    }

    @Test
    @DisplayName("hannover and lausitz never reference each other")
    void studiesAreIndependent() throws IOException {
        List<String> violations = mainSources()
                .filter(p -> Set.of("hannover", "lausitz").contains(rootOf(p)))
                .filter(p -> {
                    String other = rootOf(p).equals("hannover") ? "lausitz" : "hannover";
                    try { return STUDY_TOKENS.get(other).matcher(codeWithoutComments(p)).find(); }
                    catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(Path::toString).toList();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("only the switchboard classes in core reference a study package")
    void coreReachesStudiesOnlyViaSwitchboard() throws IOException {
        List<String> violations = mainSources()
                .filter(p -> rootOf(p).equals("core"))
                .filter(p -> !CORE_SWITCHBOARD.contains(keyOf(p)))
                .filter(p -> {
                    try {
                        String code = codeWithoutComments(p);
                        return STUDY_TOKENS.values().stream().anyMatch(t -> t.matcher(code).find());
                    } catch (IOException e) { throw new RuntimeException(e); }
                })
                .map(Path::toString).toList();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the switchboard allowlist is not padded: every listed class really reaches a study package")
    void switchboardListIsTight() throws IOException {
        for (String key : CORE_SWITCHBOARD) {
            Path p = MAIN.resolve(key);
            assertThat(Files.isRegularFile(p))
                    .as(key + " is on the allowlist but does not exist — the class moved, fix the key")
                    .isTrue();
            String code = codeWithoutComments(p);
            assertThat(STUDY_TOKENS.values().stream().anyMatch(t -> t.matcher(code).find()))
                    .as(key + " is on the allowlist but references no study package — remove it from the list")
                    .isTrue();
        }
    }
}
