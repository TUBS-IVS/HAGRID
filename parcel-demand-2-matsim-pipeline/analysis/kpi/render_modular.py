# -*- coding: utf-8 -*-
"""1d Modular tab renderer ("Kapsel-Tausch") -- board rework block 1.

Design: `docs/superpowers/specs/2026-08-17-1c-1d-board-rework-design.md` §4.2.

Before this module a DRT_MODULAR run rendered the LMD tab, which is built out of providers,
vehicle types and carriers -- none of which 1d has: it routes jsprit tours but runs no
CarrierModule (`SimulationRunnerUtils.runsCarrierModules`), so the tab was structurally empty
while all 30 modular KPIs sat unused in `kpis_long.csv`.

Every block is guarded by its KPIs being present, so a run predating a given KPI drops that block
instead of drawing a zero -- the `v is None -> continue` convention the other v2 renderers use.
No block invents a value: what the extractor did not export is not shown.
"""
from render import _kpi, _tile, _fmt_de, _fmt_pct, _render_group


# ---------------------------------------------------------------------------
# Chart helpers. Deliberately local and minimal: render_lmd's chart helpers are
# provider/vehicle-type shaped and share nothing with these.
# ---------------------------------------------------------------------------

def _bar(title, cid, pairs, height=210, horizontal=False, unit=""):
    """`pairs` = [(label, value), ...]; None values are dropped. Returns the
    (title, cid, cfg, height) tuple `_render_group` expects, or None when no
    pair survives -- that is what makes each block self-guarding."""
    kept = [(lab, val) for lab, val in pairs if val is not None]
    if not kept:
        return None
    label = title + (" [" + unit + "]" if unit else "")
    cfg = {
        "type": "bar",
        "data": {"labels": [lab for lab, _ in kept],
                 "datasets": [{"label": label,
                               "data": [round(float(v), 3) for _, v in kept],
                               "__seq": True, "borderRadius": 4, "maxBarThickness": 28}]},
        "options": {"responsive": True, "maintainAspectRatio": False,
                    "plugins": {"legend": {"display": False}}},
    }
    if horizontal:
        cfg["options"]["indexAxis"] = "y"
    return (title, cid, cfg, height)


def _stack100(title, cid, pairs, height=210):
    """One 100 % stacked bar -- for the delta decomposition, where the shares
    are the statement and the absolute total is already a tile."""
    kept = [(lab, val) for lab, val in pairs if val is not None]
    if not kept:
        return None
    cfg = {
        "type": "bar",
        "data": {"labels": [""],
                 "datasets": [{"label": lab, "data": [round(float(v) * 100, 2)], "__seq": True}
                              for lab, v in kept]},
        "options": {"responsive": True, "maintainAspectRatio": False, "indexAxis": "y",
                    "scales": {"x": {"stacked": True, "max": 100}, "y": {"stacked": True}},
                    "plugins": {"legend": {"position": "bottom"}}},
    }
    return (title, cid, cfg, height)


# ---------------------------------------------------------------------------
# Tiles
# ---------------------------------------------------------------------------

def _tiles(data):
    k = data.kpis
    t = []

    v = _kpi(k, "delivery_rate")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Zustellquote (operativ)",
                       tip="Zugestellt / Nachfrage, Not-at-home-Overlay NICHT abgezogen "
                           "(armübergreifende Konvention, METHODS-LOG §2.21). Der Nettowert "
                           "steht als delivery_rate_net_overlay in der KPI-Tabelle."))

    served, planned = _kpi(k, "parcels_served"), _kpi(k, "parcels_planned")
    if served is not None:
        sub = (_fmt_de(planned) + " geplant") if planned is not None else ""
        t.append(_tile(_fmt_de(served), "Pakete zugestellt", sub,
                       tip="parcels_served: tatsächlich zugestellte Pakete."))

    v = _kpi(k, "delta_parcels")
    if v is not None:
        t.append(_tile(_fmt_de(v), "δ Pakete (nicht zugestellt)",
                       tip="delta_parcels: geplante minus zugestellte Pakete. Die Zerlegung in "
                           "'nie losgefahren' und 'losgefahren, nicht fertig' steht im Chart."))

    done, disp = _kpi(k, "tours_completed"), _kpi(k, "tours_dispatched")
    if done is not None:
        sub = (_fmt_de(disp) + " disponiert") if disp is not None else ""
        t.append(_tile(_fmt_de(done), "Touren abgeschlossen", sub,
                       tip="tours_completed gegen tours_dispatched."))

    v = _kpi(k, "tour_completion_rate")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Tour-Abschlussquote",
                       tip="tour_completion_rate: abgeschlossene / disponierte Touren."))

    v = _kpi(k, "swaps_completed")
    if v is not None:
        peak = _kpi(k, "peak_concurrent_swaps")
        sub = ("Peak gleichzeitig: " + _fmt_de(peak)) if peak is not None else ""
        t.append(_tile(_fmt_de(v), "Kapsel-Tausche", sub,
                       tip="swaps_completed: abgeschlossene Kapsel-Tausche. peak_concurrent_swaps "
                           "ist die höchste Zahl gleichzeitiger Tausche und damit die "
                           "Dimensionierungsgröße der Wechselstation."))

    v = _kpi(k, "retooling_hours")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " h", "Rüstzeit gesamt",
                       tip="retooling_hours: Summe der Rüstzeiten für die Kapselwechsel."))

    v = _kpi(k, "freight_vehicle_hours")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " h", "Fracht-Fahrzeugstunden",
                       tip="freight_vehicle_hours: Flottenzeit im Frachtmodus. Basis für die "
                           "Kostenzurechnung Fracht/Pax."))

    dead, serv = _kpi(k, "deadhead_km_planned"), _kpi(k, "service_km_planned")
    if dead is not None and serv is not None and (dead + serv) > 0:
        t.append(_tile(_fmt_pct(dead / (dead + serv)), "Deadhead-Anteil",
                       sub=_fmt_de(dead) + " von " + _fmt_de(dead + serv) + " km",
                       tip="deadhead_km_planned / (deadhead + service). GEPLANTE Werte aus dem "
                           "jsprit-Tourenplan, nicht aus dem Mobsim."))

    late = _kpi(k, "parcels_served_late")
    if late is not None:
        sub = ""
        if served is not None and served > 0:
            sub = _fmt_pct(late / served) + " der zugestellten"
        t.append(_tile(_fmt_de(late), "Pakete verspätet", sub,
                       tip="parcels_served_late (C8): zugestellt, aber nach dem zugesagten "
                           "Zeitfenster."))

    return "".join(t)


