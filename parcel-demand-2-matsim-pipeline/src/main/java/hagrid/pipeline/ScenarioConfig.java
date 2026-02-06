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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Clean, user-friendly scenario configuration for the HAGRID demand pipeline.
 * <p>
 * This class provides a fluent builder API for configuring simulation scenarios.
 * All configuration is done through method chaining, making the code readable
 * and easy to understand.
 * </p>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * ScenarioConfig config = ScenarioConfig.builder()
 *     .concepts("basecase", "batchHigh")
 *     .dates(LocalDate.of(2025, 5, 13), LocalDate.of(2025, 5, 14))
 *     .filterRegions("Hannover")
 *     .vehicleSizes("m", "l")
 *     .vehicleSchedule(VehicleSchedule.SIMPLE_STAGGERED)
 *     .deliveryWindow(7, 14)
 *     .build();
 * }</pre>
 * 
 * @author HAGRID Team
 */
public final class ScenarioConfig {

	private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("ddMMyyyy");

	// Core scenario settings
	private final List<String> concepts;
	private final List<LocalDate> dates;
	private final String filterRegions;

	// Pipeline settings
	private final PipelineSettings pipelineSettings;

	// Vehicle configuration
	private final VehicleConfig vehicleConfig;

	// Delivery windows per provider
	private final Map<String, DeliveryWindow> deliveryWindows;

	private ScenarioConfig(Builder builder) {
		this.concepts = Collections.unmodifiableList(new ArrayList<>(builder.concepts));
		this.dates = Collections.unmodifiableList(new ArrayList<>(builder.dates));
		this.filterRegions = builder.filterRegions;
		this.pipelineSettings = new PipelineSettings(builder);
		this.vehicleConfig = new VehicleConfig(builder);
		this.deliveryWindows = Collections.unmodifiableMap(new LinkedHashMap<>(builder.deliveryWindows));
	}

	/**
	 * Creates a new builder for ScenarioConfig.
	 *
	 * @return a new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	// -------------------------------------------------------------------------
	// Core Scenario Accessors
	// -------------------------------------------------------------------------

	/**
	 * @return the list of concepts to simulate
	 */
	public List<String> getConcepts() {
		return concepts;
	}

	/**
	 * @return the list of simulation dates
	 */
	public List<LocalDate> getDates() {
		return dates;
	}

	/**
	 * @return the filter regions string
	 */
	public String getFilterRegions() {
		return filterRegions;
	}

	/**
	 * Generates a run ID for a given concept and date.
	 *
	 * @param concept the concept name
	 * @param date    the simulation date
	 * @return the run ID string
	 */
	public String createRunId(String concept, LocalDate date) {
		return concept.toUpperCase() + "_" + date.format(RUN_ID_FORMAT);
	}

	// -------------------------------------------------------------------------
	// Pipeline Settings Accessors
	// -------------------------------------------------------------------------

	public PipelineSettings getPipelineSettings() {
		return pipelineSettings;
	}

	// -------------------------------------------------------------------------
	// Vehicle Configuration Accessors
	// -------------------------------------------------------------------------

	public VehicleConfig getVehicleConfig() {
		return vehicleConfig;
	}

	// -------------------------------------------------------------------------
	// Delivery Window Accessors
	// -------------------------------------------------------------------------

	public Map<String, DeliveryWindow> getDeliveryWindows() {
		return deliveryWindows;
	}

	public DeliveryWindow getDeliveryWindow(String provider) {
		String key = normalizeProviderKey(provider);
		return deliveryWindows.getOrDefault(key, deliveryWindows.get("default"));
	}

	// -------------------------------------------------------------------------
	// Helper Methods
	// -------------------------------------------------------------------------

	private static String normalizeProviderKey(String provider) {
		return Objects.requireNonNull(provider, "provider")
				.trim()
				.toLowerCase(Locale.ROOT);
	}

	// =========================================================================
	// Nested Configuration Classes
	// =========================================================================

	/**
	 * Pipeline execution settings.
	 */
	public static final class PipelineSettings {

		private final boolean applyServiceSimplifier;
		private final boolean runRouting;
		private final boolean enableCaching;
		private final int jspritIterations;

