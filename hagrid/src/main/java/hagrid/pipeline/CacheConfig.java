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
import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Centralized cache configuration for the HAGRID pipeline.
 * <p>
 * This class replaces scattered System.setProperty calls with a unified,
 * type-safe configuration object that can be passed through the pipeline.
 * All cache-related settings are managed here for consistency and clarity.
 * </p>
 * 
 * @author HAGRID Team
 */
public final class CacheConfig {

	private static final Logger LOGGER = LogManager.getLogger(CacheConfig.class);

	/** Singleton instance holder for global access when needed. */
	private static volatile CacheConfig globalInstance;

	private final boolean enabled;
	private final Path cacheDirectory;
	private final String runId;

	private CacheConfig(Builder builder) {
		this.enabled = builder.enabled;
		this.cacheDirectory = builder.cacheDirectory;
		this.runId = builder.runId;
	}

	/**
	 * Creates a new builder for constructing a CacheConfig.
	 *
	 * @return a new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Gets the global cache configuration instance.
	 * This replaces the old System.getProperty approach.
	 *
	 * @return the global CacheConfig, or empty if not set
	 */
	public static Optional<CacheConfig> getGlobal() {
		return Optional.ofNullable(globalInstance);
	}

	/**
	 * Sets the global cache configuration instance.
	 * This replaces the old System.setProperty approach.
	 *
	 * @param config the configuration to set globally
	 */
	public static void setGlobal(CacheConfig config) {
		globalInstance = config;
		if (config != null) {
			LOGGER.info("Global cache configuration set: enabled={}, dir={}, runId={}",
					config.enabled, config.cacheDirectory, config.runId);
		}
	}

	/**
	 * Clears the global cache configuration.
	 */
	public static void clearGlobal() {
		globalInstance = null;
		LOGGER.debug("Global cache configuration cleared");
	}

	/**
	 * @return true if caching is enabled
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * @return the cache directory path
	 */
	public Path getCacheDirectory() {
		return cacheDirectory;
	}

	/**
	 * @return the run identifier for cache naming
	 */
	public String getRunId() {
		return runId;
	}

	/**
	 * Ensures the cache directory exists, creating it if necessary.
	 *
	 * @return true if the directory exists or was created successfully
	 */
	public boolean ensureDirectoryExists() {
		if (cacheDirectory == null) {
			LOGGER.warn("Cache directory is null, cannot ensure existence");
			return false;
		}
		try {
			Files.createDirectories(cacheDirectory);
			LOGGER.debug("Cache directory ensured: {}", cacheDirectory.toAbsolutePath());
			return true;
		} catch (Exception e) {
			LOGGER.warn("Could not create cache directory {}: {}", cacheDirectory, e.getMessage());
			return false;
		}
	}

	@Override
	public String toString() {
		return String.format("CacheConfig[enabled=%s, dir=%s, runId=%s]",
				enabled, cacheDirectory, runId);
	}

	/**
	 * Builder for constructing CacheConfig instances.
	 */
	public static final class Builder {

		private boolean enabled = true;
		private Path cacheDirectory;
		private String runId;

		private Builder() {
		}

		/**
		 * Sets whether caching is enabled.
		 *
		 * @param enabled true to enable caching
		 * @return this builder
		 */
		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		/**
		 * Sets the cache directory path.
		 *
		 * @param directory the cache directory
		 * @return this builder
		 */
		public Builder cacheDirectory(Path directory) {
			this.cacheDirectory = directory;
			return this;
		}

		/**
		 * Sets the run identifier.
		 *
		 * @param runId the run identifier
		 * @return this builder
		 */
		public Builder runId(String runId) {
			this.runId = Objects.requireNonNull(runId, "runId must not be null");
			return this;
		}

		/**
		 * Creates a cache configuration for a specific run.
		 * Convenience method that sets both cacheDirectory and runId.
		 *
		 * @param baseDirectory the base cache directory
		 * @param runId         the run identifier
		 * @return this builder
		 */
		public Builder forRun(Path baseDirectory, String runId) {
			this.runId = Objects.requireNonNull(runId, "runId must not be null");
			this.cacheDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory must not be null")
					.resolve(runId);
			return this;
		}

		/**
		 * Builds the CacheConfig instance.
		 *
		 * @return a new CacheConfig
		 */
		public CacheConfig build() {
			if (enabled && cacheDirectory == null) {
				throw new IllegalStateException("Cache directory must be set when caching is enabled");
			}
			return new CacheConfig(this);
		}

		/**
		 * Builds the CacheConfig and sets it as the global instance.
		 *
		 * @return the newly created CacheConfig
		 */
		public CacheConfig buildAndSetGlobal() {
			CacheConfig config = build();
			CacheConfig.setGlobal(config);
			return config;
		}
	}
}
