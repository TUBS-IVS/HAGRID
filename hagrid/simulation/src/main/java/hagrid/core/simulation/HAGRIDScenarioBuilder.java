package hagrid.core.simulation;

import hagrid.core.routing.HAGRIDRouterUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.geotools.api.feature.simple.SimpleFeature;
import org.matsim.api.core.v01.Scenario;
import hagrid.core.routing.CarrierFleetPreparation;
import hagrid.core.routing.CarrierVehicleTypeInputs;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.NetworkConfigGroup;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

    /**
 * Builds a complete MATSim {@link Scenario} for the HAGRID simulation.
 * <p>
 * Includes configuration setup, network loading, carrier merging, zone
 * assignment, and freight info logging.
 */
public class HAGRIDScenarioBuilder {

    private static final Logger LOGGER = LogManager.getLogger(HAGRIDScenarioBuilder.class);

    /**
     * Builds and returns a fully configured MATSim scenario based on the provided
     * simulation config.
     *
     * @param simConfig    the HAGRID scenario configuration
     * @param zoneFeatures collection of freight zone features (used for spatial
     *                     link tagging & zone based routing with jsprit)
     * @return the initialized MATSim scenario
     * @throws Exception if any error occurs during setup (e.g., file reading)
     */
    public static Scenario build(HAGRIDSimulationConfig simConfig, Collection<SimpleFeature> zoneFeatures)
            throws Exception {
        LOGGER.info("==============================================");
        LOGGER.info("Building HAGRID scenario '{}'", simConfig.getRunId());
        LOGGER.info("==============================================");
        Instant buildStart = Instant.now();

        LOGGER.info("[1/8] Loading MATSim base config from {}", simConfig.getConfigPath());
        Config config = ConfigUtils.loadConfig(simConfig.getConfigPath().toAbsolutePath().toString());
        LOGGER.info("[2/8] Applying HAGRID simulation overrides");
        FreightScenarioConfiguration.apply(config, simConfig);

        LOGGER.info("[3/8] Loading carrier vehicle types from {}", simConfig.getVehicleTypePath());
        CarrierVehicleTypes types = CarrierVehicleTypeInputs.read(simConfig.getVehicleTypePath());
        CarrierFleetPreparation.applySpeedLimits(types, simConfig.getResolvedRoutingConfig().fleet().cargoBikeMaxSpeedMps());
        LOGGER.info("Loaded {} vehicle type definitions", types.getVehicleTypes().size());

        LOGGER.info("[4/8] Merging delivery and supply carriers into unified plans");
        Carriers mergedCarriers = CarrierInputFiles.read(types, simConfig);
        rejectSectionNetworkChanges(config, mergedCarriers);

        LOGGER.info("[5/8] Configuring freight carrier module");
        FreightCarriersConfigGroup freightConfig = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
        freightConfig.setCarriersFile(simConfig.getMergedCarrierPath().toAbsolutePath().toString());
        freightConfig.setCarriersVehicleTypesFile(simConfig.getVehicleTypePath().toAbsolutePath().toString());

        LOGGER.info("[6/8] Loading MATSim scenario graph and population");
        boolean compactAtLoad = !ScenarioModalNetworks.hasBikeCarriers(mergedCarriers);
        Scenario scenario;
        if (simConfig.isDrtScenario()) {
            // Two-step load: register DrtRouteFactory BEFORE plans are read so that
            // MATSim can deserialise/create DrtRoute legs correctly. The one-shot
            // ScenarioUtils.loadScenario(config) gives no hook for this registration.
            scenario = ScenarioUtils.createScenario(config);
            scenario.getPopulation().getFactory().getRouteFactories()
                    .setRouteFactory(
                            org.matsim.contrib.drt.routing.DrtRoute.class,
                            new org.matsim.contrib.drt.routing.DrtRouteFactory());
            if (compactAtLoad) CompactNetworkChangeEvents.loadScenario(scenario);
            else ScenarioUtils.loadScenario(scenario);
        } else {
            scenario = ScenarioUtils.createScenario(config);
            if (compactAtLoad) CompactNetworkChangeEvents.loadScenario(scenario);
            else ScenarioUtils.loadScenario(scenario);
        }
        LOGGER.info("Scenario network contains {} links and {} nodes", scenario.getNetwork().getLinks().size(),
                scenario.getNetwork().getNodes().size());

        hagrid.core.routing.SectionBindingCompanion.requireStaticExecution(mergedCarriers,config.controller().getFirstIteration(),config.controller().getLastIteration());
        hagrid.core.routing.SectionBindingCompanion.install(scenario,hagrid.core.routing.SectionBindingCompanion.verifyAndRead(java.nio.file.Path.of(simConfig.getDeliveryCarrierPath().toString()+".section-bindings.json"),mergedCarriers,scenario.getNetwork(),simConfig.getResolvedRoutingConfig().sections().enabled()));

        // Import any required legacy modal input before providers and QSim are built.
        LOGGER.info("[7/8] Preparing the common freight network");
        ScenarioModalNetworks.attach(scenario, mergedCarriers, () -> {
            NetworkConfigGroup netCfg = ConfigUtils.addOrGetModule(config, NetworkConfigGroup.class);
            Network bikeNet = org.matsim.core.network.NetworkUtils.createNetwork(netCfg);
            new MatsimNetworkReader(bikeNet).readFile(simConfig.getBikeNetworkPath().toAbsolutePath().toString());
            LOGGER.info("Loaded cargobike network with {} links", bikeNet.getLinks().size());
            return bikeNet;
        });

        // Legacy modal merging compares original event structures; compact only after that merge.
        if (!compactAtLoad) CompactNetworkChangeEvents.compact(scenario.getNetwork());

        LOGGER.info("[8/8] Assigning freight zones to network links");
        FreightZoneAssigner.assign(scenario.getNetwork(), zoneFeatures);

        LOGGER.info("Validating carrier vehicle modes and input locations against the simulation network");
        var carrierModes = CarrierInputValidator.validate(mergedCarriers, types, scenario.getNetwork());
        FreightScenarioConfiguration.includeCarrierModes(config, carrierModes.values());
        carrierModes.forEach((id, mode) -> {
            Carrier carrier = mergedCarriers.getCarriers().get(id);
            if (carrier.getAttributes().getAttribute("carrierMode") == null) CarriersUtils.setCarrierMode(carrier, mode);
        });
        LOGGER.info("Registering already loaded carrier inputs in scenario");
        CarrierInputMerger.register(scenario, mergedCarriers, types);
        CarrierInputFiles.writeValidated(mergedCarriers, simConfig);

        logFreightInfos(scenario);

        Duration totalDuration = Duration.between(buildStart, Instant.now());
        LOGGER.info("HAGRID scenario '{}' ready in {} ms", simConfig.getRunId(), totalDuration.toMillis());

        return scenario;
    }

