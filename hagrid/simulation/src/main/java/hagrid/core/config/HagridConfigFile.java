package hagrid.core.config;

import hagrid.core.HagridConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.security.*;

/** One captured UTF-8 properties file shared by pipeline and simulation. Never reread mid-run. */
public final class HagridConfigFile {
    private final Path path;
    private final Map<String,String> values;
    private final String sha256;
    private HagridConfigFile(Path path, Map<String,String> values, String sha256) {
        this.path=path; this.values=Map.copyOf(values); this.sha256=sha256;
    }
    public static HagridConfigFile load(Path input) {
        Path path=input.toAbsolutePath().normalize();
        try {
            byte[] bytes=Files.readAllBytes(path);
            Map<String,String> values=new LinkedHashMap<>();
            Properties properties=new Properties() {
                @Override public synchronized Object put(Object key,Object value) {
                    String name=HagridConfigurationCatalog.canonicalName(key.toString().trim());
                    if(!HagridConfigurationCatalog.known(name)) throw new IllegalArgumentException("Unknown HAGRID configuration key: "+name);
                    if(values.putIfAbsent(name,value.toString().trim())!=null)
                        throw new IllegalArgumentException("Duplicate/conflicting HAGRID setting: "+name);
                    if(value.toString().isBlank()) throw new IllegalArgumentException(name+" must not be blank");
                    return super.put(key,value);
                }
            };
            var decoder=StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            properties.load(new StringReader(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()));
            HagridConfigFile result=new HagridConfigFile(path,values,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            result.validate();
            return result;
        } catch(IOException e) {throw new UncheckedIOException("Cannot read HAGRID config: "+path,e);}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void validate() {
        canonicalValues().forEach(HagridConfigResolver::validateCanonicalValue);
        DrtSettings.from(values);
        VehicleDispatchSettings.from(values);
        HagridDemandSettings.apply(new HagridConfig(),values,path.getParent());
        hagrid.core.simulation.SimulationRunnerUtils.validateLaunchValues(launchValues());
        HagridConfigResolver.validateRemovedOptimizationOptions(values);
    }
    public Path path(){return path;}
    public String sha256(){return sha256;}
    public Map<String,String> values(){return values;}
    public Map<String,String> launchValues(){return HagridConfigurationCatalog.launchValues(values);}
    public Map<String,String> canonicalValues(){
        Map<String,String> result=new LinkedHashMap<>();
        values.forEach((key,value)->{if(HagridConfigResolver.isCanonicalKey(key))result.put(key,
                HagridPathSettings.contains(key)||HagridDemandSettings.pathValued(key)||key.equals("drt.fareZoneFile")||(key.equals("routing.haltCatalogFile")||(key.equals("routing.sectionHagridInputs")&&!value.equals("auto"))||key.equals("routing.sectionEvidenceConfig")||key.equals("routing.sectionEvidenceTool")) ? HagridPathSettings.resolve(value,path.getParent()).toString() : value);});
        return Map.copyOf(result);
    }
    public void applyTo(HagridConfig target) {
        HagridDemandSettings.apply(target,values,path.getParent());
        Map<String,String> extra=new LinkedHashMap<>(canonicalValues());
        extra.keySet().removeIf(HagridDemandSettings::contains);
        target.setAdditionalConfigurationValues(extra);
    }
}
