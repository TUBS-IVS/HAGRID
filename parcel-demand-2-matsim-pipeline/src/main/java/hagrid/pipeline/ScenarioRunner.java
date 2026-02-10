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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.inject.Guice;
import com.google.inject.Injector;

import hagrid.HagridConfig;
import hagrid.HagridModule;
import hagrid.HagridPaths;
import hagrid.pipeline.ScenarioConfig.DispatchWindow;
import hagrid.pipeline.ScenarioConfig.VehicleConfig;
import hagrid.pipeline.ScenarioConfig.VehicleSchedule;

/**
 * Orchestrates the execution of HAGRID demand pipeline scenarios.
 * <p>
 * This class handles all the complexity of running scenarios:
 * <ul>
 *   <li>Iterating over concepts and dates</li>
 *   <li>Setting up logging per run</li>
 *   <li>Configuring the cache</li>
 *   <li>Applying scenario configuration to HagridConfig</li>
 *   <li>Executing the pipeline steps</li>
 *   <li>Writing summaries</li>
 * </ul>
 * </p>
 * 
 * @author HAGRID Team
 */
public final class ScenarioRunner {

	private static final Logger LOGGER = LogManager.getLogger(ScenarioRunner.class);

	private static final DateTimeFormatter RUN_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

	private final ScenarioConfig scenarioConfig;
	private final Path pipelineRoot;
	private final Path configXmlPath;

	/**
	 * Creates a new ScenarioRunner with the given configuration.
	 *
	 * @param scenarioConfig the scenario configuration
	 */
	public ScenarioRunner(ScenarioConfig scenarioConfig) {
		this(scenarioConfig, Paths.get("parcel-demand-2-matsim-pipeline"));
	}

	/**
	 * Creates a new ScenarioRunner with the given configuration and pipeline root.
	 *
	 * @param scenarioConfig the scenario configuration
	 * @param pipelineRoot   the root directory of the pipeline
	 */
	public ScenarioRunner(ScenarioConfig scenarioConfig, Path pipelineRoot) {
		this.scenarioConfig = scenarioConfig;
		this.pipelineRoot = pipelineRoot;
		this.configXmlPath = pipelineRoot.resolve("hagrid-input").resolve("config").resolve("config.xml");
	}

	/**
	 * Runs all configured scenarios (concepts x dates).
	 */
	public void runAll() {
		LOGGER.info("Starting HAGRID Demand Pipeline for {} concept(s) and {} date(s)...",
				scenarioConfig.getConcepts().size(), scenarioConfig.getDates().size());

		for (String concept : scenarioConfig.getConcepts()) {
			LOGGER.info("--- Concept batch start: {} ---", concept);
			for (LocalDate date : scenarioConfig.getDates()) {
				runSingleScenario(concept, date);
			}
			LOGGER.info("--- Concept batch finished: {} ---", concept);
		}

		LOGGER.info("All demand pipeline scenarios completed.");
	}

	/**
	 * Runs a single scenario for the given concept and date.
	 *
	 * @param concept the concept name
	 * @param date    the simulation date
	 */
	public void runSingleScenario(String concept, LocalDate date) {
		String runId = scenarioConfig.createRunId(concept, date);
		LocalDateTime startedAt = LocalDateTime.now();

		LOGGER.info("--------------------------------------------------");
		LOGGER.info("Processing scenario: {}", runId);
		LOGGER.info("--------------------------------------------------");

		// Setup per-run logging
		PipelineLogger runLogger = setupLogging(runId, startedAt);

		try {
			// Setup cache configuration
			CacheConfig cacheConfig = setupCacheConfig(runId);

			// Create and configure Guice injector
			Injector injector = createConfiguredInjector(concept, date, cacheConfig);

			// Execute pipeline
			executePipeline(injector);

			// Write summary
			writeSummary(runId, concept, date, startedAt, cacheConfig, injector);

			// Cleanup
			cleanup(injector);

			LOGGER.info("Finished scenario: {}", runId);
		} finally {
			// Detach per-run logger
			if (runLogger != null) {
				runLogger.detach();
			}
			// Clear global cache config
			CacheConfig.clearGlobal();
		}
	}

	// -------------------------------------------------------------------------
	// Private Helper Methods
	// -------------------------------------------------------------------------

