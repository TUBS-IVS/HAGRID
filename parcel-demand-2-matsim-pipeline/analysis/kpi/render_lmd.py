# -*- coding: utf-8 -*-
"""LMD tab renderer -- HAGRID Run-Dashboard v2 Plan C (Task 7).

Task 7 delivers the module skeleton and the 20-tile legacy headline set
(`_tiles`). Charts (Task 8) and tables (Task 9) are left as explicit seams
below -- this module does not implement either yet. The map block (Plan D)
is inserted after the tiles when supplied by the caller, matching
render_drt.build_tab's contract exactly.

Tile spec: see `.superpowers/sdd/task-7-brief.md` ("THE 20-TILE LMD SET").
Sources: `kpis_long` rows (via `render._kpi(data.kpis, name)`) for the
freight/economic group KPIs, and per-provider/per-type/"all" rows in
`kpis_provider.csv` (`data.provider`) via the `_pv`/`_types`/`_all`
accessors below. Every tile is guarded by its source being absent, so runs
without freight (no LMD) simply produce no tiles at all, and runs whose
provider extraction skipped a given vehicle type/summary row render fewer
tiles."""
from render import _kpi, _tile, _fmt_de, _fmt_pct

import pandas as pd

# provider="type:<VT>" rows summed for tile 5 (Supply-Fahrzeuge)
_SUPPLY_VTYPES = ["TRUCK", "TRUCK_LIGHT", "SUPPLY_VAN"]


# ---------------------------------------------------------------------------
# Provider-CSV accessors (row-key convention: see task-7-brief.md / verified
# vs extract_freight_provider.py). Reused by Tasks 8-9 -- keep the shapes
# exactly as documented here.
# ---------------------------------------------------------------------------

def _pv(provider_df):
    """Pivot of real-provider rows only (excludes `provider=="all"` and
    `provider.startswith("type:")`): rows = provider, columns = kpi_name,
    values = value. Empty DataFrame when there is no provider data or no
    real-provider rows."""
    if provider_df is None or provider_df.empty or "provider" not in provider_df.columns:
        return pd.DataFrame()
    prov = provider_df["provider"].astype(str)
    real = provider_df[(prov != "all") & (~prov.str.startswith("type:"))]
    if not len(real):
        return pd.DataFrame()
    return real.pivot_table(index="provider", columns="kpi_name", values="value", aggfunc="first")


def _types(provider_df):
    """The `provider.startswith("type:")` rows (per-vehicle-type KPIs),
    unchanged (long) shape -- columns `provider`, `kpi_name`, `value`, ..."""
    if provider_df is None or provider_df.empty or "provider" not in provider_df.columns:
        return pd.DataFrame(columns=["provider", "kpi_name", "value"])
    prov = provider_df["provider"].astype(str)
    return provider_df[prov.str.startswith("type:")]


def _all(provider_df, kpi):
    """The single `provider=="all"` row's value for `kpi`, or None if the
    provider data / that row is absent."""
    if provider_df is None or provider_df.empty or "provider" not in provider_df.columns:
        return None
    m = provider_df[(provider_df["provider"] == "all") & (provider_df["kpi_name"] == kpi)]
    return float(m.iloc[0]["value"]) if len(m) else None


def _type_val(types_df, vtype, kpi):
    """Value of `kpi` for one `type:<vtype>` row, or None if absent."""
    if types_df is None or not len(types_df):
        return None
    m = types_df[(types_df["provider"] == "type:" + vtype) & (types_df["kpi_name"] == kpi)]
    return float(m.iloc[0]["value"]) if len(m) else None


def _pv_sum(pv, kpi):
    """Sum of `kpi` over real providers, or None when the column is absent
    from the pivot (no provider carried that KPI)."""
    if kpi not in pv.columns:
        return None
    return float(pv[kpi].sum())


