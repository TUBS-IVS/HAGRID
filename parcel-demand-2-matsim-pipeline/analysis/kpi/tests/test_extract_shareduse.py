# -*- coding: utf-8 -*-
"""1c Task 8: extract_shareduse.py -- channel/delta stats from
shareduse_channel_stats.csv (Task 7 format) plus D10 pax-only corrected
passenger KPIs and the best-effort D10(c) fare split."""
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_kpis
import extract_shareduse as es
from common import KPI_GROUPS

FIX = Path(__file__).parent / "fixtures" / "shareduse"
# Real runs write the handler CSV run-ID-prefixed; the fixture mirrors that
# ("SHAREDUSE_TEST.shareduse_channel_stats.csv"), so the predicate/extractor
# are exercised against the actual on-disk filename convention.
PREFIX = "SHAREDUSE_TEST"


def _meta(prefix):
    return SimpleNamespace(prefix=prefix)


def _rows_by_name(rows):
    return {r["kpi_name"]: r for r in rows}


def _seed_stats(dirpath, prefix):
    """Copy the channel-stats fixture into dirpath under the run-ID-prefixed
    name the extractor reads ({prefix}.shareduse_channel_stats.csv), mirroring
    MATSim's prefixed output."""
    (dirpath / (prefix + ".shareduse_channel_stats.csv")).write_text(
        (FIX / (PREFIX + ".shareduse_channel_stats.csv")).read_text(encoding="utf-8"),
        encoding="utf-8")


def test_has_shareduse_stats_predicate(tmp_path):
    assert es.has_shareduse_stats(FIX, meta=_meta(PREFIX)) is True
    assert es.has_shareduse_stats(tmp_path, meta=_meta(PREFIX)) is False


def test_registered_in_build_kpis_extractors():
    assert (es.has_shareduse_stats, es.extract) in build_kpis.EXTRACTORS


def test_channel_and_freight_rows_from_stats_csv():
    """Values pinned to the exact 'metric;value' lines from Task 7's
    SharedUseKpiHandlerTest#tracksSegmentsAndWritesCsv fixture (post
    review C1/C2/I1/F8 handler format)."""
    k = _rows_by_name(es.extract(FIX, "SHAREDUSE_TEST"))

    assert k["undelivered_rate"]["kpi_group"] == "channel"
    assert k["undelivered_rate"]["value"] == pytest.approx(0.4)
    assert k["delivery_rate_total"]["kpi_group"] == "channel"
    assert k["delivery_rate_total"]["value"] == pytest.approx(0.6)
    assert k["share_channel_door"]["kpi_group"] == "channel"
    assert k["share_channel_door"]["value"] == pytest.approx(1.0)
    assert k["share_channel_locker"]["kpi_group"] == "channel"
    assert k["share_channel_locker"]["value"] == pytest.approx(0.0)

    # I1: the FULL segment decomposition reaches kpis_long (was a 7-metric subset)
    assert k["segments_injected"]["kpi_group"] == "channel"
    assert k["segments_injected"]["value"] == 2
    assert k["segments_submitted"]["value"] == 2
    assert k["segments_never_submitted"]["value"] == 0
    assert k["segments_delivered"]["value"] == 1
    assert k["segments_delivered_late"]["value"] == 0
    assert k["segments_window_expired"]["value"] == 0
    assert k["segments_pending_open"]["value"] == 1
    for name in ("segments_injected", "segments_submitted", "segments_never_submitted",
                 "segments_delivered", "segments_delivered_late",
                 "segments_window_expired", "segments_pending_open"):
        assert k[name]["kpi_group"] == "channel"
        assert k[name]["unit"] == "segments"

    assert k["parcels_submitted"]["kpi_group"] == "freight"
    assert k["parcels_submitted"]["value"] == 5
    assert k["parcels_injected"]["value"] == 5
    assert k["parcels_never_submitted"]["value"] == 0
    assert k["parcels_delivered"]["value"] == 3
    assert k["parcels_delivered_late"]["value"] == 0
    assert k["parcels_undelivered"]["value"] == 2
    for name in ("parcels_injected", "parcels_never_submitted", "parcels_delivered_late"):
        assert k[name]["kpi_group"] == "freight"
        assert k[name]["unit"] == "parcels"

    # C1: renamed from mean_delivery_delay_s; the source spells out the censoring
    assert k["mean_time_to_delivery_s"]["kpi_group"] == "freight"
    assert k["mean_time_to_delivery_s"]["value"] == pytest.approx(1800.0)
    assert "right-censored" in k["mean_time_to_delivery_s"]["source"]
    assert "mean_delivery_delay_s" not in k

    for name in ("undelivered_rate", "share_channel_door", "share_channel_locker",
                 "parcels_submitted", "parcels_delivered", "parcels_undelivered"):
        assert k[name]["source"] == "shareduse_channel_stats"


