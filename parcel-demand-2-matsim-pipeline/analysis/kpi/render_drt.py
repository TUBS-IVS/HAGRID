# -*- coding: utf-8 -*-
"""DRT tab renderer -- HAGRID Run-Dashboard v2 Plan C (Task 5).

This task delivers the module skeleton and the 22-tile headline set
(`_tiles`). Charts are Task 6 -- `build_tab` already leaves the seam for
them (`charts_html, charts_js = "", ""`). The map block (Plan D) is
inserted after the tiles when supplied by the caller.

Tile spec: see `.superpowers/sdd/task-5-brief.md` ("THE 22-TILE DRT SET").
Every tile is guarded by `if _kpi(...) is not None` so runs without event
reconstruction (no_events) simply render fewer tiles."""
from render import _kpi, _tile, _fmt_de, _fmt_pct

# Bottom-up placeholder cost rate (kept in sync with economics.py: 20 EUR/h
# labour + 5 EUR/h vehicle = 25 EUR/veh-shift-h, Rudolph ~80/20 split).
_COST_TIP = (
    "PLATZHALTER-Kostenfunktion (Beschluss 2026-07-02, noch zu praezisieren): "
    "25 EUR je Flotten-Schichtstunde (20 EUR/h Personal + 5 EUR/h Fahrzeug, "
    "Rudolph ~80/20 Personal/Fahrzeug). Nur direkte Kosten, KEIN Overhead. "
    "Literatur-Benchmark: Currie & Fournier (2020, Transport Policy), "
    "DRT-Vollkosten je Fahrzeug-Stunde. 3. Aera (2009-2019) Median ~110 AU$2019 "
    "= 68 EUR/h (2. Aera ~60 AU$ = 37 EUR/h). Vollkosten inkl. "
    "Overhead/Verwaltung - Obergrenze zum Bottom-up-Platzhalter."
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


def build_tab(data, uid, compact=False, map_block=None):
    """DRT tab body: 22-tile headline set + optional map block + charts seam.

    Returns (html, js). Charts (Task 6) are a placeholder here -- the seam
    is `charts_html, charts_js = "", ""` below."""
    tiles = _tiles(data)

    map_html, map_js = "", ""
    if map_block is not None:
        map_html = map_block.get("html", "")
        map_js = map_block.get("js", "")

    charts_html, charts_js = "", ""  # Task 6

    html = '<div class="tiles">' + tiles + "</div>" + map_html + charts_html
    js = charts_js + map_js
    return html, js
