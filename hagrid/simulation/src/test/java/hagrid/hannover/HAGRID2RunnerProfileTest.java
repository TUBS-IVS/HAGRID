package hagrid.hannover;

import hagrid.hannover.pipeline.ScenarioConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class HAGRID2RunnerProfileTest {
 @Test void noProfilePreservesExistingScenarioDefaults() throws Exception {
  var builder=ScenarioConfig.builder();
  assertThat(apply(builder,null)).isSameAs(builder);
  assertThat(builder.build().getPipelineSettings().getConfigurationFile()).isEmpty();
 }
 @Test void explicitProfileResolvesWithoutExecutingScenarios() throws Exception {
  var file=Files.createTempFile("hagrid profile ",".properties");
  try {
   Files.writeString(file,"routing.sectionHagridInputs=auto\nrouting.sectionMaxSpanMeters=200\n");
   var config=apply(ScenarioConfig.builder(),file.toString()).build();
   assertThat(config.getPipelineSettings().getConfigurationFile()).contains(file.toAbsolutePath().normalize());
  } finally {Files.deleteIfExists(file);}
 }
 @Test void missingDirectoryAndBlankProfilesFailBeforeExecution() throws Exception {
  var directory=Files.createTempDirectory("hagrid-profile-");
  try {
   assertThatThrownBy(()->apply(ScenarioConfig.builder(),directory.resolve("missing.properties").toString())).hasStackTraceContaining("configuration file");
   assertThatThrownBy(()->apply(ScenarioConfig.builder(),directory.toString())).hasStackTraceContaining("configuration file");
   assertThatThrownBy(()->apply(ScenarioConfig.builder()," ")).hasStackTraceContaining("blank");
  } finally {Files.deleteIfExists(directory);}
 }
 private static ScenarioConfig.Builder apply(ScenarioConfig.Builder builder,String path) {
  return HAGRID2MATSimPipelineRunner.Profile.apply(builder,path);
 }
}
