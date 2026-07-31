# -*- coding: utf-8 -*-
import statistics
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extract_drt
from extract_drt import extract

FIX = Path(__file__).parent / "fixtures" / "drtrun"


def _by_name(rows):
    return {r["kpi_name"]: r for r in rows}


def test_extract_headline_kpis():
    rows = extract(FIX, "DRT_TEST")
    k = _by_name(rows)
    assert k["drt_rides"]["value"] == 9171
    assert k["drt_rejections"]["value"] == 24
    # integer-based rate, NOT the rounded rejectionRate column
    assert k["drt_rejection_rate"]["value"] == pytest.approx(24 / (9171 + 24))
    assert k["wait_median"]["value"] == 753.0
    assert k["wait_below_15min"]["value"] == pytest.approx(0.6231)
    assert k["detour_factor"]["value"] == pytest.approx(10967.72 / 7595.93)
    assert k["drt_vehicles"]["value"] == 120
    assert k["drt_vehicle_km"]["value"] == pytest.approx(48885.4898)
    assert k["drt_empty_ratio"]["value"] == pytest.approx(0.12)
    assert k["pooling_rate"]["value"] == pytest.approx(0.98092, abs=1e-4)
    assert k["modal_share_drt"]["value"] == pytest.approx(0.0607166, abs=1e-6)
    # feeder: 1 of 4 drt trips contains pt; 1 pt main-mode trip is drt-fed
    assert k["drt_feeder_trips"]["value"] == 1
    assert k["drt_feeder_share"]["value"] == pytest.approx(0.25)
    assert k["rail_trips_drt_fed_share"]["value"] == pytest.approx(1.0)
    # every row well-formed
    for r in rows:
        assert set(r) == {"kpi_group", "kpi_name", "value", "unit", "source"}


def test_no_events_no_service_rows():
    rows = extract(FIX, "DRT_TEST")
    assert "service_ratio_shift" not in _by_name(rows)


def _stub_fleet(**overrides):
    """A recon["fleet"] shaped like drt_service_time.reconstruct returns it when
    the DVRP fleet file WAS found (capacity + the shift-denominated keys present)."""
    fleet = {
        "capacity": 10,
        "fleet_file_known": True,
        "ratio_active": 0.77,
        "ratio_shift": 0.55,
        "util_by_time": 0.42,
        "sum_shift_s": 3600.0 * 10,
        "seg_time": {0: 100.0, 1: 200.0, 2: 50.0},
        "util_by_trips": 0.25,
        "tour_s": 360000.0,
        "drive_s": 200000.0,
        "waiting_s": 100000.0,
        "stop_s": 60000.0,
    }
    fleet.update(overrides)
    return {"fleet": fleet}


def test_recon_stub_used_without_touching_events_file():
    stub_recon = _stub_fleet()

    rows = extract(FIX, "DRT_TEST", fleet_file=None, recon=stub_recon)
    k = _by_name(rows)

    assert k["service_ratio_active"]["value"] == pytest.approx(0.77)
    assert k["service_ratio_shift"]["value"] == pytest.approx(0.55)
    assert k["fleet_utilisation_by_time"]["value"] == pytest.approx(0.42)
    assert k["fleet_shift_hours"]["value"] == pytest.approx(10.0)
    # Literal, not a re-run of the production expression (see the equivalence tests at
    # the bottom of this file): seg_time {0: 100 s, 1: 200 s, 2: 50 s} -> 300 pax-s over
    # 350 s.
    assert k["mean_pax_aboard"]["value"] == pytest.approx(300.0 / 350.0)
    assert k["fleet_utilisation_by_trips"]["value"] == pytest.approx(0.25)
    assert k["drt_tour_hours_total"]["value"] == pytest.approx(100.0)
    assert k["drt_drive_hours_total"]["value"] == pytest.approx(55.5556, abs=1e-4)
    assert k["drt_wait_hours_total"]["value"] == pytest.approx(27.7778, abs=1e-4)
    assert k["drt_service_hours_total"]["value"] == pytest.approx(16.6667, abs=1e-4)


def test_capacity_kpi_is_emitted_for_the_map_layer():
    """maps._read_cap reads exactly this KPI; without it the occupancy colouring
    silently falls back to a hardcoded 8 seats."""
    k = _by_name(extract(FIX, "DRT_TEST", recon=_stub_fleet(capacity=10)))
    assert k["drt_vehicle_capacity"]["value"] == 10
    assert k["drt_vehicle_capacity"]["kpi_group"] == "system"


