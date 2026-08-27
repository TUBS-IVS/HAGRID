# -*- coding: utf-8 -*-
"""1c Shared-Use tab renderer ("Cargo-Hitching") -- board rework block 2.

Design: `docs/superpowers/specs/2026-08-17-1c-1d-board-rework-design.md` §4.1, with two
deviations recorded when the first post-fix run (`d1c_dep7_f140_chi150`, 2026-08-27) was
measured against it:

1. The spec made the histogram the lead chart. At `chi_detour.BIN_WIDTH_S = 100` a run at
   chi=150 produces TWO bins for the delivered bucket and four for the expired one -- the
   informative region is one bar wide. The QUANTILES carry the same comparison at full
   resolution (delivered 17/48/82/109 s vs. expired 67/115/170/232 s on that run), so the
   quantile comparison leads and the histogram follows. Once BACKLOG `[L]` BIN_WIDTH_S is
   fixed the histogram becomes useful again without a change here.
2. The spec proposed detecting the pre-2026-08-13 file vintage heuristically from the share
   of exact-zero segments. That does not work: the same bin holds legitimately small detours,
   and the run above sits at 84.6 % in bin [0,100) while being a valid post-fix file. Bin
   coarseness and the old clamp artefact are indistinguishable from the histogram, so the tab
   carries a STATIC caveat instead of a detector that would cry wolf on good data.

Everything is guarded by its KPIs being present (`v is None -> continue`), so a run without
the detour instrumentation (every 1c run before 2026-08-10) simply renders fewer blocks.
"""
from render import _kpi, _tile, _fmt_de, _fmt_pct, _render_group


_QUANTILES = [("p25_s", "p25"), ("median_s", "Median"), ("p75_s", "p75"), ("p90_s", "p90")]


# ---------------------------------------------------------------------------
# Chart helpers
# ---------------------------------------------------------------------------

def _bar(title, cid, pairs, height=210, horizontal=False, unit=""):
    kept = [(lab, val) for lab, val in pairs if val is not None]
    if not kept:
        return None
    cfg = {
        "type": "bar",
        "data": {"labels": [lab for lab, _ in kept],
                 "datasets": [{"label": title + (" [" + unit + "]" if unit else ""),
                               "data": [round(float(v), 3) for _, v in kept],
                               "__seq": True, "borderRadius": 4, "maxBarThickness": 28}]},
        "options": {"responsive": True, "maintainAspectRatio": False,
                    "plugins": {"legend": {"display": False}}},
    }
    if horizontal:
        cfg["options"]["indexAxis"] = "y"
    return (title, cid, cfg, height)


def _grouped_bar(title, cid, labels, series, height=230, ylabel=""):
    """`series` = [(name, [values...], slot), ...]; a series whose values are
    all None is dropped, and the chart itself disappears when none survives."""
    kept = [(n, v, s) for n, v, s in series if any(x is not None for x in v)]
    if not kept:
        return None
    cfg = {
        "type": "bar",
        "data": {"labels": labels,
                 "datasets": [{"label": n, "__slot": s, "borderRadius": 4,
                               "maxBarThickness": 26,
                               "data": [None if x is None else round(float(x), 2) for x in v]}
                              for n, v, s in kept]},
        "options": {"responsive": True, "maintainAspectRatio": False,
                    "plugins": {"legend": {"position": "bottom"}},
                    "scales": {"y": {"title": {"display": bool(ylabel), "text": ylabel}}}},
    }
    return (title, cid, cfg, height)


def _cumulative(title, cid, dist, height=230):
    """"How many segments would have needed a chi <= x" -- the single-run
    approximation of delta(chi) (chi_detour.py module docstring). Built from
    the histogram bins, so its resolution is BIN_WIDTH_S: see the module note.
    Returns None when the distribution rows are absent."""
    if dist is None or "series" not in dist.columns:
        return None
    datasets, labels = [], []
    for slot, (series, name) in enumerate([("chi_detour_min_delivered", "zugestellt"),
                                           ("chi_detour_min_expired", "verfallen")]):
        rows = dist[dist["series"] == series].sort_values("bin_lo")
        if not len(rows):
            continue
        run, pts = 0.0, []
        for _, r in rows.iterrows():
            run += float(r["value"])
            pts.append({"x": float(r["bin_hi"]), "y": run})
        labels = labels or [p["x"] for p in pts]
        datasets.append({"label": name, "__slot": slot, "data": pts,
                         "stepped": "before", "fill": False, "pointRadius": 3})
    if not datasets:
        return None
    cfg = {
        "type": "line",
        "data": {"datasets": datasets},
        "options": {"responsive": True, "maintainAspectRatio": False,
                    "plugins": {"legend": {"position": "bottom"}},
                    "scales": {"x": {"type": "linear",
                                     "title": {"display": True, "text": "Umweg-Minimum [s]"}},
                               "y": {"title": {"display": True, "text": "Segmente kumuliert"}}}},
    }
    return (title, cid, cfg, height)


