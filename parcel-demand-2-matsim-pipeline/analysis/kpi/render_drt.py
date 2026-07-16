# -*- coding: utf-8 -*-
"""DRT tab renderer -- HAGRID Run-Dashboard v2 Plan C (Task 5 + 6).

Task 5 delivered the module skeleton and the 22-tile headline set
(`_tiles`). Task 6 fills the chart seam: Tagesverlauf (hourly profile),
Verteilungen (wait/tour-duration/tour-distance histograms), Besetzung &
Modal Split (occupancy decomposition + modal split), Konvergenz
(iteration series) and Service-Zeit Detail (per-vehicle occupied hours).
The map block (Plan D) is inserted after the tiles when supplied by the
caller.

Tile spec: see `.superpowers/sdd/task-5-brief.md` ("THE 22-TILE DRT SET").
Chart spec: see `.superpowers/sdd/task-6-brief.md` (exact 15-chart list).
Every tile/chart is guarded by its source KPI/series/rows being absent, so
runs without event reconstruction (no_events) or without Plan-D-only
series (drt_tour_distance/occ_km) simply render fewer tiles/charts."""
import json

from render import (_kpi, _tile, _fmt_de, _fmt_pct, _panel, _series, chart_js,
                     MODE_SLOTS)

# Bottom-up placeholder cost rate (kept in sync with economics.py: 20 EUR/h
# labour + 5 EUR/h vehicle = 25 EUR/veh-shift-h, Rudolph ~80/20 split).
_COST_TIP = (
    "PLATZHALTER-Kostenfunktion (Beschluss 2026-07-02, noch zu praezisieren): "
    "25 EUR je Flotten-Schichtstunde (20 EUR/h Personal + 5 EUR/h Fahrzeug, "
    "Rudolph ~80/20 Personal/Fahrzeug). Nur direkte Kosten, KEIN Overhead. "
    "Literatur-Benchmark: Currie & Fournier (2020, Transport Policy), "
    "DRT-Vollkosten je Fahrzeug-Stunde. 3. Aera (2009-2019) Median ~110 AU$2019 "
    "= 68 EUR/h (2. Aera ~60 AU$ = 37 EUR/h). Vollkosten inkl. "
    "Overhead/Verwaltung - Obergrenze zum Bottom-up-Platzhalter. "
    "Studie: hohe Kosten je Fzg-h korrelieren signifikant mit DRT-Einstellung."
)