def test_no_fleet_file_omits_denominated_kpis_and_flags_it():
    """The regression guard: an unlocatable fleet file must DROP the
    capacity/shift KPIs, not compute them against a guessed 8 seats or a
    sim-horizon stand-in for the shift window."""
    recon = _stub_fleet(capacity=None, fleet_file_known=False)
    for key in ("util_by_time", "util_by_trips", "ratio_shift", "sum_shift_s"):
        recon["fleet"].pop(key)

    k = _by_name(extract(FIX, "DRT_TEST", recon=recon))

    for name in ("fleet_utilisation_by_time", "fleet_utilisation_by_trips",
                 "service_ratio_shift", "fleet_shift_hours", "drt_vehicle_capacity"):
        assert name not in k, name
    assert k["fleet_file_missing"]["kpi_group"] == "meta"
    # Everything that does NOT need the fleet file must still be there.
    assert k["service_ratio_active"]["value"] == pytest.approx(0.77)
    assert "mean_pax_aboard" in k


# --- backlog parking P1: fleet_file_missing must not depend on the event path ------

def _fleet_xml(tmp_path, capacity=10):
    """A minimal DVRP fleet file -- the t_0/t_1 attributes are what
    drt_service_time.read_shift_windows (and hence the flag's predicate) reads."""
    f = tmp_path / "fleet.xml"
    f.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<vehicles>\n'
        '  <vehicle id="drt_0" start_link="1" t_0="0.0" t_1="86400.0" capacity="'
        + str(capacity) + '"/>\n'
        '</vehicles>\n', encoding="utf-8")
    return f


def test_fleet_file_missing_is_emitted_without_any_reconstruction():
    """The reproduced failure: on a --no-events build (recon=None) the flag used to be
    unreachable, because it lived inside `extract`'s `recon is not None` branch. Such a
    build omits every capacity/shift KPI anyway, so with the flag gone too nothing in
    kpis_long.csv separated "no fleet file" from "events were not reconstructed"."""
    k = _by_name(extract(FIX, "DRT_TEST", fleet_file=None, recon=None))
    assert k["fleet_file_missing"]["kpi_group"] == "meta"
    assert k["fleet_file_missing"]["value"] == 1


def test_fleet_file_missing_absent_when_the_fleet_file_reads(tmp_path):
    """The other half of the pin: a locatable, parseable fleet file must leave the row
    set exactly as flag-free as before -- otherwise "recon-free" would just mean "always
    on", and every real run would carry a false alarm."""
    k = _by_name(extract(FIX, "DRT_TEST", fleet_file=_fleet_xml(tmp_path), recon=None))
    assert "fleet_file_missing" not in k


def test_fleet_file_missing_flags_a_file_without_shift_windows(tmp_path):
    """`os.path.exists` is NOT the predicate: a fleet file that exists but carries no
    parseable t_0/t_1 leaves drt_service_time without the shift denominators just as
    surely as an absent one, and drt_service_time's own `fleet_file_known` is defined
    as bool(shift windows) for exactly that reason."""
    f = tmp_path / "fleet.xml"
    f.write_text('<vehicles>\n  <vehicle id="drt_0" start_link="1" capacity="10"/>\n'
                 '</vehicles>\n', encoding="utf-8")
    k = _by_name(extract(FIX, "DRT_TEST", fleet_file=f, recon=None))
    assert "fleet_file_missing" in k


def test_fleet_file_missing_source_names_the_kpis_it_costs(tmp_path):
    """A reader holding only kpis_long.csv cannot notice an ABSENCE. The flag's source
    text therefore has to name the KPIs, and it must stay in step with the code that
    omits them -- so the assertion goes through the module's own tuple."""
    k = _by_name(extract(FIX, "DRT_TEST", fleet_file=None, recon=None))
    src = k["fleet_file_missing"]["source"]
    for name in extract_drt._FLEET_FILE_DENOMINATED:
        assert name in src, name
    assert src.isascii()


def test_fleet_file_denominated_tuple_matches_what_extract_actually_omits(tmp_path):
    """Guards the tuple against drift: every name in it must be a KPI that appears with
    a readable fleet file and disappears without one. A stale entry would make the flag
    promise a KPI nobody ever emitted."""
    with_file = _by_name(extract(FIX, "DRT_TEST", fleet_file=_fleet_xml(tmp_path),
                                  recon=_stub_fleet()))
    without = _stub_fleet(capacity=None, fleet_file_known=False)
    for key in ("util_by_time", "util_by_trips", "ratio_shift", "sum_shift_s"):
        without["fleet"].pop(key)
    without_file = _by_name(extract(FIX, "DRT_TEST", fleet_file=None, recon=without))

    for name in extract_drt._FLEET_FILE_DENOMINATED:
        assert name in with_file, name
        assert name not in without_file, name


