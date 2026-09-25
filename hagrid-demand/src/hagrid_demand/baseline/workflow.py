"""Atomic orchestration of the deterministic reference baseline."""

from __future__ import annotations

import json
import os
from pathlib import Path
import platform
import re
import shutil
import uuid
import importlib.metadata

import geopandas as gpd
import numpy as np
import pandas as pd

from hagrid_demand.common.cache import dependency_snapshot, resolve_stage, stage_key
from hagrid_demand.common.contracts import verified_scope_id
from hagrid_demand.common.provenance import canonical_json, resource_hash
from hagrid_demand.common.rng import named_rng
from hagrid_demand.data import (build_business, build_residential, read_dhl, read_hermes,
                                read_persons, read_plz)

from .config import load_baseline_config
from .out_of_home import (apply_plan, build_plan, load_points, point_stops, population_near, resolve_out_of_home,
                          shop_targets, synthesize_shops, synthetic_candidates)
from .dashboard import _report_markdown, build_report_data, render_baseline
from .calendar import DEFAULT_WEEKDAY_WEIGHTS, calendar_weights, public_holidays
from .projection import project_annual
from .spatial import resolve_spatial_plan
from .allocation import delivery_frame, draw_delivery_days, generate_days
from .shipping import delivery_calendar, resolve_temporal, shipping_weights
from .shipping_draws import expected_deliveries, simulate_deliveries
from .outputs import write_daily_aggregates
from .potentials import build_potentials
from .reference import solve_reference
from .series import build_series
from .sources import packaged_series_inputs, prepare_sources


_RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,100}")


def _json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _source_specs(config: dict) -> dict[str, Path]:
    specs = config.get("sources")
    if not isinstance(specs, list):
        raise ValueError("raw source mode requires a sources list")
    result = {}
    for spec in specs:
        if not isinstance(spec, dict) or not isinstance(spec.get("adapter"), str) or not isinstance(spec.get("file"), str):
            raise ValueError("each raw source requires adapter and file")
        result[spec["adapter"]] = Path(config["input_dir"]) / spec["file"]
        if spec["adapter"] == "dhl" and spec.get("year") != 2021:
            raise ValueError("DHL source metadata must declare year 2021")
    required = {"persons", "companies", "dhl", "hermes", "plz"}
    if missing := required.difference(result):
        raise ValueError(f"raw source mode is missing adapters: {sorted(missing)}")
    return result


def _raw_source_paths(config: dict) -> list[Path]:
    paths = [Path(config["config_path"])]
    if config["source_mode"] == "raw":
        paths.extend(_source_specs(config).values())
        paths.append(Path(config["weekly_source"]))
    elif config.get("foundation_run"):
        paths.append(Path(config["foundation_run"]) / "artifact_manifest.json")
        paths.append(Path(config["foundation_run"]) / "run.json")
        paths.append(Path(config["weekly_source"]))
    return paths


def _require_foundation_columns(table: pd.DataFrame, name: str, columns: set[str]) -> None:
    if missing := columns.difference(table.columns):
        raise ValueError(f"foundation {name} missing required columns: {sorted(missing)}")


def _postal_assignments(sites: gpd.GeoDataFrame, membership: pd.DataFrame) -> gpd.GeoDataFrame:
    """Keep spatial candidates distinct from independently supplied postal evidence."""
    _require_foundation_columns(membership, "site postal candidates", {"site_id", "plz", "plz_evidence"})
    candidates = membership[["site_id", "plz", "plz_evidence"]].copy()
    candidates = candidates.dropna(subset=["site_id", "plz", "plz_evidence"])
    candidates["plz"] = candidates.plz.astype(str).str.strip()
    if candidates.plz.eq("").any():
        raise ValueError("site postal candidates require non-empty PLZ values")
    allowed = {"spatial_candidate_unverified", "independently_verified"}
    if not candidates.plz_evidence.isin(allowed).all():
        raise ValueError("site postal candidates require explicit spatial or independently_verified evidence")
    if candidates.duplicated(["site_id", "plz", "plz_evidence"]).any():
        raise ValueError("site postal candidates must not duplicate postal evidence")
    result = sites.copy()
    result["plz"] = pd.NA
    result["plz_evidence"] = pd.NA
    result["allocation_status"] = "unlocated"
    if candidates.empty:
        return gpd.GeoDataFrame(result, geometry="geometry", crs=sites.crs)
    count = candidates.groupby("site_id").plz.nunique()
    unique_ids = set(count.loc[count.eq(1)].index)
    independent = candidates.loc[candidates.plz_evidence.eq("independently_verified")]
    independent_count = independent.groupby("site_id").plz.nunique()
    independent_unique = set(independent_count.loc[independent_count.eq(1)].index).intersection(unique_ids)
    spatial = candidates.loc[candidates.plz_evidence.eq("spatial_candidate_unverified")]
    spatial_count = spatial.groupby("site_id").plz.nunique()
    spatial_unique = set(spatial_count.loc[spatial_count.eq(1)].index).intersection(unique_ids)
    independent_map = independent.loc[independent.site_id.isin(independent_unique)].drop_duplicates("site_id").set_index("site_id").plz
    spatial_map = spatial.loc[spatial.site_id.isin(spatial_unique)].drop_duplicates("site_id").set_index("site_id").plz
    valid_points = result.location_status.eq("source_point_unverified")
    for index, site_id in result.site_id.items():
        if site_id in independent_map.index:
            result.at[index, "plz"] = independent_map.at[site_id]
            result.at[index, "plz_evidence"] = "independently_verified"
            result.at[index, "allocation_status"] = "located" if bool(valid_points.at[index]) else "unlocated"
        elif bool(valid_points.at[index]) and site_id in spatial_map.index:
            result.at[index, "plz"] = spatial_map.at[site_id]
            result.at[index, "plz_evidence"] = "spatial_candidate_unverified"
            result.at[index, "allocation_status"] = "located"
    return gpd.GeoDataFrame(result, geometry="geometry", crs=sites.crs)


def _raw_postal_candidates(sites: gpd.GeoDataFrame, postal: gpd.GeoDataFrame) -> pd.DataFrame:
    """Derive only spatial candidates for sites whose source geometry is usable."""
    eligible = sites.loc[sites.location_status.eq("source_point_unverified"), ["site_id", "geometry"]]
    if eligible.empty:
        return pd.DataFrame(columns=["site_id", "plz", "plz_evidence"])
    membership = gpd.sjoin(eligible, postal[["plz", "geometry"]], how="left", predicate="intersects")
    membership = membership[["site_id", "plz"]].dropna(subset=["plz"]).drop_duplicates()
    membership["plz_evidence"] = "spatial_candidate_unverified"
    return pd.DataFrame(membership)