def test_legacy_stats_csv_still_extracts_old_rows(tmp_path):
    """Tolerant reading (I1): a channel-stats CSV from a FINISHED run written by
    the pre-review handler (13 metrics, mean_delivery_delay_s, no injected/late
    counters) must still yield the 7 legacy rows without a KeyError -- the new
    keys are simply absent."""
    (tmp_path / "LEGACY.shareduse_channel_stats.csv").write_text(
        "metric;value\n"
        "segments_submitted;2\n"
        "segments_delivered;1\n"
        "segments_rejected_final;0\n"
        "segments_window_expired;0\n"
        "segments_pending_open;1\n"
        "segments_pending_eod;1\n"
        "parcels_submitted;5\n"
        "parcels_delivered;3\n"
        "parcels_undelivered;2\n"
        "undelivered_rate;0.4\n"
        "share_channel_door;1.0\n"
        "share_channel_locker;0.0\n"
        "mean_delivery_delay_s;1800.0\n",
        encoding="utf-8")

    k = _rows_by_name(es.extract(tmp_path, "LEGACY"))

    # the 7 rows the old extractor emitted are all still there ...
    assert k["undelivered_rate"]["value"] == pytest.approx(0.4)
    assert k["share_channel_door"]["value"] == pytest.approx(1.0)
    assert k["share_channel_locker"]["value"] == pytest.approx(0.0)
    assert k["parcels_submitted"]["value"] == 5
    assert k["parcels_delivered"]["value"] == 3
    assert k["parcels_undelivered"]["value"] == 2
    # ... the delay arrives under the NEW name via the legacy-key fallback, with
    # an HONEST provenance label (pre-I1 value included late deliveries -- it must
    # not claim the new in-window-only semantics) ...
    assert k["mean_time_to_delivery_s"]["value"] == pytest.approx(1800.0)
    assert "pre-I1" in k["mean_time_to_delivery_s"]["source"]
    assert "in-window only" not in k["mean_time_to_delivery_s"]["source"]
    assert "mean_delivery_delay_s" not in k
    # ... and keys the old handler never wrote are absent, not zero-filled.
    for absent in ("segments_injected", "segments_never_submitted", "parcels_injected",
                   "parcels_never_submitted", "parcels_delivered_late",
                   "segments_delivered_late", "delivery_rate_total"):
        assert absent not in k


def test_legacy_zero_delivery_delay_pseudo_result_suppressed(tmp_path):
    """Review pass on the fix package: the OLD handler wrote
    `mean_delivery_delay_s;0.0` even with ZERO deliveries (the chi=0 probe --
    exactly the C1 pseudo-result the rename kills). Re-extracting such a legacy
    CSV must NOT resurrect the 0.0 under the new name."""
    (tmp_path / "PROBE.shareduse_channel_stats.csv").write_text(
        "metric;value\n"
        "segments_submitted;2929\n"
        "segments_delivered;0\n"
        "segments_rejected_final;0\n"
        "segments_window_expired;2929\n"
        "segments_pending_open;0\n"
        "segments_pending_eod;2929\n"
        "parcels_submitted;6188\n"
        "parcels_delivered;0\n"
        "parcels_undelivered;6188\n"
        "undelivered_rate;1.0\n"
        "share_channel_door;1.0\n"
        "share_channel_locker;0.0\n"
        "mean_delivery_delay_s;0.0\n",
        encoding="utf-8")

    k = _rows_by_name(es.extract(tmp_path, "PROBE"))

    assert "mean_time_to_delivery_s" not in k
    assert "mean_delivery_delay_s" not in k
    assert k["undelivered_rate"]["value"] == pytest.approx(1.0)


