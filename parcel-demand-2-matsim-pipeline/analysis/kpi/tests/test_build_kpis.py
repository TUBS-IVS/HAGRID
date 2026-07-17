# -*- coding: utf-8 -*-
import gzip
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "drtrun"
MINI_FIX = Path(__file__).parent / "fixtures" / "mini_lmd"
MINI_EVENTS_FIX = Path(__file__).parent / "fixtures" / "mini_events"


def test_build_writes_all_csvs(tmp_path):
    out = build(FIX, no_events=True, out_dir=tmp_path)
    assert (out / "kpis_long.csv").exists()
    assert (out / "kpis_wide.csv").exists()
    assert (out / "kpi_timeseries.csv").exists()
    long_txt = (out / "kpis_long.csv").read_text(encoding="utf-8")
    # drt + freight + economics all present in one canonical file
    assert ";passenger;drt_rides;9171;" in long_txt
    assert ";freight;parcels_total;500;" in long_txt
    assert ";economic;freight_cost_per_parcel;" in long_txt

    # FROZEN-SCHEMA REGRESSION: 1e long-CSV header must not change.
    long_header = long_txt.splitlines()[0]
    assert long_header == ("run_id;study_area;scenario;operation_mode;"
                            "kpi_group;kpi_name;value;unit;source")

    # v2 Plan A Task 8: new per-run CSVs alongside the frozen long/wide/timeseries ones.
    assert (out / "kpi_iterations.csv").exists()
    it_txt = (out / "kpi_iterations.csv").read_text(encoding="utf-8")
    assert it_txt.splitlines()[0] == "run_id;series;iteration;value;unit"

    # drtrun HAS freight (TimeDistance_perCarrier.tsv) -> kpis_provider.csv written.
    assert (out / "kpis_provider.csv").exists()
    prov_txt = (out / "kpis_provider.csv").read_text(encoding="utf-8")
    assert prov_txt.splitlines()[0] == "run_id;provider;kpi_name;value;unit;source"

    assert (out / "kpi_distributions.csv").exists()
    dist_txt = (out / "kpi_distributions.csv").read_text(encoding="utf-8")
    assert dist_txt.splitlines()[0] == "run_id;series;bin_lo;bin_hi;value;unit"
    # drtrun lacks analysis/freight/TimeDistance_perVehicle.tsv, so lmd_tour_distance
    # cannot be exercised here; drtrun DOES have output_drt_legs_drt.csv, so assert on
    # drt_wait instead (network-free, events-free -- works even with no_events=True,
    # where recon is None and occ_*/drt_tour_duration rows are absent as expected).
    assert ";drt_wait;" in dist_txt

    # v2 Plan C Task 3: drtrun's carriers XML has no <tour> elements (summary
    # attributes only), so extract_vehicles finds 0 VehRecords; with
    # no_events=True, recon is None too -> 0 drt rows. build_kpis must not
    # write an empty kpi_vehicles.csv (mirrors the kpis_provider.csv guard).
    assert not (out / "kpi_vehicles.csv").exists()


def test_no_events_dashboard_has_no_leaflet(tmp_path):
    """Task 8 regression: maps are only built in the events branch. With
    no_events=True the dashboard must carry ZERO Leaflet bytes (blocks stays
    None -> render_run_page gets maps=None -> no vendored map head)."""
    out = build(FIX, no_events=True, out_dir=tmp_path)
    html = (out / "kpi_dashboard.html").read_text(encoding="utf-8")
    assert "Leaflet 1.9.4" not in html
    assert not (out / "map_data.json").exists()


def test_build_survives_missing_vehicle_types_xml(tmp_path):
    """v2 Plan A final-review: a bad/missing freight XML (here, the vehicle-types
    gz) must NOT kill the whole build. The DRT path, kpi_iterations.csv, and
    kpi_distributions.csv (all written before the provider block) must be
    preserved; only kpis_provider.csv is allowed to be skipped."""
    d = tmp_path / "drtrun_copy"
    shutil.copytree(FIX, d)
    (d / "DRT_TEST.output_carriersVehicleTypes.xml.gz").unlink()

    out = build(d, no_events=True, out_dir=tmp_path / "out")

    assert (out / "kpi_iterations.csv").exists()
    assert (out / "kpi_distributions.csv").exists()
    assert not (out / "kpis_provider.csv").exists()


