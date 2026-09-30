# -*- coding: utf-8 -*-
"""1c tab: the parcel balance. Numbers are the real f140 chi900 s1337 run (2026-09-04)."""
import json
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render  # noqa: E402
import render_shareduse  # noqa: E402


def _data(pairs):
    kpis = pd.DataFrame([{"kpi_group": "freight", "kpi_name": n, "value": v, "unit": "",
                          "source": ""} for n, v in pairs])
    empty = pd.DataFrame()
    return render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=empty, iterations=empty, distributions=empty, vehicles=empty)


_REAL = [("parcels_in_demand", 6052), ("parcels_drt_borne", 5946), ("parcels_delivered", 5946),
         ("parcels_delivered_late", 0), ("parcels_walked", 91),
         ("parcels_dropped_at_depot_link", 15), ("parcels_undelivered", 0),
         ("delivery_rate", 0.997521)]


def test_delivered_tile_matches_the_delivery_rate_and_names_the_walk_channel():
    """Until 2026-09-29 the tile showed the DRT-borne 5,946 right next to a rate of 99.75 %
    (= 6,037 / 6,052), so the two tiles did not agree and the 91 walked parcels were nowhere."""
    html, _ = render_shareduse.build_tab(_data(_REAL), uid="s")
    assert "6.037" in html
    assert "91 zu Fuß" in html


def test_parcel_balance_chart_shows_every_channel():
    html, js = render_shareduse.build_tab(_data(_REAL), uid="s")
    assert "Paket-Bilanz" in html
    line = next(l for l in js.splitlines() if "c_s_parcels_s" in l)
    for label in ("Nachfrage", "per DRT", "zu Fuß", "Hoftor-Verwurf", "nicht zugestellt"):
        assert json.dumps(label) in line   # the chart config is JSON (ß -> ß)
    assert "warnbanner" not in html.split("Paket-Bilanz", 1)[1].split("</h2>", 1)[0]


def test_parcel_balance_that_does_not_add_up_is_flagged():
    broken = [(n, 5900 if n == "parcels_drt_borne" else v) for n, v in _REAL]
    html, _ = render_shareduse.build_tab(_data(broken), uid="s")
    assert "Paketbilanz geht nicht auf" in html


def test_run_without_walk_channel_keeps_the_old_tile():
    """Pre-2026-08-26 CSVs (or an unconfirmed walk channel) have no parcels_walked row."""
    old = [(n, v) for n, v in _REAL if n != "parcels_walked"]
    html, _ = render_shareduse.build_tab(_data(old), uid="s")
    assert "5.946" in html
    assert "zu Fuß" not in html.split("Paket-Bilanz")[0]
