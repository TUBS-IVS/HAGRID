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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import hagrid.pipeline.ScenarioConfig.DeliveryWindow;
import hagrid.pipeline.ScenarioConfig.VehicleSchedule;

/**
 * Writes scenario run summaries to disk.
 * <p>
 * Creates human-readable configuration summaries for each pipeline run,
 * documenting all relevant settings for reproducibility.
 * </p>
 * 
 * @author HAGRID Team
 */
public final class ScenarioSummaryWriter {

	private static final Logger LOGGER = LogManager.getLogger(ScenarioSummaryWriter.class);

	private static final String SEPARATOR = System.lineSeparator();

	private ScenarioSummaryWriter() {
		// Utility class
	}

	/**
	 * Writes a comprehensive run summary to the output directory.
	 *
	 * @param context the run context containing all necessary information
	 */
	public static void write(RunContext context) {
		Path outputDir = context.getOutputDirectory();
		String runId = context.getRunId();

		try {
			Files.createDirectories(outputDir);
			Path summaryFile = outputDir.resolve(runId + "_scenario_summary.txt");

			StringBuilder sb = new StringBuilder();
			appendHeader(sb, context);
			appendScenarioInfo(sb, context);
			appendPipelineSettings(sb, context);
			appendDeliveryWindows(sb, context);
			appendVehicleProfiles(sb, context);
			appendCacheInfo(sb, context);
			// Pipeline execution statistics (demand, splitting, merging)
			if (context.getPipelineStatistics() != null) {
				appendDemandOverview(sb, context);
				appendDemandByProvider(sb, context);
				appendSplittingInfo(sb, context);
				appendCarrierMerging(sb, context);
				appendFinalCarrierStats(sb, context);
			}

			Files.writeString(summaryFile, sb.toString(), StandardCharsets.UTF_8);
			LOGGER.info("Wrote run configuration summary to {}", summaryFile.toAbsolutePath());
		} catch (Exception e) {
			LOGGER.warn("Could not write run configuration summary for {}: {}", runId, e.getMessage());
		}
	}

	private static void appendHeader(StringBuilder sb, RunContext context) {
		sb.append("HAGRID Demand Pipeline Run Summary").append(SEPARATOR);
		sb.append("===================================").append(SEPARATOR);
		sb.append(SEPARATOR);
	}

	private static void appendScenarioInfo(StringBuilder sb, RunContext context) {
		sb.append("[Scenario Information]").append(SEPARATOR);
		sb.append("  Run ID:          ").append(context.getRunId()).append(SEPARATOR);
		sb.append("  Concept:         ").append(context.getConcept()).append(SEPARATOR);
		sb.append("  Simulation Date: ").append(context.getSimulationDate()).append(SEPARATOR);
		sb.append("  Started At:      ").append(context.getStartedAt()).append(SEPARATOR);
		sb.append("  Filter Regions:  ").append(context.getScenarioConfig().getFilterRegions()).append(SEPARATOR);
		sb.append(SEPARATOR);
	}

	private static void appendPipelineSettings(StringBuilder sb, RunContext context) {
		ScenarioConfig.PipelineSettings settings = context.getScenarioConfig().getPipelineSettings();
		sb.append("[Pipeline Settings]").append(SEPARATOR);
		sb.append("  Service Simplifier: ").append(settings.isApplyServiceSimplifier()).append(SEPARATOR);
		sb.append("  Run Routing:        ").append(settings.isRunRouting()).append(SEPARATOR);
		sb.append("  Caching Enabled:    ").append(settings.isCachingEnabled()).append(SEPARATOR);
		sb.append(SEPARATOR);
	}

	private static void appendDeliveryWindows(StringBuilder sb, RunContext context) {
		Map<String, DeliveryWindow> windows = context.getScenarioConfig().getDeliveryWindows();
		sb.append("[Delivery Windows]").append(SEPARATOR);
		windows.forEach((provider, window) -> {
			sb.append("  ").append(provider).append(": ").append(window).append(SEPARATOR);
		});
		sb.append(SEPARATOR);
	}

	private static void appendVehicleProfiles(StringBuilder sb, RunContext context) {
		ScenarioConfig.VehicleConfig vehicleConfig = context.getScenarioConfig().getVehicleConfig();
		sb.append("[Vehicle Configuration]").append(SEPARATOR);

		Set<String> allProviders = new LinkedHashSet<>();
		allProviders.add("default");
		allProviders.addAll(vehicleConfig.getAllProviders());
		allProviders.addAll(context.getScenarioConfig().getDeliveryWindows().keySet());

		for (String provider : allProviders) {
			sb.append("  ").append(provider).append(":").append(SEPARATOR);

			List<String> sizes = vehicleConfig.getVehicleSizesForProvider(provider);
			sb.append("    Sizes:    ").append(sizes).append(SEPARATOR);

			VehicleSchedule schedule = vehicleConfig.getScheduleForProvider(provider);
			sb.append("    Schedule: ").append(schedule.describe()).append(SEPARATOR);

			DeliveryWindow window = context.getScenarioConfig().getDeliveryWindow(provider);
			if (window != null) {
				List<Integer> dispatchHours = schedule.computeDispatchHours(
						window.getStartHour(), window.getEndHour());
				sb.append("    Dispatch: ").append(dispatchHours).append(SEPARATOR);
			}
		}
		sb.append(SEPARATOR);
	}

