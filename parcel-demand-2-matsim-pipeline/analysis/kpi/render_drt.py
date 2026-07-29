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

import extract_drt
import pax_only
from render import (_kpi, _kpi_source, _tile, _fmt_de, _fmt_pct, _panel, _series,
                     chart_js, MODE_SLOTS, _donut)

# German labels for the modal-split donut segments (chart 10); unknown mode
# ids fall back to the raw kpi_name suffix.
_MODE_LABELS_DE = {"car": "Pkw", "ride": "Mitfahrt", "walk": "Fuß",
                    "bike": "Rad", "drt": "DRT", "pt": "ÖV"}

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


def _tip_src(desc, kpis, name):
    """Tooltip text plus the row's ACTUAL `source` column (E4). On a
    Shared-Use run pax_only.apply_overrides swaps the canonical row for its
    pax-only correction, so hardcoding "drt_customer_stats" here would cite
    the wrong basis exactly on the runs where the basis matters. Rows without
    a source (reduced test fixtures) get the plain description."""
    src = _kpi_source(kpis, name)
    return desc + ((" Quelle: " + src + ".") if src else "")


# ---------------------------------------------------------------------------
# 1d MODULAR contamination banner (Task 4, review I1/I8/I4 -- METHODS-LOG
# 2.14 points 3-4). Same `warnbanner` CSS class/pattern as the Konvergenz-
# group banner below (:~700), reused rather than re-invented: on a run
# without either meta marker row (baseline/Shared-Use/LMD/no-events builds
# that predate Task 3) both helpers return "" and every affected tile/chart
# renders byte-identical to before.
# ---------------------------------------------------------------------------
_MODULAR_BADGE_HTML = ('<div class="warnbanner">enthaelt Frachtanteil, '
                        's. METHODS-LOG 2.14</div>')

#: Exactly the tiles review found publishing 1d MODULAR freight-contaminated
#: values with no marker: extract_drt.MODULAR_UNCORRECTABLE's drt_vehicle_km
#: (its tile, #7 "Fahrzeug-km", also carries drt_empty_ratio as a sub-line --
#: one tile, one call site, so only the tile's own KPI name is listed here;
#: drt_dp_over_dt has no tile at all) and fleet_utilisation_by_trips, plus
#: extract_drt.MODULAR_FREIGHT_IN_WINDOW in full. Kept as its own literal here
#: (not derived from those two extract_drt tuples): which KPIs get a DASHBOARD
#: badge is a render decision, not extract_drt's correction bookkeeping, even
#: though today the two happen to agree.
_MODULAR_BADGE_KPIS = frozenset((
    "drt_vehicle_km", "fleet_utilisation_by_trips",
    "service_ratio_active", "fleet_utilisation_by_time", "mean_pax_aboard",
    "drt_tour_hours_total",
))


def _contamination_badge(kpis, kpi_name):
    """The warnbanner badge for one tile's KPI, or "" when either the tile's
    KPI is not one of the affected `_MODULAR_BADGE_KPIS` (a coding mistake at
    the call site should never leak a badge onto an unrelated tile) or the
    `meta/modular_contaminated_kpis` marker row (Task 3, extract_drt) is
    absent from this run's kpis (baseline/Shared-Use/LMD runs, or a 1d run
    whose CSVs predate Task 3)."""
    if kpi_name not in _MODULAR_BADGE_KPIS:
        return ""
    if _kpi(kpis, extract_drt.MODULAR_CONTAMINATION_KPI) is None:
        return ""
    return _MODULAR_BADGE_HTML


def _secondary_contamination_badge(kpis):
    """Same badge, gated on the SECONDARY marker (meta/modular_secondary_contaminated,
    Task 3) instead of the primary one -- for the chart/table consumers
    (kpi_distributions.csv's occupancy decomposition + tour-duration series,
    kpi_vehicles.csv's per-vehicle occupied time) that carry no provenance
    channel of their own (METHODS-LOG 2.14 point 4)."""
    if _kpi(kpis, "modular_secondary_contaminated") is None:
        return ""
    return _MODULAR_BADGE_HTML


