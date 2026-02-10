package hagrid;

import hagrid.HagridConfig.*;
import hagrid.utils.general.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link HagridConfig} — the main POJO configuration for the HAGRID pipeline.
 */
@DisplayName("HagridConfig")
class HagridConfigTest {

    private HagridConfig config;

    @BeforeEach
    void setUp() {
        config = new HagridConfig();
    }

    // =========================================================================
    // TOP-LEVEL DEFAULTS
    // =========================================================================

    @Nested
    @DisplayName("Top-Level Defaults")
    class TopLevelDefaults {

        @Test
        @DisplayName("default scenario is BASECASE")
        void defaultScenario() {
            assertThat(config.getScenario()).isEqualTo(Scenario.BASECASE);
        }

        @Test
        @DisplayName("simulationDate is null before setSimulationDate")
        void noDateInitially() {
            assertThat(config.getSimulationDate()).isNull();
        }

        @Test
        @DisplayName("runId is null before setSimulationDate")
        void noRunIdInitially() {
            assertThat(config.getRunId()).isNull();
        }

        @Test
        @DisplayName("filterRegions defaults to ALL")
        void defaultFilterRegions() {
            assertThat(config.getFilterRegions()).containsExactly(Region.ALL);
        }

        @Test
        @DisplayName("sections are never null")
        void sectionsNeverNull() {
            assertThat(config.paths()).isNotNull();
            assertThat(config.providers()).isNotNull();
            assertThat(config.vehicles()).isNotNull();
            assertThat(config.routing()).isNotNull();
            assertThat(config.supply()).isNotNull();
            assertThat(config.hubs()).isNotNull();
            assertThat(config.network()).isNotNull();
            assertThat(config.io()).isNotNull();
        }
    }

    // =========================================================================
    // SCENARIO SETTINGS
    // =========================================================================

    @Nested
    @DisplayName("Scenario Settings")
    class ScenarioSettings {

        @Test
        @DisplayName("setScenario switches to WHITE_LABEL and updates providers")
        void switchToWhiteLabel() {
            config.setScenario(Scenario.WHITE_LABEL);

            assertThat(config.getScenario()).isEqualTo(Scenario.WHITE_LABEL);
            assertThat(config.isWhiteLabel()).isTrue();
            // White-label only has 'wl' provider
            assertThat(config.providers().getDeliveryRate("wl")).isEqualTo(94);
        }

        @Test
        @DisplayName("setSimulationDate generates runId as SCENARIO_ddMMyyyy (no tag)")
        void simulationDateSetsRunId() {
            config.setSimulationDate(LocalDate.of(2025, 5, 13));

            assertThat(config.getRunId()).isEqualTo("BASECASE_13052025");
        }

        @Test
        @DisplayName("setTag + setSimulationDate generates runId as SCENARIO_ddMMyyyy_TAG")
        void simulationDateWithTagSetsRunId() {
            config.setTag("V1");
            config.setSimulationDate(LocalDate.of(2025, 5, 13));

            assertThat(config.getRunId()).isEqualTo("BASECASE_13052025_V1");
            assertThat(config.getTag()).isEqualTo("V1");
        }

        @Test
        @DisplayName("setTag with empty string keeps runId without tag suffix")
        void emptyTagKeepsRunIdUnchanged() {
            config.setTag("");
            config.setSimulationDate(LocalDate.of(2025, 5, 13));

            assertThat(config.getRunId()).isEqualTo("BASECASE_13052025");
            assertThat(config.getTag()).isEmpty();
        }

        @Test
        @DisplayName("setConcept parses scenario name (case-insensitive)")
        void setConceptCaseInsensitive() {
            config.setConcept("white_label");
            assertThat(config.getScenario()).isEqualTo(Scenario.WHITE_LABEL);
        }