def test_delivered_late_rows_reach_kpis_long(tmp_path):
    """I1/F4 delivered-late split: nonzero late counters must be exported."""
    (tmp_path / "LATE.shareduse_channel_stats.csv").write_text(
        "metric;value\n"
        "segments_submitted;3\n"
        "segments_delivered;1\n"
        "segments_delivered_late;2\n"
        "parcels_submitted;10\n"
        "parcels_delivered;3\n"
        "parcels_delivered_late;7\n"
        "parcels_undelivered;7\n"
        "undelivered_rate;0.7\n",
        encoding="utf-8")

    k = _rows_by_name(es.extract(tmp_path, "LATE"))

    assert k["segments_delivered_late"]["kpi_group"] == "channel"
    assert k["segments_delivered_late"]["value"] == 2
    assert k["parcels_delivered_late"]["kpi_group"] == "freight"
    assert k["parcels_delivered_late"]["value"] == 7
    # delta counts late as NOT within-window: undelivered includes the 7 late parcels
    assert k["parcels_undelivered"]["value"] == 7


def test_no_delay_row_when_key_absent(tmp_path):
    """C1: the handler OMITS mean_time_to_delivery_s when nothing was delivered
    in-window (chi=0 probe); the extractor must not re-materialize a 0.0."""
    (tmp_path / "NODELIV.shareduse_channel_stats.csv").write_text(
        "metric;value\n"
        "segments_submitted;3\n"
        "segments_delivered;0\n"
        "parcels_submitted;10\n"
        "parcels_delivered;0\n"
        "parcels_undelivered;10\n"
        "undelivered_rate;1.0\n",
        encoding="utf-8")

    k = _rows_by_name(es.extract(tmp_path, "NODELIV"))

    assert "mean_time_to_delivery_s" not in k
    assert "mean_delivery_delay_s" not in k
    assert k["undelivered_rate"]["value"] == pytest.approx(1.0)


def test_pax_only_corrections_exclude_parcel_legs():
    """Fixture legs: 3 pax legs (waitTime 300/400/100) + 2 parcel_ legs
    (waitTime 9999/8888, deliberately huge outliers). drt_rides_pax_only must
    count ONLY the 3 pax legs, and wait_mean/median_pax_only must average
    ONLY those -- if a parcel leg leaked in, the mean would blow past 1000s."""
    k = _rows_by_name(es.extract(FIX, "SHAREDUSE_TEST"))

    assert k["drt_rides_pax_only"]["kpi_group"] == "passenger"
    assert k["drt_rides_pax_only"]["value"] == 3
    assert k["wait_mean_pax_only"]["value"] == pytest.approx((300 + 400 + 100) / 3.0)
    assert k["wait_median_pax_only"]["value"] == pytest.approx(300.0)
    assert k["wait_mean_pax_only"]["value"] < 1000.0
    assert k["wait_median_pax_only"]["kpi_group"] == "passenger"


def test_fare_split_best_effort_d10c():
    """fareForLeg is a native column on the same legs CSV: pax legs carry
    2.0 EUR each (x3 = 6.0), parcel legs carry 1.5 EUR each (x2 = 3.0)."""
    k = _rows_by_name(es.extract(FIX, "SHAREDUSE_TEST"))

    assert k["fare_revenue_pax_only"]["kpi_group"] == "economic"
    assert k["fare_revenue_pax_only"]["value"] == pytest.approx(6.0)
    assert k["parcel_fare_revenue"]["kpi_group"] == "economic"
    assert k["parcel_fare_revenue"]["value"] == pytest.approx(3.0)
    # fares ARE configured here -> the I3 not-configured flag must not fire
    assert "fare_not_configured" not in k


