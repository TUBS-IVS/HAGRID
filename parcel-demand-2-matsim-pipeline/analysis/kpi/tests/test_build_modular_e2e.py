# -*- coding: utf-8 -*-
"""End-to-end pin for the 1d Modular marker chain (review M9): no fixture before this one
carried BOTH a modular_tour_stats.csv AND modularTour events on the SAME run -- every unit
test exercised one half of that combination, never both together, which is exactly why the
original marker-emission bug (living only inside the recon-available branch of
extract_drt.extract, fixed by review C1) survived as long as it did.

fixtures/modularrun/ is the drtrun fixture's scaffolding (drt_customer/vehicle/sharing_stats,
modestats, output_trips, output_drt_legs/rejections, the freight carriers XML + TSVs) plus:
  - MODULAR_TEST.modular_tour_stats.csv: a conforming 26-metric file (every identity
    extract_modular re-checks holds exactly).
  - MODULAR_TEST.output_events.xml.gz: one vehicle's full day, hand-written -- passenger
    drive/stop, one COMPLETE freight excursion (DISPATCHED..COMPLETED bracketing approach
    drive / capsule swap out / freight drive+dwell / return drive / capsule swap back), more
    passenger work. The freight identity (drive+stop+freight+retooling+waiting = tour span)
    closes exactly for this vehicle -- see test_modular_service_time.py for the arithmetic
    this mirrors.
"""
import pytest
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build_kpis import build

FIX = Path(__file__).parent / "fixtures" / "modularrun"

#: Same literal as test_render.py's CONTAMINATION_BADGE -- kept independent (not imported)
#: so a change to the badge text has to be a deliberate edit in both places, not a silent
#: one-sided drift between the render-unit tests and this integration pin.
CONTAMINATION_BADGE = "enthaelt Frachtanteil, s. METHODS-LOG 2.14"


def _copy_fixture(tmp_path):
    """A no_events=False build calls events_cache.ensure_caches, which WRITES the two
    *_filtered.txt caches next to the input events file -- i.e. into the tracked fixture
    directory itself if build() is pointed at FIX directly (test_build_kpis.py's
    _make_mini_events_run works around the exact same thing). Copy into tmp_path first so
    the run leaves the source fixture untouched."""
    d = tmp_path / "modularrun_copy"
    shutil.copytree(FIX, d)
    return d


def test_full_chain_with_events_carries_marker_freight_hours_pax_rows_and_badge(tmp_path):
    """build(no_events=False): the FULL chain from one real run directory -- the
    contamination marker (both rows), the event-derived freight-hours total, at least one
    `*_pax` correction row, and the dashboard badge on the rendered run page."""
    d = _copy_fixture(tmp_path)
    out = build(d, no_events=False, out_dir=tmp_path / "out")

    long_txt = (out / "kpis_long.csv").read_text(encoding="utf-8")
    assert ";meta;modular_contaminated_kpis;" in long_txt
    assert ";meta;modular_secondary_contaminated;" in long_txt
    assert ";system;drt_freight_hours_total;" in long_txt
    # A named row, not just the "_pax;" suffix: drt_tour_hours_total_pax is the one
    # unconditional *_pax row (_modular_pax_rows emits it whenever there is freight time to
    # subtract, before the tour_h > freight_h guard that gates the other three).
    assert ";system;drt_tour_hours_total_pax;" in long_txt
    # The fixture's conserving 26-metric modular_tour_stats.csv must stay conserving -- a
    # future edit that breaks one of extract_modular's five identities must fail LOUDLY here,
    # not leave the two assertions above silently green over a corrupted fixture.
    assert "modular_identity_violated" not in long_txt

    html = (out / "kpi_dashboard.html").read_text(encoding="utf-8")
    assert CONTAMINATION_BADGE in html


def test_no_events_build_still_carries_the_marker_but_not_the_event_derived_rows(tmp_path):
    """build(no_events=True) on the SAME fixture that also carries events (unlike
    test_build_kpis.py's equivalent pin, which uses a fixture with no events file at all):
    review C1's marker independence must hold even when an events-capable run happens to be
    built with --no-events. The event-derived rows (freight-hours, *_pax) need `recon`,
    which --no-events never computes, so they must be absent."""
    out = build(FIX, no_events=True, out_dir=tmp_path)

    long_txt = (out / "kpis_long.csv").read_text(encoding="utf-8")
    assert ";meta;modular_contaminated_kpis;" in long_txt
    assert ";meta;modular_secondary_contaminated;" in long_txt
    assert "_pax;" not in long_txt
    assert ";system;drt_freight_hours_total;" not in long_txt
    # Same conserving-fixture guard as the events build above -- must hold on this path too.
    assert "modular_identity_violated" not in long_txt


