# -*- coding: utf-8 -*-
import gzip
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from events_cache import ensure_caches, _freight_wanted, FREIGHT_SUFFIX

EVENTS = """<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">
<event time="100.0" type="dvrpTaskStarted" dvrpVehicle="drt_1" taskType="STAY"/>
<event time="200.0" type="PersonEntersVehicle" person="p1" vehicle="drt_1"/>
<event time="300.0" type="actstart" person="freight_dhl_veh_1" actType="service" link="l1"/>
<event time="400.0" type="actend" person="freight_dhl_veh_1" actType="service" link="l1"/>
<event time="500.0" type="actstart" person="p2" actType="home" link="l2"/>
</events>
"""

MINI_FIXTURE = Path(__file__).parent / "fixtures" / "mini_events" / "MINI.output_events.xml.gz"


def _make_run(tmp_path):
    prefix = "DRT_BASELINE_13052025_test"
    run = tmp_path / (prefix + "_iter1_jsprit1")
    run.mkdir()
    with gzip.open(run / (prefix + ".output_events.xml.gz"), "wt", encoding="utf-8") as f:
        f.write(EVENTS)
    return run, prefix


def _make_mini_run(tmp_path):
    prefix = "MINI"
    run = tmp_path / (prefix + "_run")
    run.mkdir()
    shutil.copyfile(MINI_FIXTURE, run / (prefix + ".output_events.xml.gz"))
    return run, prefix


def test_builds_both_caches(tmp_path):
    run, prefix = _make_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)
    drt_lines = drt.read_text(encoding="utf-8").splitlines()
    frt_lines = frt.read_text(encoding="utf-8").splitlines()
    assert len(drt_lines) == 2                       # the two drt_ lines
    assert all("drt_" in l for l in drt_lines)
    assert len(frt_lines) == 2                       # freight service actstart + actend
    assert any('type="actstart"' in l for l in frt_lines)
    assert any('type="actend"' in l for l in frt_lines)
    assert all("freight" in l for l in frt_lines)


def test_reuses_existing_caches(tmp_path):
    run, prefix = _make_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)
    stamp = "SENTINEL\n"
    drt.write_text(stamp, encoding="utf-8")          # simulate pre-existing cache
    drt2, frt2 = ensure_caches(run, prefix)
    assert drt2.read_text(encoding="utf-8") == stamp  # untouched when both exist


def test_freight_wanted_predicate():
    assert _freight_wanted('<event time="1.0" type="entered link" vehicle="freight_x"/>')
    assert _freight_wanted('<event time="1.0" type="actstart" actType="service" person="freight_x"/>')
    assert _freight_wanted('<event time="1.0" type="actend" actType="start" person="freight_x"/>')
    assert not _freight_wanted('<event time="1.0" type="left link" vehicle="freight_x"/>')
    assert not _freight_wanted('<event time="1.0" type="actstart" actType="home" person="p2"/>')


def test_richer_freight_cache_from_mini_fixture(tmp_path):
    run, prefix = _make_mini_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)

    frt_lines = frt.read_text(encoding="utf-8").splitlines()
    assert len(frt_lines) >= 5
    assert any('type="entered link"' in l for l in frt_lines)
    assert any('type="actstart"' in l and 'actType="service"' in l for l in frt_lines)
    assert any('type="actend"' in l for l in frt_lines)
    assert any("drt_freight_shared_1" in l for l in frt_lines)   # shared line lands here too

    drt_lines = drt.read_text(encoding="utf-8").splitlines()
    assert any("drt_veh_1" in l for l in drt_lines)
    assert any("drt_freight_shared_1" in l for l in drt_lines)   # shared line lands here too

    old_name_cache = run / (prefix + ".freight_service_starts.txt")
    assert not old_name_cache.exists()
    assert frt.name.endswith(FREIGHT_SUFFIX)