# ---------------------------------------------------------------------------
# Chart groups
# ---------------------------------------------------------------------------

def _lifecycle_charts(k, uid):
    return [
        _bar("Tour-Lebenszyklus", "c_m_funnel_" + uid, [
            ("geplant", _kpi(k, "tours_planned")),
            ("disponiert", _kpi(k, "tours_dispatched")),
            ("abgeschlossen", _kpi(k, "tours_completed")),
        ], horizontal=True),
        _bar("Wo Touren verloren gehen", "c_m_outflow_" + uid, [
            ("nie disponiert (verfallen)", _kpi(k, "tours_expired_pending")),
            ("am Splice abgelehnt", _kpi(k, "tours_rejected_at_splice")),
            ("disponiert, unvollständig", _kpi(k, "tours_dispatched_incomplete")),
            ("offen bei Betriebsende", _kpi(k, "tours_pending_eod")),
        ], horizontal=True),
        _bar("Paket-Lebenszyklus", "c_m_parcels_" + uid, [
            ("geplant", _kpi(k, "parcels_planned")),
            ("disponiert", _kpi(k, "parcels_dispatched")),
            ("zugestellt", _kpi(k, "parcels_served")),
        ], horizontal=True),
        _bar("Wo Pakete verloren gehen", "c_m_plost_" + uid, [
            ("jsprit unassigned", _kpi(k, "parcels_unassigned_jsprit")),
            ("nie disponiert", _kpi(k, "parcels_expired_pending")),
            ("disponiert, nicht zugestellt", _kpi(k, "parcels_dispatched_unserved")),
            ("offen bei Betriebsende", _kpi(k, "parcels_pending_eod")),
        ], horizontal=True),
    ]


def _delta_charts(k, uid):
    """"Never left" against "left but did not finish" -- two different failures
    with two different fixes, which is why the split is a chart, not a tile."""
    return [
        _stack100("δ-Zerlegung (Anteile an delta_parcels)", "c_m_delta_" + uid, [
            ("nie disponiert", _kpi(k, "delta_share_undispatched")),
            ("disponiert, unvollständig", _kpi(k, "delta_share_dispatched_incomplete")),
        ]),
        _bar("Pünktlichkeit (C8)", "c_m_late_" + uid, [
            ("Touren verspätet", _kpi(k, "tours_completed_late")),
            ("Pakete verspätet", _kpi(k, "parcels_served_late")),
        ]),
    ]


def _coupling_charts(k, uid):
    return [
        _bar("Geplante Fahrleistung", "c_m_km_" + uid, [
            ("Service", _kpi(k, "service_km_planned")),
            ("Deadhead", _kpi(k, "deadhead_km_planned")),
        ], unit="km"),
        _bar("Kapsel-Betrieb", "c_m_swaps_" + uid, [
            ("Tausche", _kpi(k, "swaps_completed")),
            ("Peak gleichzeitig", _kpi(k, "peak_concurrent_swaps")),
        ]),
        _bar("Paketdichte", "c_m_load_" + uid, [
            ("max. Pakete je Tour", _kpi(k, "max_parcels_per_tour")),
        ]),
    ]


def build_tab(data, uid, compact=False, map_block=None):
    """1d mechanism tab. Signature matches render_drt/render_lmd.build_tab.

    `compact=True` (comparison page) renders the lifecycle and failure groups
    only -- the coupling detail is per-run diagnosis, not a cross-run figure."""
    k = data.kpis

    map_html, map_js = "", ""
    if map_block is not None:
        map_html = map_block.get("html", "")
        map_js = map_block.get("js", "")

    groups = [("Tour- und Paket-Lebenszyklus", _lifecycle_charts(k, uid)),
              ("Ausfall und Verspätung", _delta_charts(k, uid))]
    if not compact:
        groups.append(("Preis der Kopplung", _coupling_charts(k, uid)))

    groups_html, groups_js = [], []
    for title, charts in groups:
        h, j = _render_group(title, charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

    html = '<div class="tiles">' + _tiles(data) + "</div>" + map_html + "".join(groups_html)
    js = "\n".join(g for g in groups_js if g) + map_js
    return html, js