def _hist(title, cid, dist, series, height=210):
    if dist is None or "series" not in dist.columns:
        return None
    rows = dist[dist["series"] == series].sort_values("bin_lo")
    if not len(rows):
        return None
    labels = [str(int(float(r["bin_lo"]))) + "-" + str(int(float(r["bin_hi"]))) + " s"
              for _, r in rows.iterrows()]
    cfg = {
        "type": "bar",
        "data": {"labels": labels,
                 "datasets": [{"label": title, "__seq": True, "borderRadius": 4,
                               "data": [round(float(v), 3) for v in rows["value"]]}]},
        "options": {"responsive": True, "maintainAspectRatio": False,
                    "plugins": {"legend": {"display": False}}},
    }
    return (title, cid, cfg, height)


# ---------------------------------------------------------------------------
# Caveat banner
# ---------------------------------------------------------------------------

def _caveats(k, dist):
    """Two standing reservations about the detour panel. Neither is a detector:
    the vintage cannot be read off the file (no version stamp), and the bin
    count is a fact of the export, not a defect of this run."""
    notes = []
    if _kpi(k, "chi_detour_segments_evaluated") is not None:
        notes.append("Die Umweg-Datei trägt keinen Versionsstempel. Läufe von <b>vor dem "
                     "2026-08-13</b> (χ-Gate-Fix) unterschätzen den Umweg systematisch; aus den "
                     "Zahlen selbst ist das nicht erkennbar (METHODS-LOG §2.35).")
        if dist is not None and "series" in dist.columns:
            n = len(dist[dist["series"] == "chi_detour_min_delivered"])
            if 0 < n <= 4:
                notes.append("Das Histogramm hat hier nur <b>" + str(n) + " Bins</b> "
                             "(BIN_WIDTH_S = 100 s in chi_detour.py). Für die Rasterwahl sind "
                             "die Quantile links maßgeblich, nicht die Balken.")
    if not notes:
        return ""
    return "".join('<p class="warnbanner">' + t + "</p>" for t in notes)


# ---------------------------------------------------------------------------
# Tiles
# ---------------------------------------------------------------------------

def _tiles(data):
    k = data.kpis
    t = []

    v = _kpi(k, "delivery_rate")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Zustellquote (operativ)",
                       tip="Zugestellt / Nachfrage, ohne Overlay-Abzug (armübergreifende "
                           "Konvention, METHODS-LOG §2.21). Der 1c-Arm führt ohnehin kein "
                           "Not-at-home-Overlay (M10)."))

    deliv, inj = _kpi(k, "segments_delivered"), _kpi(k, "segments_injected")
    if deliv is not None:
        sub = (_fmt_de(inj) + " injiziert") if inj is not None else ""
        t.append(_tile(_fmt_de(deliv), "Segmente zugestellt", sub,
                       tip="segments_delivered gegen segments_injected. Ein Segment ist ein "
                           "Paket-Bein, nicht ein Paket."))

    v = _kpi(k, "segments_window_expired")
    if v is not None:
        sub = ""
        if inj:
            sub = _fmt_pct(v / inj) + " der injizierten"
        t.append(_tile(_fmt_de(v), "Segmente verfallen", sub,
                       tip="segments_window_expired: im Lieferfenster nie mitgenommen."))

    v = _kpi(k, "segments_never_submitted")
    if v is not None:
        t.append(_tile(_fmt_de(v), "nie eingereicht",
                       tip="segments_never_submitted: erreichte die Insertion-Suche gar nicht "
                           "(z. B. Walk-Fallback) und taucht deshalb auch in der Umweg-Datei "
                           "nicht auf."))

    dm, em = _kpi(k, "chi_detour_delivered_median_s"), _kpi(k, "chi_detour_expired_median_s")
    if dm is not None:
        t.append(_tile(_fmt_de(dm, 1) + " s", "Median-Umweg zugestellt",
                       tip="chi_detour_delivered_median_s: kleinster je angebotener Umweg der "
                           "zugestellten Segmente."))
    if em is not None:
        sub = ""
        if dm:
            sub = "{:.1f}x zugestellt".format(em / dm).replace(".", ",")
        t.append(_tile(_fmt_de(em, 1) + " s", "Median-Umweg verfallen", sub,
                       tip="chi_detour_expired_median_s. Liegt der Wert dicht über χ, bindet die "
                           "Schwelle; liegt er weit darüber, ist χ nicht der Engpass."))

    v = _kpi(k, "chi_detour_evaluations_total")
    if v is not None:
        seg = _kpi(k, "chi_detour_segments_evaluated")
        sub = ""
        if seg:
            sub = _fmt_de(v / seg) + " je Segment"
        t.append(_tile(_fmt_de(v), "Gate-Auswertungen", sub,
                       tip="chi_detour_evaluations_total: wie oft das Gate einen Kandidaten "
                           "bewertet hat. Konkurrenz-Indikator, kein Erfolgsmaß."))

    v = _kpi(k, "parcels_delivered")
    if v is not None:
        late = _kpi(k, "parcels_delivered_late")
        sub = (_fmt_de(late) + " davon verspätet") if late is not None else ""
        t.append(_tile(_fmt_de(v), "Pakete zugestellt", sub, tip="parcels_delivered."))

    v = _kpi(k, "mean_time_to_delivery_s")
    if v is not None:
        t.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Ø Zeit bis Zustellung",
                       tip="mean_time_to_delivery_s, ab Injektion."))

    return "".join(t)


