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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import hagrid.pipeline.ScenarioConfig;
import hagrid.pipeline.ScenarioRunner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HAGRID Pipeline API - Main entry point for generating MATSim input files.
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * HAGRID.run(scenarios);
 * }</pre>
 */
public final class HAGRID {

	// Set hagrid.log.dir BEFORE Log4j2 initializes (static fields are evaluated top-down)
	static {
		if (System.getProperty("hagrid.log.dir") == null) {
			try {
				Path logDir = Path.of("hagrid-output", "logs");
				Files.createDirectories(logDir);
				System.setProperty("hagrid.log.dir", logDir.toAbsolutePath().toString());
			} catch (Exception ignored) {
				// fallback: let log4j2.xml default handle it
			}
		}
	}

	private static final Logger LOGGER = LogManager.getLogger(HAGRID.class);

	private HAGRID() {} // Utility class

	/**
	 * Generates MATSim carrier and vehicle input files for the given scenarios.
	 */
	public static void run(ScenarioConfig... scenarios) {
		LOGGER.info("");
		LOGGER.info("================================================================");
		LOGGER.info("      _    _          _____  _____ _____  _____                 ");
		LOGGER.info("     | |  | |   /\\   / ____|  __ \\_   _||  __ \\               ");
		LOGGER.info("     | |__| |  /  \\ | |  __| |__) || |  | |  | |              ");
		LOGGER.info("     |  __  | / /\\ \\| | |_ |  _  / | |  | |  | |              ");
		LOGGER.info("     | |  | |/ ____ \\ |__| | | \\ \\_| |_ | |__| |              ");
		LOGGER.info("     |_|  |_/_/    \\_\\_____|_|  \\_\\_____||_____/              ");
		LOGGER.info("                                                                ");
		LOGGER.info("            MATSim Freight Input Generator                      ");
		LOGGER.info("================================================================");
		LOGGER.info("  Scenarios to process: {}", scenarios.length);
		LOGGER.info("================================================================");

		int successful = 0;
		int failed = 0;

		for (int i = 0; i < scenarios.length; i++) {
			ScenarioConfig config = scenarios[i];
			LOGGER.info("");
			LOGGER.info("----------------------------------------------------------------");
			LOGGER.info("  Scenario {}/{}: {} | {} | Vehicles: {}", 
					i + 1, scenarios.length,
					config.getConcepts().get(0),
					config.getDates().get(0),
					config.getVehicleConfig().getDefaultVehicleSizes());
			LOGGER.info("----------------------------------------------------------------");

			try {
				new ScenarioRunner(config).runAll();
				successful++;
				LOGGER.info("  [OK] Scenario completed successfully");
			} catch (Exception e) {
				failed++;
				LOGGER.error("  [FAILED] Scenario failed: {}", e.getMessage(), e);
			}
		}

		LOGGER.info("");
		LOGGER.info("================================================================");
		LOGGER.info("                    HAGRID Complete                             ");
		LOGGER.info("================================================================");
		LOGGER.info("  Successful: {}  |  Failed: {}", successful, failed);
		LOGGER.info("================================================================");
		LOGGER.info("");
	}
}
