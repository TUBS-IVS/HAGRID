package hagrid.core.simulation;

import hagrid.core.routing.ModalNetworkConnectivity;
import hagrid.core.routing.CarrierModeResolver;
import hagrid.core.routing.RoutingNetworkValidator;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.carriers.*;
import org.matsim.vehicles.VehicleType;

import java.util.*;

/** Read-only input checks before registration/VRP construction; does not remap locations. */
public final class CarrierInputValidator {
    private CarrierInputValidator() {}

    /** Returns only explicit or unambiguously inferred modes; empty carriers need no invented mode. */
    public static Map<Id<Carrier>, String> validate(Carriers carriers, CarrierVehicleTypes types, Network network) {
        Objects.requireNonNull(carriers, "carriers");
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(network, "network");
        RoutingNetworkValidator.validate(network);
        var registry = types.getVehicleTypes();
        for (var entry : registry.entrySet()) {
            var type = entry.getValue();
            if (entry.getKey() == null || type == null || !entry.getKey().equals(type.getId())) {
                throw new IllegalArgumentException("Inconsistent vehicle type registry key " + entry.getKey());
            }
            requireTypeMode(type);
            double speed = type.getMaximumVelocity();
            if (Double.isNaN(speed) || speed <= 0) {
                throw new IllegalArgumentException("Invalid maximum velocity for vehicle type " + type.getId());
            }
        }
        var resolved = new LinkedHashMap<Id<Carrier>, String>();
        var connectivity = new ModalNetworkConnectivity(network);
        for (var entry : carriers.getCarriers().entrySet()) {
            var carrier = entry.getValue();
            if (entry.getKey() == null || carrier == null || !entry.getKey().equals(carrier.getId())) {
                throw new IllegalArgumentException("Carrier input map key must match its carrier ID");
            }
            if(!carrier.getServices().isEmpty()&&!carrier.getShipments().isEmpty())
                throw invalid(carrier,"mixed Services and Shipments are unsupported by the MATSim VRP adapter");
            var capabilities = carrier.getCarrierCapabilities();
            if (capabilities == null) throw invalid(carrier, "missing vehicle capabilities");
            boolean hasJobs = !carrier.getServices().isEmpty() || !carrier.getShipments().isEmpty();
            if (hasJobs && capabilities.getCarrierVehicles().isEmpty()) {
                throw invalid(carrier, "has jobs but no vehicles or depot representatives");
            }
            for (var type : capabilities.getVehicleTypes()) {
                requireRegisteredType(carrier, type, registry);
            }
            for (var vehicleEntry : capabilities.getCarrierVehicles().entrySet()) {
                var vehicle = vehicleEntry.getValue();
                if (vehicleEntry.getKey() == null || vehicle == null || !vehicleEntry.getKey().equals(vehicle.getId())) {
                    throw invalid(carrier, "vehicle map key must match vehicle ID");
                }
                var type = requireRegisteredType(carrier, vehicle.getType(), registry);
                requireLink(carrier, network, vehicle.getLinkId(), type.getNetworkMode(), "vehicle " + vehicle.getId() + " depot");
            }
            String mode = CarrierModeResolver.resolve(carrier).orElse(null);
            if (mode == null) continue; // Jobless/fleetless input remains unchanged.
            var depots = connectivity.depots(capabilities.getCarrierVehicles().values().stream()
                    .map(CarrierVehicle::getLinkId).toList(), mode);
            for (var serviceEntry : carrier.getServices().entrySet()) {
                var service = serviceEntry.getValue();
                if (serviceEntry.getKey() == null || service == null || !serviceEntry.getKey().equals(service.getId())) {
                    throw invalid(carrier, "service map key must match service ID");
                }
                requireLink(carrier, network, service.getServiceLinkId(), mode, "service " + service.getId());
                if (!depots.canServe(service.getServiceLinkId())) {
                    throw invalid(carrier, "service " + service.getId() + " link " + service.getServiceLinkId()
                            + " is not reachable with a return to any depot in mode " + mode);
                }
            }
            for (var shipmentEntry : carrier.getShipments().entrySet()) {
                var shipment = shipmentEntry.getValue();
                if (shipmentEntry.getKey() == null || shipment == null || !shipmentEntry.getKey().equals(shipment.getId())) {
                    throw invalid(carrier, "shipment map key must match shipment ID");
                }
                requireLink(carrier, network, shipment.getPickupLinkId(), mode, "shipment " + shipment.getId() + " pickup");
                requireLink(carrier, network, shipment.getDeliveryLinkId(), mode, "shipment " + shipment.getId() + " delivery");
                if (!depots.canServeShipment(shipment.getPickupLinkId(), shipment.getDeliveryLinkId())) {
                    throw invalid(carrier, "shipment " + shipment.getId() + " pickup " + shipment.getPickupLinkId()
                            + " and delivery " + shipment.getDeliveryLinkId()
                            + " are not reachable with a return to the same depot in mode " + mode);
                }
            }
            resolved.put(carrier.getId(), mode);
        }
        return Map.copyOf(resolved);
    }

    private static VehicleType requireRegisteredType(Carrier carrier, VehicleType type, Map<Id<VehicleType>, VehicleType> registry) {
        if (type == null) throw invalid(carrier, "vehicle type is missing");
        var registered = registry.get(type.getId());
        if (registered == null) throw invalid(carrier, "Unknown vehicle type " + type.getId());
        requireTypeMode(type);
        if (!type.getNetworkMode().equals(registered.getNetworkMode())
                || Double.compare(type.getMaximumVelocity(), registered.getMaximumVelocity()) != 0
                || !Objects.equals(type.getCapacity().getOther(), registered.getCapacity().getOther())
                || !Objects.equals(type.getCostInformation().getFixedCosts(), registered.getCostInformation().getFixedCosts())
                || !Objects.equals(type.getCostInformation().getCostsPerMeter(), registered.getCostInformation().getCostsPerMeter())
                || !Objects.equals(type.getCostInformation().getCostsPerSecond(), registered.getCostInformation().getCostsPerSecond())) {
            throw invalid(carrier, "Conflicting vehicle type definition " + type.getId());
        }
        return registered;
    }

    private static void requireTypeMode(VehicleType type) {
        if (!type.hasNetworkMode() || type.getNetworkMode() == null || type.getNetworkMode().isBlank()) {
            throw new IllegalArgumentException("Vehicle type " + type.getId() + " needs an explicit network mode");
        }
    }

    private static void requireLink(Carrier carrier, Network network, Id<Link> id, String mode, String role) {
        var link = id == null ? null : network.getLinks().get(id);
        if (link == null) throw invalid(carrier, role + " refers to missing network link " + id);
        if (!link.getAllowedModes().contains(mode)) {
            throw invalid(carrier, role + " link " + id + " does not allow mode " + mode);
        }
    }

    private static IllegalArgumentException invalid(Carrier carrier, String reason) {
        return new IllegalArgumentException("Invalid carrier " + carrier.getId() + ": " + reason);
    }
}