def _write_sources(config: dict, output: Path) -> None:
    prepared = prepare_sources(config, output)
    if config["source_mode"] != "raw":
        tables = prepared["foundation"]
        required = {"sites.parquet", "dhl_observations.parquet", "hermes_observations.parquet", "postal_support.parquet"}
        if missing := required.difference(tables):
            raise ValueError(f"foundation run missing required tables: {sorted(missing)}")
        sites = tables["sites.parquet"].copy()
        _require_foundation_columns(
            sites, "sites", {"site_id", "recipient_type", "population", "employees", "location_status", "geometry"}
        )
        dhl_table = tables["dhl_observations.parquet"]
        _require_foundation_columns(dhl_table, "DHL observations", {"year"})
        if not dhl_table.year.eq(2021).all():
            raise ValueError("foundation DHL data must contain only year 2021")
        membership = tables.get("site_postal_candidates.parquet")
        if membership is None:
            raise ValueError("foundation run requires site_postal_candidates.parquet")
        sites = _postal_assignments(gpd.GeoDataFrame(sites, geometry="geometry", crs=getattr(sites, "crs", None)), membership)
        sites["segment"] = sites.recipient_type
        employees = pd.to_numeric(sites["employees"], errors="coerce")
        sites["invalid_employees"] = sites.recipient_type.eq("business") & (employees.isna() | employees.lt(0))
        for name in required:
            table = tables[name]
            if "geometry" in table.columns:
                gpd.GeoDataFrame(table, geometry="geometry", crs=getattr(table, "crs", None)).to_parquet(output / name, index=False)
            else:
                table.to_parquet(output / name, index=False)
        sites.to_parquet(output / "sites.parquet", index=False)
        membership.to_parquet(output / "site_postal_candidates.parquet", index=False)
        source_manifest = json.loads((output / "sources.json").read_text(encoding="utf-8"))
        source_manifest.update({"mode": "foundation_run", "foundation_run": config["foundation_run"],
                                "foundation_manifest": resource_hash(Path(config["foundation_run"]) / "artifact_manifest.json"),
                                "foundation_run_state": resource_hash(Path(config["foundation_run"]) / "run.json"),
                                "invalid_employees": int(sites.invalid_employees.sum())})
        _json(output / "sources.json", source_manifest)
        return
    paths = _source_specs(config)
    persons = read_persons(paths["persons"], config["persons_crs"], config["target_crs"])
    residential = build_residential(persons, tolerance=5.0)
    businesses = build_business(paths["companies"], config["target_crs"])
    columns = ["site_id", "recipient_type", "population", "employees", "branch", "location_status", "invalid_employees", "geometry"]
    residential["invalid_employees"] = False
    sites = gpd.GeoDataFrame(pd.concat([residential[columns], businesses[columns]], ignore_index=True),
                             geometry="geometry", crs=config["target_crs"])
    postal = read_plz(paths["plz"], config["plz_crs"], config["target_crs"])
    membership = _raw_postal_candidates(sites, postal)
    sites = _postal_assignments(sites, membership)
    sites["segment"] = sites.recipient_type
    dhl = read_dhl(paths["dhl"], config["target_crs"])
    if not dhl.year.eq(2021).all():
        raise ValueError("raw DHL data must contain only year 2021")
    hermes = read_hermes(paths["hermes"])
    sites.to_parquet(output / "sites.parquet", index=False)
    dhl.to_parquet(output / "dhl_observations.parquet", index=False)
    hermes.to_parquet(output / "hermes_observations.parquet", index=False)
    postal.to_parquet(output / "postal_support.parquet", index=False)
    membership.to_parquet(output / "site_postal_candidates.parquet", index=False)
    source_manifest = json.loads((output / "sources.json").read_text(encoding="utf-8"))
    source_manifest["raw_sources"] = {name: resource_hash(path) for name, path in _source_specs(config).items()}
    source_manifest["invalid_employees"] = int(sites.invalid_employees.sum())
    _json(output / "sources.json", source_manifest)
    assert prepared["mode"] == "raw"


def _write_series(config: dict, source: Path, output: Path) -> None:
    weekly = pd.read_csv(source / "weekly_profile.csv")
    # The reference stage always needs the reference-year series, even for future-only daily runs.
    years = sorted({*config["years"], config["reference_year"]})
    series = build_series(packaged_series_inputs(), years, volume_fit_policy=config.get("volume_fit_policy", "observed_only"),
                          weekly_profile=weekly)
    for name, table in series.items():
        table.to_parquet(output / f"{name}.parquet", index=False)


_STREET_REFERENCE_FILES = ["reference_anchor.json", "reference_streets.parquet", "reference_units.parquet"]
_STOP_REFERENCE_FILES = ["reference_stops.parquet", "reference_site_stops.parquet"]


def _anchor_mode(config: dict) -> str:
    mode = (config.get("anchor") or {}).get("mode", "street" if config.get("osm_buildings") else "postal")
    if mode not in {"street", "postal"}:
        raise ValueError("anchor.mode must be street or postal")
    if mode == "street" and not (config.get("osm_buildings") and config.get("osm_points")):
        raise ValueError("anchor.mode=street requires osm_buildings and osm_points")
    return mode


def _dhl_streets(source: Path) -> gpd.GeoDataFrame:
    frame = gpd.read_parquet(source / "dhl_observations.parquet")
    frame = frame[frame.geometry_usable.astype(bool)]
    return gpd.GeoDataFrame({"sid": frame.source_row.astype("int64").to_numpy(), "plz": frame.plz.astype(str).to_numpy(),
                             "street": frame.street.astype(str).to_numpy(), "value": frame.value.astype(float).to_numpy()},
                            geometry=frame.geometry.to_numpy(), crs=frame.crs)


def _write_buildings(config: dict, source: Path, output: Path) -> None:
    from .buildings import build_buildings

    table, mapping, report = build_buildings(
        gpd.read_parquet(source / "sites.parquet"), gpd.read_parquet(config["osm_buildings"]), gpd.read_parquet(config["osm_points"]),
        _dhl_streets(source), gpd.read_parquet(source / "postal_support.parquet"), config.get("buildings", {}), int(config["seed"]))
    table.to_parquet(output / "buildings.parquet", index=False)
    mapping.to_parquet(output / "site_buildings.parquet", index=False)
    _json(output / "buildings_report.json", report)


