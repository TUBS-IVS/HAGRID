package hagrid.core.simulation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.freight.carriers.*;
import java.time.Duration;
import java.time.Instant;

/** Freight XML input/output; scenario validation remains the builder's publication gate. */
final class CarrierInputFiles {
    private static final Logger LOGGER = LogManager.getLogger(CarrierInputFiles.class);
    private CarrierInputFiles() {}

    static Carriers read(CarrierVehicleTypes types, HAGRIDSimulationConfig simConfig) throws Exception {
        Instant start = Instant.now();

        Carriers delivery = CarrierInputMerger.newInputCollection();
        new CarrierPlanXmlReader(delivery, types).readFile(simConfig.getDeliveryCarrierPath().toString());
        HAGRIDScenarioBuilder.checkStepAPenalty(delivery,simConfig.getUTurnPenaltyCost()).ifPresent(message -> {
            if(message.startsWith("INFO")) LOGGER.info(message); else LOGGER.warn(message);
        });


        LOGGER.info("Loaded {} delivery carriers", delivery.getCarriers().size());

        Carriers supply = CarrierInputMerger.newInputCollection();
        new CarrierPlanXmlReader(supply, types).readFile(simConfig.getSupplyCarrierPath().toString());

        LOGGER.info("Loaded {} supply carriers", supply.getCarriers().size());

        Carriers merged = CarrierInputMerger.merge(delivery, supply);
        LOGGER.info("Merged carrier collection contains {} carriers", merged.getCarriers().size());

        // Older input files used a mixed-case enum spelling.
        normalizeParcelTypes(merged);

        Duration mergeDuration = Duration.between(start, Instant.now());
        LOGGER.info("Carrier merge completed in {} ms", mergeDuration.toMillis());
        return merged;
    }

    static void writeValidated(Carriers carriers, HAGRIDSimulationConfig simConfig) throws Exception {
        LOGGER.info("Writing validated merged carriers to {}", simConfig.getMergedCarrierPath());
        java.nio.file.Files.createDirectories(simConfig.getMergedCarrierPath().getParent());
        var writer = new CarrierPlanWriter(carriers);
        boolean sections = carriers.getCarriers().values().stream().anyMatch(c ->
                c.getAttributes().getAttribute(hagrid.core.routing.SectionBindingCompanion.MARKER) != null);
        if (sections) writer.writePreciseTimes(simConfig.getMergedCarrierPath().toAbsolutePath().toString());
        else writer.write(simConfig.getMergedCarrierPath().toAbsolutePath().toString());
    }

    private static void normalizeParcelTypes(Carriers carriers) {
        for (Carrier carrier : carriers.getCarriers().values()) {
            for (CarrierService service : carrier.getServices().values()) {
                Object attr = service.getAttributes().getAttribute("type");
                if ("Mixed".equals(attr)) {
                    service.getAttributes().putAttribute("type", "MIXED");
                }
            }
        }
    }
}
