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

import java.nio.file.Path;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Manages per-run logging for the HAGRID pipeline.
 * <p>
 * This class handles the dynamic attachment and detachment of file appenders
 * for individual pipeline runs, ensuring each run has its own log file.
 * </p>
 * 
 * @author HAGRID Team
 */
public final class PipelineLogger {

	private static final Logger LOGGER = LogManager.getLogger(PipelineLogger.class);

	private static final String LOG_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n";

	private final String appenderName;
	private final Path logFilePath;

	private PipelineLogger(String appenderName, Path logFilePath) {
		this.appenderName = appenderName;
		this.logFilePath = logFilePath;
	}

	/**
	 * Creates and attaches a per-run file appender.
	 *
	 * @param logFilePath the path to the log file
	 * @return a PipelineLogger instance that can be used to detach the appender
	 */
	public static PipelineLogger attach(Path logFilePath) {
		String name = "PerRunFileAppender_" + System.nanoTime();

		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();

		PatternLayout layout = PatternLayout.newBuilder()
				.withConfiguration(config)
				.withPattern(LOG_PATTERN)
				.build();

		FileAppender appender = FileAppender.newBuilder()
				.setName(name)
				.withFileName(logFilePath.toString())
				.withAppend(true)
				.withLocking(false)
				.setLayout(layout)
				.setConfiguration(config)
				.build();
		appender.start();

		config.addAppender(appender);
		LoggerConfig root = config.getRootLogger();
		root.addAppender(appender, Level.INFO, null);
		ctx.updateLoggers();

		LOGGER.info("Attached per-run file logger to {}", logFilePath);

		return new PipelineLogger(name, logFilePath);
	}

	/**
	 * Detaches this logger's file appender from the logging system.
	 */
	public void detach() {
		try {
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			Configuration config = ctx.getConfiguration();
			LoggerConfig root = config.getRootLogger();
			root.removeAppender(appenderName);

			if (config.getAppenders().containsKey(appenderName)) {
				config.getAppenders().get(appenderName).stop();
				config.getAppenders().remove(appenderName);
			}

			ctx.updateLoggers();
			LOGGER.debug("Detached per-run file logger: {}", appenderName);
		} catch (Exception e) {
			LOGGER.warn("Could not detach per-run file appender {}: {}", appenderName, e.getMessage());
		}
	}

	/**
	 * @return the name of the appender
	 */
	public String getAppenderName() {
		return appenderName;
	}

	/**
	 * @return the path to the log file
	 */
	public Path getLogFilePath() {
		return logFilePath;
	}
}
