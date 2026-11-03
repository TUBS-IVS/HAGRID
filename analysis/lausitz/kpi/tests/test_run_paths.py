# -*- coding: utf-8 -*-
"""run_paths: preprocessing dir / input root under HAGRID_OUTPUT_ROOT (final review C1).

The MATSim run dir here sits OUTSIDE the module root (as under an override root), so the old
sibling convention <run_dir>/../../hagrid-output resp. ../../input points at a directory that does
not hold the artefacts. run_metadata.json names the real ones. Where the sibling location is
populated with a DECOY (different content), the assertion also proves precedence: metadata wins.
Old runs without the keys must keep the sibling convention (fallback tests at the end)."""
import json
import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_kpis
import extract_modular
import extract_shareduse
import maps
import run_paths

P = "DRT_MODULAR_13052025_ovr"


def _layout(tmp_path, with_keys=True, decoy=False):
    """module/ holds hagrid-output/<P> and input/; the run dir lives under elsewhere/."""
    module = tmp_path / "module"
    pre = module / "hagrid-output" / P
    pre.mkdir(parents=True)
    inp = module / "input"
    (inp / "lausitz").mkdir(parents=True)
    run_dir = tmp_path / "elsewhere" / "out" / (P + "_iter3_jsprit1")
    run_dir.mkdir(parents=True)
    meta = {"run_id": P, "run_dir_name": run_dir.name}
    if with_keys:
        meta["hagrid_output_dir"] = str(pre)
        meta["input_root"] = str(inp)
    (run_dir / "run_metadata.json").write_text(json.dumps(meta), encoding="utf-8")
    sib_pre = run_dir.parent.parent / "hagrid-output" / P
    sib_inp = run_dir.parent.parent / "input"
    if decoy:
        sib_pre.mkdir(parents=True)
        (sib_inp / "lausitz").mkdir(parents=True)
    return run_dir, pre, inp, sib_pre, sib_inp


# ------------------------------------------------------------------ helper itself

def test_metadata_paths_win_over_the_sibling_convention(tmp_path):
    run_dir, pre, inp, sib_pre, sib_inp = _layout(tmp_path, decoy=True)
    assert run_paths.hagrid_output_dir(run_dir, P) == pre
    assert run_paths.input_root(run_dir) == inp


def test_explicit_argument_wins_over_metadata(tmp_path):
    run_dir, pre, inp, _, _ = _layout(tmp_path)
    assert run_paths.hagrid_output_dir(run_dir, P, explicit=tmp_path / "x") == tmp_path / "x"
    assert run_paths.input_root(run_dir, explicit=tmp_path / "y") == tmp_path / "y"


def test_old_run_without_keys_uses_the_sibling_convention(tmp_path):
    run_dir, _, _, sib_pre, sib_inp = _layout(tmp_path, with_keys=False)
    assert run_paths.hagrid_output_dir(run_dir, P) == run_dir.parent.parent / "hagrid-output" / P
    assert run_paths.input_root(run_dir) == Path(run_dir) / ".." / ".." / "input"


def test_run_without_any_metadata_uses_the_sibling_convention(tmp_path):
    run_dir = tmp_path / "hagrid-matsim-output" / (P + "_iter3_jsprit1")
    run_dir.mkdir(parents=True)
    assert run_paths.hagrid_output_dir(run_dir, P) == tmp_path / "hagrid-output" / P
    assert run_paths.input_root(run_dir) == run_dir / ".." / ".." / "input"


def test_recorded_path_missing_on_this_machine_falls_back(tmp_path, capsys):
    """A run dir copied from another machine records THAT machine's path."""
    run_dir, pre, inp, sib_pre, _ = _layout(tmp_path, decoy=True)
    meta = json.loads((run_dir / "run_metadata.json").read_text(encoding="utf-8"))
    meta["hagrid_output_dir"] = str(tmp_path / "gone" / P)
    meta["input_root"] = str(tmp_path / "gone" / "input")
    (run_dir / "run_metadata.json").write_text(json.dumps(meta), encoding="utf-8")
    assert run_paths.hagrid_output_dir(run_dir, P) == sib_pre
    assert run_paths.input_root(run_dir) == run_dir / ".." / ".." / "input"
    assert "does not exist here" in capsys.readouterr().out


def test_recorded_dir_of_another_run_is_not_used(tmp_path):
    run_dir, pre, _, _, _ = _layout(tmp_path)
    assert run_paths.hagrid_output_dir(run_dir, "OTHER_RUN") == \
        run_dir.parent.parent / "hagrid-output" / "OTHER_RUN"


def test_default_root_layout_metadata_and_sibling_agree(tmp_path):
    """At the default root the recorded dirs ARE the siblings: no KPI of such a run changes."""
    module = tmp_path / "module"
    run_dir = module / "hagrid-matsim-output" / (P + "_iter3_jsprit1")
    run_dir.mkdir(parents=True)
    pre = module / "hagrid-output" / P
    pre.mkdir(parents=True)
    (module / "input").mkdir()
    (run_dir / "run_metadata.json").write_text(json.dumps(
        {"run_id": P, "hagrid_output_dir": str(pre), "input_root": str(module / "input")}),
        encoding="utf-8")
    assert run_paths.hagrid_output_dir(run_dir, P).resolve() == \
        (run_dir.parent.parent / "hagrid-output" / P).resolve()
    assert run_paths.input_root(run_dir).resolve() == (run_dir / ".." / ".." / "input").resolve()


