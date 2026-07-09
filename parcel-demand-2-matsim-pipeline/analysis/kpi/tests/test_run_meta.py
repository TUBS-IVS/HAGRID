# -*- coding: utf-8 -*-
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from run_meta import load_run_meta, parse_legacy_dir_name


def test_parse_legacy_tagged():
    m = parse_legacy_dir_name("DRT_BASELINE_13052025_married120_iter150_jsprit100")
    assert m.run_id == "DRT_BASELINE_13052025_married120"
    assert m.scenario == "DRT_BASELINE"
    assert m.tag == "married120"
    assert m.matsim_iterations == 150
    assert m.jsprit_iterations == 100
    assert m.study_area == "lausitz_hoyerswerda"
    assert m.operation_mode == "conventional"
    assert m.fleet_size is None
    assert m.prefix == "DRT_BASELINE_13052025_married120"


def test_parse_legacy_untagged():
    m = parse_legacy_dir_name("DRT_BASELINE_13052025_iter1_jsprit100")
    assert m.run_id == "DRT_BASELINE_13052025"
    assert m.tag == ""


def test_parse_legacy_multiword_tag_and_lmd():
    m = parse_legacy_dir_name("LMD_BASELINE_13052025_localdepots_stagger_c100_iter0_jsprit100")
    assert m.scenario == "LMD_BASELINE"
    assert m.tag == "localdepots_stagger_c100"
    assert m.study_area == "lausitz_hoyerswerda"


def test_parse_legacy_hannover():
    m = parse_legacy_dir_name("BASECASE_13052025_V1_iter150_jsprit10000")
    assert m.study_area == "hannover"


def test_json_takes_precedence(tmp_path):
    d = tmp_path / "DRT_BASELINE_13052025_married120_iter150_jsprit100"
    d.mkdir()
    (d / "run_metadata.json").write_text(json.dumps({
        "run_id": "DRT_BASELINE_13052025_married120",
        "run_dir_name": d.name,
        "scenario": "DRT_BASELINE",
        "study_area": "lausitz_hoyerswerda",
        "operation_mode": "autonomous",
        "tag": "married120",
        "sim_date": "13052025",
        "matsim_iterations": 150,
        "jsprit_iterations": 100,
        "fleet_size": 120,
        "drt_with_freight": True,
        "created": "2026-07-06T12:00:00",
    }), encoding="utf-8")
    m = load_run_meta(d)
    assert m.operation_mode == "autonomous"     # only the JSON knows this
    assert m.fleet_size == 120
