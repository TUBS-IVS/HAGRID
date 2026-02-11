"""
HAGRID Output Analysis
=======================

A reusable package for analysing MATSim-based parcel delivery
simulation results.  Covers event parsing, vehicle statistics, cost
modelling, EV fleet assignment, and spatially-disaggregated emissions.

Quick start
-----------
>>> from hagrid_output_analysis import (
...     ReferenceData, RunConfig, process_single_run,
... )
>>> cfg = RunConfig(
...     event_file="sim/output_events.xml.gz",
...     carrier_file="sim/output_carriers.xml.gz",
...     vehicle_types_file="sim/output_carriersVehicleTypes.xml.gz",
...     network_path="sim/output_network.xml.gz",
...     regionclusters_path="input/regionclusters.pkl",
...     networkplus_path="input/networkplus.pkl",
...     plz_areas_csv="input/plz_areas.csv",
... )
>>> ref = ReferenceData.from_config(cfg)
>>> results = process_single_run(cfg, ref)
"""

from __future__ import annotations

# -- config ----------------------------------------------------------
from hagrid_output_analysis.config import (
    AREA_TYPE_LABELS,
    CATEGORY_COLOR_DICT,
    CATEGORY_COLOR_DICT_AGG,
    DAYEND,
    DEFAULT_RUN_CONFIG,
    EMISSION_CO2_gpkm,
    EV_CLASSES,
    GLOBAL_EV_TARGET,
    LOW_UTIL_THRESHOLD,
    PLZ_LIST,
    SIM_DATE,
    VEHICLE_CAPACITY_BY_SIZE,
    VEHICLE_SIZE_MAPPING,
    AreaType,
    RunConfig,
    get_ambient_temp,
    get_season_mult,
)

# -- models ----------------------------------------------------------
from hagrid_output_analysis.models import (
    Carrier,
    Plan,
    Service,
    Vehicle,
)

# -- utils -----------------------------------------------------------
from hagrid_output_analysis.utils import (
    area_type_group,
    area_type_name,
    classify_vehicle,
    extract_provider,
    extract_veh_size,
    format_runtime,
    is_in_hannover,
)

# -- reference -------------------------------------------------------
from hagrid_output_analysis.reference import ReferenceData

# -- parsers ---------------------------------------------------------
from hagrid_output_analysis.parsers import (
    build_plot_data,
    merge_link_counts_with_network,
    parse_carriers_from_xml,
    parse_events,
    parse_vehicle_types,
)

# -- analysis --------------------------------------------------------
from hagrid_output_analysis.analysis import (
    KPI_STRUCTURE,
    add_vehicle_demand_to_result,
    attach_service_times,
    build_kpi_summary,
    build_vehicle_status_matrix,
    calculate_costs,
    compute_derived_kpis,
    enrich_vehicle_spatial,
    process_vehicle_data,
    vehicle_stats,
)

# -- ev model --------------------------------------------------------
from hagrid_output_analysis.ev_model import (
    assign_ev_flags,
    build_base_class_map,
    build_effective_class_map,
    compute_ev_counts,
    ev_sanity_report,
)

# -- emissions -------------------------------------------------------
from hagrid_output_analysis.emissions import (
    TIME_INTERVALS,
    build_vehicle_emissions_column,
    build_wide,
    build_wide_pollutant,
    calc_drive_emissions,
    calc_wtt_from_energy_g_per_km,
    cold_start_emissions,
    emissions_by_area_type,
    engine_on_share,
    filter_low_utilisation,
    idle_emissions,
    network_totals,
    run_emissions_loop,
    to_long,
    ttw_gpkm,
)

# -- batch -----------------------------------------------------------
from hagrid_output_analysis.batch import (
    discover_runs,
    process_single_run,
    run_batch,
)


__all__ = [
    # Config
    "AreaType", "RunConfig", "DEFAULT_RUN_CONFIG",
    "DAYEND", "SIM_DATE", "PLZ_LIST",
    "EMISSION_CO2_gpkm", "VEHICLE_SIZE_MAPPING",
    "AREA_TYPE_LABELS", "CATEGORY_COLOR_DICT", "CATEGORY_COLOR_DICT_AGG",
    "EV_CLASSES", "GLOBAL_EV_TARGET", "LOW_UTIL_THRESHOLD",
    "VEHICLE_CAPACITY_BY_SIZE",
    "get_ambient_temp", "get_season_mult",
    # Models
    "Service", "Vehicle", "Plan", "Carrier",
    # Utils
    "extract_provider", "extract_veh_size", "format_runtime",
    "area_type_name", "area_type_group", "classify_vehicle",
    "is_in_hannover",
    # Reference
    "ReferenceData",
    # Parsers
    "parse_events", "parse_carriers_from_xml",
    "merge_link_counts_with_network", "build_plot_data",
    "parse_vehicle_types",
    # Analysis
    "vehicle_stats", "process_vehicle_data", "calculate_costs",
    "add_vehicle_demand_to_result", "attach_service_times",
    "build_vehicle_status_matrix", "enrich_vehicle_spatial",
    "compute_derived_kpis", "build_kpi_summary", "KPI_STRUCTURE",
    # EV
    "compute_ev_counts", "assign_ev_flags",
    "build_effective_class_map", "build_base_class_map",
    "ev_sanity_report",
    # Emissions
    "TIME_INTERVALS", "ttw_gpkm", "calc_wtt_from_energy_g_per_km",
    "calc_drive_emissions", "idle_emissions", "cold_start_emissions",
    "engine_on_share", "filter_low_utilisation",
    "run_emissions_loop",
    "build_wide", "build_wide_pollutant", "to_long", "network_totals",
    "emissions_by_area_type", "build_vehicle_emissions_column",
    # Batch
    "discover_runs", "process_single_run", "run_batch",
]