	private static void appendCacheInfo(StringBuilder sb, RunContext context) {
		CacheConfig cacheConfig = context.getCacheConfig();
		if (cacheConfig != null) {
			sb.append("[Cache Configuration]").append(SEPARATOR);
			sb.append("  Enabled:   ").append(cacheConfig.isEnabled()).append(SEPARATOR);
			sb.append("  Directory: ").append(cacheConfig.getCacheDirectory()).append(SEPARATOR);
			sb.append("  Run ID:    ").append(cacheConfig.getRunId()).append(SEPARATOR);
			sb.append(SEPARATOR);
		}
	}

	// =========================================================================
	//  Pipeline execution statistics sections
	// =========================================================================

	private static void appendDemandOverview(StringBuilder sb, RunContext context) {
		PipelineStatistics stats = context.getPipelineStatistics();
		if (stats.getTotalParcels() < 0) return; // no data

		sb.append("┌──────────────────────────────────────────────────────────┐").append(SEPARATOR);
		sb.append("│                    DEMAND OVERVIEW                       │").append(SEPARATOR);
		sb.append("└──────────────────────────────────────────────────────────┘").append(SEPARATOR);
		sb.append(SEPARATOR);
		sb.append(String.format(Locale.US, "  Total Delivery Stops:  %,d%n", stats.getTotalDeliveryStops()));
		sb.append(String.format(Locale.US, "  Total Parcels:         %,d%n", stats.getTotalParcels()));
		sb.append(String.format(Locale.US, "    ├─ B2C Parcels:      %,d%n", stats.getTotalB2CParcels()));
		sb.append(String.format(Locale.US, "    ├─ B2B Parcels:      %,d%n", stats.getTotalB2BParcels()));
		sb.append(String.format(Locale.US, "    └─ B2B Ratio:        %.1f %%%n", stats.getB2bParcelRatio() * 100.0));
		sb.append(String.format(Locale.US, "  Average Weight:        %.2f kg%n", stats.getAverageWeight()));
		if (stats.getTotalLockerDeliveries() > 0) {
			sb.append(String.format(Locale.US, "  Locker Deliveries:     %,d (%,d parcels)%n",
					stats.getTotalLockerDeliveries(), stats.getTotalLockerParcels()));
		}
		sb.append(SEPARATOR);
	}

	private static void appendDemandByProvider(StringBuilder sb, RunContext context) {
		PipelineStatistics stats = context.getPipelineStatistics();
		Map<String, PipelineStatistics.ProviderStats> provStats = stats.getProviderStats();
		if (provStats.isEmpty()) return;

		sb.append("┌──────────────────────────────────────────────────────────┐").append(SEPARATOR);
		sb.append("│                   DEMAND BY PROVIDER                     │").append(SEPARATOR);
		sb.append("└──────────────────────────────────────────────────────────┘").append(SEPARATOR);
		sb.append(SEPARATOR);

		// Table header
		sb.append(String.format("  %-10s │ %8s │ %8s │ %8s │ %8s │ %8s%n",
				"Provider", "Carriers", "Services", "Parcels", "B2B", "B2C"));
		sb.append("  ───────────┼──────────┼──────────┼──────────┼──────────┼──────────").append(SEPARATOR);

		int totalCarriers = 0, totalServices = 0, totalParcels = 0, totalB2B = 0, totalB2C = 0;

		for (var entry : provStats.entrySet()) {
			PipelineStatistics.ProviderStats ps = entry.getValue();
			sb.append(String.format(Locale.US, "  %-10s │ %,8d │ %,8d │ %,8d │ %,8d │ %,8d%n",
					entry.getKey(), ps.carrierCount(), ps.serviceCount(),
					ps.totalParcels(), ps.b2bParcels(), ps.b2cParcels()));
			totalCarriers += ps.carrierCount();
			totalServices += ps.serviceCount();
			totalParcels += ps.totalParcels();
			totalB2B += ps.b2bParcels();
			totalB2C += ps.b2cParcels();
		}

		sb.append("  ───────────┼──────────┼──────────┼──────────┼──────────┼──────────").append(SEPARATOR);
		sb.append(String.format(Locale.US, "  %-10s │ %,8d │ %,8d │ %,8d │ %,8d │ %,8d%n",
				"TOTAL", totalCarriers, totalServices, totalParcels, totalB2B, totalB2C));
		sb.append(SEPARATOR);
	}