    /**
     * Compares the U-turn penalty Step A recorded on the routed delivery carriers
     * ({@link HAGRIDRouterUtils#UTURN_PENALTY_ATTR}) with this run's {@code uTurnPenalty}.
     * Never aborts; the caller logs the returned text (WARN, or INFO if it starts with "INFO").
     *
     * @param delivery      the routed delivery carriers as read from the Step-A file
     * @param runnerPenalty the U-turn penalty of this simulation run
     * @return a warning text if at least one carrier carries a deviating value (tolerance 1e-9);
     *         an "INFO ..." text if no carrier carries the attribute (files from before the check);
     *         empty if all recorded values match
     */
    static Optional<String> checkStepAPenalty(Carriers delivery, double runnerPenalty) {
        Set<String> deviating = new TreeSet<>();
        int withAttr = 0;
        for (Carrier carrier : delivery.getCarriers().values()) {
            Object raw = carrier.getAttributes().getAttribute(HAGRIDRouterUtils.UTURN_PENALTY_ATTR);
            if (raw == null) {
                continue;
            }
            withAttr++;
            Double stepA = null;
            if (raw instanceof Number n) {
                stepA = n.doubleValue();
            } else {
                try {
                    stepA = Double.parseDouble(raw.toString());
                } catch (NumberFormatException e) {
                    // never abort the run over a check: report the broken value as a deviation
                    deviating.add("unparsable value '" + raw + "'");
                }
            }
            if (stepA != null && Math.abs(stepA - runnerPenalty) > 1e-9) {
                deviating.add(stepA.toString());
            }
        }
        if (withAttr == 0) {
            return Optional.of("INFO: delivery carriers carry no '" + HAGRIDRouterUtils.UTURN_PENALTY_ATTR
                    + "' attribute (routed before the check existed); cannot verify that Step A used the runner's uTurnPenalty "
                    + runnerPenalty);
        }
        if (deviating.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("U-turn penalty mismatch: Step A routed with " + deviating
                + " but this run uses uTurnPenalty=" + runnerPenalty + " (" + HAGRIDRouterUtils.UTURN_PENALTY_ATTR
                + " on " + withAttr + " carriers). Routing and simulation are not consistent; results are not comparable "
                + "to runs with matching penalties.");
    }


    /** Inspect the input before MATSim resolves event links against the split network. */
    private static void rejectSectionNetworkChanges(Config config, Carriers carriers) {
        boolean sections=carriers.getCarriers().values().stream().anyMatch(c ->
                c.getAttributes().getAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER)!=null);
        var network=config.network();
        if(!sections || !network.isTimeVariantNetwork() || network.getChangeEventsInputFile()==null)return;
        var url=network.getChangeEventsInputFileUrl(config.getContext());
        var factory=javax.xml.stream.XMLInputFactory.newFactory();
        factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        try(var input=org.matsim.core.utils.io.IOUtils.getInputStream(url)) {
            var reader=factory.createXMLStreamReader(input);
            try {
                while(reader.hasNext())if(reader.next()==javax.xml.stream.XMLStreamConstants.START_ELEMENT
                        && "networkChangeEvent".equals(reader.getLocalName()))
                    throw new IllegalArgumentException("Section routing does not support nonempty network change events: "+url);
            } finally {reader.close();}
        } catch(javax.xml.stream.XMLStreamException | java.io.IOException e) {
            throw new IllegalArgumentException("Cannot inspect section network change events: "+url,e);
        }
    }

    /**
     * Logs summary statistics for all freight carriers in the scenario.
     *
     * @param scenario the initialized MATSim scenario
     */
    private static void logFreightInfos(Scenario scenario) {
        int servicesCep = 0, parcelsLastMileDelivery = 0;
        int servicesSupply = 0, parcelsSupply = 0;

        for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
            boolean isSupply = carrier.getId().toString().contains("supply");

            for (CarrierService service : carrier.getServices().values()) {
                if (isSupply) {
                    servicesSupply++;
                    parcelsSupply += service.getCapacityDemand();
                } else {
                    servicesCep++;
                    parcelsLastMileDelivery += service.getCapacityDemand();
                }
            }
        }

        LOGGER.info("Last Mile Delivery Services: {}, Parcels: {}", servicesCep, parcelsLastMileDelivery);
        LOGGER.info("Supply Services: {}, Parcels: {}", servicesSupply, parcelsSupply);
    }

}