def _tiles(data):
    kpis = data.kpis
    provider = data.provider
    pv = _pv(provider)
    types = _types(provider)
    t = []

    # 1. Aktive Fahrzeuge
    v = _kpi(kpis, "freight_vehicles")
    if v is not None:
        t.append(_tile(_fmt_de(v), "Aktive Fahrzeuge",
                        tip="Nicht ausgeschlossene (Low-Util) Lieferfahrzeuge ueber alle "
                            "Carrier (freight_vehicles)."))

    # 2. Carrier [sub: carriers_delivery / carriers_supply]
    v = _kpi(kpis, "carriers")
    if v is not None:
        delivery = _all(provider, "carriers_delivery")
        supply = _all(provider, "carriers_supply")
        sub = ""
        if delivery is not None and supply is not None:
            sub = _fmt_de(delivery) + " Zustellung / " + _fmt_de(supply) + " Supply"
        t.append(_tile(_fmt_de(v), "Carrier", sub,
                        tip="Anzahl Carrier (Zustellung + Supply/Linienverkehr)."))

    # 3. CEP-Vans
    v = _type_val(types, "VAN", "vehicles")
    if v is not None:
        t.append(_tile(_fmt_de(v), "CEP-Vans",
                        tip="Fahrzeuge des Typs VAN (KEP-Zustellfahrzeuge), nicht "
                            "ausgeschlossene (Low-Util) Touren."))

    # 4. Cargobikes
    v = _type_val(types, "CARGOBIKE", "vehicles")
    if v is not None:
        t.append(_tile(_fmt_de(v), "Cargobikes",
                        tip="Fahrzeuge des Typs CARGOBIKE, nicht ausgeschlossene "
                            "(Low-Util) Touren."))

    # 5. Supply-Fahrzeuge = sum of TRUCK + TRUCK_LIGHT + SUPPLY_VAN vehicles
    supply_vals = [_type_val(types, vt, "vehicles") for vt in _SUPPLY_VTYPES]
    supply_vals = [x for x in supply_vals if x is not None]
    if supply_vals:
        t.append(_tile(_fmt_de(sum(supply_vals)), "Supply-Fahrzeuge",
                        tip="Fahrzeuge der Supply-/Linienverkehr-Typen TRUCK, TRUCK_LIGHT "
                            "und SUPPLY_VAN (Depot-Nachschub, keine KEP-Zustellung)."))

    # 6. Pakete [sub: parcels_handled]
    v = _kpi(kpis, "parcels_total")
    if v is not None:
        handled = _kpi(kpis, "parcels_handled")
        sub = (_fmt_de(handled) + " bearbeitet") if handled is not None else ""
        t.append(_tile(_fmt_de(v), "Pakete", sub,
                        tip="Gesamtzahl der Pakete ueber alle Carrier (parcels_total)."))

    # 7. Verpasste Pakete [sub: rate = missed/total]
    v = _kpi(kpis, "parcels_missed")
    if v is not None:
        total = _kpi(kpis, "parcels_total")
        sub = (_fmt_pct(v / total) if total else "")
        t.append(_tile(_fmt_de(v), "Verpasste Pakete", sub,
                        tip="Nicht zugestellte, aber zugewiesene Pakete (parcels_missed, "
                            "nach Low-Util-Umverteilung)."))

    # 8. Unassigned
    v = _kpi(kpis, "parcels_unassigned")
    if v is not None:
        t.append(_tile(_fmt_de(v), "Unassigned",
                        tip="Pakete, die keinem Carrier zugewiesen werden konnten "
                            "(parcels_unassigned)."))

    # 9. Zustellquote
    v = _kpi(kpis, "delivery_rate")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Zustellquote",
                        tip="Anteil zugestellter Pakete an allen Paketen (ohne missed/"
                            "unassigned)."))

    # 10. Stopps [sub: stops/h = stops / freight_tour_hours]
    stops = _all(provider, "stops")
    if stops is not None:
        tour_hours = _kpi(kpis, "freight_tour_hours")
        sub = (_fmt_de(stops / tour_hours, 1) + " Stopps/h") if tour_hours else ""
        t.append(_tile(_fmt_de(stops), "Stopps", sub,
                        tip="Summe aller Zustell-/Abholstopps ueber die ueberlebenden "
                            "(nicht ausgeschlossenen) Zustelltouren."))

    # 11. Ø Auslastung
    v = _all(provider, "avg_load_factor")
    if v is not None:
        t.append(_tile(_fmt_pct(v), "Ø Auslastung",
                        tip="Mittlere Ladungsauslastung (Pakete/Kapazitaet) ueber die "
                            "ueberlebenden Zustelltouren."))

    # 12. Distanz gesamt
    v = _kpi(kpis, "freight_vehicle_km")
    if v is not None:
        t.append(_tile(_fmt_de(v, 1) + " km", "Distanz gesamt",
                        tip="Gesamt-Fahrzeugkilometer ueber alle Carrier "
                            "(TimeDistance_perCarrier, autoritativ)."))

    # 13. Ø Tourlänge = freight_vehicle_km / freight_tours
    km = _kpi(kpis, "freight_vehicle_km")
    tours = _kpi(kpis, "freight_tours")
    if km is not None and tours:
        t.append(_tile(_fmt_de(km / tours, 1) + " km", "Ø Tourlänge",
                        tip="Gesamtdistanz / Anzahl Touren."))

    # 14. Ø Geschwindigkeit = freight_vehicle_km / all;travel_hours
    travel_hours = _all(provider, "travel_hours")
    if km is not None and travel_hours:
        t.append(_tile(_fmt_de(km / travel_hours, 1) + " km/h", "Ø Geschwindigkeit",
                        tip="Gesamtdistanz / reine Fahrzeit (ohne Servicezeiten)."))

    # 15. Tourstunden
    tour_hours = _kpi(kpis, "freight_tour_hours")
    if tour_hours is not None:
        t.append(_tile(_fmt_de(tour_hours, 1) + " h", "Tourstunden",
                        tip="Summe der Tourdauern (Fahrzeit + Servicezeit) ueber alle "
                            "Carrier."))

    # 16. Fahranteil = all;travel_hours / freight_tour_hours
    if travel_hours is not None and tour_hours:
        t.append(_tile(_fmt_pct(travel_hours / tour_hours), "Fahranteil",
                        tip="Reine Fahrzeit / gesamte Tourdauer (Fahrzeit + "
                            "Servicezeit an den Stopps)."))

    # 17. Kosten gesamt [sub: economic freight_cost_per_parcel EUR/Paket]
    v = _kpi(kpis, "freight_total_costs")
    if v is not None:
        per_parcel = _kpi(kpis, "freight_cost_per_parcel")
        sub = (_fmt_de(per_parcel, 2) + " EUR/Paket") if per_parcel is not None else ""
        t.append(_tile(_fmt_de(v) + " EUR", "Kosten gesamt", sub,
                        tip="Gesamtkosten (Distanz + Zeit + Fixkosten + Ueberstunden) "
                            "ueber alle Carrier, nach Low-Util-Umverteilung."))

    # 18. Fixkosten = sum over real providers of provider cost_fixed
    fixed = _pv_sum(pv, "cost_fixed")
    if fixed is not None:
        t.append(_tile(_fmt_de(fixed) + " EUR", "Fixkosten",
                        tip="Summe der Fixkosten (Fahrzeugvorhaltung) ueber alle "
                            "Carrier, nach Low-Util-Umverteilung."))

    # 19. Touren [sub: Touren/Fahrzeug = freight_tours / freight_vehicles]
    if tours is not None:
        vehicles = _kpi(kpis, "freight_vehicles")
        sub = (_fmt_de(tours / vehicles, 2) + " Touren/Fzg") if vehicles else ""
        t.append(_tile(_fmt_de(tours), "Touren", sub,
                        tip="Anzahl gefahrener Touren ueber alle Carrier."))

    # 20. Low-Util ausgeschlossen = sum over real providers of provider excluded_vehicles
    excluded = _pv_sum(pv, "excluded_vehicles")
    if excluded is not None:
        t.append(_tile(_fmt_de(excluded), "Low-Util ausgeschlossen",
                        tip="Zustelltouren mit Ladungsauslastung unter der Low-Util-"
                            "Schwelle, aus Flotten-/Kostenstatistik ausgeschlossen "
                            "(Kosten/Missed re-alloziert, siehe Fixkosten/Kosten "
                            "gesamt)."))

    return "".join(t)


def build_tab(data, uid, compact=False, map_block=None):
    """LMD tab body: 20-tile legacy headline set + optional map block.

    Returns (html, js). THIS is tiles only (Task 7) -- charts (Task 8) and
    tables (Task 9) are explicit seams below, both empty for now. Every
    tile is guarded by its source KPI/provider row being absent, so runs
    without freight simply produce no LMD tab at all (see
    render.render_run_page's has_lmd gate), and provider-CSV rows that a
    given run's extraction skipped (e.g. no CARGOBIKE fleet) render fewer
    tiles."""
    tiles = _tiles(data)

    map_html, map_js = "", ""
    if map_block is not None:
        map_html = map_block.get("html", "")
        map_js = map_block.get("js", "")

    charts_html, charts_js = "", ""  # Task 8
    tables_html = ""                 # Task 9

    html = '<div class="tiles">' + tiles + "</div>" + map_html + charts_html + tables_html
    js = charts_js + map_js
    return html, js