def _tiles(data):
    kpis = data.kpis
    t = []

    # 1. DRT-Modal-Anteil
    v = _kpi(kpis, "modal_share_drt")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "DRT-Modal-Anteil",
                        tip=_tip_src("Anteil DRT an allen Wegen (letzte Iteration).",
                                     kpis, "modal_share_drt")))

    # 2. DRT-Fahrten [sub: drt_passengers Pax]
    v = _kpi(kpis, "drt_rides")
    if v is not None:
        pax = _kpi(kpis, "drt_passengers")
        sub = (_fmt_de(pax) + " Pax") if pax is not None else ""
        t.append(_tile(_fmt_de(v), "DRT-Fahrten", sub,
                        tip=_tip_src("Bediente DRT-Legs (letzte Iteration).",
                                     kpis, "drt_rides")))

    # 3. Wartezeit (Median) [sub: wait_p95]
    v = _kpi(kpis, "wait_median")
    if v is not None:
        p95 = _kpi(kpis, "wait_p95")
        sub = ("P95: " + _fmt_de(p95 / 60.0, 1) + " min") if p95 is not None else ""
        t.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Wartezeit (Median)", sub,
                        tip=_tip_src("Fahrgast-Wartezeit von Anfrage-Submission bis "
                                     "Einstieg, Median.", kpis, "wait_median")))

    # 4. Wartezeit (Ø) [sub: wait_below_15min "< 15 min"]
    v = _kpi(kpis, "wait_mean")
    if v is not None:
        w15 = _kpi(kpis, "wait_below_15min")
        sub = (_fmt_pct(w15) + " < 15 min") if w15 is not None else ""
        t.append(_tile(_fmt_de(v / 60.0, 1) + " min", "Wartezeit (Ø)", sub,
                        tip=_tip_src("Fahrgast-Wartezeit von Anfrage-Submission bis "
                                     "Einstieg, arithmetisches Mittel.", kpis, "wait_mean")))

    # 5. Ablehnungsquote [sub: drt_rejections abs.]
    v = _kpi(kpis, "drt_rejection_rate")
    if v is not None:
        rej = _kpi(kpis, "drt_rejections")
        sub = (_fmt_de(rej) + " abgelehnt") if rej is not None else ""
        t.append(_tile(_fmt_pct(v, 2), "Ablehnungsquote", sub,
                        tip=_tip_src("rejections/(rides+rejections) -- Anfragen ohne "
                                     "machbare Einfuegung.", kpis, "drt_rejection_rate")))

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
                            "autoritativ).")
                 + _contamination_badge(kpis, "drt_vehicle_km"))

    # 8. Personen-km
    v = _kpi(kpis, "drt_passenger_km")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " km", "Personen-km",
                        tip="Summe travelDistance_m = tatsaechlich gefahrene In-Vehicle-"
                            "Distanz aller Fahrgaeste inkl. Pooling-Umweg "
                            "(drt_vehicle_stats totalPassengerDistanceTraveled)."))

    # 9. Service-Zeit (aktiv) -- events only [sub: service_ratio_active_pax]
    v = _kpi(kpis, "service_ratio_active")
    if v is not None:
        pax_v = _kpi(kpis, "service_ratio_active_pax")
        sub = ("pax-bereinigt: " + _fmt_pct(pax_v)) if pax_v is not None else ""
        t.append(_tile(_fmt_pct(v), "Service-Zeit (aktiv)", sub,
                        tip="Zeit mit >=1 Fahrgast an Bord / aktive Dienstzeit (erste "
                            "Abfahrt bis letzte Aufgabe je Fzg), aus Event-Rekonstruktion.")
                 + _contamination_badge(kpis, "service_ratio_active"))

    # 10. Service-Zeit (Schicht) -- events only
    v = _kpi(kpis, "service_ratio_shift")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Service-Zeit (Schicht)",
                        tip="Zeit mit >=1 Fahrgast an Bord / Dienstfenster aus der "
                            "Flottendatei (Schicht), aus Event-Rekonstruktion."))

    # 11. Auslastung (Fahrten) (T1) -- not correctable (MODULAR_UNCORRECTABLE)
    v = _kpi(kpis, "fleet_utilisation_by_trips")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Auslastung (Fahrten)",
                        tip="Mittel von (Passagiere/Kapazitaet) ueber alle Konstant-"
                            "Besetzungs-Segmente, je Segment gleich gewichtet, aus "
                            "Event-Rekonstruktion.")
                 + _contamination_badge(kpis, "fleet_utilisation_by_trips"))

    # 12. Auslastung (Zeit) -- events only [sub: fleet_utilisation_by_time_pax]
    v = _kpi(kpis, "fleet_utilisation_by_time")
    if v is not None:
        pax_v = _kpi(kpis, "fleet_utilisation_by_time_pax")
        sub = ("pax-bereinigt: " + _fmt_pct(pax_v)) if pax_v is not None else ""
        t.append(_tile(_fmt_pct(v), "Auslastung (Zeit)", sub,
                        tip="Zeitgewichtetes Mittel der Besetzung ueber Segmente "
                            "konstanter Belegung, aus Event-Rekonstruktion.")
                 + _contamination_badge(kpis, "fleet_utilisation_by_time"))

    # 13. Ø Pax an Bord -- events only [sub: mean_pax_aboard_pax]
    v = _kpi(kpis, "mean_pax_aboard")
    if v is not None:
        pax_v = _kpi(kpis, "mean_pax_aboard_pax")
        sub = ("pax-bereinigt: " + _fmt_de(pax_v, 2)) if pax_v is not None else ""
        t.append(_tile(_fmt_de(v, 2), "Ø Pax an Bord (Fzg-Sicht)", sub,
                        tip="FAHRZEUG-Sicht: zeitgewichtetes Mittel der Fahrgaeste an Bord "
                            "ueber die aktive Tourzeit INKL. Leerfahrten (Belegung 0) -- "
                            "daher systematisch niedrig. Nicht mit dem Sharing-Faktor "
                            "(Fahrgast-Sicht, nur besetzte Zeit) verwechseln. Aus "
                            "Event-Rekonstruktion.")
                 + _contamination_badge(kpis, "mean_pax_aboard"))

    # 14. Umwegfaktor
    v = _kpi(kpis, "detour_factor")
    if v is not None:
        t.append(_tile(_fmt_de(v, 2), "Umwegfaktor",
                        tip=_tip_src("Tatsaechlich gefahrene In-Vehicle-Distanz / "
                                     "direkte Netz-Distanz. 1,0 = umwegfrei.",
                                     kpis, "detour_factor")))

    # 15. Ø Fahrtlänge (T1)
    v = _kpi(kpis, "drt_trip_distance_mean")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " km", "Ø Fahrtlänge",
                        tip=_tip_src("Mittlere Fahrtdistanz je Fahrgast.",
                                     kpis, "drt_trip_distance_mean")))

    # 16. Tourdauer gesamt (T1) -- events only [sub: drt_tour_hours_total_pax]
    v = _kpi(kpis, "drt_tour_hours_total")
    if v is not None:
        pax_v = _kpi(kpis, "drt_tour_hours_total_pax")
        sub = ("pax-bereinigt: " + _fmt_de(pax_v, 1) + " h") if pax_v is not None else ""
        t.append(_tile(_fmt_de(v, 1) + " h", "Tourdauer gesamt", sub,
                        tip="Summe der aktiven Tourzeit ueber die Flotte (erste Abfahrt "
                            "bis letzte Aufgabe je Fzg; naechtliches Depot-Parken "
                            "ausgeklammert), aus Event-Rekonstruktion.")
                 + _contamination_badge(kpis, "drt_tour_hours_total"))

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
                        tip="FAHRGAST-Sicht (nur besetzte Zeit). Pooling-Quote = Anteil der "
                            "Fahrten, die IRGENDWANN mit >=1 weiterem Fahrgast geteilt wurden "
                            "(binaer je Fahrt -- saettigt daher nahe 100%). Sharing-Faktor "
                            "(Untertitel) = zeitgewichtete, fahrgast-erlebte Belegung waehrend "
                            "der eigenen Fahrt (harmon. Mittel: 1,0=durchweg allein, "
                            "2,0=durchweg zu zweit), gemittelt ueber alle Fahrgaeste. Beide "
                            "NICHT mit 'Ø Pax an Bord' (Fahrzeug-Sicht inkl. Leerfahrten) "
                            "vergleichbar. Aus drt_sharing_metrics."))

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


