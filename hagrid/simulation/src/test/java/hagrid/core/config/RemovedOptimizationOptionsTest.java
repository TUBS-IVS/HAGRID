package hagrid.core.config;

import hagrid.core.HagridConfig;
import hagrid.core.simulation.SimulationRunnerUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

class RemovedOptimizationOptionsTest {
    @TempDir Path directory;
    @Test void rejectsEnabledRemovedOptionsBeforeNetworkLoad() {
        assertAll(List.of("routing.zoneBasedCachingEnabled", "optimization.warmStartEnabled",
                "optimization.adaptiveSearchEnabled").stream().map(key -> (org.junit.jupiter.api.function.Executable) () -> {
            var values=new HashMap<String,String>();
            values.put("paths.network",directory.resolve("missing-network.xml").toString());
            values.put(key,"true");
            values.put("optimization.adaptiveMinimumIterations","10");
            values.put("optimization.adaptiveMaximumIterations","30");
            values.put("optimization.adaptivePlateauIterations","5");
            assertThatThrownBy(() -> HagridConfigResolver.resolve(new HagridConfig(), HagridConfigResolver.hannoverTarget(),
                    HagridConfigResolver.Overrides.of(values),Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key).hasMessageContaining("removed");
        }));
    }
    @Test void acceptsNeutralLegacyOptions() {
        var plain=resolve(Map.of());
        var legacy=resolve(Map.of("routing.zoneBasedCachingEnabled","false", "routing.zoneBasedCachingThresholdMeters","1500",
                "optimization.warmStartEnabled","false", "optimization.adaptiveSearchEnabled","false"));
        assertThat(legacy.optimization()).isEqualTo(plain.optimization());
        assertThat(legacy.optimization().initialIterations()).isEqualTo(7);
        assertThat(legacy.optimization().replanningIterations()).hasValue(23);
        assertThat(legacy.optimization().carriersPerGroup()).hasValue(3);
        assertThat(legacy.routing().zoneBasedCachingEnabled()).isFalse();
    }
    @Test void legacyCliAndPropertiesCannotEnableRemovedOptions() throws Exception {
        Path file=Files.writeString(directory.resolve("hagrid.properties"),"optimization.warmStartEnabled=true\n");
        assertThatThrownBy(()->HagridConfigFile.load(file)).hasMessageContaining("optimization.warmStartEnabled").hasMessageContaining("removed");
        for(String concept:List.of("basecase","drt_baseline"))
            assertThatThrownBy(()->SimulationRunnerUtils.parseScenario("concept="+concept+",date=2025-05-13,zoneCaching=true"))
                    .hasMessageContaining("routing.zoneBasedCachingEnabled").hasMessageContaining("removed");
    }
    private ResolvedHagridConfig resolve(Map<String,String> legacy) {
        var values=new HashMap<>(legacy);values.put("optimization.initialIterations","7");
        values.put("optimization.replanningIterations","23");values.put("optimization.carriersPerGroup","3");
        return HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.hannoverTarget(),
                HagridConfigResolver.Overrides.of(values),Map.of());
    }
}
