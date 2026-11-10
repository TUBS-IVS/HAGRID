package hagrid.hannover.demand;

import com.google.inject.Inject;
import hagrid.core.HagridConfig;
import hagrid.core.routing.*;
import hagrid.core.util.HAGRIDUtils;
import hagrid.core.util.OutputFileSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.*;
import java.nio.file.Path;
import java.util.List;

/** Routes Delivery and Supply on the common modal network; publishes complete output only after all workers finish. */
public class CarrierRouter implements Runnable {
    private static final Logger LOGGER = LogManager.getLogger(CarrierRouter.class);
    private ThreadingType threadingType;
    @Inject private Scenario scenario;
    @Inject private HagridConfig hagridConfig;

    public void setThreadingType(ThreadingType threadingType) { this.threadingType = threadingType; }

    @Override public void run() {
        try {
            Carriers delivery = HAGRIDUtils.getScenarioElementAs("carriers", scenario);
            Carriers supply = HAGRIDUtils.getScenarioElementAs("supply", scenario);
            CarrierVehicleTypes types = HAGRIDUtils.getScenarioElementAs("carrierVehicleTypes", scenario);
            if(hagrid.core.config.SectionSettings.from(hagridConfig.getAdditionalConfigurationValues()).enabled()) {
                SectionOfflineRouting.run(scenario,hagridConfig,threadingType,delivery,supply,types);
                return;
            }
            try (var transaction = new CarrierRoutingTransaction(delivery, supply)) {
                List<CarrierRoutingMetrics> metrics = solve(delivery, supply, types);
                publishOutputs(delivery, supply, metrics);
                transaction.commit();
            }
            LOGGER.info("Initial routing completed for Delivery and Supply, label={}", threadingType);
        } catch (Exception failure) {
            LOGGER.error("Error routing carriers", failure);
            throw new IllegalStateException("Carrier initial routing failed", failure);
        }
    }

    private List<CarrierRoutingMetrics> solve(Carriers delivery, Carriers supply, CarrierVehicleTypes types) {
        var prepared = OfflineCarrierRouting.prepare(delivery, supply, types, scenario.getNetwork());
        var status = new CarrierRoutingStatusLogger(Path.of(hagridConfig.io().routingStatus()));
        try (var router = new Router(threadingType, status, hagridConfig, types)) {
            prepared.route(router, delivery, "delivery");
            prepared.route(router, supply, "supply");
            return List.copyOf(router.getMetrics());
        } // Actual worker termination precedes file replacement and the whole-attempt commit.
    }

    private void publishOutputs(Carriers delivery, Carriers supply, List<CarrierRoutingMetrics> metrics) throws java.io.IOException {
        var io = hagridConfig.io();
        OutputFileSet.publish(List.of(
                new OutputFileSet.Artifact(Path.of(io.deliveryCarriersRouted()), p -> new CarrierPlanWriter(delivery).write(p.toString())),
                new OutputFileSet.Artifact(Path.of(io.supplyCarriersRouted()), p -> new CarrierPlanWriter(supply).write(p.toString())),
                new OutputFileSet.Artifact(Path.of(io.routingMetrics()), p -> RoutingMetricsCsv.write(metrics, p))));
    }
}