	private PipelineLogger setupLogging(String runId, LocalDateTime startedAt) {
		String ts = startedAt.format(RUN_TS_FORMAT);
		HagridPaths paths = new HagridPaths(pipelineRoot);
		paths.initializeRun(runId);
		Path runLogDir = paths.logDir();

		try {
			Files.createDirectories(runLogDir);
			return PipelineLogger.attach(runLogDir.resolve("runner_" + ts + ".log"));
		} catch (Exception e) {
			LOGGER.warn("Could not setup per-run logging for {}: {}", runId, e.getMessage());
			return null;
		}
	}

	private CacheConfig setupCacheConfig(String runId) {
		HagridPaths paths = new HagridPaths(pipelineRoot);
		paths.initializeRun(runId);

		if (!scenarioConfig.getPipelineSettings().isCachingEnabled()) {
			return CacheConfig.builder()
					.enabled(false)
					.runId(runId)
					.cacheDirectory(paths.cacheDir())
					.build();
		}

		Path cacheBase = paths.cacheDir();
		CacheConfig cacheConfig = CacheConfig.builder()
				.enabled(true)
				.forRun(cacheBase, runId)
				.buildAndSetGlobal();

		cacheConfig.ensureDirectoryExists();
		LOGGER.info("Cache configured: {}", cacheConfig);

		return cacheConfig;
	}

	private Injector createConfiguredInjector(String concept, LocalDate date, CacheConfig cacheConfig) {
		// Create Guice injector
		Injector injector = Guice.createInjector(new HagridModule(configXmlPath.toString()));

		// Get and configure HagridConfig
		HagridConfig hagridConfig = injector.getInstance(HagridConfig.class);
		applyScenarioConfig(hagridConfig, concept, date, cacheConfig);

		// Create output directories for this run
		try {
			hagridConfig.io().createOutputDirectories();
		} catch (Exception e) {
			LOGGER.warn("Could not create output directories: {}", e.getMessage());
		}

		return injector;
	}

	private void applyScenarioConfig(HagridConfig hagridConfig, String concept, 
			LocalDate date, CacheConfig cacheConfig) {
		
		// Basic scenario settings
		hagridConfig.setConcept(concept);
		hagridConfig.setTag(scenarioConfig.getTag());
		hagridConfig.setSimulationDate(date);
		hagridConfig.setFilterRegionsAsString(scenarioConfig.getFilterRegions());

		// Vehicle configuration
		VehicleConfig vehicleConfig = scenarioConfig.getVehicleConfig();
		hagridConfig.clearProviderVehicleSizes();
		hagridConfig.setCepVehicleSizes(vehicleConfig.getDefaultVehicleSizes());

		// Log vehicle sizes per provider
		Map<String, List<String>> vehicleSizeLog = new LinkedHashMap<>();
		vehicleSizeLog.put("default", vehicleConfig.getDefaultVehicleSizes());

		for (String provider : vehicleConfig.getAllProviders()) {
			if (!"default".equals(provider)) {
				List<String> sizes = vehicleConfig.getVehicleSizesForProvider(provider);
				hagridConfig.setProviderVehicleSizes(provider, sizes);
				vehicleSizeLog.put(provider, sizes);
			}
		}
		LOGGER.info("Configured vehicle sizes per provider: {}", vehicleSizeLog);

		// Dispatch windows (when LMD vehicles can START tours)
		Map<String, DispatchWindow> windows = scenarioConfig.getDispatchWindows();
		for (Map.Entry<String, DispatchWindow> entry : windows.entrySet()) {
			String provider = entry.getKey();
			DispatchWindow window = entry.getValue();
			if ("default".equalsIgnoreCase(provider)) {
				hagridConfig.setDefaultDeliveryHours(window.getStartHour(), window.getEndHour());
			} else {
				hagridConfig.setDeliveryHours(provider, window.getStartHour(), window.getEndHour());
			}
		}
		LOGGER.info("Configured dispatch windows (vehicle start times): {}", windows);

		// Delivery time window (real TW for MATSim scoring penalties)
		int twStart = scenarioConfig.getDeliveryTimeWindowStartHour();
		int twEnd = scenarioConfig.getDeliveryTimeWindowEndHour();
		hagridConfig.routing().setDeliveryWindow(twStart, twEnd);
		LOGGER.info("Configured delivery time window (TW penalty): {}:00-{}:00", twStart, twEnd);

		// Dispatch hours based on vehicle schedules (considers custom hours and time shifts)
		hagridConfig.clearProviderDispatchHours();
		Set<String> allProviders = new LinkedHashSet<>();
		allProviders.add("default");
		allProviders.addAll(windows.keySet());
		allProviders.addAll(vehicleConfig.getAllProviders());

		Map<String, List<Integer>> dispatchHoursLog = new LinkedHashMap<>();
		for (String provider : allProviders) {
			DispatchWindow window = scenarioConfig.getDispatchWindow(provider);
			// Use VehicleConfig.computeDispatchHours which handles:
			// 1. Custom dispatch hours (if configured)
			// 2. Schedule preset + dispatch window
			// 3. Time shift adjustments
			List<Integer> dispatchHours = vehicleConfig.computeDispatchHours(provider, window);
			
			if (!dispatchHours.isEmpty()) {
				hagridConfig.setProviderDispatchHours(provider, dispatchHours);
				dispatchHoursLog.put(provider, dispatchHours);
			}
		}
		LOGGER.info("Computed dispatch hours per provider: {}", dispatchHoursLog);

		// Cache configuration (via HagridConfig if available)
		if (cacheConfig != null && cacheConfig.isEnabled()) {
			Path cacheDir = cacheConfig.getCacheDirectory();
			invokeSetter(hagridConfig, "setCarrierRoutingCacheEnabled", true);
			invokeSetter(hagridConfig, "setCarrierRoutingCacheDir", cacheDir.toString());
			invokeSetter(hagridConfig, "setCarrierCacheEnabled", true);
			invokeSetter(hagridConfig, "setCarrierCacheDir", cacheDir.toString());
			invokeSetter(hagridConfig, "setCacheDir", cacheDir.toString());
			LOGGER.info("Configured carrier routing cache: {}", cacheDir);
		}
		
		// JSprit iterations configuration
		int jspritIterations = scenarioConfig.getPipelineSettings().getJspritIterations();
		hagridConfig.routing().setJspritIterations(jspritIterations);
		LOGGER.info("JSprit iterations: {}", jspritIterations);
	}