		private PipelineSettings(Builder builder) {
			this.applyServiceSimplifier = builder.applyServiceSimplifier;
			this.runRouting = builder.runRouting;
			this.enableCaching = builder.enableCaching;
			this.jspritIterations = builder.jspritIterations;
		}

		public boolean isApplyServiceSimplifier() {
			return applyServiceSimplifier;
		}

		public boolean isRunRouting() {
			return runRouting;
		}

		public boolean isCachingEnabled() {
			return enableCaching;
		}

		/**
		 * Returns the number of JSprit iterations for routing optimization.
		 * @return number of iterations (default: 1 for initial model)
		 */
		public int getJspritIterations() {
			return jspritIterations;
		}
	}

	/**
	 * Vehicle configuration settings.
	 * <p>
	 * Supports:
	 * <ul>
	 *   <li>Vehicle sizes per provider</li>
	 *   <li>Dispatch schedules (preset or custom hours)</li>
	 *   <li>Time shifts for provider-specific adjustments</li>
	 * </ul>
	 * </p>
	 */
	public static final class VehicleConfig {

		private final List<String> defaultVehicleSizes;
		private final VehicleSchedule defaultSchedule;
		private final Map<String, List<String>> providerVehicleSizes;
		private final Map<String, VehicleSchedule> providerSchedules;
		private final Map<String, List<Integer>> providerCustomDispatchHours;
		private final Map<String, Integer> providerTimeShifts;

