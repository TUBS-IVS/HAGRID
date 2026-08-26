# -*- coding: utf-8 -*-
"""Die Task-Sequenz-Durchreichung in drt_service_time (Task 4)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "drt-headline"))


def _write_events(tmp_path):
    """Ein Fahrzeug: DRIVE, kurze STAY, DRIVE, lange STAY, MODULAR_FREIGHT_DRIVE."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<events>"]
    seq = [(0.0, 600.0, "DRIVE"),
           (600.0, 1200.0, "STAY"),
           (1200.0, 1800.0, "DRIVE"),
           (1800.0, 7200.0, "STAY"),
           (7200.0, 9000.0, "MODULAR_FREIGHT_DRIVE")]
    for t0, t1, tt in seq:
        lines.append('<event time="%.1f" type="dvrpTaskStarted" '
                     'dvrpVehicle="drt_v1" taskType="%s" />' % (t0, tt))
        lines.append('<event time="%.1f" type="dvrpTaskEnded" '
                     'dvrpVehicle="drt_v1" taskType="%s" />' % (t1, tt))
    lines.append("</events>")
    p = tmp_path / "drt_events.xml"
    p.write_text("\n".join(lines), encoding="utf-8")
    return str(p)


def test_reconstruct_exposes_sorted_task_seq(tmp_path):
    import drt_service_time as dst
    recon = dst.reconstruct(_write_events(tmp_path))
    seq = recon["per_veh"]["drt_v1"]["task_seq"]
    assert [b for (_, _, b) in seq] == [
        "DRIVE", "STAY", "DRIVE", "STAY", "FREIGHT_DRIVE"]
    assert [t0 for (t0, _, _) in seq] == sorted(t0 for (t0, _, _) in seq)
    assert seq[3][1] - seq[3][0] == pytest.approx(5400.0)


def test_task_seq_is_additive(tmp_path):
    """Die bestehenden Aggregate duerfen sich nicht veraendern."""
    import drt_service_time as dst
    recon = dst.reconstruct(_write_events(tmp_path))
    pv = recon["per_veh"]["drt_v1"]
    assert pv["stay_s"] == pytest.approx(600.0 + 5400.0)
    assert pv["drive_s"] == pytest.approx(600.0 + 600.0)
    assert pv["freight_drive_s"] == pytest.approx(1800.0)
