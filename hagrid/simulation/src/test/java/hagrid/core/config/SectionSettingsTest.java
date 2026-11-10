package hagrid.core.config;
import hagrid.core.HagridConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class SectionSettingsTest {
 @Test void fastRegretIsStrictAndRequiresSectionMode() {
  assertThat(SectionSettings.from(Map.of()).fastRegret()).isFalse();
  assertThat(SectionSettings.from(Map.of("routing.sectionFastRegret","false")).fastRegret()).isFalse();
  var values=Map.of("routing.sectionMaxSpanMeters","200","routing.haltCatalogFile","halts.json","routing.sectionFastRegret","true");
  assertThat(SectionSettings.from(values).fastRegret()).isTrue();
  assertThatThrownBy(()->SectionSettings.from(Map.of("routing.sectionFastRegret","true"))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->SectionSettings.from(Map.of("routing.sectionFastRegret","yes"))).isInstanceOf(IllegalArgumentException.class);
  var resolved=HagridConfigResolver.resolve(new HagridConfig(),HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(values),Map.of());
  assertThat(resolved.sections().fastRegret()).isTrue();
  assertThat(resolved.effectiveValues()).containsEntry("routing.sectionFastRegret","true");
  assertThat(resolved.provenance()).containsEntry("routing.sectionFastRegret",ResolvedHagridConfig.Source.RUN);
 }
 @Test void requiresBothSettingsAndExposesTypedValues() {
  var config=new HagridConfig();
  var resolved=HagridConfigResolver.resolve(config,HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of("routing.sectionMaxSpanMeters","200","routing.haltCatalogFile","halts.json")),Map.of());
  assertThat(resolved.sections().maxSpanMeters()).hasValue(200.0);
  assertThat(resolved.sections().haltCatalogFile()).hasValue("halts.json");
  assertThatThrownBy(()->HagridConfigResolver.resolve(config,HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of("routing.sectionMaxSpanMeters","200")),Map.of())).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->HagridConfigResolver.resolve(config,HagridConfigResolver.Overrides.empty(),HagridConfigResolver.Overrides.of(Map.of("routing.sectionMaxSpanMeters","0","routing.haltCatalogFile","x")),Map.of())).isInstanceOf(IllegalArgumentException.class);
 }
}