def _tiles(data):
    kpis = data.kpis
    t = []

    # 1. DRT-Modal-Anteil
    v = _kpi(kpis, "modal_share_drt")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "DRT-Modal-Anteil",
                        tip="Anteil DRT an allen Wegen (modestats, letzte Iteration)."))

    # 2. DRT-Fahrten [sub: drt_passengers Pax]
    v = _kpi(kpis, "drt_rides")
    if v is not None:
        pax = _kpi(kpis, "drt_passengers")
        sub = (_fmt_de(pax) + " Pax") if pax is not None else ""
        t.append(_tile(_fmt_de(v), "DRT-Fahrten", sub,
                        tip="Bediente DRT-Legs (rides aus drt_customer_stats, letzte Iteration)."))

    # 3. Wartezeit (Median) [sub: wait_p95]
    v = _kpi(kpis, "wait_median")
    if v is not None:
        p95 = _kpi(kpis, "wait_p95")
        sub = ("P95: " + _fmt_de(p95 / 60.0, 1) + " min") if p95 is not None else ""
        t.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Wartezeit (Median)", sub,
                        tip="Fahrgast-Wartezeit von Anfrage-Submission bis Einstieg "
                            "(drt_legs waitTime), Median."))

    # 4. Wartezeit (Ø) [sub: wait_below_15min "< 15 min"]
    v = _kpi(kpis, "wait_mean")
    if v is not None:
        w15 = _kpi(kpis, "wait_below_15min")
        sub = (_fmt_pct(w15) + " < 15 min") if w15 is not None else ""
        t.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Wartezeit (Ø)", sub,
                        tip="Fahrgast-Wartezeit von Anfrage-Submission bis Einstieg "
                            "(drt_legs waitTime), arithmetisches Mittel."))

    # 5. Ablehnungsquote [sub: drt_rejections abs.]
    v = _kpi(kpis, "drt_rejection_rate")
    if v is not None:
        rej = _kpi(kpis, "drt_rejections")
        sub = (_fmt_de(rej) + " abgelehnt") if rej is not None else ""
        t.append(_tile(_fmt_pct(v, 2), "Ablehnungsquote", sub,
                        tip="rejections/(rides+rejections) aus den Ganzzahl-Spalten von "
                            "drt_customer_stats (Anfragen ohne machbare Einfuegung)."))

    # 6. Fahrzeuge
    v = _kpi(kpis, "drt_vehicles")
    if v is not None:
        t.append(_tile(_fmt_de(v), "Fahrzeuge",
                        tip="DVRP-Flotte aus der Flottendatei; alle Fahrzeuge mit Events "
                            "im letzten Iteration."))

    # 7. Fahrzeug-km [sub: drt_empty_ratio Leeranteil]
    v = _kpi(kpis, "drt_vehicle_km")
    if v is not None:
        empty_ratio = _kpi(kpis, "drt_empty_ratio")
        sub = (_fmt_pct(empty_ratio) + " Leeranteil") if empty_ratio is not None else ""
        t.append(_tile(_fmt_de(v, 1) + " km", "Fahrzeug-km", sub,
                        tip="Fahrzeugkm aus MATSim drt_vehicle_stats (totalDistance, "
                            "autoritativ)."))

    # 8. Personen-km
    v = _kpi(kpis, "drt_passenger_km")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " km", "Personen-km",
                        tip="Summe travelDistance_m = tatsaechlich gefahrene In-Vehicle-"
                            "Distanz aller Fahrgaeste inkl. Pooling-Umweg "
                            "(drt_vehicle_stats totalPassengerDistanceTraveled)."))

    # 9. Service-Zeit (aktiv) -- events only
    v = _kpi(kpis, "service_ratio_active")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Service-Zeit (aktiv)",
                        tip="Zeit mit >=1 Fahrgast an Bord / aktive Dienstzeit (erste "
                            "Abfahrt bis letzte Aufgabe je Fzg), aus Event-Rekonstruktion."))

    # 10. Service-Zeit (Schicht) -- events only
    v = _kpi(kpis, "service_ratio_shift")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Service-Zeit (Schicht)",
                        tip="Zeit mit >=1 Fahrgast an Bord / Dienstfenster aus der "
                            "Flottendatei (Schicht), aus Event-Rekonstruktion."))

    # 11. Auslastung (Fahrten) (T1)
    v = _kpi(kpis, "fleet_utilisation_by_trips")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Auslastung (Fahrten)",
                        tip="Mittel von (Passagiere/Kapazitaet) ueber alle Konstant-"
                            "Besetzungs-Segmente, je Segment gleich gewichtet, aus "
                            "Event-Rekonstruktion."))

    # 12. Auslastung (Zeit) -- events only
    v = _kpi(kpis, "fleet_utilisation_by_time")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Auslastung (Zeit)",
                        tip="Zeitgewichtetes Mittel der Besetzung ueber Segmente "
                            "konstanter Belegung, aus Event-Rekonstruktion."))

    # 13. Ø Pax an Bord -- events only
    v = _kpi(kpis, "mean_pax_aboard")
    if v is not None:
        t.append(_tile(_fmt_de(v, 2), "Ø Pax an Bord",
                        tip="Zeitgewichtetes Mittel der Fahrgaeste an Bord ueber die "
                            "aktive Tourzeit inkl. Leerfahrten, aus Event-Rekonstruktion."))

    # 14. Umwegfaktor
    v = _kpi(kpis, "detour_factor")
    if v is not None:
        t.append(_tile(_fmt_de(v, 2), "Umwegfaktor",
                        tip="Tatsaechlich gefahrene In-Vehicle-Distanz / direkte Netz-"
                            "Distanz (Summe travelDistance_m / directTravelDistance_m aus "
                            "drt_legs). 1,0 = umwegfrei."))

    # 15. Ø Fahrtlänge (T1)
    v = _kpi(kpis, "drt_trip_distance_mean")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " km", "Ø Fahrtlänge",
                        tip="Mittlere Fahrtdistanz je Fahrgast (drt_customer_stats "
                            "distance_m_mean)."))

    # 16. Tourdauer gesamt (T1) -- events only
    v = _kpi(kpis, "drt_tour_hours_total")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " h", "Tourdauer gesamt",
                        tip="Summe der aktiven Tourzeit ueber die Flotte (erste Abfahrt "
                            "bis letzte Aufgabe je Fzg; naechtliches Depot-Parken "
                            "ausgeklammert), aus Event-Rekonstruktion."))

    # 17. Fahrzeit gesamt (T1) -- events only
    v = _kpi(kpis, "drt_drive_hours_total")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " h", "Fahrzeit gesamt",
                        tip="Summe der DRIVE-Task-Zeiten (Fahrzeug bewegt sich, mit oder "
                            "ohne Fahrgast) ueber die Flotte, aus Event-Rekonstruktion."))

    # 18. Wartedauer gesamt (T1) -- events only
    v = _kpi(kpis, "drt_wait_hours_total")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " h", "Wartedauer gesamt",
                        tip="Summe der Leerlaufzeiten zwischen Auftraegen ueber die "
                            "Flotte (Warten auf naechsten Fahrgast), aus Event-"
                            "Rekonstruktion."))

    # 19. Servicedauer gesamt (T1) -- events only
    v = _kpi(kpis, "drt_service_hours_total")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " h", "Servicedauer gesamt",
                        tip="Summe der STOP-Task-Zeiten (Ein-/Ausstieg an Pickup/"
                            "Dropoff) ueber die Flotte, aus Event-Rekonstruktion."))

    # 20. Feeder-Fahrten [sub: drt_feeder_share]
    v = _kpi(kpis, "drt_feeder_trips")
    if v is not None:
        share = _kpi(kpis, "drt_feeder_share")
        sub = (_fmt_pct(share) + " Feeder-Anteil") if share is not None else ""
        t.append(_tile(_fmt_de(v), "Feeder-Fahrten", sub,
                        tip="DRT-Fahrt in einem Weg mit Bahn-Anschluss (modes enthaelt "
                            "pt; Fahrplan ist rail-only). Kein Kettennachweis am "
                            "Bahnsteig - Naehe-Proxy."))

    # 21. Kosten (Platzhalter) [sub: drt_cost_per_ride_placeholder + Currie/Fournier]
    v = _kpi(kpis, "drt_cost_bottom_up_placeholder")
    if v is not None:
        per_ride = _kpi(kpis, "drt_cost_per_ride_placeholder")
        sub = ((_fmt_de(per_ride, 2) + " EUR/Fahrt • Currie/Fournier-Benchmark")
               if per_ride is not None else "")
        t.append(_tile(_fmt_de(v) + " EUR", "Kosten (Platzhalter)", sub, tip=_COST_TIP))

    # 22. Pooling-Quote [sub: sharing_factor]
    v = _kpi(kpis, "pooling_rate")
    if v is not None:
        sf = _kpi(kpis, "sharing_factor")
        sub = (_fmt_de(sf, 2) + " Sharing-Faktor") if sf is not None else ""
        t.append(_tile(_fmt_pct(v), "Pooling-Quote", sub,
                        tip="Anteil der DRT-Fahrten mit gleichzeitig mehreren Fahrgaesten "
                            "an Bord (poolingRate aus drt_sharing_metrics)."))

    return "".join(t)


