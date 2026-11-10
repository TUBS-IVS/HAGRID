package hagrid.core.routing.sections;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
/** A single prepared catalog or a manifest mapping carrier IDs to catalog paths. */
public final class SectionCatalogInputs {
 private SectionCatalogInputs() {}
 public static Map<String,PreparedHaltCatalog> read(Path path) throws IOException {
  var absolute=path.toAbsolutePath().normalize();var root=new ObjectMapper().readTree(absolute.toFile());var result=new TreeMap<String,PreparedHaltCatalog>();
  if(!root.has("catalogs"))add(result,PreparedHaltCatalog.read(absolute),null);
  else {
   var entries=root.path("catalogs");if(!entries.isObject()||entries.isEmpty())throw new IllegalArgumentException("Section manifest requires a nonempty catalogs map");
   var it=entries.fields();while(it.hasNext()){var entry=it.next();if(!entry.getValue().isTextual()||entry.getValue().asText().isBlank())throw new IllegalArgumentException("Invalid catalog path for "+entry.getKey());var child=Path.of(entry.getValue().asText());if(!child.isAbsolute())child=absolute.getParent().resolve(child);add(result,PreparedHaltCatalog.read(child.normalize()),entry.getKey());}
  }
  return Collections.unmodifiableMap(result);
 }
 private static void add(Map<String,PreparedHaltCatalog> catalogs,PreparedHaltCatalog value,String expected) {
  if(expected!=null&&!expected.equals(value.carrierId()))throw new IllegalArgumentException("Catalog carrier mismatch: "+expected);
  if(catalogs.putIfAbsent(value.carrierId(),value)!=null)throw new IllegalArgumentException("Duplicate catalog carrier "+value.carrierId());
 }
}

