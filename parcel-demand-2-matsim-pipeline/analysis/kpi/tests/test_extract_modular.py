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
             "tours_completed_late;1", "parcels_served_late;12",
             "tours_rejected_at_splice;3"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _write_stats_zeros(tmp_path, prefix):
    """Division-by-zero guard fixture: delta_parcels = 0 AND tours_dispatched = 0 together.

    NOT a theta=1.0 control arm, despite an earlier version of this docstring saying so
    (review Minor 8). A real theta=1.0 run never dispatches anything, so it has
    parcels_served = 0 and delta_parcels = parcels_planned -- the exact opposite of the
    delta = 0 this fixture needs. The shape here is the OTHER zero-delta case: a run that
    delivered every parcel. tours_dispatched = 0 alongside it is not realistic for such a run
    and is not meant to be; it is set to 0 purely so BOTH divisions are exercised by one
    fixture. See test_theta_one_control_arm_shape for the real control-arm numbers."""
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;0", "tours_dispatched;0",
             "tours_completed;0", "tours_dispatched_incomplete;0", "tours_pending_eod;0",
             "parcels_planned;500", "parcels_expired_pending;0", "parcels_dispatched;0",
             "parcels_served;500", "parcels_dispatched_unserved;0", "parcels_pending_eod;0",
             "delta_parcels;0", "swaps_completed;0", "retooling_hours;0.0",
             "deadhead_km_planned;0.0", "service_km_planned;0.0",
             "freight_vehicle_hours;0.0",
             "tours_completed_late;0", "parcels_served_late;0",
             "tours_rejected_at_splice;0"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _write_stats_theta_one(tmp_path, prefix):
    """The REAL theta=1.0 control arm shape: the gate never opens, so nothing is dispatched,
    nothing is served, every tour sits pending at EOD and delta == parcels_planned."""
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;0", "tours_dispatched;0",
             "tours_completed;0", "tours_dispatched_incomplete;0", "tours_pending_eod;10",
             "parcels_planned;500", "parcels_expired_pending;0", "parcels_dispatched;0",
             "parcels_served;0", "parcels_dispatched_unserved;0", "parcels_pending_eod;500",
             "delta_parcels;500", "swaps_completed;0", "retooling_hours;0.0",
             "deadhead_km_planned;0.0", "service_km_planned;0.0",
             "freight_vehicle_hours;0.0",
             "tours_completed_late;0", "parcels_served_late;0",
             "tours_rejected_at_splice;0"]
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
    # Review Minor 7: the share folds expired_pending AND pending_eod together, so the name
    # says "undispatched" rather than naming only one of its two halves.
    assert by_name[("freight", "delta_share_undispatched")] == pytest.approx((80 + 20) / 150)
    assert ("freight", "delta_share_expired_pending") not in by_name
    assert by_name[("freight", "delta_share_dispatched_incomplete")] == pytest.approx(50 / 150)
    # Tour metrics
    assert by_name[("freight", "tours_planned")] == 10
    assert by_name[("freight", "tours_dispatched")] == 7
    assert by_name[("freight", "tour_completion_rate")] == pytest.approx(6 / 7)
    # Review Finding 3: splicer rejections were invisible and their tours were published as
    # "expired pending", attributing to a too-tight gate what was really a tour that never fit.
    assert by_name[("freight", "tours_rejected_at_splice")] == 3
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
    """Both divisions guarded at once: delta_parcels = 0 (the two delta shares) and
    tours_dispatched = 0 (tour_completion_rate) must emit a clean 0.0, not NaN and not raise.

    delta_parcels = 0 is the all-delivered run; see _write_stats_zeros on why the fixture is
    NOT a theta=1.0 control arm (review Minor 8) and test_theta_one_control_arm_shape for
    what that actually looks like."""
    _write_stats_zeros(tmp_path, "ZEROS")
    rows = extract_modular.extract(tmp_path, "ZEROS")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    assert by_name[("freight", "delta_share_undispatched")] == pytest.approx(0.0)
    assert by_name[("freight", "delta_share_dispatched_incomplete")] == pytest.approx(0.0)
    assert by_name[("freight", "tour_completion_rate")] == pytest.approx(0.0)


def test_theta_one_control_arm_shape(tmp_path):
    """The control arm the sweep is anchored on: the gate never opens, so delta is the WHOLE
    planned volume and all of it is undispatched. This is the shape test_all_zeros... used to
    claim to be (review Minor 8) -- its share is 1.0, not the guarded 0.0."""
    _write_stats_theta_one(tmp_path, "THETA1")
    rows = extract_modular.extract(tmp_path, "THETA1")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    assert by_name[("freight", "parcels_served")] == 0
    assert by_name[("freight", "delta_parcels")] == 500          # == parcels_planned
    assert by_name[("freight", "delta_share_undispatched")] == pytest.approx(1.0)
    assert by_name[("freight", "delta_share_dispatched_incomplete")] == pytest.approx(0.0)
    assert by_name[("freight", "tour_completion_rate")] == pytest.approx(0.0)
    # no dispatch means no modularity cost at all -- the control arm's defining property
    assert by_name[("modular", "swaps_completed")] == 0
    assert by_name[("modular", "freight_vehicle_hours")] == pytest.approx(0.0)
