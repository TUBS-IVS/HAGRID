package hagrid.core.config;
import java.nio.file.Path;
import java.util.Set;
/** Optional path overrides; demand inputs and generated simulation inputs have distinct names. */
public final class HagridPathSettings {
    private HagridPathSettings() {}
    private static final Set<String> KEYS=Set.of(
        "resources.carrierAdmissionPolicyFile",
        "paths.freightZones",
        "paths.simulationVehicleTypes",
        "paths.simulationNetwork",
        "paths.bikeNetwork",
        "paths.networkChangeEvents",
        "paths.carrierDirectory",
        "paths.deliveryCarriers",
        "paths.supplyCarriers",
        "paths.mergedCarriers",
        "paths.matsimConfig",
        "paths.outputDirectory",
        "paths.jspritCacheDirectory",
        "paths.drtServiceArea",
        "paths.lausitzBaseConfig",
        "paths.lausitzNetworkRaw",
        "paths.passengerPlansRaw",
        "paths.drtNetworkClipped",
        "paths.passengerPlansClipped",
        "paths.drtFleet",
        "paths.transitScheduleRaw",
        "paths.transitVehiclesRaw",
        "paths.lausitzVehicleTypes",
        "paths.railScheduleFiltered",
        "paths.railVehiclesFiltered",
        "paths.drtInputsFingerprint",
        "paths.lmdDepotCsv",
        "paths.lmdDemand",
        "paths.lmdVehicleTypes",
        "paths.lmdCarriersRouted");
    public static boolean contains(String key) { return KEYS.contains(key); }
    public static Path resolve(String value, Path base) {
        if(value==null || value.isBlank()) throw new IllegalArgumentException("Path must not be blank");
        Path path=Path.of(value);
        return (path.isAbsolute()?path:base.resolve(path)).toAbsolutePath().normalize();
    }
}
