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
    assert "_pax;" in long_txt

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