def _business_employee_weight(config: dict) -> float | None:
    spec = config.get("business_potential", {"model": "company_locations"})
    if not isinstance(spec, dict) or spec.get("model") not in {"company_plus_employees", "company_locations"}:
        raise ValueError("business_potential.model must be company_plus_employees or company_locations")
    return float(spec.get("employee_weight", 0.1)) if spec["model"] == "company_plus_employees" else None


def _write_potentials(source: Path, output: Path, config: dict) -> None:
    sites = gpd.read_parquet(source / "sites.parquet")
    potentials = build_potentials(sites.drop(columns="geometry"), employee_weight=_business_employee_weight(config))
    potentials.to_parquet(output / "potentials.parquet", index=False)


def _profiles(series_dir: Path, reference_year: int) -> tuple[dict, float]:
    market = pd.read_parquet(series_dir / "market.parquet")
    priors = pd.read_parquet(series_dir / "providers.parquet")
    b2b = pd.read_parquet(series_dir / "b2b.parquet")
    market = market.loc[market.year.eq(reference_year)].copy()
    if market.carrier.duplicated().any() or market.empty:
        raise ValueError("market series must contain one row per carrier for the reference year")
    market = market.set_index("carrier", drop=False)
    priors = priors.loc[priors.year.eq(reference_year)].set_index("carrier").reindex(market.index)
    if priors.isna().any().any():
        raise ValueError("provider priors do not cover reference market")
    return ({"m": market["market_share"].to_numpy(float), "q_prior": priors["q_prior"].to_numpy(float),
             "lower": priors["lower"].to_numpy(float), "upper": priors["upper"].to_numpy(float),
             "scale": priors["q_scale"].to_numpy(float), "carriers": market.carrier.tolist()},
            float(b2b.loc[b2b.year.eq(reference_year), "share"].item()))


def _write_reference(config: dict, source: Path, series_dir: Path, potentials_dir: Path, output: Path,
                     buildings_dir: Path | None = None) -> None:
    profiles, b2b = _profiles(series_dir, config["reference_year"])
    source_sites = gpd.read_parquet(source / "sites.parquet")
    postal_scope = gpd.read_parquet(source / "postal_support.parquet")
    scope_id = verified_scope_id(postal_scope.plz.astype(str).tolist())
    invalid = source_sites.loc[source_sites.recipient_type.eq("business") & source_sites.invalid_employees.astype(bool)
                               & source_sites.plz.isin(postal_scope.plz), "site_id"].astype(str).tolist()
    if invalid:
        raise ValueError(f"invalid employees for in-scope business sites: {invalid}")
    if _anchor_mode(config) == "street":
        from .anchor import solve_street_reference

        solved = solve_street_reference(gpd.read_parquet(buildings_dir / "buildings.parquet"), _dhl_streets(source), profiles, b2b,
                                        config["reference_operating_days"], config.get("anchor"), seed=int(config["seed"]),
                                        scope_plz=postal_scope.plz.astype(str).tolist())
        b2b_series = pd.read_parquet(series_dir / "b2b.parquet").set_index("year").share
        solved["anchor"]["b2b_by_year"] = {str(int(year)): float(value) for year, value in b2b_series.items()}
        _json(output / "reference_anchor.json", solved["anchor"])
        solved["streets"].to_parquet(output / "reference_streets.parquet", index=False)
        solved["units"].to_parquet(output / "reference_units.parquet", index=False)
        from .stops import build_stops

        stop_cfg = config.get("stops", {})
        expected = solved["sites"].groupby("site_id").reference_annual.sum() / config["reference_operating_days"]
        stops, site_stops = build_stops(solved["units"], expected, _dhl_streets(source),
                                        float(stop_cfg.get("walking_radius_m", 40.)),
                                        float(stop_cfg.get("own_stop_parcels_per_day", 15.)),
                                        float(stop_cfg.get("section_length_m", 50.)))
        stops.to_parquet(output / "reference_stops.parquet", index=False)
        site_stops.rename(columns={"building_key": "site_id"}).to_parquet(output / "reference_site_stops.parquet", index=False)
    else:
        solved = solve_reference(pd.read_parquet(potentials_dir / "potentials.parquet"),
                                 gpd.read_parquet(source / "dhl_observations.parquet"), profiles, b2b,
                                 config["reference_operating_days"], scope_plz=postal_scope.plz.astype(str).tolist())
    postal_columns = ["plz", "dhl_retained_mean", "reference_annual", "private_annual", "business_annual", "b2b_share", "dhl_share"]
    solved["postal"].loc[:, postal_columns].to_parquet(output / "reference_postal.parquet", index=False)
    site_columns = ["site_id", "plz", "segment", "population", "employees", "branch", "weight", "historical_share",
                    "structural_share", "reference_annual", "allocation_status"]
    sites = solved["sites"].loc[:, site_columns].copy()
    sites.to_parquet(output / "reference_sites.parquet", index=False)
    if "geometry" in solved:
        geometry = solved["geometry"]
    else:
        geometry = source_sites.loc[source_sites.site_id.isin(sites.site_id), ["site_id", "geometry"]].copy()
        geometry = gpd.GeoDataFrame(geometry, geometry="geometry", crs=source_sites.crs)
    geometry.to_parquet(output / "reference_geometry.parquet", index=False)
    carriers = solved["carriers"]
    carrier_profiles = pd.concat([
        carriers.assign(segment="private", share=carriers.private_share),
        carriers.assign(segment="business", share=carriers.business_share),
    ], ignore_index=True)
    carrier_profiles = carrier_profiles[["year", "segment", "carrier", "market_share", "q_prior", "q_scale", "lower", "upper", "q_adjusted", "share"]]
    carrier_profiles.to_parquet(output / "reference_carrier_profiles.parquet", index=False)
    _json(output / "reference_reconciliation.json", solved["reconciliation"])
    _json(output / "reference_regional_annual.json", {"year": config["reference_year"], "regional_annual": solved["regional_annual"],
                                                         "scope_id": scope_id})
    checks = {**solved["checks"], "source_quality": solved["source_quality"], "implied_rates": solved["implied_rates"],
              "regional_annual": solved["regional_annual"], "scope_id": scope_id}
    _json(output / "reference_checks.json", checks)
    _json(output / "checks.json", checks)


