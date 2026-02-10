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
import hagrid.utils.general.SimulationBatGenerator;
import hagrid.utils.general.SimulationBatGenerator.SimulationSettings;

/**
 * HAGRID Pipeline Runner - Configure and run scenarios here.
 *
 * <p>After running the pipeline, a ready-to-execute {@code run_hagrid_sim.bat}
 * is generated automatically so the MATSim simulation can be started directly
 * on the simulation server without manual argument editing.</p>
 */
public final class HAGRID2MATSimPipelineRunner {

	// =========================================================================
	// PIPELINE SETTINGS  (demand generation + routing)
	// =========================================================================

	/** Region filter for demand generation (e.g. "Hannover", "Braunschweig"). */
	private static final String REGION = "Hannover";

	/** Vehicle dispatch schedule strategy (shift patterns for vehicle departures). */
	private static final VehicleSchedule SCHEDULE = VehicleSchedule.SIMPLE_STAGGERED;

	/** Earliest hour (inclusive) at which carriers start dispatching vehicles. */
	private static final int DISPATCH_START = 7;

	/** Latest hour (inclusive) at which carriers can still dispatch vehicles. */
	private static final int DISPATCH_END = 14;

	/** Earliest hour (inclusive) of the customer delivery time window. */
	private static final int DELIVERY_TW_START = 8;

	/** Latest hour (inclusive) of the customer delivery time window. */
	private static final int DELIVERY_TW_END = 20;

	/** Whether to run jsprit VRP routing after demand generation. */
	private static final boolean RUN_ROUTING = true;

	/** Enable carrier routing cache (reuse previously computed routes). */
	private static final boolean ENABLE_CACHING = false;

	/** jsprit VRP iterations during initial pipeline routing (1 = construction only). */
	private static final int JSPRIT_ITERATIONS = 1;

	// =========================================================================
	// SIMULATION SETTINGS  (MATSim simulation via HAGRIDSimulationRunner)
	// =========================================================================

	/** MATSim iterations (carrier plan re-planning cycles). */
	private static final int MAX_ITER = 150;

	/** jsprit VRP solver iterations during simulation carrier re-planning. */
	private static final int SIM_JSPRIT_ITER = 100;

	/** Enable zone-based transport cost caching during simulation. */
	private static final boolean ZONE_CACHING = true;

	/** Euclidean threshold (meters) below which zone-based caching kicks in. */
	private static final int ZONE_THRESHOLD = 1500;

	/** Penalty cost added for each U-turn in a route. */
	private static final double U_TURN_PENALTY = 1.0;

	/** Auto-generate analysis dashboard after each simulation run. */
	private static final boolean WRITE_DASHBOARD = true;

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
	// MAIN
	// =========================================================================

	public static void main(String[] args) {
		// 1) Run the demand-generation + routing pipeline
		HAGRID.run(SCENARIOS);

		// 2) Generate a ready-to-run bat file for the MATSim simulation
		SimulationBatGenerator.generate(SCENARIOS, new SimulationSettings(
				MAX_ITER, SIM_JSPRIT_ITER, ZONE_CACHING,
				ZONE_THRESHOLD, U_TURN_PENALTY, WRITE_DASHBOARD));
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
