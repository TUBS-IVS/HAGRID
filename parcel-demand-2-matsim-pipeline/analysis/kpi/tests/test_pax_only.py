# -*- coding: utf-8 -*-
"""pax_only.apply_overrides: the Shared-Use pax-only corrections must win under
the CANONICAL KPI names, so every consumer (render HEADLINE_KPIS, render_drt
tiles, economics) gets the honest number without knowing the rule exists."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import economics
import pax_only
from common import KPI_GROUPS, row


def _by_name(rows):
    return {r["kpi_name"]: r for r in rows}


def test_pax_only_replaces_stock_and_demotes_it():
    rows = [
        row("passenger", "drt_rides", 5000, "trips", "drt_customer_stats"),
        row("passenger", "drt_rides_pax_only", 1200, "trips", "output_drt_legs pax-filter"),
    ]
    k = _by_name(pax_only.apply_overrides(rows))

    # canonical name now carries the pax-only value ...
    assert k["drt_rides"]["value"] == 1200
    assert k["drt_rides"]["source"] == "output_drt_legs pax-filter"
    # ... and the contaminated one survives under an explicit name
    assert k["drt_rides_incl_parcels"]["value"] == 5000
    assert "incl. parcel requests" in k["drt_rides_incl_parcels"]["source"]
    assert "drt_rides_pax_only" not in k


def test_noop_without_any_pax_only_rows():
    """Every non-Shared-Use run: KPI names must stay byte-identical, and no
    provenance row may appear (it would add a spurious wide-CSV column)."""
    rows = [
        row("passenger", "drt_rides", 5000, "trips", "drt_customer_stats"),
        row("passenger", "pooling_rate", 0.4, "share", "drt_sharing_metrics"),
    ]
    before = [(r["kpi_name"], r["value"], r["source"]) for r in rows]

    after = pax_only.apply_overrides(rows)

    assert [(r["kpi_name"], r["value"], r["source"]) for r in after] == before
    assert "parcel_contaminated_kpis" not in _by_name(after)


def test_pax_only_without_stock_twin_is_left_alone():
    """`fare_revenue_pax_only` has no stock `fare_revenue` counterpart --
    renaming it would invent a canonical KPI that exists on Shared-Use runs
    only, so it must keep its explicit name."""
    rows = [
        row("economic", "fare_revenue_pax_only", 6.0, "EUR", "output_drt_legs pax-filter"),
        row("passenger", "drt_rides", 10, "trips", "drt_customer_stats"),
        row("passenger", "drt_rides_pax_only", 4, "trips", "output_drt_legs pax-filter"),
    ]
    k = _by_name(pax_only.apply_overrides(rows))

    assert k["fare_revenue_pax_only"]["value"] == pytest.approx(6.0)
    assert "fare_revenue" not in k


def test_modal_shares_are_all_corrected_together():
    """Parcel-persons only ever make drt trips, so they inflate drt and deflate
    everything else -- correcting drt alone would leave shares not summing to 1."""
    rows = [
        row("system", "modal_share_drt", 0.15, "share", "modestats"),
        row("system", "modal_share_car", 0.45, "share", "modestats"),
        row("system", "modal_share_drt_pax_only", 0.077, "share", "output_trips pax-filter"),
        row("system", "modal_share_car_pax_only", 0.508, "share", "output_trips pax-filter"),
    ]
    k = _by_name(pax_only.apply_overrides(rows))

    assert k["modal_share_drt"]["value"] == pytest.approx(0.077)
    assert k["modal_share_car"]["value"] == pytest.approx(0.508)
    assert k["modal_share_drt_incl_parcels"]["value"] == pytest.approx(0.15)
    assert k["modal_share_car_incl_parcels"]["value"] == pytest.approx(0.45)


def test_provenance_row_names_the_still_contaminated_kpis():
    rows = [
        row("passenger", "drt_rides", 5000, "trips", "drt_customer_stats"),
        row("passenger", "drt_rides_pax_only", 1200, "trips", "output_drt_legs pax-filter"),
        row("passenger", "pooling_rate", 0.4, "share", "drt_sharing_metrics"),
        row("system", "drt_dp_over_dt", 0.6, "ratio", "drt_vehicle_stats"),
    ]
    k = _by_name(pax_only.apply_overrides(rows))

    prov = k["parcel_contaminated_kpis"]
    assert prov["kpi_group"] == "meta"
    assert prov["kpi_group"] in KPI_GROUPS
    assert prov["value"] == 2
    assert set(prov["source"].split(",")) == {"pooling_rate", "drt_dp_over_dt"}


def test_economics_cost_per_ride_uses_the_pax_only_denominator():
    """The regression this whole module exists for: before the override,
    economics divided the fleet cost by the PARCEL-CONTAMINATED ride count,
    silently understating EUR/ride on every Shared-Use run."""
    rows = [
        row("system", "fleet_shift_hours", 100.0, "h", "events/fleet file"),
        row("passenger", "drt_rides", 500, "trips", "drt_customer_stats"),
        row("passenger", "drt_rides_pax_only", 100, "trips", "output_drt_legs pax-filter"),
    ]
    pax_only.apply_overrides(rows)
    k = _by_name(economics.extract(rows, fleet_size=None))

    # 100 h * 25 EUR/h = 2500 EUR over 100 PAX rides, not over 500 mixed ones
    assert k["drt_cost_per_ride_placeholder"]["value"] == pytest.approx(25.0)


def test_apply_overrides_is_idempotent():
    """build_kpis calls it once, but a re-run over an already-corrected list
    (e.g. a future caller rebuilding rows) must not double-demote."""
    rows = [
        row("passenger", "drt_rides", 5000, "trips", "drt_customer_stats"),
        row("passenger", "drt_rides_pax_only", 1200, "trips", "output_drt_legs pax-filter"),
    ]
    once = _by_name(pax_only.apply_overrides(rows))
    twice = _by_name(pax_only.apply_overrides(rows))

    assert twice["drt_rides"]["value"] == once["drt_rides"]["value"] == 1200
    assert "drt_rides_incl_parcels_incl_parcels" not in twice