def _write_report(config: dict, reference_dir: Path, output: Path, run_id: str, baseline_fingerprint: str) -> None:
    # The cached report stage is derived from exactly the same frozen semantic
    # reference contract as public dashboard regeneration.
    report = build_report_data(reference_dir, config, run_id, baseline_fingerprint)
    _json(output / "report_data.json", report)
    (output / "report.md").write_text(_report_markdown(report), encoding="utf-8")


def _daily_calendar(config: dict, run: Path) -> tuple[pd.DataFrame, dict]:
    """Build the normalised delivery calendar from the source weekly profile, weekdays and holidays."""
    calendar = config.get("calendar", {})
    if not isinstance(calendar, dict):
        raise ValueError("calendar must be a mapping")
    weekly_mode = calendar.get("weekly_profile", "source")
    if weekly_mode not in {"source", "none"}:
        raise ValueError("calendar.weekly_profile must be source or none")
    weekly = pd.read_parquet(run / "series" / "weekly.parquet") if weekly_mode == "source" else None
    region = calendar.get("holiday_region", "NI")
    extra = calendar.get("holiday_dates", [])
    if not isinstance(extra, list):
        raise ValueError("calendar.holiday_dates must be a list of ISO dates")
    holidays = sorted({*extra, *(day for year in config["years"] for day in public_holidays(year, region))})
    cfg = {"weekday_weights": calendar.get("weekday_weights", {"private": DEFAULT_WEEKDAY_WEIGHTS,
                                                                "business": DEFAULT_WEEKDAY_WEIGHTS}),
           "monthly_weights": calendar.get("monthly_weights", [1.] * 12), "holiday_dates": holidays,
           "holiday_factor": calendar.get("holiday_factor", 0.), "seasonality_strength": calendar.get("seasonality_strength", {})}
    frame = pd.concat([calendar_weights(year, segment, weekly, cfg) for year in config["years"]
                       for segment in ("private", "business")], ignore_index=True)
    active = frame.loc[frame.segment.eq("private") & frame.calendar_weight.gt(0)]
    metadata = {"weekly_profile": weekly_mode,
                "weekly_status": None if weekly is None else sorted(weekly.status.astype(str).unique().tolist()),
                "weekday_weights": cfg["weekday_weights"], "holiday_region": region, "holiday_dates": holidays,
                "holiday_factor": cfg["holiday_factor"],
                "delivery_days": {str(year): int(count) for year, count in active.groupby("year").size().items()},
                "reference_operating_days": config["reference_operating_days"]}
    return frame, metadata


def _holidays(config: dict, year: int) -> list[str]:
    calendar = config.get("calendar", {})
    return sorted({*calendar.get("holiday_dates", []), *public_holidays(year, calendar.get("holiday_region", "NI"))})



def _draw_indices(dates: pd.DatetimeIndex, selected: set | None, *, has_writer: bool) -> np.ndarray:
    """Days to draw: all of them for the annual store, otherwise only the selected ones (streams are keyed by date)."""
    if has_writer or selected is None:
        return np.arange(len(dates))
    return np.flatnonzero(pd.DatetimeIndex(dates).normalize().isin(list(selected)))


def _daily_code() -> dict:
    """Code and packaged inputs whose content keys the daily stage cache."""
    here = Path(__file__)
    return {"workflow": here, "projection": here.with_name("projection.py"), "calendar": here.with_name("calendar.py"),
            "allocation": here.with_name("allocation.py"), "outputs": here.with_name("outputs.py"),
            "spatial": here.with_name("spatial.py"), "shipping": here.with_name("shipping.py"),
            "shipping_draws": here.with_name("shipping_draws.py"), "annual": here.with_name("annual.py"),
            "temporal_inputs": here.with_name("data") / "temporal_inputs.json", "events": here.with_name("data") / "events.json",
            "out_of_home": here.with_name("out_of_home.py"), "out_of_home_inputs": here.with_name("data") / "out_of_home.json",
            "matsim_export": here.parents[1] / "compatibility" / "matsim_export.py"}