		private VehicleConfig(Builder builder) {
			this.defaultVehicleSizes = Collections.unmodifiableList(new ArrayList<>(builder.vehicleSizes));
			this.defaultSchedule = builder.defaultSchedule;
			this.providerVehicleSizes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providerVehicleSizes));
			this.providerSchedules = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providerSchedules));
			this.providerCustomDispatchHours = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providerCustomDispatchHours));
			this.providerTimeShifts = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providerTimeShifts));
		}

		public List<String> getDefaultVehicleSizes() {
			return defaultVehicleSizes;
		}

		public VehicleSchedule getDefaultSchedule() {
			return defaultSchedule;
		}

		public List<String> getVehicleSizesForProvider(String provider) {
			String key = normalizeProviderKey(provider);
			return providerVehicleSizes.getOrDefault(key, defaultVehicleSizes);
		}

		public VehicleSchedule getScheduleForProvider(String provider) {
			String key = normalizeProviderKey(provider);
			return providerSchedules.getOrDefault(key, defaultSchedule);
		}

		/**
		 * Returns custom dispatch hours for a provider, if configured.
		 *
		 * @param provider the provider name
		 * @return custom hours list, or null if using schedule preset
		 */
		public List<Integer> getCustomDispatchHours(String provider) {
			String key = normalizeProviderKey(provider);
			return providerCustomDispatchHours.get(key);
		}

		/**
		 * Returns the time shift in hours for a provider.
		 * Positive values shift dispatch later, negative earlier.
		 *
		 * @param provider the provider name
		 * @return time shift in hours (default 0)
		 */
		public int getTimeShift(String provider) {
			String key = normalizeProviderKey(provider);
			return providerTimeShifts.getOrDefault(key, 0);
		}

		/**
		 * Computes the effective dispatch hours for a provider, considering:
		 * 1. Custom dispatch hours (if set)
		 * 2. Schedule preset + delivery window
		 * 3. Time shift adjustment
		 *
		 * @param provider       the provider name
		 * @param deliveryWindow the delivery window for this provider
		 * @return list of dispatch hours
		 */
		public List<Integer> computeDispatchHours(String provider, DeliveryWindow deliveryWindow) {
			String key = normalizeProviderKey(provider);
			int timeShift = getTimeShift(provider);

			// Check for custom dispatch hours first
			List<Integer> customHours = providerCustomDispatchHours.get(key);
			if (customHours != null && !customHours.isEmpty()) {
				// Apply time shift to custom hours
				return customHours.stream()
						.map(h -> Math.max(0, Math.min(23, h + timeShift)))
						.distinct()
						.sorted()
						.collect(Collectors.toList());
			}

			// Use schedule preset
			VehicleSchedule schedule = getScheduleForProvider(provider);
			List<Integer> hours = schedule.computeDispatchHours(
					deliveryWindow.getStartHour(),
					deliveryWindow.getEndHour());

			// Apply time shift
			if (timeShift != 0) {
				return hours.stream()
						.map(h -> Math.max(0, Math.min(23, h + timeShift)))
						.distinct()
						.sorted()
						.collect(Collectors.toList());
			}

			return hours;
		}

		public Set<String> getAllProviders() {
			Set<String> providers = new LinkedHashSet<>();
			providers.add("default");
			providers.addAll(providerVehicleSizes.keySet());
			providers.addAll(providerSchedules.keySet());
			providers.addAll(providerCustomDispatchHours.keySet());
			providers.addAll(providerTimeShifts.keySet());
			return providers;
		}

		private static String normalizeProviderKey(String provider) {
			return Objects.requireNonNull(provider, "provider")
					.trim()
					.toLowerCase(Locale.ROOT);
		}
	}

	// =========================================================================
	// Vehicle Schedule Options
	// =========================================================================

	/**
	 * Defines vehicle dispatch scheduling strategies.
	 * <p>
	 * These presets determine when vehicles are dispatched within the delivery window.
	 * For custom dispatch hours, use {@link Builder#providerDispatchHours(String, Integer...)}.
	 * </p>
	 */
	public enum VehicleSchedule {

		/**
		 * Standard dispatch at 7:00 and 14:00 (morning and afternoon shifts).
		 * This is the default schedule for most providers.
		 */
		SIMPLE_STAGGERED("SIMPLE_STAGGERED (07:00 and 14:00)") {
			private static final List<Integer> DEFAULT_HOURS = List.of(7, 14);

			@Override
			public List<Integer> computeDispatchHours(int startHour, int endHour) {
				List<Integer> filtered = DEFAULT_HOURS.stream()
						.filter(h -> h >= 0 && h <= 23)
						.filter(h -> h >= startHour && h <= endHour)
						.collect(Collectors.toList());
				if (filtered.isEmpty()) {
					return List.of(Math.max(0, Math.min(23, startHour)));
				}
				return filtered;
			}
		},

		/**
		 * Extended dispatch at 7:00, 11:00, and 14:00 (three shifts).
		 */
		EXTENDED("EXTENDED (07:00, 11:00, and 14:00)") {
			private static final List<Integer> DEFAULT_HOURS = List.of(7, 11, 14);

			@Override
			public List<Integer> computeDispatchHours(int startHour, int endHour) {
				List<Integer> filtered = DEFAULT_HOURS.stream()
						.filter(h -> h >= 0 && h <= 23)
						.filter(h -> h >= startHour && h <= endHour)
						.collect(Collectors.toList());
				if (filtered.isEmpty()) {
					return List.of(Math.max(0, Math.min(23, startHour)));
				}
				return filtered;
			}
		},

		/**
		 * Dispatch every hour within the delivery window (e.g., 8-14 → 8,9,10,11,12,13,14).
		 */
		FULL_WINDOW("FULL_WINDOW (every hour within window)") {
			@Override
			public List<Integer> computeDispatchHours(int startHour, int endHour) {
				if (endHour < startHour) {
					return List.of();
				}
				return IntStream.rangeClosed(Math.max(0, startHour), Math.min(23, endHour))
						.boxed()
						.collect(Collectors.toList());
			}
		},

		/**
		 * Single early dispatch at (startHour - 1).
		 */
		EARLY_ONLY("EARLY_ONLY (one hour before window start)") {
			@Override
			public List<Integer> computeDispatchHours(int startHour, int endHour) {
				if (endHour < startHour) {
					return List.of();
				}
				int early = Math.max(0, startHour - 1);
				return (early <= 23) ? List.of(early) : List.of();
			}
		};

		private final String description;

		VehicleSchedule(String description) {
			this.description = description;
		}

		/**
		 * Computes the dispatch hours for the given delivery window.
		 *
		 * @param startHour the window start hour (0-23)
		 * @param endHour   the window end hour (0-23)
		 * @return list of dispatch hours
		 */
		public abstract List<Integer> computeDispatchHours(int startHour, int endHour);

		/**
		 * @return a human-readable description
		 */
		public String describe() {
			return description;
		}
	}

	// =========================================================================
	// Delivery Window Record
	// =========================================================================

	/**
	 * Represents a time window for deliveries.
	 */
	public static final class DeliveryWindow {

		private final int startHour;
		private final int endHour;

		/**
		 * Creates a delivery window.
		 *
		 * @param startHour start hour (0-23)
		 * @param endHour   end hour (0-23)
		 */
		public DeliveryWindow(int startHour, int endHour) {
			if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
				throw new IllegalArgumentException("Hours must be within [0, 23]");
			}
			if (endHour < startHour) {
				throw new IllegalArgumentException("End hour must be >= start hour");
			}
			this.startHour = startHour;
			this.endHour = endHour;
		}

		public int getStartHour() {
			return startHour;
		}

		public int getEndHour() {
			return endHour;
		}

		@Override
		public String toString() {
			return String.format("%02d:00-%02d:00", startHour, endHour);
		}
	}

	// =========================================================================
	// Builder
	// =========================================================================

	/**
	 * Builder for constructing ScenarioConfig instances.
	 */
	public static final class Builder {

		// Core settings
		private final List<String> concepts = new ArrayList<>();
		private final List<LocalDate> dates = new ArrayList<>();
		private String filterRegions = "Hannover";

		// Pipeline settings
		private boolean applyServiceSimplifier = false;
		private boolean runRouting = false;
		private boolean enableCaching = true;
		private int jspritIterations = 1;

		// Vehicle configuration
		private final List<String> vehicleSizes = new ArrayList<>();
		private VehicleSchedule defaultSchedule = VehicleSchedule.SIMPLE_STAGGERED;
		private final Map<String, List<String>> providerVehicleSizes = new LinkedHashMap<>();
		private final Map<String, VehicleSchedule> providerSchedules = new LinkedHashMap<>();
		private final Map<String, List<Integer>> providerCustomDispatchHours = new LinkedHashMap<>();
		private final Map<String, Integer> providerTimeShifts = new LinkedHashMap<>();

		// Delivery windows
		private final Map<String, DeliveryWindow> deliveryWindows = new LinkedHashMap<>();

		private Builder() {
			// Set defaults
			concepts.add("basecase");
			dates.add(LocalDate.of(2025, 5, 13));
			vehicleSizes.add("m");
			vehicleSizes.add("l");
			deliveryWindows.put("default", new DeliveryWindow(7, 14));
		}

		// ---------------------------------------------------------------------
		// Core Scenario Configuration
		// ---------------------------------------------------------------------

		/**
		 * Sets the concepts to simulate.
		 *
		 * @param concepts one or more concept names
		 * @return this builder
		 */
		public Builder concepts(String... concepts) {
			return concepts(List.of(concepts));
		}

		/**
		 * Sets the concepts to simulate.
		 *
		 * @param concepts list of concept names
		 * @return this builder
		 */
		public Builder concepts(List<String> concepts) {
			Objects.requireNonNull(concepts, "concepts");
			if (concepts.isEmpty()) {
				throw new IllegalArgumentException("At least one concept required");
			}
			this.concepts.clear();
			concepts.stream()
					.map(c -> Objects.requireNonNull(c, "concept").trim().toLowerCase(Locale.ROOT))
					.filter(c -> !c.isEmpty())
					.forEach(this.concepts::add);
			return this;
		}

		/**
		 * Sets the simulation dates.
		 *
		 * @param dates one or more dates
		 * @return this builder
		 */
		public Builder dates(LocalDate... dates) {
			return dates(List.of(dates));
		}

		/**
		 * Sets the simulation dates.
		 *
		 * @param dates list of dates
		 * @return this builder
		 */
		public Builder dates(List<LocalDate> dates) {
			Objects.requireNonNull(dates, "dates");
			if (dates.isEmpty()) {
				throw new IllegalArgumentException("At least one date required");
			}
			this.dates.clear();
			dates.forEach(d -> this.dates.add(Objects.requireNonNull(d, "date")));
			return this;
		}

		/**
		 * Sets the region filter.
		 *
		 * @param regions filter regions string
		 * @return this builder
		 */
		public Builder filterRegions(String regions) {
			this.filterRegions = Objects.requireNonNull(regions, "filterRegions").trim();
			return this;
		}

		// ---------------------------------------------------------------------
		// Pipeline Settings
		// ---------------------------------------------------------------------

		/**
		 * Enables or disables the service simplifier.
		 *
		 * @param apply true to apply
		 * @return this builder
		 */
		public Builder applyServiceSimplifier(boolean apply) {
			this.applyServiceSimplifier = apply;
			return this;
		}

		/**
		 * Enables or disables routing after demand generation.
		 *
		 * @param run true to run routing
		 * @return this builder
		 */
		public Builder runRouting(boolean run) {
			this.runRouting = run;
			return this;
		}

		/**
		 * Enables or disables caching.
		 *
		 * @param enable true to enable
		 * @return this builder
		 */
		public Builder enableCaching(boolean enable) {
			this.enableCaching = enable;
			return this;
		}

		/**
		 * Sets the number of JSprit iterations for routing optimization.
		 * <p>
		 * Higher values improve solution quality but increase computation time.
		 * Use 1 for initial/quick model, 20-50 for production runs.
		 * </p>
		 *
		 * @param iterations number of iterations (default: 1)
		 * @return this builder
		 */
		public Builder jspritIterations(int iterations) {
			this.jspritIterations = Math.max(1, iterations);
			return this;
		}

		// ---------------------------------------------------------------------
		// Vehicle Configuration
		// ---------------------------------------------------------------------

		/**
		 * Sets the default vehicle sizes for all providers.
		 * <p>
		 * Available options:
		 * <ul>
		 *   <li><b>"m"</b> → Medium van (ct_cep_size_m): capacity 165, uses XML costs</li>
		 *   <li><b>"l"</b> → Large van (ct_cep_size_l): capacity 230, uses XML costs</li>
		 *   <li><b>"bike"</b> → Cargo bike (ct_cep_bike): capacity 23, uses XML costs</li>
		 *   <li><b>"capacity_type"</b> (e.g., "60_m", "100_l", "50_bike") → Custom capacity 
		 *       with explicit template selection. The type ID will be "ct_cep_60_m" etc.</li>
		 *   <li><b>Numeric only (e.g., "80")</b> → Custom capacity, auto-selects "m" (≤165) or "l" (>165)</li>
		 * </ul>
		 * Default if not set: "m", "l" (both medium and large vans with original capacities).
		 * </p>
		 *
		 * @param sizes vehicle size codes (e.g., "m", "l", "60_m", "100_l")
		 * @return this builder
		 */
		public Builder vehicleSizes(String... sizes) {
			return vehicleSizes(List.of(sizes));
		}

		/**
		 * Sets the default vehicle sizes for all providers.
		 * <p>
		 * See {@link #vehicleSizes(String...)} for available options.
		 * </p>
		 *
		 * @param sizes list of vehicle size codes
		 * @return this builder
		 */
		public Builder vehicleSizes(List<String> sizes) {
			Objects.requireNonNull(sizes, "vehicleSizes");
			if (sizes.isEmpty()) {
				throw new IllegalArgumentException("At least one vehicle size required");
			}
			this.vehicleSizes.clear();
			sizes.stream()
					.map(s -> Objects.requireNonNull(s, "size").trim().toLowerCase(Locale.ROOT))
					.filter(s -> !s.isEmpty())
					.forEach(this.vehicleSizes::add);
			return this;
		}

		/**
		 * Sets the default vehicle schedule.
		 *
		 * @param schedule the schedule option
		 * @return this builder
		 */
		public Builder vehicleSchedule(VehicleSchedule schedule) {
			this.defaultSchedule = Objects.requireNonNull(schedule, "schedule");
			return this;
		}

		/**
		 * Sets vehicle sizes for a specific provider.
		 *
		 * @param provider the provider name
		 * @param sizes    the vehicle sizes
		 * @return this builder
		 */
		public Builder providerVehicleSizes(String provider, String... sizes) {
			return providerVehicleSizes(provider, List.of(sizes));
		}

		/**
		 * Sets vehicle sizes for a specific provider.
		 *
		 * @param provider the provider name
		 * @param sizes    list of vehicle sizes
		 * @return this builder
		 */
		public Builder providerVehicleSizes(String provider, List<String> sizes) {
			String key = normalizeProviderKey(provider);
			if (sizes == null || sizes.isEmpty()) {
				providerVehicleSizes.remove(key);
			} else {
				List<String> normalized = sizes.stream()
						.map(s -> s.trim().toLowerCase(Locale.ROOT))
						.collect(Collectors.toList());
				providerVehicleSizes.put(key, normalized);
			}
			return this;
		}

		/**
		 * Sets the vehicle schedule for a specific provider.
		 *
		 * @param provider the provider name
		 * @param schedule the schedule option
		 * @return this builder
		 */
		public Builder providerSchedule(String provider, VehicleSchedule schedule) {
			String key = normalizeProviderKey(provider);
			if (schedule == null) {
				providerSchedules.remove(key);
			} else {
				providerSchedules.put(key, schedule);
			}
			// Remove custom hours when setting a schedule preset
			providerCustomDispatchHours.remove(key);
			return this;
		}

		/**
		 * Sets custom dispatch hours for a specific provider.
		 * <p>
		 * This overrides any schedule preset for this provider.
		 * Use this for complete control over when vehicles are dispatched.
		 * </p>
		 *
		 * @param provider the provider name
		 * @param hours    the dispatch hours (e.g., 7, 11, 14)
		 * @return this builder
		 */
		public Builder providerDispatchHours(String provider, Integer... hours) {
			return providerDispatchHours(provider, List.of(hours));
		}

		/**
		 * Sets custom dispatch hours for a specific provider.
		 *
		 * @param provider the provider name
		 * @param hours    list of dispatch hours
		 * @return this builder
		 */
		public Builder providerDispatchHours(String provider, List<Integer> hours) {
			String key = normalizeProviderKey(provider);
			if (hours == null || hours.isEmpty()) {
				providerCustomDispatchHours.remove(key);
			} else {
				List<Integer> validated = hours.stream()
						.filter(h -> h >= 0 && h <= 23)
						.distinct()
						.sorted()
						.collect(Collectors.toList());
				providerCustomDispatchHours.put(key, validated);
			}
			return this;
		}

		/**
		 * Sets a time shift for a specific provider.
		 * <p>
		 * The time shift is applied to all dispatch hours (both preset and custom).
		 * Positive values shift later, negative values shift earlier.
		 * </p>
		 *
		 * @param provider  the provider name
		 * @param hourShift hours to shift (e.g., +1 for one hour later)
		 * @return this builder
		 */
		public Builder providerTimeShift(String provider, int hourShift) {
			String key = normalizeProviderKey(provider);
			if (hourShift == 0) {
				providerTimeShifts.remove(key);
			} else {
				providerTimeShifts.put(key, hourShift);
			}
			return this;
		}

		// ---------------------------------------------------------------------
		// Delivery Window Configuration
		// ---------------------------------------------------------------------

		/**
		 * Sets the default delivery window.
		 *
		 * @param startHour window start (0-23)
		 * @param endHour   window end (0-23)
		 * @return this builder
		 */
		public Builder deliveryWindow(int startHour, int endHour) {
			return deliveryWindow("default", startHour, endHour);
		}

		/**
		 * Sets a delivery window for a specific provider.
		 *
		 * @param provider  the provider name
		 * @param startHour window start (0-23)
		 * @param endHour   window end (0-23)
		 * @return this builder
		 */
		public Builder deliveryWindow(String provider, int startHour, int endHour) {
			String key = normalizeProviderKey(provider);
			deliveryWindows.put(key, new DeliveryWindow(startHour, endHour));
			return this;
		}

		// ---------------------------------------------------------------------
		// Build
		// ---------------------------------------------------------------------

		/**
		 * Builds the ScenarioConfig.
		 *
		 * @return a new ScenarioConfig instance
		 */
		public ScenarioConfig build() {
			validate();
			return new ScenarioConfig(this);
		}

		private void validate() {
			if (concepts.isEmpty()) {
				throw new IllegalStateException("No concepts configured");
			}
			if (dates.isEmpty()) {
				throw new IllegalStateException("No dates configured");
			}
			if (vehicleSizes.isEmpty()) {
				throw new IllegalStateException("No vehicle sizes configured");
			}
			if (!deliveryWindows.containsKey("default")) {
				deliveryWindows.put("default", new DeliveryWindow(7, 14));
			}
		}

		private static String normalizeProviderKey(String provider) {
			return Objects.requireNonNull(provider, "provider")
					.trim()
					.toLowerCase(Locale.ROOT);
		}
	}
}
