package hagrid.pipeline;

import hagrid.pipeline.ScenarioConfig.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link ScenarioConfig} — scenario configuration with Builder pattern.
 */
@DisplayName("ScenarioConfig")
class ScenarioConfigTest {

    private static final LocalDate MAY_13 = LocalDate.of(2025, 5, 13);
    private static final LocalDate MAY_14 = LocalDate.of(2025, 5, 14);

    // =========================================================================
    // BUILDER DEFAULTS
    // =========================================================================

    @Nested
    @DisplayName("Builder Defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("default builder produces valid config")
        void defaultBuilderValid() {
            ScenarioConfig config = ScenarioConfig.builder().build();

            assertThat(config.getConcepts()).containsExactly("basecase");
            assertThat(config.getDates()).containsExactly(MAY_13);
            assertThat(config.getFilterRegions()).isEqualTo("Hannover");
        }

        @Test
        @DisplayName("default vehicle sizes are m and l")
        void defaultVehicleSizes() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            assertThat(config.getVehicleConfig().getDefaultVehicleSizes())
                .containsExactly("m", "l");
        }

        @Test
        @DisplayName("default schedule is SIMPLE_STAGGERED")
        void defaultSchedule() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            assertThat(config.getVehicleConfig().getDefaultSchedule())
                .isEqualTo(VehicleSchedule.SIMPLE_STAGGERED);
        }

        @Test
        @DisplayName("default dispatch window is 7-14")
        void defaultDeliveryWindow() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            DispatchWindow dw = config.getDispatchWindow("default");
            assertThat(dw.getStartHour()).isEqualTo(7);
            assertThat(dw.getEndHour()).isEqualTo(14);
        }

        @Test
        @DisplayName("default pipeline settings: no simplifier, no routing, caching off, jsprit=1")
        void defaultPipelineSettings() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            PipelineSettings ps = config.getPipelineSettings();

            assertThat(ps.isApplyServiceSimplifier()).isFalse();
            assertThat(ps.isRunRouting()).isFalse();
            assertThat(ps.isCachingEnabled()).isFalse();
            assertThat(ps.getJspritIterations()).isEqualTo(1);
        }
    }

    // =========================================================================
    // BUILDER VALIDATION
    // =========================================================================

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("empty concepts list throws IllegalArgumentException")
        void emptyConceptsThrows() {
            assertThatThrownBy(() -> ScenarioConfig.builder().concepts(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one concept");
        }

        @Test
        @DisplayName("empty dates list throws IllegalArgumentException")
        void emptyDatesThrows() {
            assertThatThrownBy(() -> ScenarioConfig.builder().dates(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one date");
        }

        @Test
        @DisplayName("empty vehicle sizes list throws IllegalArgumentException")
        void emptyVehicleSizesThrows() {
            assertThatThrownBy(() -> ScenarioConfig.builder().vehicleSizes(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one vehicle size");
        }

        @Test
        @DisplayName("null concepts throws NullPointerException")
        void nullConceptsThrows() {
            assertThatThrownBy(() -> ScenarioConfig.builder().concepts((List<String>) null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null dates throws NullPointerException")
        void nullDatesThrows() {
            assertThatThrownBy(() -> ScenarioConfig.builder().dates((List<LocalDate>) null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // RUN-ID GENERATION
    // =========================================================================

    @Nested
    @DisplayName("Run ID Generation")
    class RunIdGeneration {

        @Test
        @DisplayName("createRunId returns UPPERCASE_ddMMyyyy without tag")
        void createRunIdFormat() {
            ScenarioConfig config = ScenarioConfig.builder().build();

            String runId = config.createRunId("basecase", MAY_13);
            assertThat(runId).isEqualTo("BASECASE_13052025");
        }

        @Test
        @DisplayName("createRunId converts concept to uppercase")
        void runIdUpperCase() {
            ScenarioConfig config = ScenarioConfig.builder().build();

            String runId = config.createRunId("batchHigh", MAY_14);
            assertThat(runId).isEqualTo("BATCHHIGH_14052025");
        }

        @Test
        @DisplayName("createRunId with already-uppercase concept stays uppercase")
        void runIdAlreadyUpperCase() {
            ScenarioConfig config = ScenarioConfig.builder().build();

            String runId = config.createRunId("UCC", MAY_13);
            assertThat(runId).isEqualTo("UCC_13052025");
        }

        @Test
        @DisplayName("createRunId appends tag when set")
        void runIdWithTag() {
            ScenarioConfig config = ScenarioConfig.builder().tag("V1").build();

            String runId = config.createRunId("basecase", MAY_13);
            assertThat(runId).isEqualTo("BASECASE_13052025_V1");
        }

        @Test
        @DisplayName("createRunId with empty tag behaves like no tag")
        void runIdWithEmptyTag() {
            ScenarioConfig config = ScenarioConfig.builder().tag("").build();

            String runId = config.createRunId("basecase", MAY_13);
            assertThat(runId).isEqualTo("BASECASE_13052025");
        }

        @Test
        @DisplayName("createRunId with null tag behaves like no tag")
        void runIdWithNullTag() {
            ScenarioConfig config = ScenarioConfig.builder().tag(null).build();

            String runId = config.createRunId("basecase", MAY_13);
            assertThat(runId).isEqualTo("BASECASE_13052025");
        }

        @Test
        @DisplayName("getTag returns configured tag")
        void getTagReturnsTag() {
            ScenarioConfig config = ScenarioConfig.builder().tag("V2").build();
            assertThat(config.getTag()).isEqualTo("V2");
        }

        @Test
        @DisplayName("default tag is empty string")
        void defaultTagIsEmpty() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            assertThat(config.getTag()).isEmpty();
        }
    }

    // =========================================================================
    // DELIVERY WINDOW
    // =========================================================================

    @Nested
    @DisplayName("DispatchWindow")
    class DeliveryWindowTests {

        @Test
        @DisplayName("valid window 8-20 creates successfully")
        void validWindow() {
            DispatchWindow dw = new DispatchWindow(8, 20);
            assertThat(dw.getStartHour()).isEqualTo(8);
            assertThat(dw.getEndHour()).isEqualTo(20);
        }

        @Test
        @DisplayName("start > end throws IllegalArgumentException")
        void startAfterEnd() {
            assertThatThrownBy(() -> new DispatchWindow(14, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("End hour must be >= start hour");
        }

        @Test
        @DisplayName("hour < 0 throws IllegalArgumentException")
        void negativeHour() {
            assertThatThrownBy(() -> new DispatchWindow(-1, 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hours must be within [0, 23]");
        }

        @Test
        @DisplayName("hour > 23 throws IllegalArgumentException")
        void hourAbove23() {
            assertThatThrownBy(() -> new DispatchWindow(7, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Hours must be within [0, 23]");
        }

        @Test
        @DisplayName("same start and end hour is valid (zero-width window)")
        void sameStartAndEnd() {
            DispatchWindow dw = new DispatchWindow(12, 12);
            assertThat(dw.getStartHour()).isEqualTo(12);
            assertThat(dw.getEndHour()).isEqualTo(12);
        }

        @Test
        @DisplayName("toString formats as HH:00-HH:00")
        void toStringFormat() {
            assertThat(new DispatchWindow(7, 14).toString()).isEqualTo("07:00-14:00");
        }
    }

    // =========================================================================
    // VEHICLE SCHEDULE
    // =========================================================================

    @Nested
    @DisplayName("VehicleSchedule")
    class VehicleScheduleTests {

        @Test
        @DisplayName("SIMPLE_STAGGERED produces [7, 14] for window 7-14")
        void simpleStaggered() {
            List<Integer> hours = VehicleSchedule.SIMPLE_STAGGERED.computeDispatchHours(7, 14);
            assertThat(hours).containsExactly(7, 14);
        }

        @Test
        @DisplayName("SIMPLE_STAGGERED falls back to start hour if no default hours in window")
        void simpleStaggeredFallback() {
            List<Integer> hours = VehicleSchedule.SIMPLE_STAGGERED.computeDispatchHours(16, 20);
            assertThat(hours).containsExactly(16);
        }

        @Test
        @DisplayName("EXTENDED produces [7, 11, 14] for window 7-14")
        void extended() {
            List<Integer> hours = VehicleSchedule.EXTENDED.computeDispatchHours(7, 14);
            assertThat(hours).containsExactly(7, 11, 14);
        }

        @Test
        @DisplayName("FULL_WINDOW produces every hour in range")
        void fullWindow() {
            List<Integer> hours = VehicleSchedule.FULL_WINDOW.computeDispatchHours(8, 11);
            assertThat(hours).containsExactly(8, 9, 10, 11);
        }

        @Test
        @DisplayName("EARLY_ONLY produces [startHour - 1]")
        void earlyOnly() {
            List<Integer> hours = VehicleSchedule.EARLY_ONLY.computeDispatchHours(8, 14);
            assertThat(hours).containsExactly(7);
        }

        @Test
        @DisplayName("EARLY_ONLY with startHour=0 clamps to 0")
        void earlyOnlyClamped() {
            List<Integer> hours = VehicleSchedule.EARLY_ONLY.computeDispatchHours(0, 14);
            assertThat(hours).containsExactly(0);
        }
    }

    // =========================================================================
    // VEHICLE CONFIG + DISPATCH HOURS
    // =========================================================================

    @Nested
    @DisplayName("VehicleConfig Dispatch")
    class VehicleConfigDispatch {

        @Test
        @DisplayName("provider-specific sizes override default")
        void providerSizesOverride() {
            ScenarioConfig config = ScenarioConfig.builder()
                .providerVehicleSizes("amazon", "s", "bike")
                .build();

            assertThat(config.getVehicleConfig().getVehicleSizesForProvider("amazon"))
                .containsExactly("s", "bike");
            assertThat(config.getVehicleConfig().getVehicleSizesForProvider("dhl"))
                .containsExactly("m", "l");
        }

        @Test
        @DisplayName("computeDispatchHours applies time shift")
        void dispatchHoursWithTimeShift() {
            ScenarioConfig config = ScenarioConfig.builder()
                .providerTimeShift("amazon", 1)
                .build();

            DispatchWindow dw = new DispatchWindow(7, 14);
            List<Integer> hours = config.getVehicleConfig()
                .computeDispatchHours("amazon", dw);
            // SIMPLE_STAGGERED [7,14] + shift 1 = [8, 15] but 15 clamped to 15 (within 0-23)
            assertThat(hours).containsExactly(8, 15);
        }

        @Test
        @DisplayName("custom dispatch hours override schedule preset")
        void customHoursOverrideSchedule() {
            ScenarioConfig config = ScenarioConfig.builder()
                .providerDispatchHours("dhl", 6, 10, 15)
                .build();

            DispatchWindow dw = new DispatchWindow(6, 20);
            List<Integer> hours = config.getVehicleConfig()
                .computeDispatchHours("dhl", dw);
            assertThat(hours).containsExactly(6, 10, 15);
        }
    }

    // =========================================================================
    // FULL SCENARIO CONFIGURATION
    // =========================================================================

    @Nested
    @DisplayName("Full Config Build")
    class FullConfigBuild {

        @Test
        @DisplayName("complex scenario config builds correctly")
        void complexConfig() {
            ScenarioConfig config = ScenarioConfig.builder()
                .concepts("basecase", "batchHigh")
                .dates(MAY_13, MAY_14)
                .filterRegions("MH")
                .vehicleSizes("m", "l", "bike")
                .vehicleSchedule(VehicleSchedule.EXTENDED)
                .dispatchWindow(8, 20)
                .jspritIterations(25)
                .runRouting(true)
                .applyServiceSimplifier(true)
                .build();

            assertThat(config.getConcepts()).containsExactly("basecase", "batchhigh");
            assertThat(config.getDates()).containsExactly(MAY_13, MAY_14);
            assertThat(config.getFilterRegions()).isEqualTo("MH");
            assertThat(config.getVehicleConfig().getDefaultVehicleSizes())
                .containsExactly("m", "l", "bike");
            assertThat(config.getVehicleConfig().getDefaultSchedule())
                .isEqualTo(VehicleSchedule.EXTENDED);
            assertThat(config.getPipelineSettings().getJspritIterations()).isEqualTo(25);
            assertThat(config.getPipelineSettings().isRunRouting()).isTrue();
            assertThat(config.getPipelineSettings().isApplyServiceSimplifier()).isTrue();
        }

        @Test
        @DisplayName("concepts list is unmodifiable")
        void conceptsUnmodifiable() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            assertThatThrownBy(() -> config.getConcepts().add("illegal"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("dates list is unmodifiable")
        void datesUnmodifiable() {
            ScenarioConfig config = ScenarioConfig.builder().build();
            assertThatThrownBy(() -> config.getDates().add(MAY_14))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("jspritIterations clamps to min 1")
        void jspritClamps() {
            ScenarioConfig config = ScenarioConfig.builder()
                .jspritIterations(0)
                .build();
            assertThat(config.getPipelineSettings().getJspritIterations()).isEqualTo(1);
        }

        @Test
        @DisplayName("provider dispatch window overrides default")
        void providerDeliveryWindow() {
            ScenarioConfig config = ScenarioConfig.builder()
                .dispatchWindow("amazon", 9, 21)
                .build();

            DispatchWindow amazon = config.getDispatchWindow("amazon");
            assertThat(amazon.getStartHour()).isEqualTo(9);
            assertThat(amazon.getEndHour()).isEqualTo(21);

            // Default still exists
            DispatchWindow def = config.getDispatchWindow("default");
            assertThat(def.getStartHour()).isEqualTo(7);
        }
    }
}