def _shipping_transit_chunks(config: dict, run: Path, output: Path, projection, plan, generation: dict, temporal: dict):
    """Draw shipping days, transit and Saturday rule per carrier; yield detail frames for the selected dates."""
    calendar = config.get("calendar", {})
    weekly = pd.read_parquet(run / "series" / "weekly.parquet") if calendar.get("weekly_profile", "source") == "source" else None
    sites, profiles = projection.sites, projection.profiles
    selected = None if generation.get("dates") is None else set(pd.to_datetime(generation["dates"]).normalize())
    regime = generation.get("regime", "fixed_annual")
    prepared, rows = [], []
    for year in config["years"]:
        holidays = _holidays(config, year)
        cal = delivery_calendar(year, holidays)
        calendar_cfg = {"monthly_weights": calendar.get("monthly_weights", [1.] * 12), "holiday_dates": holidays,
                        "seasonality_strength": calendar.get("seasonality_strength", {})}
        shipping = {segment: shipping_weights(year, segment, weekly, temporal, calendar_cfg) for segment in ("private", "business")}
        segment_totals = sites.loc[sites.year.eq(year)].groupby("segment").annual_expected.sum()
        targets = {(row.segment, row.carrier): float(segment_totals.get(row.segment, 0.)) * float(row.share)
                   for row in profiles.loc[profiles.year.eq(year)].itertuples()}
        expected = expected_deliveries(targets, shipping, cal, temporal)
        delivered = simulate_deliveries(targets, shipping, cal, temporal, seed=int(config["seed"]), year=year, regime=regime,
                                        process=generation.get("process"))
        for (segment, carrier), values in expected.items():
            rows.append(pd.DataFrame({"date": cal.dates, "year": year, "segment": segment, "carrier": carrier,
                                      "expected": values, "delivered": delivered[(segment, carrier)]}))
        prepared.append((year, cal, expected, delivered))
    pd.concat(rows, ignore_index=True).to_parquet(output / "delivery_calendar.parquet", index=False)
    status = {"mode": "shipping_transit", "regime": regime,
              "shipping_weekday_weights": {segment: values.round(6).tolist() for segment, values in temporal["shipping"].items()},
              "transit_days": {name: values.tolist() for name, values in temporal["kernels"].items()},
              "saturday_delivery": temporal["saturday"], "business_saturday_open": temporal["business_saturday_open"],
              "week_log_sd": temporal["week_log_sd"], "week_ar": temporal["week_ar"],
              "carrier_week_log_sd": temporal["carrier_week_log_sd"], "carrier_day_log_sd": temporal["carrier_day_log_sd"],
              "weekday_concentration": temporal["weekday_concentration"],
              "christmas_pull_forward_days": temporal["christmas_pull_forward_days"], "holiday_spread_days": temporal["holiday_spread_days"],
              "events": [{"name": event["name"], "carriers": event["carriers"], "uplift": event["uplift"]} for event in temporal["events"]],
              "half_delivery_days": temporal["half_delivery_days"]}
    site_groups = None
    if float(config.get("spatial", {}).get("site_frailty_cv", 0.) or 0.) > 0 and (run / "reference_site_stops.parquet").is_file():
        street = (pd.read_parquet(run / "reference_stops.parquet", columns=["stop_id", "str_idx"])
                  .drop_duplicates("stop_id").set_index("stop_id").str_idx)
        links = pd.read_parquet(run / "reference_site_stops.parquet").drop_duplicates("site_id")
        site_groups = pd.Series(links.stop_id.map(street).to_numpy(), index=links.site_id.astype(str).to_numpy())

    ooh = resolve_out_of_home(config.get("out_of_home"))
    export_stops = points = None
    if ooh is not None:
        if not config.get("osm_parcel_points"):
            raise ValueError("out_of_home requires osm_parcel_points")
        if not (run / "reference_stops.parquet").is_file():
            raise ValueError("out_of_home requires the street anchor with stops")
        base_stops = gpd.read_parquet(run / "reference_stops.parquet")
        base_links = pd.read_parquet(run / "reference_site_stops.parquet")
        points, status["out_of_home"] = _out_of_home_points(config, run, ooh, base_stops.crs)
        extra = point_stops(points, int(base_stops.stop_index.max()) + 1)
        export_stops = {"stops": gpd.GeoDataFrame(pd.concat([base_stops.assign(stop_type="home"), extra], ignore_index=True), crs=base_stops.crs),
                        "site_stops": pd.concat([base_links, pd.DataFrame({"site_id": extra.stop_id, "stop_id": extra.stop_id})], ignore_index=True)}
        extra.rename(columns={"stop_type": "kind"})[["stop_index", "point_id", "kind", "carriers", "synthetic", "plz", "geometry"]].to_parquet(
            output / "out_of_home_points.parquet", index=False)
        site_xy_of = base_stops.drop_duplicates("stop_id").set_index("stop_id").geometry
        site_stop_of = base_links.drop_duplicates("site_id").set_index("site_id").stop_id
        population_of = pd.read_parquet(run / "reference_sites.parquet", columns=["site_id", "population"]).drop_duplicates("site_id").set_index("site_id").population
        status["out_of_home"].update({"delivered": {}, "b2c_delivered": {}, "overflow_home": 0})

    writer = None
    if config.get("annual_store"):
        if not (run / "reference_stops.parquet").is_file():
            raise ValueError("annual_store requires the street anchor with stops")
        from .annual import AnnualStoreWriter
        writer = AnnualStoreWriter(output, export_stops["stops"] if export_stops else gpd.read_parquet(run / "reference_stops.parquet"),
                                   export_stops["site_stops"] if export_stops else pd.read_parquet(run / "reference_site_stops.parquet"),
                                   {day for year in config["years"] for day in _holidays(config, year)})
        status["annual_store"] = True
        if export_stops is not None:
            shutil.copy2(output / "out_of_home_points.parquet", writer.directory / "out_of_home_points.parquet")
    plans = {}

    def route(year: int, day: int, date: pd.Timestamp, segment_days: dict, days: int) -> dict:
        item = segment_days.get("private")
        if item is None:
            return segment_days
        if year not in plans:
            ids = item.sites.site_id.astype(str).to_numpy()
            located = gpd.GeoSeries(site_xy_of.reindex(site_stop_of.reindex(ids).to_numpy()).to_numpy(), crs=site_xy_of.crs)
            site_xy = np.column_stack([located.x.to_numpy(), located.y.to_numpy()])
            plans[year] = build_plan(item.sites, item.carriers, site_xy, population_of.reindex(ids).fillna(0.).to_numpy(), points, ooh,
                                     year, days, lambda carrier: named_rng(int(config["seed"]), year=year, carrier=carrier, channel="ooh-day"))
            status["out_of_home"]["target_share"] = {carrier: round(share, 5) for carrier, share in plans[year].shares.items()}
        routed, per_carrier, overflow = apply_plan(item, plans[year], day, named_rng(int(config["seed"]), year=year,
                                                                                     date=date.date().isoformat(), channel="ooh-divert"))
        totals = status["out_of_home"]
        for carrier, parcels, delivered in zip(item.carriers, per_carrier, item.counts.sum(axis=0)):
            totals["delivered"][carrier] = totals["delivered"].get(carrier, 0) + int(parcels)
            totals["b2c_delivered"][carrier] = totals["b2c_delivered"].get(carrier, 0) + int(delivered)
        totals["overflow_home"] += int(overflow)
        return {**segment_days, "private": routed}

    def frames():
        completed = False
        try:
            for year, cal, expected, delivered in prepared:
                indices = _draw_indices(cal.dates, selected, has_writer=writer is not None)
                if not len(indices):
                    continue
                subset = {key: values[indices] for key, values in delivered.items()}
                for position, (date, segment_days) in enumerate(draw_delivery_days(
                        sites, profiles, subset, cal.dates[indices], generation, 0, 0, spatial_plan=plan,
                        cache_dir=Path(config["cache_root"]), site_groups=site_groups)):
                    if ooh is not None:
                        segment_days = route(year, int(indices[position]), date, segment_days, len(cal.dates))
                    if writer is not None:
                        writer.add_day(date, segment_days)
                    if selected is None or date.normalize() in selected:
                        yield delivery_frame(date, segment_days, expected, int(indices[position]), year, 0, 0)
            completed = True
        finally:
            if writer is not None:
                if completed:
                    writer.close({"years": config["years"], "temporal": status})
                else:
                    writer.abort()

    return frames(), status, export_stops


