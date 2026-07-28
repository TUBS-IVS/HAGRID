# -*- coding: utf-8 -*-
"""1d Task 13: extract_modular.py -- delta decomposition + modularity-cost rows from
modular_tour_stats.csv (Task 9 format). Unlike Shared-Use, pax side needs NO correction
(design D7): parcels never ride as DVRP passengers, so drt_customer_stats is pax-truth
as-is. This module only surfaces the freight/tour side."""
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extract_modular


def _write_stats(tmp_path, prefix):
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;2", "tours_dispatched;7",
             "tours_completed;6", "tours_dispatched_incomplete;1", "tours_pending_eod;1",
             "parcels_planned;500", "parcels_expired_pending;80", "parcels_dispatched;400",
             "parcels_served;350", "parcels_dispatched_unserved;50", "parcels_pending_eod;20",
             "delta_parcels;150", "swaps_completed;13", "retooling_hours;1.516",
             "deadhead_km_planned;42.5", "service_km_planned;120.0",
             "freight_vehicle_hours;21.75",
             "tours_completed_late;1", "parcels_served_late;12"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _write_stats_zeros(tmp_path, prefix):
    """All-zeros fixture: theta=1.0 control arm (no dispatches) or full-delivery run (no delta)."""
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;0", "tours_dispatched;0",
             "tours_completed;0", "tours_dispatched_incomplete;0", "tours_pending_eod;0",
             "parcels_planned;500", "parcels_expired_pending;0", "parcels_dispatched;0",
             "parcels_served;500", "parcels_dispatched_unserved;0", "parcels_pending_eod;0",
             "delta_parcels;0", "swaps_completed;0", "retooling_hours;0.0",
             "deadhead_km_planned;0.0", "service_km_planned;0.0",
             "freight_vehicle_hours;0.0",
             "tours_completed_late;0", "parcels_served_late;0"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _meta(prefix):
    return SimpleNamespace(prefix=prefix)


def test_predicate_matches_run_id_prefixed_file(tmp_path):
    assert not extract_modular.has_modular_stats(tmp_path, _meta("DRT_MODULAR_X"))
    _write_stats(tmp_path, "DRT_MODULAR_X")
    assert extract_modular.has_modular_stats(tmp_path, _meta("DRT_MODULAR_X"))


def test_extract_emits_delta_decomposition(tmp_path):
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    # Freight counts
    assert by_name[("freight", "parcels_planned")] == 500
    assert by_name[("freight", "parcels_served")] == 350
    assert by_name[("freight", "delta_parcels")] == 150
    assert by_name[("freight", "delta_share_expired_pending")] == pytest.approx((80 + 20) / 150)
    assert by_name[("freight", "delta_share_dispatched_incomplete")] == pytest.approx(50 / 150)
    # Tour metrics
    assert by_name[("freight", "tours_planned")] == 10
    assert by_name[("freight", "tours_dispatched")] == 7
    assert by_name[("freight", "tour_completion_rate")] == pytest.approx(6 / 7)
    # Late delivery
    assert by_name[("freight", "tours_completed_late")] == 1
    assert by_name[("freight", "parcels_served_late")] == 12
    # Modularity costs
    assert by_name[("modular", "swaps_completed")] == 13
    assert by_name[("modular", "retooling_hours")] == pytest.approx(1.516)
    assert by_name[("modular", "deadhead_km_planned")] == pytest.approx(42.5)
    assert by_name[("modular", "service_km_planned")] == pytest.approx(120.0)
    assert by_name[("modular", "freight_vehicle_hours")] == pytest.approx(21.75)


def test_all_zeros_guards_division_by_zero(tmp_path):
    """Verify delta shares and tour_completion_rate emit 0.0 when delta=0 and dispatches=0.

    This is the real output shape of the theta=1.0 control arm (tours_dispatched=0)
    and any run that delivers all parcels (delta_parcels=0). Guards must prevent
    division by zero and emit clean 0.0, not NaN or raise."""
    _write_stats_zeros(tmp_path, "ZEROS")
    rows = extract_modular.extract(tmp_path, "ZEROS")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    assert by_name[("freight", "delta_share_expired_pending")] == pytest.approx(0.0)
    assert by_name[("freight", "delta_share_dispatched_incomplete")] == pytest.approx(0.0)
    assert by_name[("freight", "tour_completion_rate")] == pytest.approx(0.0)