# ---------------------------------------------------------------------------
# Chart builders (Task 6). Each returns (title, canvas_id, cfg, height) or
# None when its source series/rows are absent -- callers just skip Nones.
# ---------------------------------------------------------------------------

def _fmt_num(v):
    v = float(v)
    s = str(int(v)) if v == int(v) else str(round(v, 2))
    return s.replace(".", ",")   # German decimals (presentation-only); whole nums unaffected


def _bin_label(lo, hi, div=1):
    return _fmt_num(lo / div) + "-" + _fmt_num(hi / div)


def _ts_chart(ts, series, title, cid, kind, transform=None, height=210):
    """Bar/line chart over `data.ts` hours for one series. `transform`, if
    given, is applied to every value (e.g. seconds -> minutes)."""
    hrs, vals = _series(ts, series)
    if not hrs:
        return None
    if transform is not None:
        vals = [transform(v) for v in vals]
    ds = {"label": title, "data": vals, "__seq": True}
    if kind == "bar":
        ds["borderRadius"] = 4
        ds["maxBarThickness"] = 18
    else:
        ds["borderWidth"] = 2
        ds["pointRadius"] = 0
        ds["tension"] = 0.25
    cfg = {"type": kind, "data": {"labels": hrs, "datasets": [ds]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _feeder_toggle(ts, cid, btn_id, title="Feeder-Fahrten je Stunde", height=210):
    """Feeder-trips-per-hour bar chart with an Absolut/Anteil toggle (chart
    5). cfgB's share is feeder/rides for that hour, 0 when rides is 0/absent."""
    hrs_f, feed = _series(ts, "drt_feeder_trips")
    if not hrs_f:
        return None
    hrs_r, rides = _series(ts, "drt_rides")
    rides_by_hr = dict(zip(hrs_r, rides))
    shares = [round((f / rides_by_hr[h] * 100.0) if rides_by_hr.get(h) else 0.0, 2)
              for h, f in zip(hrs_f, feed)]
    cfgA = {"type": "bar", "data": {"labels": hrs_f, "datasets": [{
                "label": "Feeder-Fahrten", "data": feed, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}}}
    cfgB = {"type": "bar", "data": {"labels": hrs_f, "datasets": [{
                "label": "Feeder-Anteil [%]", "data": shares, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}}}
    return (title, cid, btn_id, cfgA, cfgB, height)


def _dist_chart(dist, series, title, cid, unit_div=1, height=210):
    """Bar chart over `data.distributions` bins (chart 6/7/8). bin_lo/bin_hi
    are divided by `unit_div` for the "lo-hi" labels; value is the raw
    bin count."""
    if "series" not in dist.columns:
        return None
    rows = dist[dist["series"] == series].sort_values("bin_lo")
    if not len(rows):
        return None
    labels = [_bin_label(r["bin_lo"], r["bin_hi"], unit_div) for _, r in rows.iterrows()]
    vals = [round(float(v), 3) for v in rows["value"]]
    cfg = {"type": "bar", "data": {"labels": labels, "datasets": [{
                "label": title, "data": vals, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 24}]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _occ_chart(dist, cid, title="Besetzungs-Dekomposition", height=210):
    """Horizontal 100%-stacked bar (chart 9): rows = whichever of
    occ_segments/occ_time/occ_km exist, one dataset per occupancy level
    (__ramp), values = shares (as percent)."""
    if "series" not in dist.columns:
        return None
    mapping = [("occ_segments", "Segmente"), ("occ_time", "Zeit"), ("occ_km", "Distanz")]
    present = [(s, lbl) for s, lbl in mapping if len(dist[dist["series"] == s])]
    if not present:
        return None
    labels = [lbl for _, lbl in present]
    levels = sorted({int(v) for s, _ in present for v in dist[dist["series"] == s]["bin_lo"]})
    if not levels:
        return None
    max_level = max(levels)
    datasets = []
    for lv in levels:
        row = []
        for s, _ in present:
            sub = dist[(dist["series"] == s) & (dist["bin_lo"] == lv)]
            row.append(round(float(sub.iloc[0]["value"]) * 100, 2) if len(sub) else 0.0)
        datasets.append({"label": str(lv), "data": row, "stack": "s", "__ramp": [lv, max_level]})
    cfg = {"type": "bar", "data": {"labels": labels, "datasets": datasets},
           "options": {"indexAxis": "y", "responsive": True, "maintainAspectRatio": False,
                       "scales": {"x": {"stacked": True, "max": 100, "grid": {"display": False}},
                                  "y": {"stacked": True}}}}
    return (title, cid, cfg, height)


def _modal_chart(kpis, cid, title="Modal Split", height=120):
    """Single horizontal 100%-stacked bar (chart 10) -- ported from
    render.render_run_sections (the modal_share_* block), fixed MODE_SLOTS
    colors. render_run_sections itself is not called (Task 10 deletes it)."""
    modes = kpis[kpis["kpi_name"].str.startswith("modal_share_")]
    if not len(modes):
        return None
    datasets = []
    for _, r in modes.iterrows():
        mode = r["kpi_name"].replace("modal_share_", "")
        datasets.append({"label": mode, "data": [round(float(r["value"]) * 100, 2)], "stack": "s",
                          "categoryPercentage": 0.5, "__slot": MODE_SLOTS.get(mode, 6)})
    cfg = {"type": "bar", "data": {"labels": ["Modal Split"], "datasets": datasets},
           "options": {"indexAxis": "y", "responsive": True, "maintainAspectRatio": False,
                       "scales": {"x": {"stacked": True, "max": 100, "grid": {"display": False}},
                                  "y": {"stacked": True, "display": False}}}}
    return (title, cid, cfg, height)


def _iter_series(it, name):
    if "series" not in it.columns:
        return [], []
    m = it[it["series"] == name].sort_values("iteration")
    return list(m["iteration"].astype(int)), list(m["value"].astype(float))


def _iter_chart(it, series_list, title, cid, slots=None, transform=None,
                legend=False, height=210):
    """Line chart over `data.iterations` (charts 11-14). `series_list` is a
    list of (series_name, label); entries whose series is absent are
    skipped individually. `slots`, if given, is a list of __slot ints
    aligned by index to `series_list` (else every dataset gets __seq)."""
    datasets = []
    labels = None
    for i, (name, label) in enumerate(series_list):
        iters, vals = _iter_series(it, name)
        if not iters:
            continue
        if transform is not None:
            vals = [transform(v) for v in vals]
        if labels is None:
            labels = iters
        ds = {"label": label, "data": vals, "borderWidth": 2, "pointRadius": 0, "tension": 0.25}
        if slots is not None:
            ds["__slot"] = slots[i]
        else:
            ds["__seq"] = True
        datasets.append(ds)
    if not datasets:
        return None
    cfg = {"type": "line", "data": {"labels": labels, "datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": legend}}}}
    return (title, cid, cfg, height)


def _vehicle_chart(vehicles, cid, title="Besetzte Zeit je Fahrzeug [h]", height=260):
    """Per-vehicle bar (chart 15): DRT vehicles sorted by occupied_h desc."""
    if "role" not in vehicles.columns:
        return None
    veh = vehicles[vehicles["role"] == "drt"].sort_values("occupied_h", ascending=False)
    if not len(veh):
        return None
    labels = [str(v) for v in veh["vehicle_id"]]
    vals = [round(float(v), 2) for v in veh["occupied_h"]]
    cfg = {"type": "bar", "data": {"labels": labels, "datasets": [{
                "label": title, "data": vals, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 24}]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _render_group(title_h2, charts, toggle=None):
    """Wrap a list of (title, cid, cfg, height) chart tuples (+ optional
    toggle spec from `_feeder_toggle`) in a `<h2>` + `.grid2` section. Empty
    groups (no charts, no toggle) render nothing."""
    panels = [_panel(t, cid, h) for t, cid, _cfg, h in charts]
    js = [chart_js(cid, cfg) for _, cid, cfg, _h in charts]
    if toggle is not None:
        ttitle, cid, btn_id, cfgA, cfgB, height = toggle
        panels.append(
            '<div class="panel"><h3>' + ttitle + '</h3>'
            '<button id="' + btn_id + '" class="tglbtn">Absolut</button>'
            '<div style="height:' + str(height) + 'px"><canvas id="' + cid + '"></canvas></div></div>')
        js.append("mkToggle(" + json.dumps(btn_id) + ", " + json.dumps(cid) + ", "
                   + json.dumps(cfgA) + ", " + json.dumps(cfgB) + ', "Absolut", "Anteil");')
    if not panels:
        return "", ""
    html = "<h2>" + title_h2 + '</h2><div class="grid2">' + "".join(panels) + "</div>"
    return html, "\n".join(js)


def build_tab(data, uid, compact=False, map_block=None):
    """DRT tab body: 22-tile headline set + optional map block + charts.

    Returns (html, js). `compact=True` renders only the Tagesverlauf
    charts 1-4 (no feeder toggle) plus the Modal Split (chart 10) -- no
    distributions, occupancy, convergence, or per-vehicle charts. Every
    chart is skipped individually when its source series/rows are absent
    (e.g. runs without event reconstruction, or without the Plan-D-only
    drt_tour_distance/occ_km series)."""
    tiles = _tiles(data)

    map_html, map_js = "", ""
    if map_block is not None:
        map_html = map_block.get("html", "")
        map_js = map_block.get("js", "")

    kpis, ts = data.kpis, data.ts
    dist, iters, vehicles = data.distributions, data.iterations, data.vehicles

    groups_html, groups_js = [], []

    # --- Tagesverlauf (charts 1-5) ---
    tag_charts = []
    for series, title, cid, kind, transform in [
        ("drt_rides", "DRT-Fahrten je Stunde (bedient)", "c_rides_" + uid, "bar", None),
        ("drt_requests_submitted", "DRT-Anfragen je Stunde (eingereicht)",
         "c_subm_" + uid, "line", None),
        ("drt_rejections", "Ablehnungen je Stunde", "c_rej_" + uid, "bar", None),
        ("drt_wait_mean", "Mittlere Wartezeit je Stunde [min]", "c_wait_" + uid, "line",
         lambda v: round(v / 60.0, 2)),
    ]:
        c = _ts_chart(ts, series, title, cid, kind, transform=transform)
        if c:
            tag_charts.append(c)
    toggle = None if compact else _feeder_toggle(ts, "c_feed_" + uid, "btn_feed_" + uid)
    h, j = _render_group("Tagesverlauf", tag_charts, toggle)
    if h:
        groups_html.append(h)
        groups_js.append(j)

    if not compact:
        # --- Verteilungen (charts 6-8) ---
        dist_charts = []
        c = _dist_chart(dist, "drt_wait", "Wartezeit-Verteilung [min]", "c_wdist_" + uid, unit_div=60)
        if c:
            title, cid, cfg, height = c
            markers = []
            for kpi_name, label in [("wait_median", "Median"), ("wait_mean", "Ø"),
                                     ("wait_p95", "P95")]:
                v = _kpi(kpis, kpi_name)
                if v is not None:
                    markers.append({"x": round(v / 60.0 - 0.5, 3), "label": label})
            if markers:
                cfg["options"]["plugins"]["vlines"] = {"lines": markers}
            dist_charts.append((title, cid, cfg, height))
        c = _dist_chart(dist, "drt_tour_duration", "Aktive Tourdauer je Fahrzeug [h]",
                         "c_tdur_" + uid)
        if c:
            dist_charts.append(c)
        c = _dist_chart(dist, "drt_tour_distance", "Tourdistanz je Fahrzeug [km]",
                         "c_tdist_" + uid)   # Plan D series -- guarded, absent until then
        if c:
            dist_charts.append(c)
        h, j = _render_group("Verteilungen", dist_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

    # --- Besetzung & Modal Split (charts 9-10; occ only outside compact) ---
    occ_modal_charts = []
    if not compact:
        c = _occ_chart(dist, "c_occ_" + uid)
        if c:
            occ_modal_charts.append(c)
    c = _modal_chart(kpis, "c_modal_" + uid)
    if c:
        occ_modal_charts.append(c)
    h, j = _render_group("Besetzung & Modal Split", occ_modal_charts)
    if h:
        groups_html.append(h)
        groups_js.append(j)

    if not compact:
        # --- Konvergenz (charts 11-14) ---
        conv_charts = []
        c = _iter_chart(iters, [("drt_rides", "Fahrten")], "Fahrten über Iterationen",
                         "c_it_rides_" + uid)
        if c:
            conv_charts.append(c)
        c = _iter_chart(iters, [("drt_rejection_rate", "Ablehnungsquote")],
                         "Ablehnungsquote über Iterationen [%]", "c_it_rej_" + uid,
                         transform=lambda v: round(v * 100, 2))
        if c:
            conv_charts.append(c)
        c = _iter_chart(iters, [("wait_mean", "Ø"), ("wait_p95", "P95")],
                         "Wartezeit über Iterationen [min]", "c_it_wait_" + uid,
                         slots=[0, 1], transform=lambda v: round(v / 60.0, 2), legend=True)
        if c:
            conv_charts.append(c)
        modal_series = [("modal_share_" + m, m) for m in MODE_SLOTS]
        modal_slots = [MODE_SLOTS[m] for m in MODE_SLOTS]
        c = _iter_chart(iters, modal_series, "Modal Shares über Iterationen [%]",
                         "c_it_modal_" + uid, slots=modal_slots,
                         transform=lambda v: round(v * 100, 2), legend=True)
        if c:
            conv_charts.append(c)
        h, j = _render_group("Konvergenz", conv_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

        # --- Service-Zeit Detail (chart 15) ---
        veh_charts = []
        c = _vehicle_chart(vehicles, "c_veh_" + uid)
        if c:
            veh_charts.append(c)
        h, j = _render_group("Service-Zeit Detail", veh_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

    charts_html = "".join(groups_html)
    charts_js = "\n".join(g for g in groups_js if g)

    html = '<div class="tiles">' + tiles + "</div>" + map_html + charts_html
    js = charts_js + map_js
    return html, js