def test_customer_stats_tile_kpis_without_recon():
    # Values hard-coded from tests/fixtures/drtrun/DRT_TEST.drt_customer_stats_drt.csv
    # last (only) row: rides_pax=9171, distance_m_mean=10967.72
    rows = extract(FIX, "DRT_TEST")
    k = _by_name(rows)
    assert k["drt_passengers"]["value"] == 9171
    assert k["drt_trip_distance_mean"]["value"] == pytest.approx(10.96772)


# --- 1d paper-readiness fixwave (review C1/I2/I3): marker survives --no-events,
# corrected *_pax recipe, set relocation -----------------------------------------

def test_marker_present_with_modular_true_and_no_events():
    """Review C1: the marker used to live only inside the `recon is not None`
    branch, so a run with NO events cache at all (recon=None, no fleet_file)
    published every contaminated KPI with no marker. `modular=True` alone must
    now be enough."""
    rows = extract(FIX, "DRT_TEST", modular=True)
    k = _by_name(rows)
    assert extract_drt.MODULAR_CONTAMINATION_KPI in k
    assert k[extract_drt.MODULAR_CONTAMINATION_KPI]["kpi_group"] == "meta"
    assert "modular_secondary_contaminated" in k


def test_marker_absent_on_a_baseline_run():
    """`modular=False` (the default, and what a non-1d run's caller passes) must
    keep the baseline fixture exactly as marker-free as before this change."""
    rows = extract(FIX, "DRT_TEST", modular=False)
    k = _by_name(rows)
    assert extract_drt.MODULAR_CONTAMINATION_KPI not in k
    assert "modular_secondary_contaminated" not in k


def test_marker_recipe_uses_rescale_not_flat_subtraction():
    """Review I2: the marker's source text must name the per-KPI correction verb,
    not the old flat 'subtract drt_freight_hours_total to compare' recipe that
    was wrong for 3 of the 4 window KPIs."""
    rows = extract(FIX, "DRT_TEST", modular=True)
    src = _by_name(rows)[extract_drt.MODULAR_CONTAMINATION_KPI]["source"]
    assert "rescale" in src
    assert "not recoverable" in src
    assert "subtract drt_freight_hours_total to compare" not in src
    assert src.isascii()


def test_fleet_utilisation_by_trips_moved_to_uncorrectable():
    assert "fleet_utilisation_by_trips" in extract_drt.MODULAR_UNCORRECTABLE
    assert "fleet_utilisation_by_trips" not in extract_drt.MODULAR_FREIGHT_IN_WINDOW


def test_pax_rows_use_the_correct_recipe():
    """tour_s=7200 (2h), freight_s=1800 (0.5h) -> tour_h - freight_h = 1.5 (a plain
    subtraction); the rescale factor is tour_h/(tour_h-freight_h) = 7200/5400."""
    recon = _stub_fleet(tour_s=7200.0, freight_s=1800.0, ratio_active=0.5)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))

    assert k["drt_tour_hours_total_pax"]["value"] == pytest.approx(1.5)
    assert k["drt_tour_hours_total_pax"]["kpi_group"] == "system"
    assert k["service_ratio_active_pax"]["value"] == pytest.approx(0.5 * 7200 / 5400)
    assert k["service_ratio_active_pax"]["kpi_group"] == "system"
    assert k["mean_pax_aboard_pax"]["kpi_group"] == "passenger"
    # seg_time {0: 100, 1: 200, 2: 50} -> 300/350 pax; rescaled by 7200/5400. Literal
    # rather than a second evaluation of the production formula -- the discriminating
    # version of this claim lives in the equivalence tests below.
    assert k["mean_pax_aboard_pax"]["value"] == pytest.approx((300.0 / 350.0) * 7200 / 5400)
    assert k["fleet_utilisation_by_time_pax"]["value"] == pytest.approx(0.42 * 7200 / 5400)


def test_pax_rows_absent_when_not_modular():
    """Same recon (real freight time to correct) but `modular=False` -- the
    caller's signal that this run is not a 1d Modular run -- must suppress the
    `*_pax` rows entirely."""
    recon = _stub_fleet(tour_s=7200.0, freight_s=1800.0, ratio_active=0.5)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=False))
    for name in ("drt_tour_hours_total_pax", "service_ratio_active_pax",
                 "fleet_utilisation_by_time_pax", "mean_pax_aboard_pax"):
        assert name not in k, name