def _out_of_home_points(config: dict, run: Path, ooh: dict, crs) -> tuple[gpd.GeoDataFrame, dict]:
    """OSM pickup points plus synthetic shops, each with its PLZ."""
    points = load_points(Path(config["osm_parcel_points"]), crs)
    postal = gpd.read_parquet(run / "sources" / "postal_support.parquet").to_crs(crs)
    status = {"osm_points": {str(kind): int(count) for kind, count in points.kind.value_counts().items()}, "synthetic_shops": 0}
    if ooh.get("synthetic_shops", True) and config.get("osm_points"):
        units = gpd.read_parquet(run / "reference_units.parquet").to_crs(crs)
        candidates = synthetic_candidates(gpd.read_parquet(config["osm_points"]).to_crs(crs), ooh["synthetic_poi_types"])
        candidates = candidates.loc[candidates.within(postal.union_all())].reset_index(drop=True)
        targets = shop_targets(ooh, float(units.population.sum()))
        points = synthesize_shops(points, candidates, population_near(candidates, units, float(ooh["synthetic_population_radius_m"])),
                                  targets, named_rng(int(config["seed"]), channel="ooh-synthetic-shops"))
        status.update({"synthetic_shops": int(points.synthetic.sum()), "shop_targets": targets})
    joined = gpd.sjoin_nearest(points[["point_id", "geometry"]], postal[["plz", "geometry"]], how="left")
    points["plz"] = joined.groupby(level=0).plz.first().reindex(points.index).astype(str).to_numpy()
    return points, status


def _matsim_export_enabled(config: dict) -> bool:
    value = config.get("matsim_export", True)
    if not isinstance(value, bool):
        raise ValueError("matsim_export must be true or false")
    return value


def _write_daily(config: dict, run: Path, output: Path) -> None:
    """Project full calendar-year counts and stream only selected daily detail."""
    reference_sites = pd.read_parquet(run / "reference_sites.parquet")
    reference = {"sites": reference_sites, "regional_annual": json.loads((run / "reference_regional_annual.json").read_text(encoding="utf-8"))["regional_annual"],
                 "scope_id": json.loads((run / "reference_checks.json").read_text(encoding="utf-8"))["scope_id"],
                 "geometry": gpd.read_parquet(run / "reference_geometry.parquet")}
    series = {name: pd.read_parquet(run / "series" / f"{name}.parquet") for name in ("volume", "market", "b2b", "providers")}
    projection_cfg = {"memory": {"fixed": 1}, "regional_level": config["regional_level"]}
    if (run / "reference_anchor.json").is_file():
        anchor = json.loads((run / "reference_anchor.json").read_text(encoding="utf-8"))
        projection_cfg["dhl_b2b"] = {"q_2021": anchor["q_dhl"], "b_2021": anchor["b2b_by_year"][str(config["reference_year"])]}
    projection = project_annual(reference, series, config["years"], projection_cfg)
    calendar, calendar_metadata = _daily_calendar(config, run)
    plan = resolve_spatial_plan(reference, projection, {"seed": config["seed"], "spatial": config["spatial"]}, 0, Path(config["cache_root"]))
    generation = {"seed": config["seed"], "spatial": config["spatial"], "dates": config.get("dates"),
                  "regime": config.get("regime", "fixed_annual"), "process": config.get("process", {})}
    detail_draws = {tuple(item) for item in config.get("detail_draws", [[0, 0]])}
    if any(len(item) != 2 for item in detail_draws):
        raise ValueError("detail_draws must contain [outer_id, inner_id] pairs")
    temporal = resolve_temporal(config.get("temporal"))
    if temporal is None:
        if config.get("annual_store"):
            raise ValueError("annual_store requires temporal.mode = shipping_transit")
        if config.get("out_of_home"):
            raise ValueError("out_of_home requires temporal.mode = shipping_transit")
        chunks = generate_days(projection.sites, projection.profiles, calendar, generation, 0, 0,
                               spatial_plan=plan, cache_dir=Path(config["cache_root"]))
        temporal_status, ooh_stops = {"mode": "delivery_calendar"}, None
    else:
        chunks, temporal_status, ooh_stops = _shipping_transit_chunks(config, run, output, projection, plan, generation, temporal)
    matsim_ledgers: list[dict] = []
    stops = None
    if _matsim_export_enabled(config):
        from hagrid_demand.compatibility.matsim_export import compare_with_notebook, with_matsim_export, write_matsim_manifest
        if ooh_stops is not None:
            stops = ooh_stops
        elif (run / "reference_stops.parquet").is_file():
            stops = {"site_stops": pd.read_parquet(run / "reference_site_stops.parquet"),
                     "stops": gpd.read_parquet(run / "reference_stops.parquet")}
        chunks = with_matsim_export(chunks, reference["geometry"], output / "matsim", matsim_ledgers, stops,
                                    int(config.get("stops", {}).get("max_parcels_per_row", 400)))
    summary = write_daily_aggregates(chunks, output, detail_draws)
    if _matsim_export_enabled(config):
        notebook_dir = config.get("notebook_output_dir")
        comparison = compare_with_notebook(matsim_ledgers, output / "matsim", Path(notebook_dir)) if notebook_dir else None
        write_matsim_manifest(matsim_ledgers, output / "matsim", str(reference["geometry"].crs), stop_mode=stops is not None,
                              comparison=comparison)
    projection.sites.to_parquet(output / "annual_projection.parquet", index=False)
    projection.profiles.to_parquet(output / "carrier_profiles.parquet", index=False)
    projection.postal.to_parquet(output / "postal_projection.parquet", index=False)
    calendar.to_parquet(output / "calendar_weights.parquet", index=False)
    _json(output / "daily_status.json", {"output_scope": "daily", "years": config["years"], "selected_dates_are_filter_only": True,
                                          "spatial_status": plan.status, "calendar": calendar_metadata, "temporal": temporal_status,
                                          "writer": summary})
    if config.get("legacy_export"):
        from hagrid_demand.compatibility.legacy_exports import export_legacy
        export_legacy(series, reference, projection, Path(config["legacy_contract"]), config["years"], output / "legacy", config["schema_version"])


def _validate(output: Path, names: list[str]) -> None:
    missing = [name for name in names if not (output / name).is_file()]
    if missing:
        raise ValueError(f"stage did not write required artifacts: {missing}")


def _copy_public(run: Path, stage: str, name: str) -> None:
    source = run / stage / name
    target = run / name
    temporary = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
    shutil.copy2(source, temporary)
    os.replace(temporary, target)


def _run_state(run: Path, config: dict) -> dict:
    return {"run_id": run.name, "status": "running", "config": config, "completed_stages": []}


def _runtime_payload() -> dict:
    packages = {name: importlib.metadata.version(name) for name in
                ("numpy", "scipy", "pandas", "geopandas", "shapely", "pyarrow")}
    return {"python": platform.python_version(), "packages": packages}


def _verify_runtime(run: Path) -> None:
    path = run / "runtime.json"
    try:
        recorded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("cannot resume: runtime.json is required") from exc
    if recorded != _runtime_payload():
        raise ValueError("cannot resume: runtime contract changed")