def test_build_writes_kpi_vehicles_csv_with_freight(tmp_path):
    """v2 Plan C Task 3: mini_lmd HAS real freight tours (unlike drtrun), so
    build() must wire extract_vehicles and write kpi_vehicles.csv with
    freight rows joined against TimeDistance_perVehicle.tsv."""
    d = tmp_path / "mini_lmd_copy"
    shutil.copytree(MINI_FIX, d)
    # mini_lmd has drt_customer_stats but lacks drt_vehicle_stats (that fixture
    # is freight-focused, not a full DRT fixture) -- drop it so is_drt is False
    # and the build stays on the freight-only path this test targets.
    (d / "MINI.drt_customer_stats_drt.csv").unlink()
    (d / "run_metadata.json").write_text(json.dumps({
        "run_id": "MINI", "run_dir_name": "mini_lmd_copy", "scenario": "LMD_TEST",
        "study_area": "lausitz_hoyerswerda", "operation_mode": "conventional",
        "tag": "", "matsim_iterations": 1, "jsprit_iterations": 1,
    }), encoding="utf-8")

    out = build(d, no_events=True, out_dir=tmp_path / "out")

    assert (out / "kpi_vehicles.csv").exists()
    veh_txt = (out / "kpi_vehicles.csv").read_text(encoding="utf-8")
    lines = veh_txt.splitlines()
    assert lines[0] == (
        "run_id;role;vehicle_id;provider;vehicle_type;distance_km;duration_h;"
        "travel_h;parcels;stops;load_factor;excluded;occupied_h;active_h;"
        "shift_h;ratio_active")
    assert ";freight;freight_dhl_veh_dhl_ct_cep_size_s_h8_v0_0;dhl;" in veh_txt
    assert not any(";drt;" in line for line in lines[1:])


def _make_mini_events_run(tmp_path):
    """Copy mini_events into tmp_path as a run dir with a minimal run_metadata.json
    (prefix "MINI"). Also: (a) drops any stray *_filtered.txt event caches that may
    sit next to the fixture (leftovers from earlier ad-hoc runs against the fixture
    dir itself) so events_cache.ensure_caches regenerates them fresh from the
    output_events.xml.gz below, and (b) rewrites the fixture's "drt_veh_1" vehicle id
    to "drt_1" in THIS COPY ONLY. (b) works around a pre-existing, unrelated bug in
    analysis/drt-headline/drt_service_time.py's reconstruct() vehicle sort key
    (`int(v.split("_")[1]) if v.split("_")[-1].isdigit() else 0`), which assumes
    "drt_<int>" ids and raises ValueError on "drt_veh_1" (splits to "veh", not an
    int) -- discovered while building this coverage-only test. Fixing that bug is
    out of scope for this review-fix (production-code changes are not requested
    here), so the workaround lives only in this test's tmp_path copy; the committed
    fixture under tests/fixtures/mini_events/ is never modified.
    """
    d = tmp_path / "mini_events_copy"
    shutil.copytree(MINI_EVENTS_FIX, d,
                     ignore=shutil.ignore_patterns("_make_network_fixture.py"))
    for stray in ("MINI.drt_events_filtered.txt", "MINI.freight_events_filtered.txt"):
        p = d / stray
        if p.exists():
            p.unlink()
    events_gz = d / "MINI.output_events.xml.gz"
    with gzip.open(events_gz, "rt", encoding="utf-8") as f:
        txt = f.read().replace("drt_veh_1", "drt_1")
    with gzip.open(events_gz, "wt", encoding="utf-8") as f:
        f.write(txt)
    (d / "run_metadata.json").write_text(json.dumps({
        "run_id": "MINI", "run_dir_name": "mini_events_copy", "scenario": "DRT_TEST",
        "study_area": "lausitz_hoyerswerda", "operation_mode": "conventional",
        "tag": "", "matsim_iterations": 1, "jsprit_iterations": 1,
    }), encoding="utf-8")
    return d


def test_build_geometry_block_emits_drt_tour_distance_with_network(tmp_path):
    """v2 Plan D Task 5 review-fix: build_kpis.build's network-geometry block
    (build_kpis.py:93-118) had zero coverage -- both build() tests above use
    no_events=True, which leaves drt_cache None and skips the block entirely.
    Here events are enabled (no_events=False) AND the network file is present,
    so reconstruct_drt_paths()/load_link_geometry() must run and feed
    veh_km/occ_km_shares into distributions.extract(). mini_events' DRT links
    ("d1", "l3") are NOT present in the mini network fixture ("l1"/"l2"/"l9"),
    so veh_km comes back {"...": 0.0, ...} and occ_km_shares {} -- that is
    EXPECTED (see geometry/distributions tests for the non-degenerate cases)
    and still proves the wiring here: drt_tour_distance rows are written and
    the degenerate all-zero km values don't crash binning."""
    d = _make_mini_events_run(tmp_path)

    out = build(d, no_events=False, out_dir=tmp_path / "out")

    dist_txt = (out / "kpi_distributions.csv").read_text(encoding="utf-8")
    rows = [line.split(";") for line in dist_txt.splitlines()[1:] if line]
    assert any(r[1] == "drt_tour_distance" for r in rows), dist_txt


def test_build_geometry_block_skips_gracefully_without_network(tmp_path, capsys):
    """Companion to the test above: with the network file absent, build() must
    not raise and must print the ASCII skip note (build_kpis.py:118) instead of
    computing veh_km/occ_km_shares, so kpi_distributions.csv still gets written
    without a drt_tour_distance series."""
    d = _make_mini_events_run(tmp_path)
    (d / "MINI.output_network.xml.gz").unlink()

    out = build(d, no_events=False, out_dir=tmp_path / "out")

    captured = capsys.readouterr()
    assert "network file absent" in captured.out
    dist_txt = (out / "kpi_distributions.csv").read_text(encoding="utf-8")
    assert "drt_tour_distance" not in dist_txt
