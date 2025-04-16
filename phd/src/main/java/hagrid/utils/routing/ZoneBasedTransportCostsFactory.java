package hagrid.utils.routing;

import org.apache.logging.log4j.core.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.VehicleType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ZoneBasedTransportCostsFactory implements VRPTransportCostsFactory {
    Scenario scenario;
    Carriers carriers;
    Map<String, TravelTime> travelTimes;
    Config config;
    private static final Logger log = (Logger) org.apache.logging.log4j.LogManager.getLogger(ZoneBasedTransportCostsFactory.class);

    public ZoneBasedTransportCostsFactory(Scenario scenario, Carriers carriers, Map<String, TravelTime> travelTimes, Config config) {
        this.scenario = scenario;
        this.carriers = carriers;
        this.travelTimes = travelTimes;
        this.config = config;
    }

    @Override
    public Map<String, VRPTransportCosts> createVRPTransportCostsWithModeCongestedTravelTime() {
        FreightCarriersConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        Map<String, VRPTransportCosts> byModeVRPTransportCosts = new HashMap<>();

        Set<VehicleType> vehicleTypes = new HashSet<>();
        Set<String> vehicleTransportModes = new HashSet<>();

        // Collect vehicle types and transport modes
        carriers.getCarriers().values().forEach(carrier -> vehicleTypes.addAll(carrier.getCarrierCapabilities().getVehicleTypes()));
        carriers.getCarriers().values().forEach(carrier -> carrier.getCarrierCapabilities().getVehicleTypes()
                .forEach(type -> vehicleTransportModes.add(type.getNetworkMode())));

        for (String mode : vehicleTransportModes) {
            log.info("Creating ZoneBasedTransportCosts for Mode: " + mode);
            ZoneBasedTransportCosts.Builder zoneBuilder = ZoneBasedTransportCosts.Builder.newInstance(
                    scenario.getNetwork(),
                    vehicleTypes);

            // Set time slice width and travel time
            zoneBuilder.setTimeSliceWidth(freightConfigGroup.getTravelTimeSliceWidth());
            zoneBuilder.setTravelTime(travelTimes.get(mode));
            
            // Create ZoneBasedTransportCosts, which uses the shared cache
            byModeVRPTransportCosts.put(mode, zoneBuilder.build());
        }

        return byModeVRPTransportCosts;
    }

    @Override
    public VRPTransportCosts createVRPTransportCosts() {
        FreightCarriersConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        Set<VehicleType> vehicleTypes = new HashSet<>();
        carriers.getCarriers().values().forEach(carrier -> vehicleTypes.addAll(carrier.getCarrierCapabilities().getVehicleTypes()));

        ZoneBasedTransportCosts.Builder zoneBuilder = ZoneBasedTransportCosts.Builder.newInstance(scenario.getNetwork(), vehicleTypes);
        zoneBuilder.setTimeSliceWidth(freightConfigGroup.getTravelTimeSliceWidth());
        zoneBuilder.setTravelTime(travelTimes.get(TransportMode.car));
        
        // Create ZoneBasedTransportCosts, which uses the shared cache
        return zoneBuilder.build();
    }
}
