package hagrid.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link CacheConfig} — cache configuration with Builder pattern.
 */
@DisplayName("CacheConfig")
class CacheConfigTest {

    @AfterEach
    void cleanUpGlobal() {
        CacheConfig.clearGlobal();
    }

    // =========================================================================
    // BUILDER VALIDATION
    // =========================================================================

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("enabled without cacheDirectory throws IllegalStateException")
        void enabledWithoutDirThrows() {
            assertThatThrownBy(() -> CacheConfig.builder().enabled(true).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cache directory must be set");
        }

        @Test
        @DisplayName("disabled cache builds OK without directory")
        void disabledBuildsWithoutDir() {
            CacheConfig config = CacheConfig.builder().enabled(false).build();

            assertThat(config.isEnabled()).isFalse();
            assertThat(config.getCacheDirectory()).isNull();
        }

        @Test
        @DisplayName("enabled with directory builds successfully")
        void enabledWithDirBuilds(@TempDir Path tempDir) {
            CacheConfig config = CacheConfig.builder()
                .enabled(true)
                .cacheDirectory(tempDir)
                .build();

            assertThat(config.isEnabled()).isTrue();
            assertThat(config.getCacheDirectory()).isEqualTo(tempDir);
        }

        @Test
        @DisplayName("runId(null) throws NullPointerException")
        void nullRunIdThrows() {
            assertThatThrownBy(() -> CacheConfig.builder().runId(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // forRun() CONVENIENCE
    // =========================================================================

    @Nested
    @DisplayName("forRun()")
    class ForRun {

        @Test
        @DisplayName("forRun sets both cacheDirectory and runId")
        void setsDirectoryAndRunId(@TempDir Path tempDir) {
            CacheConfig config = CacheConfig.builder()
                .forRun(tempDir, "BASECASE_13052025")
                .build();

            assertThat(config.getRunId()).isEqualTo("BASECASE_13052025");
            assertThat(config.getCacheDirectory().toString())
                .contains("BASECASE_13052025");
        }

        @Test
        @DisplayName("forRun with null baseDirectory throws NullPointerException")
        void nullBaseDirThrows() {
            assertThatThrownBy(() -> CacheConfig.builder().forRun(null, "RUN"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("forRun with null runId throws NullPointerException")
        void nullRunIdThrows(@TempDir Path tempDir) {
            assertThatThrownBy(() -> CacheConfig.builder().forRun(tempDir, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // GLOBAL INSTANCE
    // =========================================================================

    @Nested
    @DisplayName("Global Instance")
    class GlobalInstance {

        @Test
        @DisplayName("getGlobal is empty initially")
        void emptyInitially() {
            assertThat(CacheConfig.getGlobal()).isEmpty();
        }

        @Test
        @DisplayName("setGlobal + getGlobal round-trip")
        void roundTrip(@TempDir Path tempDir) {
            CacheConfig config = CacheConfig.builder()
                .cacheDirectory(tempDir)
                .runId("TEST_RUN")
                .build();

            CacheConfig.setGlobal(config);

            assertThat(CacheConfig.getGlobal()).isPresent().contains(config);
        }

        @Test
        @DisplayName("clearGlobal resets to empty")
        void clearResets(@TempDir Path tempDir) {
            CacheConfig config = CacheConfig.builder()
                .cacheDirectory(tempDir)
                .build();
            CacheConfig.setGlobal(config);
            CacheConfig.clearGlobal();

            assertThat(CacheConfig.getGlobal()).isEmpty();
        }

        @Test
        @DisplayName("buildAndSetGlobal sets global instance")
        void buildAndSetGlobal(@TempDir Path tempDir) {
            CacheConfig config = CacheConfig.builder()
                .cacheDirectory(tempDir)
                .runId("AUTO_SET")
                .buildAndSetGlobal();

            assertThat(CacheConfig.getGlobal()).isPresent().contains(config);
            assertThat(config.getRunId()).isEqualTo("AUTO_SET");
        }
    }

    // =========================================================================
    // ensureDirectoryExists
    // =========================================================================

    @Nested
    @DisplayName("ensureDirectoryExists")
    class EnsureDirectoryExists {

        @Test
        @DisplayName("creates directory when it does not exist")
        void createsDirectory(@TempDir Path tempDir) {
            Path subDir = tempDir.resolve("cache").resolve("deep");
            CacheConfig config = CacheConfig.builder()
                .cacheDirectory(subDir)
                .build();

            assertThat(config.ensureDirectoryExists()).isTrue();
            assertThat(subDir).isDirectory();
        }

        @Test
        @DisplayName("returns false when cacheDirectory is null (disabled)")
        void returnsFalseForNull() {
            CacheConfig config = CacheConfig.builder().enabled(false).build();
            assertThat(config.ensureDirectoryExists()).isFalse();
        }
    }

    // =========================================================================
    // toString
    // =========================================================================

    @Test
    @DisplayName("toString contains enabled, dir, runId")
    void toStringFormat(@TempDir Path tempDir) {
        CacheConfig config = CacheConfig.builder()
            .cacheDirectory(tempDir)
            .runId("RUN_42")
            .build();

        assertThat(config.toString())
            .contains("enabled=true")
            .contains("RUN_42");
    }
}
