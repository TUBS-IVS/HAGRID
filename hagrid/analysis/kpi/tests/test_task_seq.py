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


def _write_events_out_of_t0_order(tmp_path):
    """Zwei abgeschlossene Tasks auf einem Fahrzeug, aber im EVENTSTROM in
    der FALSCHEN Reihenfolge geschrieben: die STAY-Phase (t0=600) steht vor
    der DRIVE-Phase (t0=0). reconstruct() puffert done_tasks in der
    Reihenfolge, in der die dvrpTaskEnded-Zeilen im Stream erscheinen --
    das ist hier NICHT die t0-Reihenfolge. Ohne den `.sort()`-Aufruf in
    reconstruct() kaeme task_seq daher als [STAY, DRIVE] zurueck statt
    [DRIVE, STAY]; Task 4s eigene Fixture schreibt chronologisch und haette
    das nicht entdeckt."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<events>",
             # im Stream ZUERST: die spaeter beginnende STAY-Phase (t0=600)
             '<event time="600.0" type="dvrpTaskStarted" '
             'dvrpVehicle="drt_v1" taskType="STAY" />',
             '<event time="1200.0" type="dvrpTaskEnded" '
             'dvrpVehicle="drt_v1" taskType="STAY" />',
             # im Stream DANACH: die frueher beginnende DRIVE-Phase (t0=0)
             '<event time="0.0" type="dvrpTaskStarted" '
             'dvrpVehicle="drt_v1" taskType="DRIVE" />',
             '<event time="600.0" type="dvrpTaskEnded" '
             'dvrpVehicle="drt_v1" taskType="DRIVE" />',
             "</events>"]
    p = tmp_path / "drt_events_unordered.xml"
    p.write_text("\n".join(lines), encoding="utf-8")
    return str(p)


def test_task_seq_is_sorted_even_when_finished_tasks_arrive_out_of_order(tmp_path):
    import drt_service_time as dst
    recon = dst.reconstruct(_write_events_out_of_t0_order(tmp_path))
    seq = recon["per_veh"]["drt_v1"]["task_seq"]
    assert [t0 for (t0, _, _) in seq] == [0.0, 600.0]
    assert [b for (_, _, b) in seq] == ["DRIVE", "STAY"]


def test_task_seq_is_empty_list_for_a_vehicle_with_no_finished_tasks(tmp_path):
    """Ein Fahrzeug, das im Flottenfile steht, aber im Eventstrom nie einen
    Task abschliesst, muss eine leere Liste tragen -- keinen fehlenden Key
    und kein None, sonst bricht cold_starts_by_regime()."""
    import drt_service_time as dst
    fleet = tmp_path / "fleet.xml"
    fleet.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<vehicles>\n'
        '  <vehicle id="drt_v1" start_link="1" t_0="0.0" t_1="86400.0" '
        'capacity="8"/>\n'
        '  <vehicle id="drt_v2" start_link="1" t_0="0.0" t_1="86400.0" '
        'capacity="8"/>\n'
        '</vehicles>\n', encoding="utf-8")
    recon = dst.reconstruct(_write_events(tmp_path), fleet_file=str(fleet))
    assert recon["per_veh"]["drt_v2"]["task_seq"] == []
