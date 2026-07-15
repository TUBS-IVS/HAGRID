# -*- coding: utf-8 -*-
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "drtrun"
MINI_FIX = Path(__file__).parent / "fixtures" / "mini_lmd"


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