        @Test
        @DisplayName("setConcept throws on unknown concept")
        void unknownConceptThrows() {
            assertThatThrownBy(() -> config.setConcept("unknown_scenario"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("addFilterRegion removes ALL when specific region is added")
        void addFilterRegionRemovesAll() {
            config.addFilterRegion(Region.HANNOVER);
            assertThat(config.getFilterRegions())
                .doesNotContain(Region.ALL)
                .contains(Region.HANNOVER);
        }
    }

    // =========================================================================
    // VEHICLE CONFIG
    // =========================================================================

    @Nested
    @DisplayName("VehicleConfig")
    class VehicleConfigTests {

        @Test
        @DisplayName("default active sizes are m and l")
        void defaultSizes() {
            assertThat(config.vehicles().getActiveSizes()).containsExactly("m", "l");
        }

        @Test
        @DisplayName("known alias capacities: s=80, m=165, l=230, bike=23")
        void knownAliasCapacities() {
            VehicleConfig vc = config.vehicles();
            assertThat(vc.getCapacity("s")).isEqualTo(80);
            assertThat(vc.getCapacity("m")).isEqualTo(165);
            assertThat(vc.getCapacity("l")).isEqualTo(230);
            assertThat(vc.getCapacity("bike")).isEqualTo(23);
        }

        @Test
        @DisplayName("capacity lookup is case-insensitive and trims whitespace")
        void capacityNormalization() {
            VehicleConfig vc = config.vehicles();
            assertThat(vc.getCapacity(" M ")).isEqualTo(165);
            assertThat(vc.getCapacity("BIKE")).isEqualTo(23);
        }

        @Test
        @DisplayName("compound size '60_m' extracts numeric prefix 60")
        void compoundSizeExtractsNumericPrefix() {
            assertThat(config.vehicles().getCapacity("60_m")).isEqualTo(60);
        }

        @Test
        @DisplayName("pure numeric string returns its int value")
        void pureNumericSize() {
            assertThat(config.vehicles().getCapacity("100")).isEqualTo(100);
        }

        @Test
        @DisplayName("unknown size throws IllegalArgumentException")
        void unknownSizeThrows() {
            assertThatThrownBy(() -> config.vehicles().getCapacity("helicopter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown vehicle size");
        }

        @Test
        @DisplayName("getMinCapacity with default sizes (m,l) returns 165")
        void minCapacityDefault() {
            assertThat(config.vehicles().getMinCapacity()).isEqualTo(165);
        }

        @Test
        @DisplayName("getMaxCapacity with default sizes (m,l) returns 230")
        void maxCapacityDefault() {
            assertThat(config.vehicles().getMaxCapacity()).isEqualTo(230);
        }

        @Test
        @DisplayName("getMinCapacity with bike included returns 23")
        void minCapacityWithBike() {
            config.vehicles().setActiveSizes(List.of("m", "l", "bike"));
            assertThat(config.vehicles().getMinCapacity()).isEqualTo(23);
        }

        @Test
        @DisplayName("getMinCapacity with empty list throws IllegalStateException")
        void minCapacityEmptyThrows() {
            config.vehicles().setActiveSizes(List.of());
            assertThatThrownBy(() -> config.vehicles().getMinCapacity())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No vehicle sizes configured");
        }

        @Test
        @DisplayName("getSizesForProvider falls back to active sizes")
        void sizesForProviderFallback() {
            assertThat(config.vehicles().getSizesForProvider("dhl"))
                .containsExactly("m", "l");
        }

        @Test
        @DisplayName("getSizesForProvider returns provider-specific override")
        void sizesForProviderOverride() {
            config.vehicles().setProviderSizes("amazon", List.of("s", "m"));
            assertThat(config.vehicles().getSizesForProvider("amazon"))
                .containsExactly("s", "m");
        }

        @Test
        @DisplayName("getSizesForProvider(null) returns default active sizes")
        void sizesForNullProvider() {
            assertThat(config.vehicles().getSizesForProvider(null))
                .containsExactly("m", "l");
        }

        @Test
        @DisplayName("default dispatch hours are [7, 14]")
        void defaultDispatchHours() {
            assertThat(config.vehicles().getDispatchHours(null)).containsExactly(7, 14);
        }

        @Test
        @DisplayName("dispatch hours with time shift applied correctly")
        void dispatchHoursWithTimeShift() {
            config.vehicles().setProviderTimeShift("amazon", 1);
            // Default hours [7, 14] + shift 1 = [8, 15]
            assertThat(config.vehicles().getDispatchHours("amazon")).containsExactly(8, 15);
        }

        @Test
        @DisplayName("time shift is clamped to [0, 23]")
        void timeShiftClamped() {
            config.vehicles().setDefaultDispatchHours(List.of(22, 23));
            config.vehicles().setProviderTimeShift("late", 3);
            // 22+3=25 → clamped to 23, 23+3=26 → clamped to 23
            assertThat(config.vehicles().getDispatchHours("late")).containsExactly(23, 23);
        }
    }

    // =========================================================================
    // ROUTING CONFIG
    // =========================================================================

    @Nested
    @DisplayName("RoutingConfig")
    class RoutingConfigTests {

        @Test
        @DisplayName("demandBorder defaults to 600")
        void demandBorderDefault() {
            assertThat(config.routing().getDemandBorder()).isEqualTo(600);
        }

        @Test
        @DisplayName("dhlBorder defaults to 450")
        void dhlBorderDefault() {
            assertThat(config.routing().getDhlBorder()).isEqualTo(450);
        }

        @Test
        @DisplayName("carrierMergeThreshold defaults to 75")
        void mergeThresholdDefault() {
            assertThat(config.routing().getCarrierMergeThreshold()).isEqualTo(75);
        }

        @Test
        @DisplayName("setCarrierMergeThreshold clamps to minimum 1")
        void mergeThresholdClamped() {
            config.routing().setCarrierMergeThreshold(0);
            assertThat(config.routing().getCarrierMergeThreshold()).isEqualTo(1);

            config.routing().setCarrierMergeThreshold(-5);
            assertThat(config.routing().getCarrierMergeThreshold()).isEqualTo(1);
        }

        @Test
        @DisplayName("jspritIterations defaults to 1")
        void jspritDefault() {
            assertThat(config.routing().getJspritIterations()).isEqualTo(1);
        }

        @Test
        @DisplayName("setJspritIterations clamps to minimum 1")
        void jspritClamped() {
            config.routing().setJspritIterations(0);
            assertThat(config.routing().getJspritIterations()).isEqualTo(1);
        }

        @Test
        @DisplayName("maxRouteDurationSeconds defaults to 27000 (7.5h)")
        void maxRouteDuration() {
            assertThat(config.routing().getMaxRouteDurationSeconds()).isEqualTo(27000);
        }

        @Test
        @DisplayName("maxDriverTimeMinutes defaults to 600 (10h)")
        void maxDriverTime() {
            assertThat(config.routing().getMaxDriverTimeMinutes()).isEqualTo(600.0);
        }

        @Test
        @DisplayName("delivery time window converts hours to seconds")
        void deliveryTimeWindow() {
            // Default: 8-20 hours → 28800-72000 seconds
            var window = config.routing().getDeliveryTimeWindow();
            assertThat(window.getStart()).isEqualTo(8 * 3600.0);
            assertThat(window.getEnd()).isEqualTo(20 * 3600.0);
        }
    }

    // =========================================================================
    // PROVIDER CONFIG
    // =========================================================================

    @Nested
    @DisplayName("ProviderConfig")
    class ProviderConfigTests {

        @Test
        @DisplayName("BASECASE has 7 providers with expected rates")
        void basecaseRates() {
            Map<String, Integer> rates = config.providers().getAllDeliveryRates();
            assertThat(rates)
                .containsEntry("dhl", 94)
                .containsEntry("gls", 91)
                .containsEntry("hermes", 91)
                .containsEntry("dpd", 89)
                .containsEntry("ups", 89)
                .containsEntry("amazon", 93)
                .containsEntry("fedex", 89)
                .hasSize(7);
        }

        @Test
        @DisplayName("unknown provider returns default rate 90")
        void unknownProviderRate() {
            assertThat(config.providers().getDeliveryRate("unknown_cep")).isEqualTo(90);
        }

        @Test
        @DisplayName("setDeliveryRate overrides for a provider")
        void overrideRate() {
            config.providers().setDeliveryRate("dhl", 99);
            assertThat(config.providers().getDeliveryRate("dhl")).isEqualTo(99);
        }

        @Test
        @DisplayName("shapefile providers contain *_tag entries")
        void shapefileProviders() {
            assertThat(config.providers().getShapefileProviders())
                .contains("dhl_tag", "hermes_tag", "amazon_tag")
                .hasSize(7);
        }

        @Test
        @DisplayName("location providers list")
        void locationProviders() {
            assertThat(config.providers().getLocationProviders())
                .containsExactly("dhl", "dpd", "gls", "hermes", "ups");
        }
    }

    // =========================================================================
    // SUPPLY CONFIG
    // =========================================================================

    @Nested
    @DisplayName("SupplyConfig")
    class SupplyConfigTests {

        @Test
        @DisplayName("default vehicle capacity is 2000")
        void defaultCapacity() {
            assertThat(config.supply().getVehicleCapacity()).isEqualTo(2000);
        }

        @Test
        @DisplayName("getMinSplitDemand = capacity / 2")
        void minSplitDemand() {
            assertThat(config.supply().getMinSplitDemand()).isEqualTo(1000);
        }

        @Test
        @DisplayName("link directions have 4 entries (N/S/E/W)")
        void linkDirections() {
            assertThat(config.supply().getLinkDirections()).hasSize(4)
                .containsKeys("south", "north", "east", "west");
        }

        @Test
        @DisplayName("direction probabilities sum to 1.0")
        void directionProbabilitiesSum() {
            double sum = config.supply().getDirectionProbabilities().values().stream()
                .mapToDouble(Double::doubleValue).sum();
            assertThat(sum).isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("link directions map is unmodifiable")
        void linkDirectionsUnmodifiable() {
            assertThatThrownBy(() -> config.supply().getLinkDirections().put("up", "123"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // =========================================================================
    // HUB CONFIG
    // =========================================================================

    @Nested
    @DisplayName("HubConfig")
    class HubConfigTests {

        @Test
        @DisplayName("default limits: DHL=16000, Post=6000")
        void defaultLimits() {
            assertThat(config.hubs().getLimitDHL()).isEqualTo(16000);
            assertThat(config.hubs().getLimitPost()).isEqualTo(6000);
        }

        @Test
        @DisplayName("parcel locker defaults: demand=25, duration=20min")
        void parcelLockerDefaults() {
            assertThat(config.hubs().getParcelLockerDemand()).isEqualTo(25);
            assertThat(config.hubs().getParcelLockerDurationMinutes()).isEqualTo(20);
        }
    }

    // =========================================================================
    // NETWORK CONFIG
    // =========================================================================

    @Nested
    @DisplayName("NetworkConfig")
    class NetworkConfigTests {

        @Test
        @DisplayName("default network filters")
        void defaults() {
            assertThat(config.network().getMinLinkLengthMeters()).isEqualTo(5.0);
            assertThat(config.network().getMinFreeSpeedMps()).isCloseTo(2.777778, within(0.0001));
            assertThat(config.network().getFreeSpeedThresholdMps()).isEqualTo(17.0);
        }
    }

    // =========================================================================
    // CONVENIENCE METHODS
    // =========================================================================

    @Nested
    @DisplayName("Convenience Delegates")
    class ConvenienceMethods {

        @Test
        @DisplayName("getMinVehicleCapacity delegates to vehicles().getMinCapacity()")
        void minVehicleCapacity() {
            assertThat(config.getMinVehicleCapacity()).isEqualTo(config.vehicles().getMinCapacity());
        }

        @Test
        @DisplayName("getMaxVehicleCapacity delegates to vehicles().getMaxCapacity()")
        void maxVehicleCapacity() {
            assertThat(config.getMaxVehicleCapacity()).isEqualTo(config.vehicles().getMaxCapacity());
        }

        @Test
        @DisplayName("getDemandBorder delegates to routing().getDemandBorder()")
        void demandBorder() {
            assertThat(config.getDemandBorder()).isEqualTo(config.routing().getDemandBorder());
        }

        @Test
        @DisplayName("getCarrierMergeThreshold delegates to routing()")
        void mergeThreshold() {
            assertThat(config.getCarrierMergeThreshold())
                .isEqualTo(config.routing().getCarrierMergeThreshold());
        }

        @Test
        @DisplayName("legacy flat accessors match section accessors")
        void legacyAccessors() {
            assertThat(config.getNetworkXmlPath()).isEqualTo(config.paths().getNetwork());
            assertThat(config.getVehicleTypePath()).isEqualTo(config.paths().getVehicleTypes());
            assertThat(config.getHubDataPath()).isEqualTo(config.paths().getHubData());
            assertThat(config.getMaxRouteDuration()).isEqualTo(config.routing().getMaxRouteDurationSeconds());
            assertThat(config.getDHLBorder()).isEqualTo(config.routing().getDhlBorder());
            assertThat(config.getSupplyVehCap()).isEqualTo(config.supply().getVehicleCapacity());
            assertThat(config.getHubLimitDHL()).isEqualTo(config.hubs().getLimitDHL());
            assertThat(config.getMinLinkLength()).isEqualTo(config.network().getMinLinkLengthMeters());
        }
    }

    // =========================================================================
    // TOSTRING
    // =========================================================================

    @Test
    @DisplayName("toString contains scenario and vehicle sizes")
    void toStringFormat() {
        assertThat(config.toString())
            .contains("BASECASE")
            .contains("[m, l]");
    }
}