# ---------------------------------------------------------------------------
# Chart groups
# ---------------------------------------------------------------------------

def _channel_charts(k, uid):
    return [
        _bar("Segment-Bilanz", "c_s_funnel_" + uid, [
            ("injiziert", _kpi(k, "segments_injected")),
            ("eingereicht", _kpi(k, "segments_submitted")),
            ("zugestellt", _kpi(k, "segments_delivered")),
        ], horizontal=True),
        _bar("Wo Segmente verloren gehen", "c_s_lost_" + uid, [
            ("nie eingereicht", _kpi(k, "segments_never_submitted")),
            ("Fenster verfallen", _kpi(k, "segments_window_expired")),
            ("final abgelehnt", _kpi(k, "segments_rejected_final")),
            ("offen bei Betriebsende", _kpi(k, "segments_pending_eod")),
            ("noch offen", _kpi(k, "segments_pending_open")),
        ], horizontal=True),
    ]


def _gate_charts(k, dist, uid):
    quant = _grouped_bar(
        "Umweg-Minimum: Quantile", "c_s_quant_" + uid,
        [lab for _, lab in _QUANTILES],
        [("zugestellt", [_kpi(k, "chi_detour_delivered_" + q) for q, _ in _QUANTILES], 0),
         ("verfallen", [_kpi(k, "chi_detour_expired_" + q) for q, _ in _QUANTILES], 1)],
        ylabel="Sekunden")
    return [
        quant,
        _cumulative("Segmente mit Umweg-Minimum ≤ x", "c_s_cum_" + uid, dist),
        _hist("Verteilung zugestellt", "c_s_hd_" + uid, dist, "chi_detour_min_delivered"),
        _hist("Verteilung verfallen", "c_s_he_" + uid, dist, "chi_detour_min_expired"),
    ]


def _context_charts(k, uid):
    return [
        _bar("Kanal-Anteile", "c_s_chan_" + uid, [
            ("Haustür", _kpi(k, "share_channel_door")),
            ("Packstation", _kpi(k, "share_channel_locker")),
        ]),
        _bar("Mitnahme-Kontext", "c_s_ctx_" + uid, [
            ("Ø Pakete an Bord (zugestellt)", _kpi(k, "chi_detour_delivered_mean_parcels")),
            ("Ø Pakete an Bord (verfallen)", _kpi(k, "chi_detour_expired_mean_parcels")),
        ]),
    ]


def build_tab(data, uid, compact=False, map_block=None):
    """1c mechanism tab. Signature matches render_drt/render_lmd.build_tab.

    `compact=True` (comparison page) renders the channel balance and the gate
    quantiles only -- histograms and context are per-run diagnosis."""
    k, dist = data.kpis, data.distributions

    map_html, map_js = "", ""
    if map_block is not None:
        map_html = map_block.get("html", "")
        map_js = map_block.get("js", "")

    gate = _gate_charts(k, dist, uid)
    groups = [("Kanal-Bilanz", _channel_charts(k, uid)),
              ("χ-Gate: Umweg-Diagnose", gate[:2] if compact else gate)]
    if not compact:
        groups.append(("Kontext", _context_charts(k, uid)))

    groups_html, groups_js = [], []
    for title, charts in groups:
        h, j = _render_group(title, charts)
        if h:
            if title.startswith("χ-Gate"):
                h = h.replace("</h2>", "</h2>" + _caveats(k, dist), 1)
            groups_html.append(h)
            groups_js.append(j)

    html = '<div class="tiles">' + _tiles(data) + "</div>" + map_html + "".join(groups_html)
    js = "\n".join(g for g in groups_js if g) + map_js
    return html, js