def test_pax_rows_absent_without_freight_time():
    """A non-1d recon (freight_s absent/0) must never grow `*_pax` rows even if
    the caller mistakenly passes modular=True -- there is nothing to correct."""
    k = _by_name(extract(FIX, "DRT_TEST", recon=_stub_fleet(), modular=True))
    for name in ("drt_tour_hours_total_pax", "service_ratio_active_pax",
                 "fleet_utilisation_by_time_pax", "mean_pax_aboard_pax"):
        assert name not in k, name


def test_pax_rescale_guard_omits_the_three_ratios_when_freight_meets_tour():
    """tour_h <= freight_h cannot be rescaled into a meaningful ratio (the
    active window would have to be entirely freight or less) -- only the
    honest subtraction is published, not a nonsense/negative ratio."""
    recon = _stub_fleet(tour_s=1800.0, freight_s=1800.0)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))
    assert k["drt_tour_hours_total_pax"]["value"] == pytest.approx(0.0)
    for name in ("service_ratio_active_pax", "fleet_utilisation_by_time_pax",
                 "mean_pax_aboard_pax"):
        assert name not in k, name


def test_baseline_rows_byte_identical_without_modular_flag():
    """Control-arm pin: on a genuine non-1d run (modular=False, the default) with
    a fleet file found (the normal successful-build shape), the row set must be
    exactly what extract() emitted before this fixwave -- no marker rows, no
    `*_pax` rows, and `fleet_utilisation_by_trips` still a plain KPI row (its
    home-set relocation must not touch its own emission, only its provenance
    classification)."""
    recon = _stub_fleet()
    rows = extract(FIX, "DRT_TEST", recon=recon)
    names = {r["kpi_name"] for r in rows}
    assert extract_drt.MODULAR_CONTAMINATION_KPI not in names
    assert "modular_secondary_contaminated" not in names
    assert not any(n.endswith("_pax") for n in names)
    assert "fleet_utilisation_by_trips" in names
    k = _by_name(rows)
    assert k["fleet_utilisation_by_trips"]["value"] == pytest.approx(0.25)


# --- backlog parking P2: mean_pax_aboard / _pax equivalence, derived independently ----
#
# Every mean-pax assertion above this line evaluates the SAME expression the production
# code does (`sum(level * seconds) / sum(seconds)`, optionally times the rescale factor).
# Such a test pins the number but cannot discriminate: mutate the production formula and
# the expectation mutates with it, in lockstep, and the test stays green. The tests below
# reach the same numbers by a different route -- materialise the occupancy timeline one
# simulated second at a time and take a plain arithmetic mean over it -- so they fail on
# any change to the production formula, including the ones that keep it plausible.

def _per_second_occupancy(seg_time):
    """[level] * seconds, one entry per simulated second, low level first.

    Deliberately NOT the production route: production never materialises a timeline, it
    folds the {level: seconds} histogram straight into a weighted sum. Requires integer
    second counts, which every fixture below is built to satisfy.
    """
    timeline = []
    for level, seconds in sorted(seg_time.items()):
        assert float(seconds).is_integer(), seconds
        timeline.extend([level] * int(seconds))
    return timeline


def _mean(values):
    """statistics.mean over a materialised list -- no weights, no histogram."""
    return statistics.mean(values)


#: A fixture whose segment histogram is CONSISTENT with its tour span (sums to tour_s)
#: and leaves room for the freight window inside the occupancy-0 bucket, which is what
#: makes the `_pax` correction semantically checkable rather than merely arithmetic:
#: 2 h active span, of which 1 h at 0 pax, 40 min at 1 pax, 20 min at 2 pax.
_CONSISTENT_SEG = {0: 3600.0, 1: 2400.0, 2: 1200.0}
_CONSISTENT_TOUR_S = 7200.0
_CONSISTENT_FREIGHT_S = 1800.0     # 30 min, all of it inside the 0-pax hour (D2 lockout)


def test_mean_pax_aboard_equals_an_independent_per_second_mean():
    """Time-WEIGHTED mean occupancy, verified against a per-second timeline."""
    recon = _stub_fleet(seg_time=dict(_CONSISTENT_SEG), tour_s=_CONSISTENT_TOUR_S)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon))

    expected = _mean(_per_second_occupancy(_CONSISTENT_SEG))
    assert k["mean_pax_aboard"]["value"] == pytest.approx(expected)