# --- Task 8: the 1d emissions regime split, end to end -------------------

#: Link entries placed so that two fall INSIDE MODULAR_FREIGHT_DRIVE windows
#: ([1000,1100], [1520,1600], [1720,1800] in the fixture's event stream) and
#: two fall inside plain pax DRIVE tasks. 5 km each side, by construction.
_LINK_EVENTS = [(150.0, "e1"), (1050.0, "e2"), (1550.0, "e3"), (2350.0, "e4")]
_LINK_M = {"e1": 1000.0, "e2": 2000.0, "e3": 3000.0, "e4": 4000.0}
_FREIGHT_KM = (_LINK_M["e2"] + _LINK_M["e3"]) / 1000.0      # 5.0
_PAX_KM = (_LINK_M["e1"] + _LINK_M["e4"]) / 1000.0          # 5.0

_NETWORK_XML = """<?xml version="1.0" encoding="utf-8"?>
<network name="modular_e2e">
<nodes>
<node id="q1" x="864000.0" y="5705000.0"/>
<node id="q2" x="864500.0" y="5705100.0"/>
<node id="q3" x="865000.0" y="5705050.0"/>
<node id="q4" x="865600.0" y="5705200.0"/>
<node id="q5" x="866300.0" y="5705400.0"/>
</nodes>
<links>
<link id="e1" from="q1" to="q2" length="1000.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
<link id="e2" from="q2" to="q3" length="2000.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
<link id="e3" from="q3" to="q4" length="3000.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
<link id="e4" from="q4" to="q5" length="4000.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
</links>
</network>
"""


def _add_links_and_network(d):
    """Give the modular fixture a link path + a matching network, so the
    emissions arms have km to work with. Done in the tmp copy (not in the
    tracked fixture) to leave every existing assertion on it untouched."""
    import gzip

    ev = d / "MODULAR_TEST.output_events.xml.gz"
    with gzip.open(ev, "rt", encoding="utf-8") as f:
        lines = f.read().splitlines()
    extra = ['<event time="' + str(t) + '" type="entered link" link="' + lid
             + '" vehicle="drt_veh_1"/>' for t, lid in _LINK_EVENTS]
    body = lines[:-1] + extra + [lines[-1]]
    with gzip.open(ev, "wt", encoding="utf-8") as f:
        f.write("\n".join(body) + "\n")
    with gzip.open(d / "MODULAR_TEST.output_network.xml.gz", "wt",
                   encoding="utf-8") as f:
        f.write(_NETWORK_XML)
    return d


def test_modular_emissions_split_freight_from_pax_km_without_residue(tmp_path):
    """REGRESSION on a silent-zero wiring bug, twice made: the
    MODULAR_FREIGHT_DRIVE windows live in the *drt* events cache (DVRP tasks on
    drt_* vehicles), while the freight cache is 0 bytes on every 1d run.
    Handing the freight cache to the extractor produces NO error -- the
    freight_modular_* rows just vanish and their km are booked as pax km, which
    looks entirely plausible in the CSV.

    The discriminating assertion is therefore the km SPLIT, not the presence of
    rows: with the wrong cache, drt km is 10.0 and freight km 0."""
    d = _add_links_and_network(_copy_fixture(tmp_path))

    out = build(d, no_events=False, out_dir=tmp_path / "out")

    det = (out / "kpi_emissions_vehicles.csv").read_text(encoding="utf-8")
    rows = [ln.split(";") for ln in det.splitlines()[1:] if ln]
    km = {r[1]: float(r[5]) for r in rows if r[7] == "diesel"}
    assert km["freight_modular"] == pytest.approx(_FREIGHT_KM)
    assert km["drt"] == pytest.approx(_PAX_KM)

    v = {}
    for line in (out / "kpis_long.csv").read_text(encoding="utf-8").splitlines():
        f = line.split(";")
        if len(f) > 7 and f[4] == "environment":
            v[f[5]] = float(f[6])
    # The split is exact in memory; the tolerance is the CSV's 6 significant
    # digits (pm10_nonexhaust: 0.495255 written vs 0.495256 summed), not slack
    # in the arithmetic. A mis-split would be off by ~100 %, not 1e-6.
    for metric in ("co2e_wtw", "energy_final", "pm10_nonexhaust"):
        assert v["drt_" + metric] + v["freight_modular_" + metric] == \
            pytest.approx(v["total_" + metric], rel=1e-5), metric
    # 1d hauls freight as a capsule, not as parcel PERSONS -> no mass basis
    assert "alloc_share_parcels_mass" not in v
    # ... and no van mix either: both 1d fleets carry the fixed N1-III
    # substitution, so a share over them would be 1.0 by construction
    assert "segment_km_share_n1_iii" not in v
