# -*- coding: utf-8 -*-
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "drtrun"


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