# ------------------------------------------------------------------ call sites

def test_build_kpis_fleet_file_found_via_metadata(tmp_path):
    run_dir, pre, _, sib_pre, _ = _layout(tmp_path, decoy=True)
    real = pre / (P + "_drt_fleet.xml.gz")
    real.write_bytes(b"x")
    (sib_pre / (P + "_drt_fleet.xml.gz")).write_bytes(b"decoy")
    assert build_kpis._default_fleet_file(run_dir, SimpleNamespace(run_id=P)) == real


def test_build_kpis_fleet_file_missed_without_metadata_keys(tmp_path):
    """Control: the same override layout WITHOUT the keys misses (the C1 bug)."""
    run_dir, pre, _, _, _ = _layout(tmp_path, with_keys=False)
    (pre / (P + "_drt_fleet.xml.gz")).write_bytes(b"x")
    assert build_kpis._default_fleet_file(run_dir, SimpleNamespace(run_id=P)) is None


def _provenance(d, offered):
    (d / (P + extract_shareduse.PROVENANCE_SUFFIX)).write_text(
        "metric;value\nparcels_offered;%d\n" % offered, encoding="utf-8")


def test_shareduse_provenance_found_via_metadata(tmp_path):
    run_dir, pre, _, sib_pre, _ = _layout(tmp_path, decoy=True)
    _provenance(pre, 970)
    _provenance(sib_pre, 1)
    assert extract_shareduse._read_provenance(run_dir, P) == {"parcels_offered": 970}


def test_shareduse_provenance_old_run_sibling_fallback(tmp_path):
    run_dir, _, _, sib_pre, _ = _layout(tmp_path, with_keys=False, decoy=True)
    _provenance(sib_pre, 955)
    assert extract_shareduse._read_provenance(run_dir, P) == {"parcels_offered": 955}


def _carriers(d, parcels):
    d.mkdir(parents=True, exist_ok=True)
    (d / (P + "_lmd_carriers_routed.xml")).write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n<carriers>\n'
        '  <carrier id="hoy_nord">\n    <attributes>\n'
        '      <attribute name="district" class="java.lang.String">hoy_nord</attribute>\n'
        '      <attribute name="numberOfParcels" class="java.lang.Integer">%d</attribute>\n'
        '    </attributes>\n    <capabilities fleetSize="INFINITE"><vehicles/></capabilities>\n'
        '    <services>\n      <service id="s0" capacityDemand="1"/>\n    </services>\n'
        '    <plans></plans>\n  </carrier>\n</carriers>' % parcels, encoding="utf-8")


def test_modular_carriers_found_via_metadata(tmp_path):
    run_dir, pre, _, sib_pre, _ = _layout(tmp_path, decoy=True)
    _carriers(pre / "carriers", 1886)
    _carriers(sib_pre / "carriers", 7)
    rows = {r["kpi_name"]: r["value"] for r in extract_modular._district_rows(run_dir, P)}
    assert rows["district_parcels_hoy_nord"] == 1886


def test_modular_carriers_old_run_sibling_fallback(tmp_path):
    run_dir, _, _, sib_pre, _ = _layout(tmp_path, with_keys=False, decoy=True)
    _carriers(sib_pre / "carriers", 42)
    rows = {r["kpi_name"]: r["value"] for r in extract_modular._district_rows(run_dir, P)}
    assert rows["district_parcels_hoy_nord"] == 42


def _depots_csv(inp, provider):
    hubs = inp / "lausitz" / "hubs"
    hubs.mkdir(parents=True, exist_ok=True)
    (hubs / "lmd-depots.csv").write_text("provider;x;y\n%s;864000;5705000\n" % provider,
                                         encoding="utf-8")


def test_maps_depots_found_via_metadata(tmp_path):
    run_dir, _, inp, _, sib_inp = _layout(tmp_path, decoy=True)
    _depots_csv(inp, "real")
    _depots_csv(sib_inp, "decoy")
    assert [d["name"] for d in maps._depots(run_dir)] == ["real"]


def test_maps_depots_old_run_sibling_fallback(tmp_path):
    run_dir, _, _, _, sib_inp = _layout(tmp_path, with_keys=False, decoy=True)
    _depots_csv(sib_inp, "old")
    assert [d["name"] for d in maps._depots(run_dir)] == ["old"]


def _service_area_shp(inp, x0):
    import geopandas as gpd
    from shapely.geometry import box
    d = inp / "lausitz" / "drt"
    d.mkdir(parents=True, exist_ok=True)
    gpd.GeoDataFrame({"id": [1]}, geometry=[box(x0, 5705000, x0 + 1000, 5706000)],
                     crs="EPSG:25832").to_file(d / "drt-service-area.shp")


def test_maps_service_area_found_via_metadata(tmp_path):
    import pytest
    pytest.importorskip("geopandas")
    run_dir, _, inp, _, sib_inp = _layout(tmp_path, decoy=True)
    _service_area_shp(inp, 864000)
    _service_area_shp(sib_inp, 800000)
    rings, poly = maps._service_area(run_dir)
    assert poly.bounds[0] == 864000


def test_maps_service_area_old_run_sibling_fallback(tmp_path):
    import pytest
    pytest.importorskip("geopandas")
    run_dir, _, _, _, sib_inp = _layout(tmp_path, with_keys=False, decoy=True)
    _service_area_shp(sib_inp, 800000)
    rings, poly = maps._service_area(run_dir)
    assert poly.bounds[0] == 800000