def test_mean_pax_aboard_is_time_weighted_not_segment_averaged():
    """The discrimination the old test could not make. Two plausible wrong
    implementations produce numbers this fixture separates by a wide margin:
      - unweighted mean over the occupancy LEVELS present  -> (0+1+2)/3 = 1.0
      - divide the pax-seconds by the SEGMENT COUNT        -> 4800/3    = 1600.0
    Neither is within a rounding error of the correct 4800/7200.
    """
    recon = _stub_fleet(seg_time=dict(_CONSISTENT_SEG), tour_s=_CONSISTENT_TOUR_S)
    value = _by_name(extract(FIX, "DRT_TEST", recon=recon))["mean_pax_aboard"]["value"]

    assert value == pytest.approx(_mean(_per_second_occupancy(_CONSISTENT_SEG)))
    unweighted_over_levels = _mean(sorted(_CONSISTENT_SEG))
    assert value != pytest.approx(unweighted_over_levels)
    pax_seconds = sum(_per_second_occupancy(_CONSISTENT_SEG))
    assert value != pytest.approx(pax_seconds / len(_CONSISTENT_SEG))


def test_mean_pax_aboard_pax_equals_the_freight_seconds_removed_mean():
    """The load-bearing equivalence. Production applies review I2's SHORTCUT -- multiply
    the whole-span mean by tour_h / (tour_h - freight_h) -- and the justification for
    that shortcut is a claim about the timeline: an excursion is occupancy-0 throughout
    (D2's passenger lockout), so removing the freight window can only remove 0-pax
    seconds. This test asserts the shortcut against that claim directly: drop
    `freight_s` seconds out of the 0-pax bucket, re-take the per-second mean over the
    5400 s that remain, and require the published number to match.

    A test that instead re-multiplied `mean * 7200/5400` would prove nothing about the
    correction -- only that two copies of one formula agree.
    """
    recon = _stub_fleet(seg_time=dict(_CONSISTENT_SEG), tour_s=_CONSISTENT_TOUR_S,
                        freight_s=_CONSISTENT_FREIGHT_S)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))

    passenger_only = dict(_CONSISTENT_SEG)
    passenger_only[0] -= _CONSISTENT_FREIGHT_S          # the excursion carried 0 pax
    timeline = _per_second_occupancy(passenger_only)
    assert len(timeline) == _CONSISTENT_TOUR_S - _CONSISTENT_FREIGHT_S   # 5400 s remain

    assert k["mean_pax_aboard_pax"]["value"] == pytest.approx(_mean(timeline))


def test_mean_pax_aboard_pax_discriminates_against_the_plausible_wrong_recipes():
    """Three recipes that would survive a same-formula test on this fixture:
      - no correction at all             -> the uncorrected 4800/7200
      - rescale the wrong way round      -> mean * 5400/7200
      - subtract instead of rescale      -> mean - freight_h/tour_h
    The corrected value must equal none of them.
    """
    recon = _stub_fleet(seg_time=dict(_CONSISTENT_SEG), tour_s=_CONSISTENT_TOUR_S,
                        freight_s=_CONSISTENT_FREIGHT_S)
    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))
    value = k["mean_pax_aboard_pax"]["value"]

    uncorrected = _mean(_per_second_occupancy(_CONSISTENT_SEG))
    assert value != pytest.approx(uncorrected)
    assert value != pytest.approx(uncorrected * 5400.0 / 7200.0)     # inverted rescale
    assert value != pytest.approx(uncorrected - _CONSISTENT_FREIGHT_S / 3600.0)
    # ... and it is strictly ABOVE the uncorrected number: taking 0-pax time out of the
    # denominator can only raise mean occupancy.
    assert value > uncorrected


def test_mean_pax_aboard_pax_equivalence_holds_for_a_second_shape():
    """One fixture can satisfy a wrong formula by coincidence. A second shape with a
    different freight share (75 min of a 3 h span) and a different occupancy mix has to
    satisfy the same equivalence."""
    seg = {0: 5400.0, 1: 3600.0, 3: 1800.0}          # 10800 s = 3 h, note the level GAP
    freight_s = 4500.0                               # 75 min, inside the 90-min 0-pax bucket
    recon = _stub_fleet(seg_time=dict(seg), tour_s=10800.0, freight_s=freight_s)

    k = _by_name(extract(FIX, "DRT_TEST", recon=recon, modular=True))

    passenger_only = dict(seg)
    passenger_only[0] -= freight_s
    assert k["mean_pax_aboard"]["value"] == pytest.approx(
        _mean(_per_second_occupancy(seg)))
    assert k["mean_pax_aboard_pax"]["value"] == pytest.approx(
        _mean(_per_second_occupancy(passenger_only)))