def _frozen_reference_artifacts(run: Path) -> dict:
    """Hash the public reference contract that downstream stages are allowed to consume."""
    groups = {
        "structure": ("reference_sites.parquet", "reference_postal.parquet"),
        "geometry": ("reference_geometry.parquet",),
        "series": ("series/market.parquet", "series/b2b.parquet", "series/volume.parquet", "series/providers.parquet", "series/weekly.parquet"),
        "scope": ("sources/postal_support.parquet", "reference_checks.json"),
        "reconciliation": ("reference_carrier_profiles.parquet", "reference_reconciliation.json"),
        "regional": ("reference_regional_annual.json", "checks.json"),
    }
    if (run / "reference_anchor.json").is_file():
        groups["anchor"] = tuple(name for name in (*_STREET_REFERENCE_FILES, *_STOP_REFERENCE_FILES) if (run / name).is_file())
    result = {}
    for group, names in groups.items():
        result[group] = {}
        for name in names:
            artifact = run / name
            if not artifact.is_file():
                raise ValueError(f"missing frozen semantic reference artifact: {name}")
            result[group][name] = resource_hash(artifact)
    return result


def _baseline_fingerprint(run: Path) -> tuple[str, dict]:
    artifacts = _frozen_reference_artifacts(run)
    from hagrid_demand.common.provenance import canonical_digest

    return canonical_digest({"baseline_reference_contract": 1, "artifacts": artifacts}), artifacts


