# -*- coding: utf-8 -*-
"""No silent denominators: when the DVRP fleet file is missing, the capacity-
and shift-denominated KPIs must be OMITTED (with a provenance flag), never
computed against a guessed seat count or a substituted shift window.

Regression context: read_capacity() used to default to 8 seats. That was only
coincidentally right while every fleet on disk was an 8-seater; once
SharedUse.BASE_SEATS went to 10 (2026-07-20) a DRT_BASELINE run with an
unlocatable fleet file would have reported utilisation inflated by 25% with no
warning at all."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "drt-headline"))

import drt_service_time
import maps
import run_meta

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _fleet_xml(tmp_path, capacity):
    f = tmp_path / "fleet.xml"
    f.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<vehicles>\n'
        '  <vehicle id="drt_0" start_link="1" t_0="0.0" t_1="86400.0" capacity="'
        + str(capacity) + '"/>\n'
        '</vehicles>\n', encoding="utf-8")
    return f


def test_read_capacity_returns_none_without_fleet_file(tmp_path):
    assert drt_service_time.read_capacity(None) is None
    assert drt_service_time.read_capacity(str(tmp_path / "nope.xml")) is None


def test_read_capacity_reads_the_real_seat_count(tmp_path):
    """A 10-seat baseline vehicle must read back as 10, not as the old default 8."""
    assert drt_service_time.read_capacity(str(_fleet_xml(tmp_path, 10))) == 10


@pytest.mark.parametrize("raw,expected", [
    ("8", 8),                                  # DrtFleetGenerator scalar
    ("10", 10),
    ("passengers=8,parcels=20", 8),            # MATSim 2D-load dump (Shared-Use)
    ("parcels=20,passengers=10", 10),          # dimension order must not matter
    ("parcels=20", None),                      # 2D load without a seat dimension
    ("", None),
    ("n/a", None),
    (None, None),
])
def test_parse_capacity_handles_both_serialisations(raw, expected):
    """The 2D form is exactly the Shared-Use case: on such a run MATSim dumps
    capacity="passengers=8,parcels=20", which the scalar-only parse could not read
    (it silently became the old default 8 -- right by luck for Shared-Use, wrong for
    a 10-seat baseline)."""
    assert drt_service_time.parse_capacity(raw) == expected


def test_read_capacity_on_a_real_2d_fleet_dump(tmp_path):
    f = tmp_path / "fleet2d.xml"
    f.write_text(
        '<vehicles>\n'
        '  <vehicle id="drt_0" start_link="l0" t_0="0.0" t_1="86400.0"'
        ' capacity="passengers=8,parcels=20"/>\n'
        '</vehicles>\n', encoding="utf-8")
    assert drt_service_time.read_capacity(str(f)) == 8


def test_reconstruct_omits_capacity_and_shift_keys_without_fleet_file(tmp_path):
    events = tmp_path / "events.txt"
    events.write_text(
        '<event time="100.0" type="vehicle enters traffic" vehicle="drt_0" link="1"/>\n',
        encoding="utf-8")

    fl = drt_service_time.reconstruct(str(events), None)["fleet"]

    assert fl["capacity"] is None
    assert fl["fleet_file_known"] is False
    # The four keys that would otherwise carry a fabricated denominator.
    for key in ("util_by_time", "util_by_trips", "ratio_shift", "sum_shift_s"):
        assert key not in fl, key


def test_reconstruct_keeps_capacity_and_shift_keys_with_fleet_file(tmp_path):
    events = tmp_path / "events.txt"
    events.write_text(
        '<event time="100.0" type="vehicle enters traffic" vehicle="drt_0" link="1"/>\n',
        encoding="utf-8")

    fl = drt_service_time.reconstruct(str(events), str(_fleet_xml(tmp_path, 10)))["fleet"]

    assert fl["capacity"] == 10
    assert fl["fleet_file_known"] is True
    for key in ("util_by_time", "util_by_trips", "ratio_shift", "sum_shift_s"):
        assert key in fl, key


def test_maps_read_cap_uses_the_emitted_capacity_kpi(tmp_path):
    """The lookup used to match kpi_name~"capacity" AND kpi_group~"drt" -- a
    combination no extractor ever writes, so DEFAULT_CAP always won."""
    analysis = tmp_path / "analysis"
    analysis.mkdir()
    (analysis / "kpis_long.csv").write_text(
        "run_id;study_area;scenario;operation_mode;kpi_group;kpi_name;value;unit;source\n"
        "R;lausitz;DRT_BASELINE;conventional;system;drt_vehicle_capacity;10;seats;fleet file\n",
        encoding="utf-8")

    assert maps._read_cap(tmp_path) == 10


def test_maps_read_cap_falls_back_only_when_the_kpi_is_absent(tmp_path):
    analysis = tmp_path / "analysis"
    analysis.mkdir()
    (analysis / "kpis_long.csv").write_text(
        "run_id;study_area;scenario;operation_mode;kpi_group;kpi_name;value;unit;source\n"
        "R;lausitz;DRT_BASELINE;conventional;system;drt_vehicles;80;vehicles;drt_vehicle_stats\n",
        encoding="utf-8")

    assert maps._read_cap(tmp_path) == maps.DEFAULT_CAP


def test_run_meta_marks_the_dir_name_fallback(tmp_path, capsys):
    """A run whose metadata write failed (writeRunMetadataSafely swallows it)
    must be identifiable afterwards -- the fallback loses fleet_size, which
    silently removes every economics cost KPI."""
    d = tmp_path / "DRT_BASELINE_13052025_fleet80_iter150_jsprit100"
    d.mkdir()

    meta = run_meta.load_run_meta(d)

    assert meta.meta_source == "dir-name"
    assert meta.fleet_size is None
    assert "WARNING" in capsys.readouterr().out


def test_run_meta_reports_json_source_when_present():
    meta = run_meta.load_run_meta(FIX)
    assert meta.meta_source == "run_metadata.json"
