# -*- coding: utf-8 -*-
"""1d Modular: freight tasks must not be reported as passenger service or as fleet idleness.

Regression context (whole-branch review, Finding 1). `drt_service_time` bucketed
`dvrpTaskStarted` durations by task-type NAME into exactly three keys (STAY/DRIVE/STOP). A
DRT_MODULAR run puts three more kinds of time on the same vehicles:

  MODULAR_FREIGHT_DRIVE  -> landed in a dict key nothing ever read
  MODULAR_FREIGHT_STOP   -> landed in a dict key nothing ever read
  "STOP" (the capsule swap, which inherits DefaultDrtCapacityChangeTask's type and is
                          therefore called literally "STOP") -> counted as a PASSENGER stop

Because `waiting_s = tour_s - drive_s - stop_s` while `tour_s` spans the excursion, the two
unread keys came back out as WAITING -- the whole freight workload of the fleet republished as
"idle between jobs" in `drt_wait_hours_total`, and the retooling published as passenger
service in `drt_service_hours_total`.

`test_freight_time_is_not_waiting_and_swaps_are_not_passenger_stops` pins the arithmetic that
distinguishes the two behaviours; every other test here guards a way the separation could
silently over- or under-reach.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "drt-headline"))

import drt_service_time
import extract_drt


def _task(t0, t1, ttype, veh="drt_0"):
    return [
        '<event time="%.1f" type="dvrpTaskStarted" dvrpVehicle="%s" taskType="%s" dvrpMode="drt"/>'
        % (t0, veh, ttype),
        '<event time="%.1f" type="dvrpTaskEnded" dvrpVehicle="%s" taskType="%s" dvrpMode="drt"/>'
        % (t1, veh, ttype),
    ]


def _tour_event(t, phase, veh="drt_0", tour="dhl_t0"):
    """A ModularTourEvent as MATSim serialises it (attributes from getAttributes())."""
    return ['<event time="%.1f" type="modularTour" tourId="%s" phase="%s" vehicle="%s"'
            ' parcels="12" deadheadMeters="2500.0" serviceMeters="4200.0"/>' % (t, tour, phase, veh)]


def _events(tmp_path, lines, name="events.txt"):
    f = tmp_path / name
    f.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return f


#: One vehicle: morning passenger work, one COMPLETE freight excursion, afternoon passenger
#: work. Every duration is distinct so no two buckets can be confused by coincidence.
#:
#:   100..200  DRIVE                pax   100 s
#:   200..260  STOP                 pax    60 s
#:   260..400  DRIVE                pax   140 s
#:   400..460  STOP                 pax    60 s
#:   460..1000 STAY                 idle  540 s   <- genuinely idle, inside the active window
#:   1000      modularTour DISPATCHED
#:   1000..1100 MODULAR_FREIGHT_DRIVE      100 s  (approach to depot)
#:   1100..1520 STOP                       420 s  (capsule swap OUT -- named "STOP")
#:   1520..1600 MODULAR_FREIGHT_DRIVE       80 s
#:   1600..1720 MODULAR_FREIGHT_STOP       120 s  (freight dwell at a delivery stop)
#:   1720..1800 MODULAR_FREIGHT_DRIVE       80 s  (return leg)
#:   1800..2220 STOP                       420 s  (capsule swap BACK -- named "STOP")
#:   2220      modularTour COMPLETED
#:   2220..2300 STAY                idle    80 s   <- genuinely idle
#:   2300..2400 DRIVE               pax    100 s
#:   2400..2460 STOP                pax     60 s
def _excursion_day():
    lines = []
    lines += _task(0, 100, "STAY")
    lines += _task(100, 200, "DRIVE")
    lines += _task(200, 260, "STOP")
    lines += _task(260, 400, "DRIVE")
    lines += _task(400, 460, "STOP")
    lines += _task(460, 1000, "STAY")
    lines += _tour_event(1000, "DISPATCHED")
    lines += _task(1000, 1100, "MODULAR_FREIGHT_DRIVE")
    lines += _task(1100, 1520, "STOP")
    lines += _task(1520, 1600, "MODULAR_FREIGHT_DRIVE")
    lines += _task(1600, 1720, "MODULAR_FREIGHT_STOP")
    lines += _task(1720, 1800, "MODULAR_FREIGHT_DRIVE")
    lines += _task(1800, 2220, "STOP")
    lines += _tour_event(2220, "COMPLETED")
    lines += _task(2220, 2300, "STAY")
    lines += _task(2300, 2400, "DRIVE")
    lines += _task(2400, 2460, "STOP")
    return lines


PAX_DRIVE_S = 100 + 140 + 100      # 340
PAX_STOP_S = 60 + 60 + 60          # 180 -- the three PASSENGER stops only
FREIGHT_DRIVE_S = 100 + 80 + 80    # 260
FREIGHT_STOP_S = 120
RETOOLING_S = 420 + 420            # 840 -- the two capsule swaps
ACTIVE_S = 2460 - 100              # first productive start .. last productive end
IDLE_STAY_S = 540 + 80             # 620 -- the only genuinely idle time in that window


def test_freight_time_is_not_waiting_and_swaps_are_not_passenger_stops(tmp_path):
    fl = drt_service_time.reconstruct(str(_events(tmp_path, _excursion_day())), None)["fleet"]

    # (a) the three freight components are separated out, each into its own quantity
    assert fl["freight_drive_s"] == pytest.approx(FREIGHT_DRIVE_S)
    assert fl["freight_stop_s"] == pytest.approx(FREIGHT_STOP_S)
    assert fl["retooling_s"] == pytest.approx(RETOOLING_S)
    assert fl["freight_s"] == pytest.approx(FREIGHT_DRIVE_S + FREIGHT_STOP_S + RETOOLING_S)

    # (b) drive/stop stay PASSENGER quantities. stop_s is the assertion that fails under the
    # old code: it would read 180 + 840 = 1020, i.e. 14 min of capsule retooling per excursion
    # published as passenger service time.
    assert fl["drive_s"] == pytest.approx(PAX_DRIVE_S)
    assert fl["stop_s"] == pytest.approx(PAX_STOP_S)

    # (c) waiting is REAL idleness and nothing else. Under the old code the two unread freight
    # keys fell through to here: 2360 - 340 - 1020 = 1000 s, of which 380 s was freight work.
    assert fl["tour_s"] == pytest.approx(ACTIVE_S)
    assert fl["waiting_s"] == pytest.approx(IDLE_STAY_S)

    # (d) the composition closes exactly -- no component silently double-counted or dropped
    assert (fl["drive_s"] + fl["stop_s"] + fl["freight_s"] + fl["waiting_s"]
            == pytest.approx(fl["tour_s"]))
    assert fl["modular_freight_seen"] is True


def test_baseline_run_is_untouched_by_the_freight_separation(tmp_path):
    """No modularTour events, no freight task types: every component 0.0, waiting unchanged,
    and no 1d provenance row. A correction that also moved baseline numbers would invalidate
    every run this study compares 1d against."""
    lines = []
    lines += _task(0, 100, "STAY")
    lines += _task(100, 200, "DRIVE")
    lines += _task(200, 260, "STOP")
    lines += _task(260, 500, "STAY")
    lines += _task(500, 600, "DRIVE")
    lines += _task(600, 660, "STOP")

    fl = drt_service_time.reconstruct(str(_events(tmp_path, lines)), None)["fleet"]

    assert fl["modular_freight_seen"] is False
    assert fl["freight_drive_s"] == 0.0
    assert fl["freight_stop_s"] == 0.0
    assert fl["retooling_s"] == 0.0
    assert fl["drive_s"] == pytest.approx(200.0)
    assert fl["stop_s"] == pytest.approx(120.0)
    # tour 100..660 = 560; productive 320; the 240 s STAY between the two jobs is the waiting
    assert fl["waiting_s"] == pytest.approx(240.0)
    assert extract_drt._modular_rows(fl) == []


def test_passenger_stops_outside_a_freight_window_are_never_retooling(tmp_path):
    """The window is the ONLY thing separating a swap from a passenger stop, so a window that
    over-reaches would quietly convert passenger service into retooling. Both pax stops here
    sit outside the bracket -- one before DISPATCHED, one after COMPLETED."""
    fl = drt_service_time.reconstruct(str(_events(tmp_path, _excursion_day())), None)["fleet"]
    # 3 pax stops x 60 s survive as passenger stop time; only the 2 x 420 s swaps moved out
    assert fl["stop_s"] == pytest.approx(180.0)
    assert fl["retooling_s"] == pytest.approx(840.0)


def test_incomplete_excursion_keeps_its_freight_time_out_of_waiting(tmp_path):
    """A tour DISPATCHED but never COMPLETED (`tours_dispatched_incomplete`) has no swap-back
    and no closing event. Its window must stay open to the end of the day, or the swap-out
    would be the last thing classified correctly and everything after it would fall back into
    passenger/idle time -- understating freight exactly in the saturated arms where it matters."""
    lines = []
    lines += _task(100, 200, "DRIVE")
    lines += _tour_event(1000, "DISPATCHED")
    lines += _task(1000, 1100, "MODULAR_FREIGHT_DRIVE")
    lines += _task(1100, 1520, "STOP")               # swap out
    lines += _task(1520, 1700, "MODULAR_FREIGHT_STOP")
    # ... day ends here: no return leg, no swap back, no COMPLETED event

    fl = drt_service_time.reconstruct(str(_events(tmp_path, lines)), None)["fleet"]

    assert fl["retooling_s"] == pytest.approx(420.0)   # the swap-out, not a passenger stop
    assert fl["stop_s"] == 0.0
    assert fl["freight_drive_s"] == pytest.approx(100.0)
    assert fl["freight_stop_s"] == pytest.approx(180.0)
    assert fl["modular_freight_seen"] is True


def test_freight_windows_bracket_each_excursion_separately():
    """Two excursions on one vehicle, the second dispatched in the very simstep the first
    completed. Sorting must close before it opens, or the two merge into one window and the
    passenger work that could occur between them would be misread as freight."""
    assert drt_service_time.freight_windows(
        [(100.0, +1), (500.0, -1), (900.0, +1), (1400.0, -1)]) == [(100.0, 500.0),
                                                                    (900.0, 1400.0)]
    assert drt_service_time.freight_windows(
        [(100.0, +1), (500.0, -1), (500.0, +1), (900.0, -1)]) == [(100.0, 500.0),
                                                                   (500.0, 900.0)]
    # unclosed -> open to +inf (see test_incomplete_excursion_... for why that is correct)
    assert drt_service_time.freight_windows([(100.0, +1)]) == [(100.0, float("inf"))]
    assert drt_service_time.freight_windows([]) == []


def test_modular_rows_publish_the_components_and_the_contamination_marker(tmp_path):
    """The freight time must be VISIBLE, not merely no longer misfiled -- and the KPIs that
    stay contaminated must say so in the CSV, not only in a docstring."""
    fl = drt_service_time.reconstruct(str(_events(tmp_path, _excursion_day())), None)["fleet"]

    rows = extract_drt._modular_rows(fl)
    by_name = {r["kpi_name"]: r for r in rows}

    assert by_name["drt_freight_drive_hours_total"]["value"] == pytest.approx(FREIGHT_DRIVE_S / 3600.0)
    assert by_name["drt_freight_dwell_hours_total"]["value"] == pytest.approx(FREIGHT_STOP_S / 3600.0)
    assert by_name["drt_retooling_hours_total"]["value"] == pytest.approx(RETOOLING_S / 3600.0)
    assert by_name["drt_freight_hours_total"]["value"] == pytest.approx(
        (FREIGHT_DRIVE_S + FREIGHT_STOP_S + RETOOLING_S) / 3600.0)

    marker = by_name[extract_drt.MODULAR_CONTAMINATION_KPI]
    assert marker["kpi_group"] == "meta"
    # the two uncorrectable drt_vehicle_stats KPIs the review calls out by name must appear
    assert "drt_empty_ratio" in marker["source"]
    assert "drt_dp_over_dt" in marker["source"]
    # and the corrected ones must be distinguishable from them, not lumped together
    assert "CORRECTED" in marker["source"]
    assert "NOT CORRECTABLE" in marker["source"]
    assert marker["value"] == (len(extract_drt.MODULAR_UNCORRECTABLE)
                               + len(extract_drt.MODULAR_FREIGHT_IN_WINDOW))
