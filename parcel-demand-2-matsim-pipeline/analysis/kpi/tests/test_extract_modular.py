# -*- coding: utf-8 -*-
"""1d Task 13: extract_modular.py -- delta decomposition + modularity-cost rows from
modular_tour_stats.csv (Task 9 format). Unlike Shared-Use, pax side needs NO correction
(design D7): parcels never ride as DVRP passengers, so drt_customer_stats is pax-truth
as-is. This module only surfaces the freight/tour side.

Task 2 (paper-readiness fixwave, review F1/I6/M1/M2/M4, METHODS-LOG 2.16) added:
- the five Task-1 plan-time metrics (parcels_demand, parcels_unassigned_jsprit,
  parcels_missed_overlay, max_parcels_per_tour, peak_concurrent_swaps) -- absent on
  pre-review CSVs, via stats.get() semantics;
- the 8 raw decomposition counters as their own published rows (M2);
- Python-side re-checking of the five conservation identities + identity 0 + the two
  negative-residual guards (I6) -- previously only Java logged violations, into a log
  this pipeline never reads;
- the OMITTED-not-0.0 convention for undefined ratios (M4): tour_completion_rate and
  delta_share_dispatched_incomplete are omitted when tours_dispatched == 0;
  delta_share_undispatched and delta_share_dispatched_incomplete are omitted when
  delta_parcels == 0;
- the unreadable-CSV policy (M1): a 0-byte or header-only modular_tour_stats.csv now
  degrades to a single meta row instead of raising out of build_kpis.
"""
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extract_modular


def _write_stats(tmp_path, prefix):
    """The conforming base fixture -- every one of the five conservation identities (plus
    identity 0) holds exactly, so this fixture must NEVER produce a
    ("meta", "modular_identity_violated") row. Carries the Task-1 five appended metrics
    (parcels_demand = parcels_planned + parcels_unassigned_jsprit = 500 + 30 = 530, per
    identity 0)."""
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;2", "tours_dispatched;7",
             "tours_completed;6", "tours_dispatched_incomplete;1", "tours_pending_eod;1",
             "parcels_planned;500", "parcels_expired_pending;80", "parcels_dispatched;400",
             "parcels_served;350", "parcels_dispatched_unserved;50", "parcels_pending_eod;20",
             "delta_parcels;150", "swaps_completed;13", "retooling_hours;1.516",
             "deadhead_km_planned;42.5", "service_km_planned;120.0",
             "freight_vehicle_hours;21.75",
             "tours_completed_late;1", "parcels_served_late;12",
             "tours_rejected_at_splice;3",
             # Task 1 (review F1/F3/F5/F7, METHODS-LOG 2.16): appended after
             # tours_rejected_at_splice, in this exact order.
             "parcels_demand;530", "parcels_unassigned_jsprit;30",
             "parcels_missed_overlay;4", "max_parcels_per_tour;8",
             "peak_concurrent_swaps;2"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _write_stats_pre_task1(tmp_path, prefix):
    """The OLD 21-metric format, byte-for-byte what every 1d run predating this fixwave
    wrote (already marked 'alte, falsche Werte' in METHODS-LOG 2.14) -- no Task-1 lines.
    Backward-compat pin: extraction must still succeed and simply omit the five new rows,
    per the brief's explicit decision AGAINST a 'modular_stats_pre_review' meta row."""
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


