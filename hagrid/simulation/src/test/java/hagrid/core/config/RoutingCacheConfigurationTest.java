package hagrid.core.config;

import hagrid.core.HagridConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RoutingCacheConfigurationTest {
    private static ResolvedHagridConfig resolve(HagridConfig defaults,Map<String,String> cli) {
        return HagridConfigResolver.resolve(defaults,HagridConfigResolver.hannoverTarget(),HagridConfigResolver.Overrides.empty(),cli);
    }
    @Test void conservativeExactCacheDefaults() {
        var config=resolve(new HagridConfig(),Map.of());
        assertTrue(config.routing().transportCacheEnabled());
        assertEquals(100000,config.routing().maximumCacheEntries());
        assertEquals(1,config.routing().maximumConcurrentRouters());
        assertEquals(0,config.routing().odTimeQuantizationSeconds());
        assertFalse(config.routing().zoneBasedCachingEnabled());
    }
    @Test void groupedConfigIsFrozenAndExplicitCliHasPriority() {
        var defaults=new HagridConfig(); defaults.routing().setMaximumCacheEntries(25);
        defaults.routing().setMaximumConcurrentRouters(2); defaults.routing().setOdTimeQuantizationSeconds(60);
        var frozen=resolve(defaults,Map.of("routing.maximumCacheEntries","10","routing.transportCacheEnabled","false"));
        defaults.routing().setMaximumCacheEntries(50);
        assertEquals(10,frozen.routing().maximumCacheEntries()); assertFalse(frozen.routing().transportCacheEnabled());
        assertEquals(2,frozen.routing().maximumConcurrentRouters()); assertEquals(60,frozen.routing().odTimeQuantizationSeconds());
        assertEquals(ResolvedHagridConfig.Source.CLI,frozen.provenance().get("routing.maximumCacheEntries"));
    }
    @Test void invalidBoundsAndQuantizationAreRejected() {
        for(var item:Map.of("routing.maximumCacheEntries","0","routing.maximumConcurrentRouters","-1",
                "routing.odTimeQuantizationSeconds","NaN","routing.transportCacheEnabled","yes").entrySet())
            assertThrows(IllegalArgumentException.class,()->resolve(new HagridConfig(),Map.of(item.getKey(),item.getValue())));
        assertThrows(IllegalArgumentException.class,()->resolve(new HagridConfig(),Map.of("routing.odTimeQuantizationSeconds","-1")));
    }
}