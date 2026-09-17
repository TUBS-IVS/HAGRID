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
	private static final int SIM_JSPRIT_ITER = 1000;

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

			// DEV-PC ARM, 2026-07-31: completes the v2 capacity sweep.
			// 30v2-150v2 are already done on the sim-PC (70v2 crashed there on a JVM
			// access violation and was never redone), so this arm covers 160-400 in
			// steps of 10, plus 70v2.
			//
			// Distinct tags -> distinct runId -> distinct runId.hashCode() seed
			// -> reseeded demand-layer RNG (dispatch time-shifts + missed-delivery draw)
			// AND reseeded CarrierVehicleFactory (CarrierGenerator.java:87-89).
			// All tags read the SAME demand shapefile (tag-independent baseRunId).
			// vehicleSize "<n>_l": clones ct_cep_size_l, overrides freight capacity to <n>
			// (costs/speed/dimensions stay on the l-template -> only capacity varies across the sweep).
			//
			// ORDER IS DELIBERATE and doubles as this machine's smoke test: no Hannover
			// run has ever executed on the dev-PC (63.5 GB vs the sim-PC's 128 GB). The
			// lightest capacity runs first, so a broken Hannover path fails within
			// minutes on tag 1 rather than hours in. 70v2 is LAST because low capacity
			// means the most services after the merger split (30v2 32,906 vs 50v2 32,817)
			// and by far the largest fleet (~2,800+ vehicles vs ~650-680 at 160+) - the
			// sim-PC's own 70v2 crash dump shows ZHeap used 87.8 GB, which does not fit
			// here. If Step A dies on memory at 70v2, the other 25 tags are already done.
			scenario("basecase", "160v2", LocalDate.of(2025, 5, 13), "160_l"),
			scenario("basecase", "170v2", LocalDate.of(2025, 5, 13), "170_l"),
			scenario("basecase", "180v2", LocalDate.of(2025, 5, 13), "180_l"),
			scenario("basecase", "190v2", LocalDate.of(2025, 5, 13), "190_l"),
			scenario("basecase", "200v2", LocalDate.of(2025, 5, 13), "200_l"),
			scenario("basecase", "210v2", LocalDate.of(2025, 5, 13), "210_l"),
			scenario("basecase", "220v2", LocalDate.of(2025, 5, 13), "220_l"),
			scenario("basecase", "230v2", LocalDate.of(2025, 5, 13), "230_l"),
			scenario("basecase", "240v2", LocalDate.of(2025, 5, 13), "240_l"),
			scenario("basecase", "250v2", LocalDate.of(2025, 5, 13), "250_l"),
			scenario("basecase", "260v2", LocalDate.of(2025, 5, 13), "260_l"),
			scenario("basecase", "270v2", LocalDate.of(2025, 5, 13), "270_l"),
			scenario("basecase", "280v2", LocalDate.of(2025, 5, 13), "280_l"),
			scenario("basecase", "290v2", LocalDate.of(2025, 5, 13), "290_l"),
			scenario("basecase", "300v2", LocalDate.of(2025, 5, 13), "300_l"),
			scenario("basecase", "310v2", LocalDate.of(2025, 5, 13), "310_l"),
			scenario("basecase", "320v2", LocalDate.of(2025, 5, 13), "320_l"),
			scenario("basecase", "330v2", LocalDate.of(2025, 5, 13), "330_l"),
			scenario("basecase", "340v2", LocalDate.of(2025, 5, 13), "340_l"),
			scenario("basecase", "350v2", LocalDate.of(2025, 5, 13), "350_l"),
			scenario("basecase", "360v2", LocalDate.of(2025, 5, 13), "360_l"),
			scenario("basecase", "370v2", LocalDate.of(2025, 5, 13), "370_l"),
			scenario("basecase", "380v2", LocalDate.of(2025, 5, 13), "380_l"),
			scenario("basecase", "390v2", LocalDate.of(2025, 5, 13), "390_l"),
			scenario("basecase", "400v2", LocalDate.of(2025, 5, 13), "400_l"),
			scenario("basecase", "70v2", LocalDate.of(2025, 5, 13), "70_l"),

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
