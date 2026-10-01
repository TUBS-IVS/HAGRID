package hagrid.lausitz.freight;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything one LMD preprocessing computation depends on besides code and JVM.
 *
 * <p>{@link LausitzFreightPreprocessor} reads its inputs ONLY from this record, and
 * {@link JspritCacheKey} derives the cache key from ALL of its components by reflection. A new
 * preprocessing input therefore has to become a component and enters the key automatically. The
 * output path is deliberately not a component: where a result is written never changes what it is.
 *
 * <p>Component types are limited to what {@link JspritCacheKey} can encode: {@link Path} (hashed by
 * content, a {@code .shp} as its whole sidecar family), {@link Variant}, {@code int}/{@link Integer}
 * and {@code List<String>}. {@code null} means "not applicable to this variant".
 */
public record LmdPreprocessInputs(
        Variant variant,
        Path demandShp,
        Path depotCsv,
        Path network,
        Path vehicleTypes,
        Path serviceAreaShp,
        int jspritIterations,
        Integer maxTourDurationSeconds,
        List<String> openDepots,
        Integer maxJobsPerDistrict) {

    /** Which preprocessing produced the carriers: LMD baseline (vans) or 1d modular (capsules). */
    public enum Variant { BASELINE, MODULAR }

    public LmdPreprocessInputs {
        openDepots = openDepots == null ? null : List.copyOf(openDepots);
    }

    public static LmdPreprocessInputs baseline(String demandShp, String depotCsv, String networkFile,
                                               String vehicleTypesFile, int jspritIterations,
                                               String serviceAreaShp) {
        return new LmdPreprocessInputs(Variant.BASELINE, Path.of(demandShp), Path.of(depotCsv),
                Path.of(networkFile), Path.of(vehicleTypesFile), optionalPath(serviceAreaShp),
                jspritIterations, null, null, null);
    }

    /**
     * {@code openDepots} {@code null} and empty both mean "all depots" in
     * {@code DeliveryDistrictBuilder.selectOpenDepots}, so both normalise to an empty list - the
     * same behaviour must give the same key.
     */
    public static LmdPreprocessInputs modular(String demandShp, String depotCsv, String networkFile,
                                              String vanTypesFile, int jspritIterations,
                                              String serviceAreaShp, int maxTourDurationSeconds,
                                              List<String> openDepots, int maxJobsPerDistrict) {
        return new LmdPreprocessInputs(Variant.MODULAR, Path.of(demandShp), Path.of(depotCsv),
                Path.of(networkFile), Path.of(vanTypesFile), optionalPath(serviceAreaShp),
                jspritIterations, maxTourDurationSeconds,
                openDepots == null ? List.of() : openDepots, maxJobsPerDistrict);
    }

    private static Path optionalPath(String path) {
        return path == null || path.isBlank() ? null : Path.of(path);
    }
}
