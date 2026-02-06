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

package hagrid;

import java.time.LocalDate;

import hagrid.pipeline.ScenarioConfig;
import hagrid.pipeline.ScenarioConfig.VehicleSchedule;

/**
 * Helper class for quickly building scenario configurations.
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * import static hagrid.ScenarioBuilder.*;
 * 
 * scenario("basecase", LocalDate.of(2025, 5, 13), "m", "l")
 * }</pre>
 */
public final class ScenarioBuilder {

	// =========================================================================
	// DEFAULT SETTINGS - Change these to adjust all scenarios
	// =========================================================================

	private static final String DEFAULT_REGION = "Hannover";
	private static final VehicleSchedule DEFAULT_SCHEDULE = VehicleSchedule.SIMPLE_STAGGERED;
	private static final int DEFAULT_DELIVERY_START = 7;
	private static final int DEFAULT_DELIVERY_END = 14;
	private static final boolean DEFAULT_RUN_ROUTING = false;
	private static final boolean DEFAULT_ENABLE_CACHING = true;

	private ScenarioBuilder() {} // Utility class

	// =========================================================================
	// SCENARIO BUILDERS
	// =========================================================================

	/**
	 * Creates a scenario with the given concept, date, and vehicle sizes.
	 * Uses default settings for region, schedule, delivery window, etc.
	 */
	public static ScenarioConfig scenario(String concept, LocalDate date, String... vehicleSizes) {
		return ScenarioConfig.builder()
				.concepts(concept)
				.dates(date)
				.filterRegions(DEFAULT_REGION)
				.vehicleSizes(vehicleSizes)
				.vehicleSchedule(DEFAULT_SCHEDULE)
				.deliveryWindow(DEFAULT_DELIVERY_START, DEFAULT_DELIVERY_END)
				.providerTimeShift("Amazon", +1)
				.applyServiceSimplifier(false)
				.runRouting(DEFAULT_RUN_ROUTING)
				.enableCaching(DEFAULT_ENABLE_CACHING)
				.build();
	}

	/**
	 * Creates a scenario with extended dispatch schedule (7, 11, 14).
	 */
	public static ScenarioConfig scenarioExtended(String concept, LocalDate date, String... vehicleSizes) {
		return ScenarioConfig.builder()
				.concepts(concept)
				.dates(date)
				.filterRegions(DEFAULT_REGION)
				.vehicleSizes(vehicleSizes)
				.vehicleSchedule(VehicleSchedule.EXTENDED)
				.deliveryWindow(DEFAULT_DELIVERY_START, DEFAULT_DELIVERY_END)
				.providerTimeShift("Amazon", +1)
				.applyServiceSimplifier(false)
				.runRouting(DEFAULT_RUN_ROUTING)
				.enableCaching(DEFAULT_ENABLE_CACHING)
				.build();
	}

	/**
	 * Creates a scenario with custom region.
	 */
	public static ScenarioConfig scenario(String concept, LocalDate date, String region, String... vehicleSizes) {
		return ScenarioConfig.builder()
				.concepts(concept)
				.dates(date)
				.filterRegions(region)
				.vehicleSizes(vehicleSizes)
				.vehicleSchedule(DEFAULT_SCHEDULE)
				.deliveryWindow(DEFAULT_DELIVERY_START, DEFAULT_DELIVERY_END)
				.providerTimeShift("Amazon", +1)
				.applyServiceSimplifier(false)
				.runRouting(DEFAULT_RUN_ROUTING)
				.enableCaching(DEFAULT_ENABLE_CACHING)
				.build();
	}
}