def _write_stats_identity4_violation(tmp_path, prefix):
    """Same shape as _write_stats but parcels_dispatched_unserved is corrupted so identity 4
    (parcels_dispatched == parcels_served + parcels_dispatched_unserved) fails:
    400 != 350 + 999. Every other identity still conserves, so this fixture isolates
    identity 4 alone -- the meta row's source must name it specifically."""
    lines = ["metric;value",
             "tours_planned;10", "tours_expired_pending;2", "tours_dispatched;7",
             "tours_completed;6", "tours_dispatched_incomplete;1", "tours_pending_eod;1",
             "parcels_planned;500", "parcels_expired_pending;80", "parcels_dispatched;400",
             "parcels_served;350", "parcels_dispatched_unserved;999", "parcels_pending_eod;20",
             "delta_parcels;150", "swaps_completed;13", "retooling_hours;1.516",
             "deadhead_km_planned;42.5", "service_km_planned;120.0",
             "freight_vehicle_hours;21.75",
             "tours_completed_late;1", "parcels_served_late;12",
             "tours_rejected_at_splice;3",
             "parcels_demand;530", "parcels_unassigned_jsprit;30",
             "parcels_missed_overlay;4", "max_parcels_per_tour;8",
             "peak_concurrent_swaps;2"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _write_stats_zeros(tmp_path, prefix):
    """Division-by-zero guard fixture: an EMPTY-DAY run where nothing happens at all -- zero
    parcels planned, zero tours planned. This is the only shape under which
    tours_dispatched == 0 AND delta_parcels == 0 can hold SIMULTANEOUSLY without violating
    identity 4 (parcels_served can only be > 0 via a dispatched tour, so delta == 0 with zero
    dispatch forces parcels_planned == 0 too). An earlier version of this fixture faked
    parcels_planned = 500 / parcels_served = 500 with zero dispatched tours (review Minor 8's
    predecessor problem) -- that shape is NOT reachable by the real accounting and, now that
    this extractor checks the five identities, would itself have tripped identity 3 and 4.
    Fixed here rather than carried forward wrong."""
    lines = ["metric;value",
             "tours_planned;0", "tours_expired_pending;0", "tours_dispatched;0",
             "tours_completed;0", "tours_dispatched_incomplete;0", "tours_pending_eod;0",
             "parcels_planned;0", "parcels_expired_pending;0", "parcels_dispatched;0",
             "parcels_served;0", "parcels_dispatched_unserved;0", "parcels_pending_eod;0",
             "delta_parcels;0", "swaps_completed;0", "retooling_hours;0.0",
             "deadhead_km_planned;0.0", "service_km_planned;0.0",
             "freight_vehicle_hours;0.0",
             "tours_completed_late;0", "parcels_served_late;0",
             "tours_rejected_at_splice;0"]
    (tmp_path / (prefix + ".modular_tour_stats.csv")).write_text("\n".join(lines))


def _write_stats_theta_one(tmp_path, prefix):
    """The REAL theta=1.0 control arm shape: the gate never opens, so nothing is dispatched,
    nothing is served, every tour sits pending at EOD and delta == parcels_planned. Fully
    conserving (checked against all five identities)."""
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
    # No identity violation on the conforming base fixture.
    assert ("meta", "modular_identity_violated") not in by_name


def test_extract_emits_task1_plan_time_rows(tmp_path):
    """Task 1's five appended metrics (review F1/F3/F5/F7, METHODS-LOG 2.16) must reach
    kpis_long.csv with the group/name/unit the brief's Interfaces block fixes."""
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    by_key = {(r["kpi_group"], r["kpi_name"]): r for r in rows}

    def _check(group, name, value, unit):
        r = by_key[(group, name)]
        assert r["value"] == value
        assert r["unit"] == unit

    _check("freight", "parcels_demand", 530, "parcels")
    _check("freight", "parcels_unassigned_jsprit", 30, "parcels")
    _check("freight", "parcels_missed_overlay", 4, "parcels")
    _check("freight", "max_parcels_per_tour", 8, "parcels")
    _check("modular", "peak_concurrent_swaps", 2, "swaps")


def test_extract_emits_raw_decomposition_counters(tmp_path):
    """Review M2: the raw decomposition counters must be individually published so a paper
    table of the delta breakdown can be built straight from kpis_long.csv, without falling
    back to the Java CSV."""
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    assert by_name[("freight", "parcels_expired_pending")] == 80
    assert by_name[("freight", "parcels_pending_eod")] == 20
    assert by_name[("freight", "parcels_dispatched")] == 400
    assert by_name[("freight", "parcels_dispatched_unserved")] == 50
    assert by_name[("freight", "tours_completed")] == 6
    assert by_name[("freight", "tours_dispatched_incomplete")] == 1
    assert by_name[("freight", "tours_expired_pending")] == 2
    assert by_name[("freight", "tours_pending_eod")] == 1


def test_identity_violation_yields_named_meta_row(tmp_path):
    """Review I6: Python must re-check the five conservation identities itself instead of
    trusting the CSV -- Java's own violation log lives in the MATSim run log, which this
    pipeline never reads."""
    _write_stats_identity4_violation(tmp_path, "BAD")
    rows = extract_modular.extract(tmp_path, "BAD")
    by_name = {(r["kpi_group"], r["kpi_name"]): r for r in rows}
    assert ("meta", "modular_identity_violated") in by_name
    meta_row = by_name[("meta", "modular_identity_violated")]
    assert meta_row["value"] == 1
    assert "identity_4" in meta_row["source"]
    assert meta_row["source"].isascii()


def test_conforming_fixture_has_no_identity_violation_row(tmp_path):
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    names = {(r["kpi_group"], r["kpi_name"]) for r in rows}
    assert ("meta", "modular_identity_violated") not in names


def test_shares_sum_to_one_on_conforming_fixture(tmp_path):
    """Review I6: delta_share_undispatched + delta_share_dispatched_incomplete must sum to
    1.0 whenever delta_parcels > 0 -- this is what a negative parcels_pending_eod residual
    would silently break with no visible symptom (the identity checks are the runtime
    tripwire; this is the algebraic property they protect)."""
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    assert by_name[("freight", "delta_parcels")] > 0
    total = (by_name[("freight", "delta_share_undispatched")]
             + by_name[("freight", "delta_share_dispatched_incomplete")])
    assert total == pytest.approx(1.0)


def test_all_zeros_omits_undefined_ratios(tmp_path):
    """Review M4: tours_dispatched == 0 and delta_parcels == 0 together (the empty-day
    fixture) must OMIT tour_completion_rate, delta_share_undispatched, and
    delta_share_dispatched_incomplete entirely -- undefined is not 0.0. A 0.0 on a
    theta-sweep chart plots as a genuine "0% completion" data point rather than "no tours
    were dispatched"."""
    _write_stats_zeros(tmp_path, "ZEROS")
    rows = extract_modular.extract(tmp_path, "ZEROS")
    names = {(r["kpi_group"], r["kpi_name"]) for r in rows}
    assert ("freight", "delta_share_undispatched") not in names
    assert ("freight", "delta_share_dispatched_incomplete") not in names
    assert ("freight", "tour_completion_rate") not in names
    assert ("meta", "modular_identity_violated") not in names


def test_theta_one_control_arm_shape(tmp_path):
    """The control arm the sweep is anchored on: the gate never opens, so delta is the WHOLE
    planned volume and all of it is undispatched. Review M4: tour_completion_rate and
    delta_share_dispatched_incomplete must be ABSENT (tours_dispatched == 0), not 0.0 --
    a 0.0 here would misread as "0% of dispatched tours completed" on a theta-sweep chart.
    delta_share_undispatched stays PRESENT (delta_parcels == 500 != 0) at 1.0."""
    _write_stats_theta_one(tmp_path, "THETA1")
    rows = extract_modular.extract(tmp_path, "THETA1")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    names = set(by_name)
    assert by_name[("freight", "parcels_served")] == 0
    assert by_name[("freight", "delta_parcels")] == 500          # == parcels_planned
    assert by_name[("freight", "delta_share_undispatched")] == pytest.approx(1.0)
    assert ("freight", "delta_share_dispatched_incomplete") not in names
    assert ("freight", "tour_completion_rate") not in names
    # no dispatch means no modularity cost at all -- the control arm's defining property
    assert by_name[("modular", "swaps_completed")] == 0
    assert by_name[("modular", "freight_vehicle_hours")] == pytest.approx(0.0)
    assert ("meta", "modular_identity_violated") not in names


def test_header_only_csv_is_unreadable(tmp_path):
    """Review M1: a header-only modular_tour_stats.csv (metric;value with zero data rows)
    must degrade the same way every other optional input in this pipeline does -- a single
    flagged meta row, not a KeyError propagating out of build_kpis."""
    (tmp_path / "H.modular_tour_stats.csv").write_text("metric;value\n")
    rows = extract_modular.extract(tmp_path, "H")
    assert len(rows) == 1
    assert rows[0]["kpi_group"] == "meta"
    assert rows[0]["kpi_name"] == "modular_stats_unreadable"
    assert rows[0]["value"] == 1
    assert rows[0]["unit"] == "flag"
    assert rows[0]["source"].isascii()


def test_zero_byte_csv_is_unreadable(tmp_path):
    """Review M1: a 0-byte modular_tour_stats.csv (pandas.errors.EmptyDataError at read
    time) must not crash build_kpis either -- same single meta row, no exception."""
    (tmp_path / "Z.modular_tour_stats.csv").write_text("")
    rows = extract_modular.extract(tmp_path, "Z")
    assert len(rows) == 1
    assert rows[0]["kpi_group"] == "meta"
    assert rows[0]["kpi_name"] == "modular_stats_unreadable"
    assert rows[0]["value"] == 1
    assert rows[0]["unit"] == "flag"


def test_missing_csv_predicate_is_false_not_unreadable(tmp_path):
    """Pin (brief Step 1): a MISSING file is a different case from an unreadable one -- it
    must keep the existing has_modular_stats() == False behaviour (extract() is never even
    called; build_kpis's EXTRACTORS loop is gated on the predicate)."""
    assert extract_modular.has_modular_stats(tmp_path, _meta("MISSING")) is False


def test_old_21_metric_csv_omits_task1_rows(tmp_path):
    """Backward-compat (brief Step 1's explicit decision): an OLD 21-metric CSV predating
    this fixwave still extracts everything else; the five new rows are simply absent (no
    'modular_stats_pre_review' meta row -- the brief decided against one)."""
    _write_stats_pre_task1(tmp_path, "OLD")
    rows = extract_modular.extract(tmp_path, "OLD")
    by_name = {(r["kpi_group"], r["kpi_name"]): r["value"] for r in rows}
    # Old rows still extract correctly.
    assert by_name[("freight", "parcels_planned")] == 500
    assert by_name[("freight", "delta_parcels")] == 150
    assert by_name[("freight", "tours_rejected_at_splice")] == 3
    assert by_name[("freight", "parcels_dispatched")] == 400  # raw counter, M2
    # The five Task-1 names are absent.
    assert ("freight", "parcels_demand") not in by_name
    assert ("freight", "parcels_unassigned_jsprit") not in by_name
    assert ("freight", "parcels_missed_overlay") not in by_name
    assert ("freight", "max_parcels_per_tour") not in by_name
    assert ("modular", "peak_concurrent_swaps") not in by_name
    # No spurious meta rows: this CSV parses cleanly and conserves.
    assert ("meta", "modular_stats_unreadable") not in by_name
    assert ("meta", "modular_identity_violated") not in by_name
