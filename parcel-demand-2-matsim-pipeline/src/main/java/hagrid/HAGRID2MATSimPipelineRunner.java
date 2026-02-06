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
 * HAGRID Pipeline Runner - Configure and run scenarios here.
 */
public final class HAGRID2MATSimPipelineRunner {

	// =========================================================================
	// GLOBAL SETTINGS - Apply to all scenarios (must be first!)
	// =========================================================================

	private static final String REGION = "Hannover";
	private static final VehicleSchedule SCHEDULE = VehicleSchedule.SIMPLE_STAGGERED;
	private static final int DELIVERY_START = 7;
	private static final int DELIVERY_END = 14;
	private static final boolean RUN_ROUTING = true;
	private static final boolean ENABLE_CACHING = true;
	private static final int JSPRIT_ITERATIONS = 1;  // 1 for initial model

	// =========================================================================
	// SCENARIO DEFINITIONS - Define your scenarios here
	// =========================================================================

	private static final ScenarioConfig[] SCENARIOS = {
			
			// Scenario 1: Standard Basecase mit M und L
			scenario("basecase", LocalDate.of(2025, 5, 13), "m", "l"),
			
			// Scenario 2: Nur 100er Fahrzeuge (auskommentieren wenn gewünscht)
			// scenario("basecase", LocalDate.of(2025, 5, 13), "100"),
			
			// Scenario 3: Nur 80er Fahrzeuge
			// scenario("basecase", LocalDate.of(2025, 5, 13), "80"),
			
			// Scenario 4: Bikes only
			// scenario("basecase", LocalDate.of(2025, 5, 13), "bike"),
	};

	// =========================================================================
	// MAIN - Runs all defined scenarios
	// =========================================================================

	public static void main(String[] args) {
		HAGRID.run(SCENARIOS);
	}

	// =========================================================================
	// HELPER - Quick scenario builder
	// =========================================================================

	private static ScenarioConfig scenario(String concept, LocalDate date, String... vehicleSizes) {
		return ScenarioConfig.builder()
				.concepts(concept)
				.dates(date)
				.filterRegions(REGION)
				.vehicleSizes(vehicleSizes)
				.vehicleSchedule(SCHEDULE)
				.deliveryWindow(DELIVERY_START, DELIVERY_END)
				.providerTimeShift("Amazon", +1)
				.applyServiceSimplifier(true)
				.runRouting(RUN_ROUTING)
				.jspritIterations(JSPRIT_ITERATIONS)
				.enableCaching(ENABLE_CACHING)
				.build();
	}
}
