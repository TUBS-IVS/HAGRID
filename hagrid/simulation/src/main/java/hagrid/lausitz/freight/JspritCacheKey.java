package hagrid.lausitz.freight;

import com.fasterxml.jackson.databind.ObjectMapper;
import hagrid.core.routing.HAGRIDRouterUtils;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Cache key of one LMD preprocessing computation (spec 2026-10-01 section 5): a canonical
 * {@code name=value} manifest over every {@link LmdPreprocessInputs} component (read by
 * reflection), the jsprit system properties, the code fingerprint and the JVM - and its SHA-256.
 *
 * <p><b>Rule for maintainers:</b> a NEW system property read anywhere on the jsprit path must be
 * added to {@link #SYSTEM_PROPERTIES}, or a cache hit would ignore it. Record components need no
 * such care, they enter the key automatically.
 *
 * <p>Deliberately NOT in the key: run id, tag, MATSim seed, DRT fleet size and the output path.
 * None of them reaches the preprocessing - 23 Baseline runs 2026-08-15..09-30 over fleets 100-150
 * and seeds 1337-1341 produced byte-identical carriers.
 */
final class JspritCacheKey {

    /** Properties that change the jsprit result. {@code hagrid.jsprit.onlyCarrier} is NOT listed:
     *  it bypasses the cache altogether ({@link JspritPlanCache}). */
    static final List<String> SYSTEM_PROPERTIES = List.of(HAGRIDRouterUtils.JSPRIT_SEED_PROPERTY);
    static final String NONE = "<none>";
    static final String UNSET = "<unset>";
    /** Compact (never indented) - the encoding enters the key. */
    private static final ObjectMapper LIST_JSON = new ObjectMapper();

    private final LmdPreprocessInputs.Variant variant;
    private final Map<String, String> components;
    private final String fullHash;

    private JspritCacheKey(LmdPreprocessInputs.Variant variant, Map<String, String> components) {
        this.variant = variant;
        this.components = Collections.unmodifiableMap(components);
        this.fullHash = Sha256.ofText(canonicalText());
    }

    static JspritCacheKey of(LmdPreprocessInputs in, String codeFingerprint,
                             UnaryOperator<String> systemProperty, String javaRuntime) throws IOException {
        Map<String, String> c = new LinkedHashMap<>();
        for (RecordComponent rc : LmdPreprocessInputs.class.getRecordComponents()) {
            Object value;
            try {
                value = rc.getAccessor().invoke(in);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read LmdPreprocessInputs." + rc.getName(), e);
            }
            c.put(snakeCase(rc.getName()), encode(rc.getName(), value));
        }
        for (String property : SYSTEM_PROPERTIES) {
            String v = systemProperty.apply(property);
            c.put("prop." + property, v == null || v.isBlank() ? UNSET : v.trim());
        }
        c.put("code", Objects.requireNonNull(codeFingerprint, "codeFingerprint"));
        c.put("java", Objects.requireNonNull(javaRuntime, "javaRuntime"));
        return new JspritCacheKey(in.variant(), c);
    }

    LmdPreprocessInputs.Variant variant() {
        return variant;
    }

    Map<String, String> components() {
        return components;
    }

    String fullHash() {
        return fullHash;
    }

    /** Entry directory name: variant plus the first 16 hex characters. A hit additionally
     *  requires the FULL hash in the manifest, so a prefix collision is a miss, never a wrong hit. */
    String dirName() {
        return variant.name().toLowerCase(Locale.ROOT) + "-" + fullHash.substring(0, 16);
    }

    String canonicalText() {
        StringBuilder sb = new StringBuilder();
        components.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    /** Names of the components that differ from {@code previous} (in key order, then removed ones). */
    List<String> changedComponents(Map<String, String> previous) {
        List<String> changed = new ArrayList<>();
        components.forEach((k, v) -> {
            if (!v.equals(previous.get(k))) {
                changed.add(k);
            }
        });
        previous.keySet().stream().filter(k -> !components.containsKey(k)).forEach(changed::add);
        return changed;
    }

    static String snakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String encode(String name, Object value) throws IOException {
        if (value == null) {
            return NONE;
        }
        if (value instanceof Path p) {
            return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp")
                    ? shapefileFamily(p)
                    : "sha256:" + Sha256.ofFile(p);
        }
        if (value instanceof LmdPreprocessInputs.Variant v) {
            return v.name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof Integer i) {
            return Integer.toString(i);
        }
        if (value instanceof List<?> list) {
            // M6: a JSON array of strings, so ["a,b"] and ["a","b"] differ; the empty list stays []
            return LIST_JSON.writeValueAsString(list.stream().map(String::valueOf).toList());
        }
        throw new IllegalStateException("LmdPreprocessInputs component '" + name + "' has type "
                + value.getClass().getName() + ", which JspritCacheKey cannot encode - extend encode()");
    }

    /**
     * Hashes every file next to the {@code .shp} whose base name matches, ignoring case (shapefiles
     * from Windows shares come as {@code .SHP}/{@code .DBF}). A neighbour such as
     * {@code drt-service-area-with-ruhland.shp} has a different base name and is not included.
     */
    private static String shapefileFamily(Path shp) throws IOException {
        if (!Files.isRegularFile(shp)) {
            throw new NoSuchFileException(shp.toString());
        }
        String file = shp.getFileName().toString();
        String base = file.substring(0, file.length() - ".shp".length());
        Map<String, String> byExtension = new TreeMap<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(shp.toAbsolutePath().getParent())) {
            for (Path f : dir) {
                String n = f.getFileName().toString();
                int dot = n.lastIndexOf('.');
                if (dot <= 0 || !Files.isRegularFile(f) || !n.substring(0, dot).equalsIgnoreCase(base)) {
                    continue;
                }
                String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (byExtension.put(ext, Sha256.ofFile(f)) != null) {
                    throw new IllegalStateException("two ." + ext + " files for shapefile " + shp
                            + " that differ only in case - remove one");
                }
            }
        }
        StringJoiner j = new StringJoiner(",");
        byExtension.forEach((ext, hash) -> j.add(ext + ":" + hash));
        return j.toString();
    }
}