def _hours_0_23(ts, series):
    """Reindex `_series(ts, series)` onto a fixed 0..23 hour axis so every
    Tagesverlauf chart spans the full day regardless of which hours actually
    have data; missing hours -> 0.0 (does not itself decide whether a chart
    should render at all -- callers still guard on the raw `_series` result
    being non-empty)."""
    hrs, vals = _series(ts, series)
    by_hr = dict(zip(hrs, vals))
    return list(range(24)), [by_hr.get(h, 0.0) for h in range(24)]


def _ts_chart(ts, series, title, cid, kind, transform=None, height=210):
    """Bar/line chart over `data.ts`, reindexed onto 0..23 (`_hours_0_23`).
    `transform`, if given, is applied to every value (e.g. seconds ->
    minutes) after the 0-fill."""
    hrs, _vals = _series(ts, series)
    if not hrs:
        return None
    hours, vals = _hours_0_23(ts, series)
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
    cfg = {"type": kind, "data": {"labels": hours, "datasets": [ds]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _requests_chart(ts, cid, title="Anfragen & bediente Abfahrten je Stunde",
                     height=210):
    """Combined Tagesverlauf chart (merges the former separate served/bedient
    and submitted-requests charts, #4+#5): served rides (bar) and submitted
    requests (line) on ONE shared y-axis -- both series are requests/h, the
    same unit and scale, so a second axis would mislead rather than clarify.
    Skipped only when NEITHER series has any data; each series is
    independently reindexed onto 0..23 via `_hours_0_23` (missing hours -> 0).

    Colors: the served-rides bar uses the standard `__seq` sequential blue.
    The submitted-requests line uses `__ramp: [0, 1]`, which resolveColors
    (render.py) resolves to the fixed neutral OTHER gray -- the same
    muted/context color used elsewhere for unclassified series -- giving a
    contrasting-but-recessive line without introducing a new color marker
    (out of scope: only render_drt.py/tests are touched by this task)."""
    hrs_r, _ = _series(ts, "drt_rides")
    hrs_s, _ = _series(ts, "drt_requests_submitted")
    if not hrs_r and not hrs_s:
        return None
    hours, rides = _hours_0_23(ts, "drt_rides")
    _, submitted = _hours_0_23(ts, "drt_requests_submitted")
    ds_rides = {"type": "bar", "label": "bediente Abfahrten", "data": rides,
                "__seq": True, "borderRadius": 4, "maxBarThickness": 18}
    ds_subm = {"type": "line", "label": "Anfragen", "data": submitted,
               "__ramp": [0, 1], "borderWidth": 2, "pointRadius": 0,
               "tension": 0.25, "fill": False}
    cfg = {"type": "bar", "data": {"labels": hours, "datasets": [ds_rides, ds_subm]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}}}}
    return (title, cid, cfg, height)


