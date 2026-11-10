package hagrid.hannover.demand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;

/** HAGRID-owned input preparation; no external road-source discovery or downloads. */
final class SectionEvidencePreparation {
 private SectionEvidencePreparation() {}
 static Path prepare(Network network, Path catalog, Map<String,String> config, double minimumLinkLength) throws IOException {
  rejectExternalConfig(config);
  Path path=catalog.toAbsolutePath().normalize();
  hagrid.core.routing.sections.SectionCatalogInputs.read(path);
  return path;
 }
 /** Prepare only from the actual HAGRID network, deliveries and existing demand references. */
 static Path prepareHagrid(Network network, org.matsim.freight.carriers.Carriers carriers,
                           Path inputConfig, Path output, Map<String,String> config) throws IOException {
  rejectExternalConfig(config);
  Path dir=Files.createTempDirectory("hagrid-inputs-");
  Path snapshot=dir.resolve("network.xml"),jobs=dir.resolve("carriers.xml");
  try {
   new NetworkWriter(network).write(snapshot.toString());
   new org.matsim.freight.carriers.CarrierPlanWriter(carriers).writePreciseTimes(jobs.toString());
   long timeout=Long.parseLong(config.getOrDefault("routing.sectionEvidenceTimeoutSeconds","120"));
   if(timeout<=0)throw new IllegalArgumentException("section preparation timeout must be positive");
   Files.createDirectories(output);
   var command=new java.util.ArrayList<>(List.of(python(config),findScript(config).toString(),"--config",inputConfig.toAbsolutePath().toString(),
      "--network",snapshot.toString(),"--carriers",jobs.toString(),"--output",output.toAbsolutePath().toString()));
   if(config.containsKey("routing.sectionAllowStreetCrossing")) {
    command.add("--allow-street-crossing");command.add(config.get("routing.sectionAllowStreetCrossing"));
   }
   int exit=runProcess(command,timeout);
   if(exit!=0)throw new IOException("HAGRID-only halt preparation failed; no routing started. Details: "+output.resolve("report.json"));
   Path catalog=output.resolve("catalogs.json").toAbsolutePath();
   hagrid.core.routing.sections.SectionCatalogInputs.read(catalog);
   return catalog;
  } finally {Files.deleteIfExists(jobs);Files.deleteIfExists(snapshot);Files.deleteIfExists(dir);}
 }
 static Path resolveHagridInputs(String setting,String freightDemand) {
  if(!"auto".equals(setting))return Path.of(setting).toAbsolutePath().normalize();
  if(freightDemand==null||freightDemand.isBlank())throw new IllegalArgumentException("Auto HAGRID section inputs require paths.freightDemand");
  Path parent=Path.of(freightDemand).toAbsolutePath().normalize().getParent();
  return parent.resolve("hagrid-section-inputs.json");
 }
 private static void rejectExternalConfig(Map<String,String> config) {
  if(config.containsKey("routing.sectionEvidenceConfig") || config.containsKey("routing.sectionEvidenceTool"))
   throw new IllegalArgumentException("External section evidence is no longer invoked by HAGRID. Use routing.sectionHagridInputs with existing HAGRID demand references.");
 }

 static String python(Map<String,String> config) {
  String value=config.get("routing.sectionEvidencePython");
  if(value==null||value.isBlank())value=System.getenv("HAGRID_SECTION_PYTHON");
  return value==null||value.isBlank()?"python":value;
 }
 static int runProcess(List<String> command,long timeoutSeconds) throws IOException {
  Process process=new ProcessBuilder(command).inheritIO().start();
  try {
   if(!process.waitFor(timeoutSeconds,TimeUnit.SECONDS))throw new IOException("Section evidence preparation timed out after "+timeoutSeconds+" seconds");
   return process.exitValue();
  } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Interrupted during section evidence preparation",e);}
  finally {
   if(process.isAlive()) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();
    try {process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   }
  }
 }
 static Path findScript(Map<String,String> config) {
  String root=System.getProperty("hagrid.pipeline.root");
  for(Path initial:List.of(root==null||root.isBlank()?Path.of(""):Path.of(root),Path.of(""))) {
   Path cursor=initial.toAbsolutePath().normalize();
   while(cursor!=null) {Path candidate=cursor.resolve("tools/section-road-evidence/prepare_hagrid_inputs.py");if(Files.isRegularFile(candidate))return candidate;cursor=cursor.getParent();}
  }
  throw new IllegalStateException("Cannot locate HAGRID input preparation; set hagrid.pipeline.root to the HAGRID checkout");
 }
}
