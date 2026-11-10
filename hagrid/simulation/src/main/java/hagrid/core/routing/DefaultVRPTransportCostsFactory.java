package hagrid.core.routing;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.jsprit.VRPTransportCosts;
import org.matsim.vehicles.VehicleType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Universal frozen-request factory. Its deprecated live methods are explicit legacy adapters. */
public class DefaultVRPTransportCostsFactory implements VRPTransportCostsFactory {
    /** Frozen requests need no live provider registry. */
    public DefaultVRPTransportCostsFactory() {

    }
    private void requireLegacyInputs() {
        if(scenario==null || carriers==null || travelTimes==null || config==null)
            throw new IllegalStateException("Historical live API needs explicit live inputs; use RoutingRequest");
    }
    Scenario scenario;
    Carriers carriers;
    Map<String, TravelTime> travelTimes;
    Config config;
        private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(DefaultVRPTransportCostsFactory.class);

    public DefaultVRPTransportCostsFactory(Scenario scenario, Carriers carriers, Map<String, TravelTime> travelTimes, Config config,
                                          boolean zoneBasedCachingEnabled, double zoneBasedEuclideanThresholdMeters) {
        this.scenario = scenario;
        this.carriers = carriers;
        this.travelTimes = travelTimes;
        this.config = config;
        hagrid.core.config.ResolvedHagridConfig.rejectRemoved("routing.zoneBasedCachingEnabled",zoneBasedCachingEnabled);
    }

    @Override
    @Deprecated(forRemoval=false)
    public Map<String, VRPTransportCosts> createVRPTransportCostsWithModeCongestedTravelTime() {
        requireLegacyInputs();
        FreightCarriersConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        Map<String, VRPTransportCosts> byModeVRPTransportCosts = new HashMap<>();

        Set<VehicleType> vehicleTypes = new HashSet<>();
        Set<String> vehicleTransportModes = new HashSet<>();
        
        carriers.getCarriers().values().forEach(carrier -> {
            carrier.getCarrierCapabilities().getVehicleTypes().forEach(type -> {
                if (!type.hasNetworkMode()) {
                    log.warn("VehicleType {} has no networkMode. Setting 'car' as default.", type.getId());
                    type.setNetworkMode(TransportMode.car);
                }
                vehicleTypes.add(type);
                vehicleTransportModes.add(type.getNetworkMode());
            });
        });

        for (String mode : vehicleTransportModes) {
            log.info("Creating ZoneBasedTransportCosts for Mode: " + mode);
            var modeNetwork = NetworkUtils.createNetwork(config);
            new TransportModeNetworkFilter(scenario.getNetwork()).filter(modeNetwork, Set.of(mode));
            ZoneBasedTransportCosts.Builder zoneBuilder = ZoneBasedTransportCosts.Builder.newInstance(
                    modeNetwork,
                    vehicleTypes.stream().filter(type -> mode.equals(type.getNetworkMode())).toList());

            // Set time slice width and travel time
            zoneBuilder.setTimeSliceWidth(freightConfigGroup.getTravelTimeSliceWidth());
            zoneBuilder.setTravelTime(Objects.requireNonNull(travelTimes.get(mode), "No TravelTime supplied for network mode " + mode));

            
            // All carriers using this mode share this context's cache.
            byModeVRPTransportCosts.put(mode, zoneBuilder.build());
        }

        return byModeVRPTransportCosts;
    }

    @Override
    @Deprecated(forRemoval=false)
    public VRPTransportCosts createVRPTransportCosts() {
        requireLegacyInputs();
        FreightCarriersConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        Set<VehicleType> vehicleTypes = new HashSet<>();
        carriers.getCarriers().values().forEach(carrier -> vehicleTypes.addAll(carrier.getCarrierCapabilities().getVehicleTypes()));

        var carNetwork = NetworkUtils.createNetwork(config);
        new TransportModeNetworkFilter(scenario.getNetwork()).filter(carNetwork, Set.of(TransportMode.car));
        ZoneBasedTransportCosts.Builder zoneBuilder = ZoneBasedTransportCosts.Builder.newInstance(carNetwork, vehicleTypes);
        zoneBuilder.setTimeSliceWidth(freightConfigGroup.getTravelTimeSliceWidth());
        zoneBuilder.setTravelTime(travelTimes.get(TransportMode.car));

        
        // Own cache for this network and travel-time context.
        return zoneBuilder.build();
    }
}