	private void executePipeline(Injector injector) {
		PipelineExecutor executor = new PipelineExecutor(injector);
		
		boolean applyServiceSimplifier = scenarioConfig.getPipelineSettings().isApplyServiceSimplifier();
		boolean runRouting = scenarioConfig.getPipelineSettings().isRunRouting();
		
		executor.executeAll(applyServiceSimplifier, runRouting);
	}

	private void writeSummary(String runId, String concept, LocalDate date, 
			LocalDateTime startedAt, CacheConfig cacheConfig, Injector injector) {
		
		HagridPaths paths = new HagridPaths(pipelineRoot);
		paths.initializeRun(runId);
		Path outputDir = paths.summaryDir();

		// Collect pipeline statistics from the Scenario
		PipelineStatistics pipelineStats = null;
		try {
			org.matsim.api.core.v01.Scenario scenario = injector.getInstance(org.matsim.api.core.v01.Scenario.class);
			pipelineStats = PipelineStatistics.collectFrom(scenario);
		} catch (Exception e) {
			LOGGER.warn("Could not collect pipeline statistics: {}", e.getMessage());
		}

		ScenarioSummaryWriter.RunContext context = ScenarioSummaryWriter.RunContext.builder()
				.runId(runId)
				.concept(concept)
				.simulationDate(date)
				.startedAt(startedAt)
				.outputDirectory(outputDir)
				.scenarioConfig(scenarioConfig)
				.cacheConfig(cacheConfig)
				.pipelineStatistics(pipelineStats)
				.build();
		
		ScenarioSummaryWriter.write(context);
	}

	private void cleanup(Injector injector) {
		// Allow GC to collect resources
		System.gc();
		
		try {
			Thread.sleep(2000); // Give GC some time
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Reflection helper for optional config setters.
	 * Allows setting properties that may not exist in all versions.
	 */
	private static void invokeSetter(Object target, String methodName, Object value) {
		try {
			Class<?> clazz = target.getClass();
			for (var method : clazz.getMethods()) {
				if (method.getName().equals(methodName) && method.getParameterCount() == 1) {
					Class<?> paramType = method.getParameterTypes()[0];
					if (value instanceof Boolean b && (paramType == boolean.class || paramType == Boolean.class)) {
						method.invoke(target, b);
						return;
					}
					if (value instanceof String s && paramType == String.class) {
						method.invoke(target, s);
						return;
					}
				}
			}
		} catch (Exception ignored) {
			// Method may not exist - that's OK
		}
	}
}