	private static void appendSplittingInfo(StringBuilder sb, RunContext context) {
		PipelineStatistics stats = context.getPipelineStatistics();
		Map<String, PipelineStatistics.SplitInfo> splits = stats.getSplitDetails();

		sb.append("┌──────────────────────────────────────────────────────────┐").append(SEPARATOR);
		sb.append("│                  DEMAND SPLITTING (KMeans)               │").append(SEPARATOR);
		sb.append("└──────────────────────────────────────────────────────────┘").append(SEPARATOR);
		sb.append(SEPARATOR);
		sb.append(String.format(Locale.US, "  Carrier keys before split: %,d%n", stats.getCarrierKeysBeforeSplit()));
		sb.append(String.format(Locale.US, "  Carrier keys after split:  %,d%n", stats.getCarrierKeysAfterSplit()));
		sb.append(String.format(Locale.US, "  Carriers split:            %,d%n", splits.size()));
		sb.append(SEPARATOR);

		if (!splits.isEmpty()) {
			sb.append("  Split details:").append(SEPARATOR);
			sb.append(String.format("  %-25s │ %10s │ %10s%n", "Original Key", "Deliveries", "Split Into"));
			sb.append("  ──────────────────────────┼────────────┼────────────").append(SEPARATOR);

			for (var entry : splits.entrySet()) {
				PipelineStatistics.SplitInfo si = entry.getValue();
				sb.append(String.format(Locale.US, "  %-25s │ %,10d │ %10d%n",
						si.originalKey(), si.deliveriesBeforeSplit(), si.splitInto()));
			}
			sb.append(SEPARATOR);
		}
	}

	private static void appendCarrierMerging(StringBuilder sb, RunContext context) {
		PipelineStatistics stats = context.getPipelineStatistics();
		CarrierMergeLog mergeLog = stats.getCarrierMergeLog();
		if (mergeLog == null) return;

		sb.append("┌──────────────────────────────────────────────────────────┐").append(SEPARATOR);
		sb.append("│              CARRIER MERGING (Small → Large)             │").append(SEPARATOR);
		sb.append("└──────────────────────────────────────────────────────────┘").append(SEPARATOR);
		sb.append(SEPARATOR);
		sb.append(String.format(Locale.US, "  Merge threshold:           %,d parcels%n", mergeLog.getMergeThreshold()));
		sb.append(String.format(Locale.US, "  Carriers before merge:     %,d%n", mergeLog.getCarriersBeforeMerge()));
		sb.append(String.format(Locale.US, "  Carriers below threshold:  %,d%n", mergeLog.getCarriersBelowThreshold()));
		sb.append(String.format(Locale.US, "  Carriers after merge:      %,d%n", mergeLog.getCarriersAfterMerge()));
		sb.append(String.format(Locale.US, "  Total merge operations:    %,d%n", mergeLog.getEntries().size()));
		sb.append(SEPARATOR);

		if (!mergeLog.getEntries().isEmpty()) {
			sb.append("  Merge details:").append(SEPARATOR);
			sb.append(String.format("  %-26s │ %-26s │ %5s │ %6s │ %6s │ %6s │ %-14s%n",
					"Source (dissolved)", "Target (absorbed)",
					"Svc-S", "Pcl-S", "Tgt-b", "Tgt-a", "Type"));
			sb.append("  ──────────────────────────┼────────────────────────────┼───────┼────────┼────────┼────────┼──────────────").append(SEPARATOR);

			for (CarrierMergeLog.MergeEntry e : mergeLog.getEntries()) {
				String srcId = truncate(e.sourceCarrierId(), 26);
				String tgtId = truncate(e.targetCarrierId(), 26);
				String type = e.iteration() > 0
						? e.mergeType() + "[" + e.iteration() + "]"
						: e.mergeType();
				sb.append(String.format(Locale.US, "  %-26s │ %-26s │ %,5d │ %,6d │ %,6d │ %,6d │ %-14s%n",
						srcId, tgtId,
						e.sourceServices(), e.sourceParcels(),
						e.targetParcelsBefore(), e.targetParcelsAfter(),
						type));
			}
			sb.append(SEPARATOR);

			// Summary by merge type
			var byType = mergeLog.getEntries().stream()
					.collect(java.util.stream.Collectors.groupingBy(
							CarrierMergeLog.MergeEntry::mergeType,
							java.util.LinkedHashMap::new,
							java.util.stream.Collectors.toList()));
			sb.append("  Summary by merge type:").append(SEPARATOR);
			for (var entry : byType.entrySet()) {
				int count = entry.getValue().size();
				int parcels = entry.getValue().stream().mapToInt(CarrierMergeLog.MergeEntry::sourceParcels).sum();
				sb.append(String.format(Locale.US, "    %-20s  %,3d merges  (%,d parcels moved)%n",
						entry.getKey(), count, parcels));
			}
			sb.append(SEPARATOR);
		}
	}