def test_fare_rows_suppressed_when_all_fares_zero(tmp_path):
    """I3: fareForLeg is 0.0 per leg when no DRT fare is configured -- an
    all-zero pax AND parcel sum must suppress the fare rows (they would render
    as a spurious '0 EUR revenue' finding) and emit the meta flag instead."""
    _seed_stats(tmp_path, "ZEROFARE")
    (tmp_path / "ZEROFARE.output_drt_legs_drt.csv").write_text(
        "submissionTime;departureTime;personId;requestId;vehicleId;waitTime;fareForLeg\n"
        "25000;25200;p1;drt_1;drt_veh_1;300;0.0\n"
        "30000;30100;parcel_dhl_1_B2C;drt_4;drt_veh_2;9999;0.0\n",
        encoding="utf-8")

    k = _rows_by_name(es.extract(tmp_path, "ZEROFARE"))

    assert "fare_revenue_pax_only" not in k
    assert "parcel_fare_revenue" not in k
    assert k["fare_not_configured"]["kpi_group"] == "meta"
    assert k["fare_not_configured"]["value"] == 1
    assert k["fare_not_configured"]["unit"] == "flag"


def test_all_rows_have_five_keys_and_valid_kpi_group():
    rows = es.extract(FIX, "SHAREDUSE_TEST")
    assert len(rows) > 0
    for r in rows:
        assert set(r.keys()) == {"kpi_group", "kpi_name", "value", "unit", "source"}
        assert r["kpi_group"] in KPI_GROUPS


def test_extract_skips_pax_rows_gracefully_without_legs_file(tmp_path):
    """Only shareduse_channel_stats.csv present (no legs CSV for this
    prefix) -- channel/freight rows must still be emitted, but none of the
    legs-derived passenger/economic pax-only rows."""
    _seed_stats(tmp_path, "NO_LEGS")

    rows = es.extract(tmp_path, "NO_LEGS")
    k = _rows_by_name(rows)

    assert "parcels_submitted" in k
    assert "drt_rides_pax_only" not in k
    assert "wait_mean_pax_only" not in k
    assert "fare_revenue_pax_only" not in k


def test_extract_omits_wait_rows_when_pax_slice_is_empty(tmp_path):
    """Legs CSV with ONLY parcel_ legs (no pax at all): drt_rides_pax_only
    must be 0, and wait_mean/median_pax_only must be omitted entirely rather
    than emitting NaN (pandas .mean()/.median() on 0 rows -> NaN)."""
    _seed_stats(tmp_path, "ONLYPARCEL")
    (tmp_path / "ONLYPARCEL.output_drt_legs_drt.csv").write_text(
        "submissionTime;departureTime;personId;requestId;vehicleId;waitTime;fareForLeg\n"
        "30000;30100;parcel_dhl_1_B2C;drt_4;drt_veh_2;9999;1.5\n",
        encoding="utf-8")

    rows = es.extract(tmp_path, "ONLYPARCEL")
    k = _rows_by_name(rows)

    assert k["drt_rides_pax_only"]["value"] == 0
    assert "wait_mean_pax_only" not in k
    assert "wait_median_pax_only" not in k
    # fare split still computable (pax sum over zero rows is a clean 0.0, no NaN)
    assert k["fare_revenue_pax_only"]["value"] == pytest.approx(0.0)
    assert k["parcel_fare_revenue"]["value"] == pytest.approx(1.5)


def test_extract_skips_fare_rows_when_column_absent(tmp_path):
    """Older/alternate legs CSV without fareForLeg: fare rows must be
    omitted gracefully, while the pax-only wait/ride corrections still work."""
    _seed_stats(tmp_path, "NOFARE")
    (tmp_path / "NOFARE.output_drt_legs_drt.csv").write_text(
        "submissionTime;departureTime;personId;requestId;vehicleId;waitTime\n"
        "25000;25200;p1;drt_1;drt_veh_1;300\n"
        "30000;30100;parcel_dhl_1_B2C;drt_4;drt_veh_2;9999\n",
        encoding="utf-8")

    rows = es.extract(tmp_path, "NOFARE")
    k = _rows_by_name(rows)

    assert k["drt_rides_pax_only"]["value"] == 1
    assert "fare_revenue_pax_only" not in k
    assert "parcel_fare_revenue" not in k