def run_baseline(config_path: Path, run_id: str, resume: bool = False) -> Path:
    """Build or safely resume the one-year deterministic reference baseline."""
    if not _RUN_ID.fullmatch(run_id):
        raise ValueError("Invalid run_id")
    config = load_baseline_config(Path(config_path))
    if config["reference_year"] != 2021:
        raise ValueError("The reference milestone requires reference_year=2021")
    if config.get("dhl_exclude_above") != 1000:
        raise ValueError("The reference milestone requires dhl_exclude_above=1000")
    run = Path(config["output_dir"]) / run_id
    if run.exists() and not resume:
        raise FileExistsError(f"Baseline run already exists: {run}")
    if resume:
        if not run.is_dir() or not (run / "config.resolved.json").is_file():
            raise FileNotFoundError(f"No resumable baseline run: {run}")
        prior = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
        if canonical_json(prior) != canonical_json(config):
            raise ValueError("cannot resume: resolved configuration changed")
        _verify_runtime(run)
    else:
        run.mkdir(parents=True, exist_ok=False)
        _json(run / "config.resolved.json", config)
        _json(run / "runtime.json", _runtime_payload())
        _json(run / "run.json", _run_state(run, config))
    try:
        source_dependencies = {"source_files": _raw_source_paths(config)}
        source_snapshot = dependency_snapshot(source_dependencies)
        source_fingerprint = stage_key("sources", source_dependencies, config,
                                       {"workflow": Path(__file__), "sources": Path(__file__).with_name("sources.py"),
                                        "data": Path(__file__).parents[1] / "data.py", "linking": Path(__file__).parents[1] / "linking.py"},
                                       dependency_snapshot=source_snapshot)
    except Exception as exc:
        state = _run_state(run, config); state.update(status="failed", error=f"{type(exc).__name__}: {exc}")
        _json(run / "run.json", state)
        raise
    if resume and (run / "stage_manifest.json").is_file():
        prior_manifest = json.loads((run / "stage_manifest.json").read_text(encoding="utf-8"))
        previous = prior_manifest.get("stages", {}).get("sources", {}).get("fingerprint")
        if previous is not None and previous != source_fingerprint:
            raise ValueError("cannot resume: consumed source files changed")
    if resume:
        prior_state = json.loads((run / "run.json").read_text(encoding="utf-8"))
        if prior_state.get("consumed_source_fingerprint") != source_fingerprint:
            raise ValueError("cannot resume: consumed source files changed")
    state = _run_state(run, config)
    state["consumed_source_fingerprint"] = source_fingerprint
    _json(run / "run.json", state)
    try:
        cache_root = Path(config["cache_root"])
        source_artifacts = ["sources.json", "sites.parquet", "dhl_observations.parquet", "hermes_observations.parquet",
                            "postal_support.parquet", "site_postal_candidates.parquet", "weekly_profile.csv"]
        resolve_stage(run, "sources", source_fingerprint, cache_root=cache_root, dependencies=source_dependencies,
                      build=lambda output: _write_sources(config, output),
                      validate=lambda output: _validate(output, source_artifacts), dependency_snapshot=source_snapshot)
        state["completed_stages"].append("sources")
        series_dependencies = {"inputs": Path(__file__).parent / "data", "weekly": run / "sources" / "weekly_profile.csv",
                               "year": config["reference_year"]}
        series_snapshot = dependency_snapshot(series_dependencies)
        series_fingerprint = stage_key("series", series_dependencies, config,
                                       {"workflow": Path(__file__), "series": Path(__file__).with_name("series.py"),
                                        "sources": Path(__file__).with_name("sources.py")}, dependency_snapshot=series_snapshot)
        resolve_stage(run, "series", series_fingerprint, cache_root=cache_root, dependencies=series_dependencies,
                      build=lambda output: _write_series(config, run / "sources", output),
                      validate=lambda output: _validate(output, ["market.parquet", "b2b.parquet", "volume.parquet", "providers.parquet", "weekly.parquet"]),
                      dependency_snapshot=series_snapshot)
        state["completed_stages"].append("series")
        potential_dependencies = {"sources": run / "sources"}
        potential_snapshot = dependency_snapshot(potential_dependencies)
        potential_fingerprint = stage_key("potentials", potential_dependencies, config,
                                          {"workflow": Path(__file__), "potentials": Path(__file__).with_name("potentials.py")},
                                          dependency_snapshot=potential_snapshot)
        resolve_stage(run, "potentials", potential_fingerprint, cache_root=cache_root, dependencies=potential_dependencies,
                      build=lambda output: _write_potentials(run / "sources", output, config),
                      validate=lambda output: _validate(output, ["potentials.parquet"]), dependency_snapshot=potential_snapshot)
        state["completed_stages"].append("potentials")
        if _anchor_mode(config) == "street":
            buildings_dependencies = {"sources": run / "sources", "osm_buildings": Path(config["osm_buildings"]),
                                      "osm_points": Path(config["osm_points"])}
            buildings_snapshot = dependency_snapshot(buildings_dependencies)
            buildings_fingerprint = stage_key("buildings", buildings_dependencies, config,
                                              {"workflow": Path(__file__), "buildings": Path(__file__).with_name("buildings.py")},
                                              dependency_snapshot=buildings_snapshot)
            resolve_stage(run, "buildings", buildings_fingerprint, cache_root=cache_root, dependencies=buildings_dependencies,
                          build=lambda output: _write_buildings(config, run / "sources", output),
                          validate=lambda output: _validate(output, ["buildings.parquet", "site_buildings.parquet", "buildings_report.json"]),
                          dependency_snapshot=buildings_snapshot)
            state["completed_stages"].append("buildings")
        street_mode = _anchor_mode(config) == "street"
        reference_dependencies = {"sources": run / "sources", "series": run / "series", "potentials": run / "potentials"}
        reference_files = {"workflow": Path(__file__), "reference": Path(__file__).with_name("reference.py")}
        if street_mode:
            reference_dependencies["buildings"] = run / "buildings"
            reference_files.update({"anchor": Path(__file__).with_name("anchor.py"), "stops": Path(__file__).with_name("stops.py")})
        reference_snapshot = dependency_snapshot(reference_dependencies)
        reference_fingerprint = stage_key("reference", reference_dependencies, config, reference_files,
                                          dependency_snapshot=reference_snapshot)
        reference_public = ["reference_postal.parquet", "reference_sites.parquet", "reference_geometry.parquet",
                            "reference_carrier_profiles.parquet", "reference_reconciliation.json", "reference_regional_annual.json",
                            "reference_checks.json", "checks.json"] + (
                                _STREET_REFERENCE_FILES + _STOP_REFERENCE_FILES if street_mode else [])
        resolve_stage(run, "reference", reference_fingerprint, cache_root=cache_root, dependencies=reference_dependencies,
                      build=lambda output: _write_reference(config, run / "sources", run / "series", run / "potentials", output,
                                                            run / "buildings" if street_mode else None),
                      validate=lambda output: _validate(output, reference_public), dependency_snapshot=reference_snapshot)
        for name in reference_public:
            _copy_public(run, "reference", name)
        state["completed_stages"].append("reference")
        baseline_fingerprint, baseline_artifacts = _baseline_fingerprint(run)
        state["baseline_fingerprint"] = baseline_fingerprint
        state["baseline_fingerprint_artifacts"] = baseline_artifacts
        state["config_sha256"] = resource_hash(run / "config.resolved.json")
        state["regional_annual"] = json.loads((run / "reference_regional_annual.json").read_text(encoding="utf-8"))["regional_annual"]
        _json(run / "run.json", state)
        if config["output_scope"] == "daily":
            daily_dependencies = {"reference": run / "reference", "series": run / "series", "config": config}
            if config.get("out_of_home") and config.get("osm_parcel_points"):
                daily_dependencies["osm_parcel_points"] = Path(config["osm_parcel_points"])
            daily_snapshot = dependency_snapshot(daily_dependencies)
            daily_fingerprint = stage_key("daily", daily_dependencies, config, _daily_code(),
                                          dependency_snapshot=daily_snapshot)
            resolve_stage(run, "daily", daily_fingerprint, cache_root=cache_root, dependencies=daily_dependencies,
                          build=lambda output: _write_daily(config, run, output),
                          validate=lambda output: _validate(output, ["daily_aggregates.parquet", "annual_projection.parquet", "carrier_profiles.parquet", "postal_projection.parquet", "calendar_weights.parquet", "daily_status.json"] + (["legacy/05_ga_corrected_b2b_with_marked_adjust_gdf.csv"] if config.get("legacy_export") else [])
                                                               + (["matsim/matsim_export.json"] if _matsim_export_enabled(config) else [])
                                                               + (["annual/days.parquet", "annual/stop_daily.parquet"] if config.get("annual_store") else [])),
                          dependency_snapshot=daily_snapshot)
            for name in ("daily_aggregates.parquet", "annual_projection.parquet", "carrier_profiles.parquet", "postal_projection.parquet", "calendar_weights.parquet", "daily_status.json"):
                _copy_public(run, "daily", name)
            if (run / "daily" / "delivery_calendar.parquet").is_file():
                _copy_public(run, "daily", "delivery_calendar.parquet")
            if (run / "daily" / "out_of_home_points.parquet").is_file():
                _copy_public(run, "daily", "out_of_home_points.parquet")
            if (run / "daily" / "annual").is_dir():
                if (run / "annual").exists():
                    shutil.rmtree(run / "annual")
                shutil.copytree(run / "daily" / "annual", run / "annual")
            if _matsim_export_enabled(config):
                target = run / "matsim"
                if target.exists():
                    shutil.rmtree(target)
                shutil.copytree(run / "daily" / "matsim", target)
            state["completed_stages"].extend(["calendar", "daily"])
        report_dependencies = {"reference": run / "reference", "run_id": run_id, "baseline_fingerprint": baseline_fingerprint}
        report_snapshot = dependency_snapshot(report_dependencies)
        report_fingerprint = stage_key("report", report_dependencies, config,
                                       {"workflow": Path(__file__), "dashboard": Path(__file__).with_name("dashboard.py")},
                                       dependency_snapshot=report_snapshot)
        resolve_stage(run, "report", report_fingerprint, cache_root=cache_root, dependencies=report_dependencies,
                      build=lambda output: _write_report(config, run / "reference", output, run_id, baseline_fingerprint),
                      validate=lambda output: _validate(output, ["report_data.json", "report.md"]),
                      dependency_snapshot=report_snapshot)
        for name in ("report_data.json", "report.md"):
            _copy_public(run, "report", name)
        state["completed_stages"].append("report")
        state["completed_stages"].append("dashboard")
        state["status"] = "complete_daily" if config["output_scope"] == "daily" else "complete_reference"
        _json(run / "run.json", state)
        if (run / "annual" / "days.parquet").is_file():
            from .annual_dashboard import write_annual_dashboard
            write_annual_dashboard(run, run / "annual_dashboard.html")
        render_baseline(run)
        return run
    except Exception as exc:
        state["status"] = "failed"
        state["error"] = f"{type(exc).__name__}: {exc}"
        _json(run / "run.json", state)
        try:
            from .dashboard import register_report, render_catalog
            root = Path(config.get("dashboard_root") or Path(config["output_dir"]) / "dashboard")
            register_report(run, root)
            render_catalog(root)
        except Exception:
            pass
        raise
