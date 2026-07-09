# -*- coding: utf-8 -*-
import gzip
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from events_cache import ensure_caches

EVENTS = """<?xml version="1.0" encoding="utf-8"?>
<events version="1.0">
<event time="100.0" type="dvrpTaskStarted" dvrpVehicle="drt_1" taskType="STAY"/>
<event time="200.0" type="PersonEntersVehicle" person="p1" vehicle="drt_1"/>
<event time="300.0" type="actstart" person="freight_dhl_veh_1" actType="service" link="l1"/>
<event time="400.0" type="actend" person="freight_dhl_veh_1" actType="service" link="l1"/>
<event time="500.0" type="actstart" person="p2" actType="home" link="l2"/>
</events>
"""


def _make_run(tmp_path):
    prefix = "DRT_BASELINE_13052025_test"
    run = tmp_path / (prefix + "_iter1_jsprit1")
    run.mkdir()
    with gzip.open(run / (prefix + ".output_events.xml.gz"), "wt", encoding="utf-8") as f:
        f.write(EVENTS)
    return run, prefix


def test_builds_both_caches(tmp_path):
    run, prefix = _make_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)
    drt_lines = drt.read_text(encoding="utf-8").splitlines()
    frt_lines = frt.read_text(encoding="utf-8").splitlines()
    assert len(drt_lines) == 2                       # the two drt_ lines
    assert all("drt_" in l for l in drt_lines)
    assert len(frt_lines) == 1                       # only freight service actstart
    assert 'actType="service"' in frt_lines[0] and "freight" in frt_lines[0]


def test_reuses_existing_caches(tmp_path):
    run, prefix = _make_run(tmp_path)
    drt, frt = ensure_caches(run, prefix)
    stamp = "SENTINEL\n"
    drt.write_text(stamp, encoding="utf-8")          # simulate pre-existing cache
    drt2, frt2 = ensure_caches(run, prefix)
    assert drt2.read_text(encoding="utf-8") == stamp  # untouched when both exist
