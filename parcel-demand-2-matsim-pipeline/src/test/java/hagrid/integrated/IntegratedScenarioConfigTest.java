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
            assertThat(c.getCargoLabourCostPerHour()).isEqualTo(20.0);   // Rudolph ~80/20 anchor
            assertThat(c.getDeliveryDwellFactorAutonomous()).isEqualTo(1.5);
            assertThat(c.getAutonomousMaxSpeedKmh()).isEqualTo(30.0);
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

        @Test
        @DisplayName("speed sensitivity lever: 50 km/h (spec section 6.1)")
        void maxSpeedSensitivity() {
            IntegratedScenarioConfig c = IntegratedScenarioConfig.builder()
                    .operationMode(OperationMode.AUTONOMOUS)
                    .autonomousMaxSpeedKmh(50.0)
                    .build();
            assertThat(c.effectiveMaxSpeedMps()).hasValue(50.0 / 3.6);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {
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

        @Test
        @DisplayName("cargoLabourCostPerHour must be >= 0")
        void labourCostNonNegative() {
            assertThatThrownBy(() -> IntegratedScenarioConfig.builder().cargoLabourCostPerHour(-1.0).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cargoLabourCostPerHour");
        }
    }
}