def _feeder_toggle(ts, cid, btn_id, title="Feeder-Fahrten je Stunde", height=210):
    """Feeder-trips-per-hour bar chart with an Absolut/Anteil toggle (chart
    5), reindexed onto 0..23 (`_hours_0_23`). cfgB's share is feeder/rides
    for that hour, 0 when rides is 0/absent."""
    hrs_f, _ = _series(ts, "drt_feeder_trips")
    if not hrs_f:
        return None
    hours, feed = _hours_0_23(ts, "drt_feeder_trips")
    _, rides = _hours_0_23(ts, "drt_rides")
    shares = [round((f / r * 100.0) if r else 0.0, 2) for f, r in zip(feed, rides)]
    cfgA = {"type": "bar", "data": {"labels": hours, "datasets": [{
                "label": "Feeder-Fahrten", "data": feed, "__seq": True,
                "borderRadius": 4, "maxBarThickness": 18}]},
            "options": {"responsive": True, "maintainAspectRatio": False,
                        "plugins": {"legend": {"display": False}}}}
    cfgB = {"type": "bar", "data": {"labels": hours, "datasets": [{
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


def _modal_chart(kpis, cid, title="Modal Split", height=220):
    """Modal-split donut (chart 10) via the shared `render._donut` helper --
    one segment per modal_share_* mode, color follows the fixed MODE_SLOTS
    assignment (`__slots`, null-safe), German labels, center total "100 %".
    Pie/donut is allowed here per the 2026-07-16 user override."""
    modes_df = kpis[kpis["kpi_name"].str.startswith("modal_share_")]
    if not len(modes_df):
        return None
    modes = [r["kpi_name"].replace("modal_share_", "") for _, r in modes_df.iterrows()]
    values = [round(float(r["value"]) * 100, 2) for _, r in modes_df.iterrows()]
    labels = [_MODE_LABELS_DE.get(m, m) for m in modes]
    color_marker = {"__slots": [MODE_SLOTS.get(m, 6) for m in modes]}
    return _donut(cid, title, labels, values, color_marker, height=height,
                  center_label="100 %")


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


def _render_group(title_h2, charts, toggle=None, badge_titles=None):
    """Wrap a list of (title, cid, cfg, height) chart tuples (+ optional
    toggle spec from `_feeder_toggle`) in a `<h2>` + `.grid2` section. Empty
    groups (no charts, no toggle) render nothing.

    `badge_titles` (Task 4 secondary contamination, review I1): an optional
    set of chart titles that get `_MODULAR_BADGE_HTML` appended right after
    their own panel -- unlike the primary-marker tiles, secondary
    contamination does not affect every chart in a group (e.g. Verteilungen
    also has the uncontaminated wait-time chart), so the badge is attached
    per-panel here rather than once after the `<h2>` the way the existing
    Konvergenz banner works. None/empty (the default) -> no change, so every
    existing call site stays byte-identical."""
    badge_titles = badge_titles or frozenset()
    panels = [_panel(t, cid, h) + (_MODULAR_BADGE_HTML if t in badge_titles else "")
              for t, cid, _cfg, h in charts]
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

    # Task 4 secondary contamination (review I1, METHODS-LOG 2.14 point 4):
    # the occupancy decomposition chart, the tour-duration distribution chart
    # and the per-vehicle chart all read kpi_distributions.csv/kpi_vehicles.csv,
    # which carry no provenance channel of their own -- see
    # `_secondary_contamination_badge`. "" (marker absent) -> empty set below
    # -> every panel unchanged.
    secondary_titles = (
        {"Aktive Tourdauer je Fahrzeug [h]", "Besetzungs-Dekomposition",
         "Besetzte Zeit je Fahrzeug [h]"}
        if _secondary_contamination_badge(kpis) else frozenset())

    groups_html, groups_js = [], []

    # --- Tagesverlauf (charts 1-5; #4+#5 merged into one combined chart) ---
    tag_charts = []
    c = _requests_chart(ts, "c_req_" + uid)
    if c:
        tag_charts.append(c)
    for series, title, cid, kind, transform in [
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
        h, j = _render_group("Verteilungen", dist_charts, badge_titles=secondary_titles)
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
    h, j = _render_group("Besetzung & Modal Split", occ_modal_charts, badge_titles=secondary_titles)
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
            # E2: kpi_iterations.csv reads drt_customer_stats for EVERY iteration
            # unfiltered (there is no per-iteration parcel identity to filter on,
            # see pax_only's KNOWN-GAP note), so on a Shared-Use run these series
            # include parcel-agents and their LEVELS contradict the pax-only tiles
            # above. The banner sits between the <h2> and the charts so the group
            # cannot be screenshot without it. Baseline runs carry no
            # CONTAMINATION_KPI row -> no banner, page unchanged.
            if _kpi(kpis, pax_only.CONTAMINATION_KPI) is not None:
                banner = ('<div class="warnbanner">&#9888; Iterationsreihen enthalten '
                          'Paket-Agenten (drt_customer_stats, nicht pax-bereinigt) '
                          '&mdash; Niveaus sind mit den pax-bereinigten Kacheln oben '
                          'nicht vergleichbar.</div>')
                h = h.replace("</h2>", "</h2>" + banner, 1)
            groups_html.append(h)
            groups_js.append(j)

        # --- Service-Zeit Detail (chart 15) ---
        veh_charts = []
        c = _vehicle_chart(vehicles, "c_veh_" + uid)
        if c:
            veh_charts.append(c)
        h, j = _render_group("Service-Zeit Detail", veh_charts, badge_titles=secondary_titles)
        if h:
            groups_html.append(h)
            groups_js.append(j)

    charts_html = "".join(groups_html)
    charts_js = "\n".join(g for g in groups_js if g)

    html = '<div class="tiles">' + tiles + "</div>" + map_html + charts_html
    js = charts_js + map_js
    return html, js
