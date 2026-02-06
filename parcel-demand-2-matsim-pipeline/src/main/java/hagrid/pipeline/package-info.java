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

/**
 * HAGRID Pipeline Infrastructure Package.
 * <p>
 * This package provides the core infrastructure for running the HAGRID demand 
 * pipeline. The package follows a clean separation of concerns:
 * </p>
 * 
 * <h2>Key Classes:</h2>
 * <ul>
 *   <li>{@link hagrid.pipeline.ScenarioConfig} - User-friendly scenario configuration with fluent builder API</li>
 *   <li>{@link hagrid.pipeline.ScenarioRunner} - Orchestrates execution of pipeline scenarios</li>
 *   <li>{@link hagrid.pipeline.PipelineExecutor} - Executes individual pipeline processing steps</li>
 *   <li>{@link hagrid.pipeline.CacheConfig} - Centralized cache configuration (replaces System.setProperty)</li>
 *   <li>{@link hagrid.pipeline.PipelineLogger} - Per-run logging management</li>
 *   <li>{@link hagrid.pipeline.ScenarioSummaryWriter} - Writes human-readable run summaries</li>
 * </ul>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * // Configure your scenario
 * ScenarioConfig config = ScenarioConfig.builder()
 *     .concepts("basecase")
 *     .dates(LocalDate.of(2025, 5, 13))
 *     .filterRegions("Hannover")
 *     .vehicleSizes("m", "l")
 *     .vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)
 *     .deliveryWindow(7, 14)
 *     .build();
 * 
 * // Run the pipeline
 * ScenarioRunner runner = new ScenarioRunner(config);
 * runner.runAll();
 * }</pre>
 * 
 * <h2>MATSim Conventions:</h2>
 * <p>
 * This package follows MATSim coding conventions:
 * </p>
 * <ul>
 *   <li>Uses {@link java.util.LinkedHashMap} and {@link java.util.LinkedHashSet} 
 *       for deterministic iteration order</li>
 *   <li>Follows Java code conventions for naming and formatting</li>
 *   <li>Uses proper random number handling for reproducibility</li>
 * </ul>
 * 
 * @author HAGRID Team
 * @see hagrid.HAGRID2MATSimPipelineRunner_old
 */
package hagrid.pipeline;
