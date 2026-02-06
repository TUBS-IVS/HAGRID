/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package hagrid.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.inject.Injector;

import hagrid.demand.CarrierGenerator;
import hagrid.demand.CarrierRouter;
import hagrid.demand.CarrierServiceMerger;
import hagrid.demand.DeliveryGenerator;
import hagrid.demand.DemandProcessor;
import hagrid.demand.LogisticsDataProcessor;
import hagrid.demand.NetworkProcessor;
import hagrid.demand.SupplyCarrierGenerator;
import hagrid.utils.routing.ThreadingType;

/**
 * Executes the individual steps of the HAGRID demand pipeline.
 * <p>
 * This class encapsulates all pipeline processing steps, providing a clean
 * separation between configuration and execution. Each step is executed
 * using dependency injection via Guice.
 * </p>
 * 
 * @author HAGRID Team
 */
public final class PipelineExecutor {

	private static final Logger LOGGER = LogManager.getLogger(PipelineExecutor.class);

	private final Injector injector;

	/**
	 * Creates a new PipelineExecutor.
	 *
	 * @param injector the Guice injector for dependency resolution
	 */
	public PipelineExecutor(Injector injector) {
		this.injector = injector;
	}

	/**
	 * Executes the complete demand pipeline.
	 * <p>
	 * Runs all processing steps in sequence:
	 * <ol>
	 *   <li>Network processing</li>
	 *   <li>Logistics data processing</li>
	 *   <li>Demand processing</li>
	 *   <li>Delivery generation</li>
	 *   <li>Carrier generation</li>
	 *   <li>Supply generation</li>
	 * </ol>
	 * </p>
	 */
	public void executeAll() {
		runNetworkProcessing();
		runLogisticsDataProcessing();
		runDemandProcessing();
		runDeliveryGeneration();
		runCarrierGeneration();
		runSupplyGeneration();
	}

	/**
	 * Executes the complete demand pipeline with optional service merging.
	 *
	 * @param applyServiceSimplifier if true, merges carrier services
	 */
	public void executeAll(boolean applyServiceSimplifier) {
		executeAll();
		if (applyServiceSimplifier) {
			runCarrierServiceMerger(true);
		}
	}

	/**
	 * Executes the complete demand pipeline with optional service merging and routing.
	 *
	 * @param applyServiceSimplifier if true, merges carrier services
	 * @param runRouting             if true, runs the routing step
	 */
	public void executeAll(boolean applyServiceSimplifier, boolean runRouting) {
		executeAll(applyServiceSimplifier);
		if (runRouting) {
			runRouter(ThreadingType.COMPLETABLE_FUTURE);
		}
	}

	// -------------------------------------------------------------------------
	// Individual Pipeline Steps
	// -------------------------------------------------------------------------

	/**
	 * Step 1: Process the network data.
	 * <p>
	 * Initializes and executes the NetworkProcessor to process network data
	 * required for further analysis. Creates subnetworks for different vehicle types.
	 * </p>
	 */
	public void runNetworkProcessing() {
		LOGGER.info("Initializing NetworkProcessor...");
		NetworkProcessor processor = injector.getInstance(NetworkProcessor.class);

		LOGGER.info("Starting network processing...");
		processor.run();
		LOGGER.info("Network processing completed. Subnetworks created.");
	}

	/**
	 * Step 2: Process logistics data.
	 * <p>
	 * Initializes and executes the LogisticsDataProcessor to process
	 * logistics-related data such as hubs and shipping points.
	 * </p>
	 */
	public void runLogisticsDataProcessing() {
		LOGGER.info("Initializing LogisticsDataProcessor...");
		LogisticsDataProcessor processor = injector.getInstance(LogisticsDataProcessor.class);

		LOGGER.info("Starting logistics data processing...");
		processor.run();
		LOGGER.info("Logistics data processing completed.");
	}

	/**
	 * Step 3: Process freight demand data.
	 * <p>
	 * Initializes and executes the DemandProcessor to split and sort
	 * the parcel input data for further processing.
	 * </p>
	 */
	public void runDemandProcessing() {
		LOGGER.info("Initializing DemandProcessor...");
		DemandProcessor processor = injector.getInstance(DemandProcessor.class);

		LOGGER.info("Starting demand data processing...");
		processor.run();
		LOGGER.info("Demand data processing completed.");
	}

	/**
	 * Step 4: Generate deliveries/parcels.
	 * <p>
	 * Converts the processed demand data into parcel objects for
	 * routing and delivery simulation in MATSim.
	 * </p>
	 */
	public void runDeliveryGeneration() {
		LOGGER.info("Initializing DeliveryGenerator...");
		DeliveryGenerator generator = injector.getInstance(DeliveryGenerator.class);

		LOGGER.info("Starting delivery generation based on sorted demand...");
		generator.run();
		LOGGER.info("Delivery and parcel generation completed.");
	}

	/**
	 * Step 5: Generate carriers.
	 * <p>
	 * Converts the processed demand data into carrier objects for
	 * routing and delivery simulation in MATSim.
	 * </p>
	 */
	public void runCarrierGeneration() {
		LOGGER.info("Initializing CarrierGenerator...");
		CarrierGenerator generator = injector.getInstance(CarrierGenerator.class);

		LOGGER.info("Starting carrier generation based on sorted demand...");
		generator.run();
		LOGGER.info("Carrier generation completed.");
	}

	/**
	 * Step 6: Generate supply carriers.
	 * <p>
	 * Creates supply carriers responsible for delivering parcels to hubs
	 * based on the previously generated carriers and their services.
	 * </p>
	 */
	public void runSupplyGeneration() {
		LOGGER.info("Initializing SupplyCarrierGenerator...");
		SupplyCarrierGenerator generator = injector.getInstance(SupplyCarrierGenerator.class);

		LOGGER.info("Starting supply carrier generation based on generated carriers...");
		generator.run();
		LOGGER.info("Supply carrier generation completed.");
	}

	/**
	 * Step 7 (optional): Merge carrier services.
	 * <p>
	 * Consolidates B2B/B2C services per link and provider (e.g., DHL, Hermes).
	 * This reduces the number of services for routing optimization while
	 * preserving original capacity, type, and attribute information.
	 * </p>
	 *
	 * @param fullMerge if true, performs a full merge; otherwise partial
	 */
	public void runCarrierServiceMerger(boolean fullMerge) {
		LOGGER.info("Initializing CarrierServiceMerger for parcel service consolidation...");
		CarrierServiceMerger merger = injector.getInstance(CarrierServiceMerger.class);

		LOGGER.info("Starting carrier service merge (fullMerge={})...", fullMerge);
		merger.setFullMerge(fullMerge);
		merger.run();
		LOGGER.info("Carrier service merge completed successfully.");
	}

	/**
	 * Step 8 (optional): Run routing for carriers.
	 * <p>
	 * Performs routing for both delivery and supply carriers based on
	 * the provided network and costs.
	 * </p>
	 *
	 * @param threadingType the threading type for parallel processing
	 */
	public void runRouter(ThreadingType threadingType) {
		try {
			LOGGER.info("Initializing CarrierRouter with threading type: {}...", threadingType);
			CarrierRouter router = injector.getInstance(CarrierRouter.class);

			if (router == null) {
				LOGGER.error("CarrierRouter instance is null");
				return;
			}

			router.setThreadingType(threadingType);
			LOGGER.info("Starting routing process for delivery and supply carriers...");
			router.run();
			LOGGER.info("Routing process for delivery and supply carriers completed.");
		} catch (Exception e) {
			LOGGER.error("Error initializing or running CarrierRouter", e);
		}
	}
}
