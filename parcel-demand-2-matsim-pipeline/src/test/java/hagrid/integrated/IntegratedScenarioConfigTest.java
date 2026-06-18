package hagrid.integrated;

import hagrid.integrated.IntegratedScenarioConfig.OperationMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("IntegratedScenarioConfig")
class IntegratedScenarioConfigTest {

    @Nested
    @DisplayName("Defaults")
    class Defaults {
        @Test
        @DisplayName("spec-grounded defaults")
        void defaults() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder().build();
            assertThat(c.getOperationMode()).isEqualTo(OperationMode.CONVENTIONAL);
            assertThat(c.getRetoolingTimeSeconds()).isEqualTo(420.0);   // 7 min
            assertThat(c.getIdleThreshold()).isEqualTo(0.50);
            assertThat(c.getAutonomousMaxSpeedKmh()).isEqualTo(30.0);
            assertThat(c.getDepotCount()).isEqualTo(3);
            assertThat(c.getExcludedRoadTypes()).containsExactly("motorway", "motorway_link");
        }
    }

    @Nested
    @DisplayName("Operation mode helpers (spec section 4.4)")
    class OperationModeHelpers {
        @Test
        @DisplayName("CONVENTIONAL: labour on, dwell x1, no speed cap, no road exclusion")
        void conventional() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder()
                    .operationMode(OperationMode.CONVENTIONAL)
                    .cargoLabourCostPerHour(20.0)
                    .build();
            assertThat(c.effectiveLabourCostPerHour()).isEqualTo(20.0);
            assertThat(c.effectiveDeliveryDwellFactor()).isEqualTo(1.0);
            assertThat(c.effectiveMaxSpeedMps()).isEmpty();
            assertThat(c.effectiveExcludedRoadTypes()).isEmpty();
        }

        @Test
        @DisplayName("AUTONOMOUS: labour off, dwell stretched, speed capped, motorways excluded")
        void autonomous() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder()
                    .operationMode(OperationMode.AUTONOMOUS)
                    .cargoLabourCostPerHour(20.0)
                    .deliveryDwellFactorAutonomous(1.5)
                    .autonomousMaxSpeedKmh(30.0)
                    .build();
            assertThat(c.effectiveLabourCostPerHour()).isZero();
            assertThat(c.effectiveDeliveryDwellFactor()).isEqualTo(1.5);
            assertThat(c.effectiveMaxSpeedMps()).hasValue(30.0 / 3.6);
            assertThat(c.effectiveExcludedRoadTypes()).containsExactly("motorway", "motorway_link");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {
        @Test
        @DisplayName("idleThreshold must be within [0,1]")
        void idleThresholdRange() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().idleThreshold(1.5).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idleThreshold");
        }

        @Test
        @DisplayName("depotCount must be >= 1")
        void depotCountPositive() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().depotCount(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("depotCount");
        }

        @Test
        @DisplayName("b2cLockerShare must be within [0,1]")
        void b2cLockerShareRange() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().b2cLockerShare(1.5).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("b2cLockerShare");
        }

        @Test
        @DisplayName("fleetSize must be >= 1")
        void fleetSizePositive() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().fleetSize(0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fleetSize");
        }

        @Test
        @DisplayName("retoolingTimeSeconds must be >= 0")
        void retoolingNonNegative() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().retoolingTimeSeconds(-1.0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retoolingTimeSeconds");
        }

        @Test
        @DisplayName("deliveryDwellFactorAutonomous must be >= 1.0")
        void dwellFactorMinimum() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().deliveryDwellFactorAutonomous(0.5).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deliveryDwellFactorAutonomous");
        }

        @Test
        @DisplayName("autonomousMaxSpeedKmh must be > 0")
        void maxSpeedPositive() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().autonomousMaxSpeedKmh(0.0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("autonomousMaxSpeedKmh");
        }
    }
}