	private static String truncate(String s, int maxLen) {
		return s.length() <= maxLen ? s : s.substring(0, maxLen - 2) + "..";
	}

	private static void appendFinalCarrierStats(StringBuilder sb, RunContext context) {
		PipelineStatistics stats = context.getPipelineStatistics();

		sb.append("┌──────────────────────────────────────────────────────────┐").append(SEPARATOR);
		sb.append("│                 FINAL CARRIER STATISTICS                 │").append(SEPARATOR);
		sb.append("└──────────────────────────────────────────────────────────┘").append(SEPARATOR);
		sb.append(SEPARATOR);
		sb.append(String.format(Locale.US, "  Total Carriers:            %,d%n", stats.getFinalCarrierCount()));
		sb.append(String.format(Locale.US, "  Total Services (stops):    %,d%n", stats.getFinalServiceCount()));
		sb.append(String.format(Locale.US, "  Total Capacity (parcels):  %,d%n", stats.getTotalCapacity()));
		sb.append(SEPARATOR);
		sb.append("  Services per Carrier:").append(SEPARATOR);
		sb.append(String.format(Locale.US, "    ├─ Min:     %,d%n", stats.getMinServicesPerCarrier()));
		sb.append(String.format(Locale.US, "    ├─ Max:     %,d%n", stats.getMaxServicesPerCarrier()));
		sb.append(String.format(Locale.US, "    └─ Average: %.1f%n", stats.getAvgServicesPerCarrier()));
		sb.append(SEPARATOR);
	}

	// =========================================================================
	// Run Context
	// =========================================================================

	/**
	 * Context containing all information needed to write a run summary.
	 */
	public static final class RunContext {

		private final String runId;
		private final String concept;
		private final LocalDate simulationDate;
		private final LocalDateTime startedAt;
		private final Path outputDirectory;
		private final ScenarioConfig scenarioConfig;
		private final CacheConfig cacheConfig;
		private final PipelineStatistics pipelineStatistics;

		private RunContext(Builder builder) {
			this.runId = builder.runId;
			this.concept = builder.concept;
			this.simulationDate = builder.simulationDate;
			this.startedAt = builder.startedAt;
			this.outputDirectory = builder.outputDirectory;
			this.scenarioConfig = builder.scenarioConfig;
			this.cacheConfig = builder.cacheConfig;
			this.pipelineStatistics = builder.pipelineStatistics;
		}

		public static Builder builder() {
			return new Builder();
		}

		public String getRunId() {
			return runId;
		}

		public String getConcept() {
			return concept;
		}

		public LocalDate getSimulationDate() {
			return simulationDate;
		}

		public LocalDateTime getStartedAt() {
			return startedAt;
		}

		public Path getOutputDirectory() {
			return outputDirectory;
		}

		public ScenarioConfig getScenarioConfig() {
			return scenarioConfig;
		}

		public CacheConfig getCacheConfig() {
			return cacheConfig;
		}

		public PipelineStatistics getPipelineStatistics() {
			return pipelineStatistics;
		}

		public static final class Builder {

			private String runId;
			private String concept;
			private LocalDate simulationDate;
			private LocalDateTime startedAt;
			private Path outputDirectory;
			private ScenarioConfig scenarioConfig;
			private CacheConfig cacheConfig;
			private PipelineStatistics pipelineStatistics;

			private Builder() {
			}

			public Builder runId(String runId) {
				this.runId = runId;
				return this;
			}

			public Builder concept(String concept) {
				this.concept = concept;
				return this;
			}

			public Builder simulationDate(LocalDate date) {
				this.simulationDate = date;
				return this;
			}

			public Builder startedAt(LocalDateTime startedAt) {
				this.startedAt = startedAt;
				return this;
			}

			public Builder outputDirectory(Path outputDirectory) {
				this.outputDirectory = outputDirectory;
				return this;
			}

			public Builder scenarioConfig(ScenarioConfig config) {
				this.scenarioConfig = config;
				return this;
			}

			public Builder cacheConfig(CacheConfig config) {
				this.cacheConfig = config;
				return this;
			}

			public Builder pipelineStatistics(PipelineStatistics stats) {
				this.pipelineStatistics = stats;
				return this;
			}

			public RunContext build() {
				return new RunContext(this);
			}
		}
	}
}
