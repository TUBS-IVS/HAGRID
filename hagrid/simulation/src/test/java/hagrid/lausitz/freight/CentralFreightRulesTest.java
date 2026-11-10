package hagrid.lausitz.freight;

import hagrid.core.HagridConfig;
import hagrid.core.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CentralFreightRulesTest {
    @TempDir Path temp;
    private ResolvedHagridConfig rules(String buffer,String end) {
        return HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),
                HagridConfigResolver.Overrides.of(Map.of("tourRules.vehicleAvailabilityBufferSeconds",buffer,
                "tourRules.vehicleDayEndSeconds",end,"tourRules.durationPerParcelSeconds","180")),Map.of());
    }
    @Test void allPreprocessingCopiesPreserveEffectiveRules() {
        var source=LmdPreprocessInputs.baseline("demand","depot","network","types",1,null)
                .withTourRules(rules("900","72000"));
        assertThat(source.tourRules().vehicleAvailabilityBufferSeconds()).isEqualTo(900);
        assertThat(source.tourRules().vehicleDayEndSeconds()).isEqualTo(72000);
        assertThat(source.tourRules().durationPerParcelMinutes()).isEqualTo(3);
        var changed=source.withResources(new ResolvedHagridConfig.Resources(1,OptionalInt.of(1))).withRouteDurationSeconds(14400);
        assertThat(changed.tourRules().vehicleAvailabilityBufferSeconds()).isEqualTo(900);
        assertThat(changed.tourRules().maxRouteDurationSeconds()).isEqualTo(14400);
    }
    @Test void bufferAndDayEndArePartOfTheActualCacheIdentity() throws Exception {
        Path input=Files.writeString(temp.resolve("input.txt"),"fixture");
        var base=LmdPreprocessInputs.baseline(input.toString(),input.toString(),input.toString(),input.toString(),1,null);
        var a=JspritCacheKey.of(base.withTourRules(rules("900","72000")),"code",k->null,"java21");
        var b=JspritCacheKey.of(base.withTourRules(rules("1200","72000")),"code",k->null,"java21");
        var c=JspritCacheKey.of(base.withTourRules(rules("900","75600")),"code",k->null,"java21");
        assertThat(a.fullHash()).isNotEqualTo(b.fullHash()).isNotEqualTo(c.fullHash());
    }
}
