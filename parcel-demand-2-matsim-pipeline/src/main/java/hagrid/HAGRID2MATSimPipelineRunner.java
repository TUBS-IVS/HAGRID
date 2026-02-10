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
	private static final int DISPATCH_START = 7;
	private static final int DISPATCH_END = 14;
	private static final int DELIVERY_TW_START = 8;
	private static final int DELIVERY_TW_END = 20;
	private static final boolean RUN_ROUTING = true;
	private static final boolean ENABLE_CACHING = false;
	private static final int JSPRIT_ITERATIONS = 1;  // 1 for initial model

	// =========================================================================
	// SCENARIO DEFINITIONS - Define your scenarios here
	// =========================================================================

	private static final ScenarioConfig[] SCENARIOS = {
			
			// Scenario 1: Standard Basecase mit M und L
			scenario("basecase", LocalDate.of(2025, 5, 13), "m", "l"),
			
			// Scenario 2: Basecase V1 mit 100er Fahrzeugen (gleicher Tag, neuer Name)
			// scenario("basecase", "V1", LocalDate.of(2025, 5, 13), "100_l"),
			
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

	/** Create a scenario without version tag (runId = CONCEPT_ddMMyyyy). */
	private static ScenarioConfig scenario(String concept, LocalDate date, String... vehicleSizes) {
		return buildScenario(concept, "", date, vehicleSizes);
	}

	/** Create a scenario with a version tag (runId = CONCEPT_ddMMyyyy_TAG). */
	private static ScenarioConfig scenario(String concept, String tag, LocalDate date, String... vehicleSizes) {
		return buildScenario(concept, tag, date, vehicleSizes);
	}

	private static ScenarioConfig buildScenario(String concept, String tag, LocalDate date, String... vehicleSizes) {
		return ScenarioConfig.builder()
				.concepts(concept)
				.dates(date)
				.tag(tag)
				.filterRegions(REGION)
				.vehicleSizes(vehicleSizes)
				.vehicleSchedule(SCHEDULE)
				.dispatchWindow(DISPATCH_START, DISPATCH_END)
				.providerTimeShift("Amazon", +1)
				.deliveryTimeWindow(DELIVERY_TW_START, DELIVERY_TW_END)				
				.applyServiceSimplifier(true)
				.runRouting(RUN_ROUTING)
				.jspritIterations(JSPRIT_ITERATIONS)
				.enableCaching(ENABLE_CACHING)
				.build();
	}
}
