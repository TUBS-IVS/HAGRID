package hagrid.core.simulation;

import hagrid.core.routing.ModalNetworkImporter;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;
import java.util.*;
import java.util.function.Supplier;

/** Establishes one authoritative simulation graph before modal routing providers are created. */
final class ScenarioModalNetworks {
    private ScenarioModalNetworks() {}

    static void attach(Scenario scenario, Carriers carriers, Supplier<Network> cargoBikeLoader) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(carriers, "carriers");
        Objects.requireNonNull(cargoBikeLoader, "cargoBikeLoader");
        if (scenario.getScenarioElement("carNetwork") != null || scenario.getScenarioElement("bikeNetwork") != null) {
            throw new IllegalArgumentException("Scenario already contains modal network aliases");
        }
        boolean needsBike = hasBikeCarriers(carriers);
        Network common = scenario.getNetwork();
        if (needsBike && !supportsBikeLocations(common, carriers)) {
            if (!(scenario instanceof MutableScenario mutable)) {
                throw new IllegalArgumentException("Modal network import requires a mutable scenario during setup");
            }
            Network bike = Objects.requireNonNull(cargoBikeLoader.get(), "cargo-bike network");
            // Preserve exact mode names; bike/cargobike conversion requires an explicit input migration.
            common = ModalNetworkImporter.merge(List.of(common, bike), Map.of());
            // Do not publish a partial graph or aliases if reading/consistency checks fail.
            mutable.setNetwork(common);
        }
        scenario.addScenarioElement("carNetwork", common);
        scenario.addScenarioElement("bikeNetwork", common);
    }

    static boolean hasBikeCarriers(Carriers carriers) {
        return carriers.getCarriers().values().stream().anyMatch(carrier -> {
            var capabilities = requireCapabilities(carrier);
            return capabilities.getVehicleTypes().stream().anyMatch(ScenarioModalNetworks::isBike)
                    || capabilities.getCarrierVehicles().values().stream().anyMatch(vehicle -> isBike(vehicle.getType()));
        });
    }

    private static boolean supportsBikeLocations(Network network, Carriers carriers) {
        var availableModes = new HashSet<String>();
        network.getLinks().values().forEach(link -> availableModes.addAll(link.getAllowedModes()));
        for (var carrier : carriers.getCarriers().values()) {
            var capabilities = requireCapabilities(carrier);
            var modes = new HashSet<String>();
            capabilities.getVehicleTypes().stream().filter(ScenarioModalNetworks::isBike)
                    .forEach(type -> modes.add(type.getNetworkMode()));
            for (var vehicle : capabilities.getCarrierVehicles().values()) {
                if (isBike(vehicle.getType())) {
                    String mode = vehicle.getType().getNetworkMode();
                    modes.add(mode);
                    if (!allows(network, vehicle.getLinkId(), mode)) return false;
                }
            }
            for (String mode : modes) {
                if (!availableModes.contains(mode)) return false;
                for (var service : carrier.getServices().values()) {
                    if (!allows(network, service.getServiceLinkId(), mode)) return false;
                }
                for (var shipment : carrier.getShipments().values()) {
                    if (!allows(network, shipment.getPickupLinkId(), mode)
                            || !allows(network, shipment.getDeliveryLinkId(), mode)) return false;
                }
            }
        }
        return true;
    }

    private static boolean allows(Network network, org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Link> id,
                                  String mode) {
        var link = network.getLinks().get(id);
        return link != null && link.getAllowedModes().contains(mode);
    }

    private static CarrierCapabilities requireCapabilities(Carrier carrier) {
        if (carrier.getCarrierCapabilities() == null) {
            throw new IllegalArgumentException("Carrier " + carrier.getId() + " has no vehicle capabilities");
        }
        return carrier.getCarrierCapabilities();
    }

    private static boolean isBike(VehicleType type) {
        return type != null && ("cargobike".equals(type.getNetworkMode()) || "bike".equals(type.getNetworkMode()));
    }
}
