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

Task 6 (self-referential capacity budget, plan 2026-09-04) added the budget rows:
budget_active (always written by Java) plus budget_blocked_dispatches /
budget_overrides_expiry (only when the budget ran). The tests below pin the three-state
behaviour -- absent / off / on -- because collapsing any two of them is what makes a budget
arm uninterpretable: an off run publishing zeros would be quoted as "the budget never bound".
"""
import gzip
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


def _write_carriers_xml(tmp_path, prefix, carriers):
    """Task 10: a minimal routed-carriers XML fixture (namespace-free, mirroring
    tests/fixtures/mini_lmd/MINI.output_carriers.xml.gz's own style). `carriers` is a list of
    (carrier_id, district_or_None, number_of_parcels, [service_capacity, ...]) tuples -- a district
    carrier has one CarrierService per pooled stop (LmdCarrierBuilder.buildDistrict), so
    len(service_capacities) is the segment count."""
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', "<carriers>"]
    for carrier_id, district, number_of_parcels, service_caps in carriers:
        lines.append('  <carrier id="%s">' % carrier_id)
        lines.append("    <attributes>")
        if district is not None:
            lines.append('      <attribute name="district" class="java.lang.String">%s'
                          "</attribute>" % district)
        lines.append('      <attribute name="numberOfParcels" class="java.lang.Integer">%d'
                      "</attribute>" % number_of_parcels)
        lines.append("    </attributes>")
        lines.append('    <capabilities fleetSize="INFINITE"><vehicles/></capabilities>')
        lines.append("    <services>")
        for i, cap in enumerate(service_caps):
            lines.append('      <service id="s%d" capacityDemand="%d"/>' % (i, cap))
        lines.append("    </services>")
        lines.append("    <plans></plans>")
        lines.append("  </carrier>")
    lines.append("</carriers>")
    _write_gz(tmp_path / (prefix + ".output_carriers.xml.gz"), lines)
    return lines


def _write_gz(path, lines):
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write("\n".join(lines))




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


def test_per_site_swap_peak_rows_surfaced_from_stats_csv(tmp_path):
    """Task 10 fix round 1: peak_concurrent_swaps_<site> rows (Java already aggregates by
    PHYSICAL SITE, stripping any maxJobsPerDistrict "#<n>" split suffix before the key ever
    reaches this CSV) must reach kpis_long.csv under kpi_group "modular", alongside the untouched
    global peak_concurrent_swaps row -- surfaced by a key-prefix scan since the site set is
    run-specific and unbounded."""
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
             "parcels_demand;530", "parcels_unassigned_jsprit;30",
             "parcels_missed_overlay;4", "max_parcels_per_tour;8",
             "peak_concurrent_swaps;2",
             "peak_concurrent_swaps_hoy_sued;2", "peak_concurrent_swaps_wittichenau;1"]
    (tmp_path / "P.modular_tour_stats.csv").write_text("\n".join(lines))

    rows = extract_modular.extract(tmp_path, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r for r in rows}

    assert by_name[("modular", "peak_concurrent_swaps")]["value"] == 2
    hoy = by_name[("modular", "peak_concurrent_swaps_hoy_sued")]
    assert hoy["value"] == 2
    assert hoy["unit"] == "swaps"
    wit = by_name[("modular", "peak_concurrent_swaps_wittichenau")]
    assert wit["value"] == 1


def test_per_site_swap_peak_rows_absent_on_csv_predating_the_feature(tmp_path):
    """A CSV predating Task 10 (no peak_concurrent_swaps_<site> keys at all) must simply omit
    them -- no meta row, no exception, same tolerance as the pre-existing Task-1 backward-compat
    pin."""
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    names = {r["kpi_name"] for r in rows}
    assert not any(n.startswith("peak_concurrent_swaps_") for n in names)


def _write_stats_with_budget(tmp_path, prefix, active, blocked=None, overrides=None):
    """The conforming base fixture plus Task 6's budget block, written the way Java writes it:
    `budget_active` always, the two counters only when active. Deliberately UNEQUAL counter
    values (7 vs 3) -- equal ones would let a transposed pair pass unnoticed."""
    _write_stats(tmp_path, prefix)
    path = tmp_path / (prefix + ".modular_tour_stats.csv")
    extra = ["budget_active;" + str(active)]
    if active:
        extra.append("budget_blocked_dispatches;" + str(blocked))
        extra.append("budget_overrides_expiry;" + str(overrides))
    path.write_text(path.read_text() + "\n" + "\n".join(extra))


def test_budget_rows_surfaced_when_the_budget_ran(tmp_path):
    """Task 6. The two counters must reach kpis_long.csv under their own names, with the
    units that carry their semantics: blocked dispatches are ATTEMPTS (one tour held all
    morning contributes thousands), overrides are dispatches. The values are unequal so a
    transposition fails here rather than being published as a plausible pair."""
    _write_stats_with_budget(tmp_path, "B", active=1, blocked=7, overrides=3)
    rows = extract_modular.extract(tmp_path, "B")
    by_key = {(r["kpi_group"], r["kpi_name"]): r for r in rows}

    assert by_key[("modular", "budget_active")]["value"] == 1
    assert by_key[("modular", "budget_blocked_dispatches")]["value"] == 7
    assert by_key[("modular", "budget_blocked_dispatches")]["unit"] == "attempts"
    assert by_key[("modular", "budget_overrides_expiry")]["value"] == 3
    assert by_key[("modular", "budget_overrides_expiry")]["unit"] == "dispatches"


def test_budget_off_is_distinguishable_from_budget_on_but_never_bound(tmp_path):
    """THE OFF/ON DISCRIMINATION TEST, Python half. budgetMode=off and "the budget ran and
    never blocked anything" are different runs and must not extract to the same rows. If the
    off case emitted zeros, the two would be identical here and a reader of kpis_long.csv
    could not tell a feature that was switched off from a feature that measured nothing."""
    off_dir = tmp_path / "off"
    on_dir = tmp_path / "on"
    off_dir.mkdir()
    on_dir.mkdir()
    _write_stats_with_budget(off_dir, "X", active=0)
    _write_stats_with_budget(on_dir, "X", active=1, blocked=0, overrides=0)

    off = {(r["kpi_group"], r["kpi_name"]): r["value"]
           for r in extract_modular.extract(off_dir, "X")}
    on = {(r["kpi_group"], r["kpi_name"]): r["value"]
          for r in extract_modular.extract(on_dir, "X")}

    assert off[("modular", "budget_active")] == 0
    assert ("modular", "budget_blocked_dispatches") not in off
    assert ("modular", "budget_overrides_expiry") not in off

    assert on[("modular", "budget_active")] == 1
    # a MEASURED zero: present, and readable as one
    assert on[("modular", "budget_blocked_dispatches")] == 0
    assert on[("modular", "budget_overrides_expiry")] == 0

    assert off != on


def _write_stats_with_ramp(tmp_path, prefix, urgency, samples, p90=None, mx=None):
    """The budget block plus the 2026-09-05 ramp rows, written the way Java writes them:
    `budget_urgency_admits` alongside the other counters, and the chain-ratio PAIR only when
    `chain_ratio_samples` is non-zero. Counter values are pairwise unequal (7 / 3 / 5) so a
    transposition fails here rather than being published as a plausible triple."""
    _write_stats_with_budget(tmp_path, prefix, active=1, blocked=7, overrides=3)
    path = tmp_path / (prefix + ".modular_tour_stats.csv")
    extra = ["budget_urgency_admits;" + str(urgency),
             "chain_ratio_samples;" + str(samples)]
    if samples:
        extra.append("chain_ratio_p90;" + str(p90))
        extra.append("chain_ratio_max;" + str(mx))
    path.write_text(path.read_text() + "\n" + "\n".join(extra))


def test_ramp_rows_surfaced_with_their_own_names(tmp_path):
    """METHODS-LOG 2.58. The ramp counter must reach kpis_long.csv under its OWN name and not
    be folded into budget_overrides_expiry: the two answer opposite questions -- "the ramp
    carried this tour in time" versus "the deadline had already passed and the budget was
    overridden". Summing them would hide exactly the failure the ramp was built to remove."""
    _write_stats_with_ramp(tmp_path, "R", urgency=5, samples=46, p90=1.31, mx=1.44)
    by_key = {(r["kpi_group"], r["kpi_name"]): r
              for r in extract_modular.extract(tmp_path, "R")}

    assert by_key[("modular", "budget_urgency_admits")]["value"] == 5
    assert by_key[("modular", "budget_urgency_admits")]["unit"] == "dispatches"
    # unchanged and NOT merged with the ramp counter
    assert by_key[("modular", "budget_overrides_expiry")]["value"] == 3
    assert by_key[("modular", "chain_ratio_samples")]["value"] == 46
    assert by_key[("modular", "chain_ratio_p90")]["value"] == 1.31
    assert by_key[("modular", "chain_ratio_p90")]["unit"] == "ratio"
    assert by_key[("modular", "chain_ratio_max")]["value"] == 1.44


def test_ramp_counter_zero_is_a_measured_zero(tmp_path):
    """A run in which the ramp never bound must publish a READABLE zero, not an absent row.
    budget_urgency_admits == 0 is the signal that the arm is the plain budget arm under a new
    name and tested no mechanism -- if that case simply omitted the row it would be
    indistinguishable from a pre-2026-09-05 CSV, i.e. from a run where the question does not
    apply at all."""
    _write_stats_with_ramp(tmp_path, "Z", urgency=0, samples=46, p90=1.2, mx=1.3)
    by_key = {(r["kpi_group"], r["kpi_name"]): r["value"]
              for r in extract_modular.extract(tmp_path, "Z")}
    assert by_key[("modular", "budget_urgency_admits")] == 0


def test_chain_ratio_pair_absent_when_nothing_was_dispatched(tmp_path):
    """ABSENCE, not NaN and not zero, is how "no dispatches" is stated -- a ratio of 0.0 is a
    meaningful and very wrong statement about the tours, and a literal NaN parses and then
    propagates silently through any mean taken downstream. chain_ratio_samples still carries
    the fact explicitly, so the absence is never left to be inferred."""
    _write_stats_with_ramp(tmp_path, "N", urgency=0, samples=0)
    by_key = {(r["kpi_group"], r["kpi_name"]): r["value"]
              for r in extract_modular.extract(tmp_path, "N")}
    assert by_key[("modular", "chain_ratio_samples")] == 0
    assert ("modular", "chain_ratio_p90") not in by_key
    assert ("modular", "chain_ratio_max") not in by_key


def test_ramp_rows_absent_on_a_task6_csv_without_them(tmp_path):
    """Backward compat WITHIN the budget block, which is why these lookups use .get() while
    the two Task-6 counters use stats[...]. A CSV written between Task 6 and 2026-09-05 says
    budget_active;1 and legitimately has no ramp rows -- that is a readable old run, not the
    Java-side bug the strict lookups guard against."""
    _write_stats_with_budget(tmp_path, "T6", active=1, blocked=7, overrides=3)
    by_key = {(r["kpi_group"], r["kpi_name"]): r["value"]
              for r in extract_modular.extract(tmp_path, "T6")}
    assert by_key[("modular", "budget_active")] == 1
    assert by_key[("modular", "budget_blocked_dispatches")] == 7
    assert ("modular", "budget_urgency_admits") not in by_key
    assert ("modular", "chain_ratio_samples") not in by_key


def test_budget_rows_absent_on_csv_predating_the_feature(tmp_path):
    """Backward compat, same silent-absence convention as the Task-1 block: a CSV written
    before Task 6 has no budget_active row at all, and that must extract cleanly with no
    budget rows and no flag row apologising for them."""
    _write_stats(tmp_path, "OLDBUD")
    names = {r["kpi_name"] for r in extract_modular.extract(tmp_path, "OLDBUD")}
    assert not any(n.startswith("budget_") for n in names)


def test_budget_active_without_counters_raises_rather_than_degrading(tmp_path):
    """A CSV claiming budget_active;1 while omitting a counter can only come from a Java-side
    bug. This module's documented policy (review Important 1) is that such a bug raises for
    real instead of being relabelled "modular_stats_unreadable" -- the M1 degradation covers
    a 0-byte/header-only file and nothing else. Pinned so a future well-meaning .get() with a
    default cannot quietly publish a fabricated zero."""
    _write_stats(tmp_path, "BROKEN")
    path = tmp_path / "BROKEN.modular_tour_stats.csv"
    path.write_text(path.read_text() + "\nbudget_active;1")
    with pytest.raises(KeyError):
        extract_modular.extract(tmp_path, "BROKEN")


def test_district_rows_from_output_carriers(tmp_path):
    """Task 10 (spec 2026-08-17, "make the idealisations measurable"): district_parcels_<id> /
    district_segments_<id> come straight from the ROUTED carriers XML, not from
    modular_tour_stats.csv -- this is what puts the 89-vs-1886-parcel catchment spread into
    kpis_long.csv instead of a footnote. district_segments counts CarrierService entries (pooled
    stops), not parcels."""
    _write_stats(tmp_path, "P")
    _write_carriers_xml(tmp_path, "P", [
        ("hoy_nord", "hoy_nord", 1886, [900, 986]),
        ("spreetal", "spreetal", 89, [89]),
    ])
    rows = extract_modular.extract(tmp_path, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r for r in rows}
    hoy_parcels = by_name[("freight", "district_parcels_hoy_nord")]
    assert hoy_parcels["value"] == 1886
    assert hoy_parcels["unit"] == "parcels"
    assert by_name[("freight", "district_segments_hoy_nord")]["value"] == 2
    spreetal_parcels = by_name[("freight", "district_parcels_spreetal")]
    assert spreetal_parcels["value"] == 89
    assert by_name[("freight", "district_segments_spreetal")]["value"] == 1


def test_district_rows_skip_legacy_carriers_without_district_attribute(tmp_path):
    """A carrier with no "district" attribute (legacy LmdCarrierBuilder.buildCore,
    single-provider carrier predating the district rework) is not a district and out of this
    metric's scope -- it must not produce a district_parcels_<carrier_id> row under its own id."""
    _write_stats(tmp_path, "P")
    _write_carriers_xml(tmp_path, "P", [("dhl", None, 100, [60, 40])])
    rows = extract_modular.extract(tmp_path, "P")
    names = {r["kpi_name"] for r in rows}
    assert not any(n.startswith("district_parcels_") or n.startswith("district_segments_")
                   for n in names)


def test_district_rows_absent_when_carriers_xml_missing(tmp_path):
    """No output_carriers.xml.gz at all (every OTHER fixture in this file) must degrade to ZERO
    district rows -- no exception, no meta flag. This is an ADDITIVE metric with nothing else
    depending on it, so it fails silent-and-absent rather than loud, unlike modular_tour_stats.csv
    itself (M1)."""
    _write_stats(tmp_path, "P")
    rows = extract_modular.extract(tmp_path, "P")
    names = {r["kpi_name"] for r in rows}
    assert not any(n.startswith("district_parcels_") or n.startswith("district_segments_")
                   for n in names)
    assert ("meta", "district_rows_unavailable") not in {(r["kpi_group"], r["kpi_name"]) for r in rows}


def _write_modular_carriers_xml(root, prefix, carriers):
    """The 1d location AND format: DRT_MODULAR never runs MATSim's carriers module, so its
    carriers are a PREPROCESSING artefact written UNCOMPRESSED to
    hagrid-output/<run_id>/carriers/<run_id>_lmd_carriers_routed.xml -- a SIBLING of the MATSim
    output tree, not a file inside the run dir. Both differences (place and compression) are why
    the district rows used to fall through on every real 1d run: the reader looked only in the run
    dir and called gzip.open unconditionally."""
    lines = _write_carriers_xml(root, prefix + "__unused__", carriers)
    d = root / "hagrid-output" / prefix / "carriers"
    d.mkdir(parents=True, exist_ok=True)
    (d / (prefix + "_lmd_carriers_routed.xml")).write_text(
        "\n".join(lines), encoding="utf-8")


def _modular_run_dir(root, prefix):
    """A run dir nested like the real thing, because the reader resolves the preprocessing
    carriers RELATIVE to it (run_dir.parent.parent / "hagrid-output" / ...). A flat tmp_path
    would miss the sibling directory and make this test pass for the wrong reason."""
    d = root / "hagrid-matsim-output" / (prefix + "_iter150_jsprit100")
    d.mkdir(parents=True, exist_ok=True)
    return d


def test_district_rows_from_modular_preprocessing_carriers(tmp_path):
    """1d reads its districts from the preprocessing carriers file, not from a MATSim output.
    Before the two-candidate lookup this degraded to ZERO district rows on every real 1d run
    while the data sat on disk -- and kpis_long.csv showed nothing at all, only a stdout line
    named the miss."""
    run_dir = _modular_run_dir(tmp_path, "P")
    _write_stats(run_dir, "P")
    _write_modular_carriers_xml(tmp_path, "P", [
        ("hoy_sued#0", "hoy_sued#0", 1589, [1] * 298),
        ("hoy_sued#1", "hoy_sued#1", 2415, [1] * 297),
    ])
    rows = extract_modular.extract(run_dir, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r for r in rows}
    assert by_name[("freight", "district_parcels_hoy_sued#0")]["value"] == 1589
    assert by_name[("freight", "district_segments_hoy_sued#0")]["value"] == 298
    assert by_name[("freight", "district_parcels_hoy_sued#1")]["value"] == 2415
    assert by_name[("freight", "district_segments_hoy_sued#1")]["value"] == 297


def test_matsim_carriers_win_over_the_preprocessing_copy(tmp_path):
    """Precedence, pinned on DIFFERENT parcel counts so the assertion can tell WHICH file was
    read: with both present, MATSim's output_carriers.xml.gz is the EXECUTED plan and must win
    over the preprocessing artefact. A reader that simply tried the new path first would pass the
    test above and still silently re-baseline every LMD_BASELINE run."""
    run_dir = _modular_run_dir(tmp_path, "P")
    _write_stats(run_dir, "P")
    _write_carriers_xml(run_dir, "P", [("hoy_nord", "hoy_nord", 1886, [900, 986])])
    _write_modular_carriers_xml(tmp_path, "P", [("hoy_nord", "hoy_nord", 4242, [1, 1, 1])])
    rows = extract_modular.extract(run_dir, "P")
    by_name = {(r["kpi_group"], r["kpi_name"]): r for r in rows}
    assert by_name[("freight", "district_parcels_hoy_nord")]["value"] == 1886
    assert by_name[("freight", "district_segments_hoy_nord")]["value"] == 2
