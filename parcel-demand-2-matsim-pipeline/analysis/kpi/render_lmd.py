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
from render import (_kpi, _tile, _fmt_de, _fmt_pct, _panel, _series, chart_js, provider_slot,
                     _donut, size_marker)

import re

import pandas as pd

# provider="type:<VT>" rows summed for tile 5 (Supply-Fahrzeuge)
_SUPPLY_VTYPES = ["TRUCK", "TRUCK_LIGHT", "SUPPLY_VAN"]


# ---------------------------------------------------------------------------
# Provider-CSV accessors (row-key convention: see task-7-brief.md / verified
# vs extract_freight_provider.py). Reused by Tasks 8-9 -- keep the shapes
# exactly as documented here.
# ---------------------------------------------------------------------------

def _pv(provider_df):
    """Pivot of real-provider rows only (excludes `provider=="all"`,
    `provider.startswith("type:")` and `provider.startswith("vtype:")`):
    rows = provider, columns = kpi_name, values = value. Empty DataFrame
    when there is no provider data or no real-provider rows."""
    if provider_df is None or provider_df.empty or "provider" not in provider_df.columns:
        return pd.DataFrame()
    prov = provider_df["provider"].astype(str)
    real = provider_df[(prov != "all") & (~prov.str.startswith("type:"))
                        & (~prov.str.startswith("vtype:"))]
    if not len(real):
        return pd.DataFrame()
    return real.pivot_table(index="provider", columns="kpi_name", values="value", aggfunc="first")


def _types(provider_df):
    """The `provider.startswith("type:")` rows (per-vehicle-type KPIs, the
    BROAD bucket -- e.g. all CEP van sizes as one "VAN" row), unchanged
    (long) shape -- columns `provider`, `kpi_name`, `value`, ... . Consumed
    by the LMD tiles (3/4/5), which must NOT switch to the fine rows."""
    if provider_df is None or provider_df.empty or "provider" not in provider_df.columns:
        return pd.DataFrame(columns=["provider", "kpi_name", "value"])
    prov = provider_df["provider"].astype(str)
    return provider_df[prov.str.startswith("type:")]


def _vtypes(provider_df):
    """The `provider.startswith("vtype:")` rows (v2 Plan C Task 12) --
    per-vehicle-TYPE-ID KPIs (fine granularity, e.g. one row per
    ct_cep_size_s/m/l instead of one averaged "VAN" row). Same long shape
    as `_types`. Consumed by the Fahrzeugtyp-Analytik charts (10-12) only --
    the tiles keep reading `_types`."""
    if provider_df is None or provider_df.empty or "provider" not in provider_df.columns:
        return pd.DataFrame(columns=["provider", "kpi_name", "value"])
    prov = provider_df["provider"].astype(str)
    return provider_df[prov.str.startswith("vtype:")]


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


# ---------------------------------------------------------------------------
# Chart builders (Task 8). Each returns (title, canvas_id, cfg, height) or
# None when its source series/rows are absent -- callers just skip Nones.
# Structural pattern follows render_drt.py (Task 6): thin per-chart builders,
# `_render_group` wraps a list into one `<h2>` + `.grid2` section (nothing
# rendered when every chart in the group is absent/None).
#
# Color-safety note (client-side only -- resolveColors in render.py):
#   - `__slots` (plural) takes an array PARALLEL to a dataset's data points
#     and maps each None entry to OTHER (gray); this is the only null-safe
#     path.
#   - `__slot` (singular, one int for the whole dataset) does NOT handle
#     None -- `null % CAT.length` in JS is 0, so an unmapped/"other" entity
#     would silently render as CAT[0] (dhl-blue). `__slot` is therefore only
#     ever used here for FIXED, non-entity series (cost components, the
#     travel/service split, the two depot lines, the four scoring lines) --
#     never for a per-provider dataset.
#   - Per-provider datasets that are a SINGLE flat color across many points
#     (one line per provider in charts 16/17, one dataset per provider in the
#     scatters 22/23) use `__slots` with the SAME slot value repeated for
#     every point -- reusing the already-correct null-safe array path rather
#     than adding a second color-resolution rule to `resolveColors`.
# ---------------------------------------------------------------------------

def _fmt_num(v):
    v = float(v)
    s = str(int(v)) if v == int(v) else str(round(v, 2))
    return s.replace(".", ",")   # German decimals (presentation-only); whole nums unaffected


def _bin_label(lo, hi, div=1):
    # en dash with surrounding spaces so negative ranges (chart 20, carrier
    # scores) read cleanly, e.g. "-100 – -50" rather than the ambiguous
    # bare-hyphen "-100--50".
    return _fmt_num(lo / div) + " – " + _fmt_num(hi / div)


def _prov_order(pv):
    """Provider labels sorted by parcels_total desc (the sort order mandated
    for every Provider-Analytik chart); falls back to pv's own row order if
    parcels_total isn't carried (shouldn't happen in practice -- chart 1 is
    always the first provider chart built and needs that column too)."""
    if "parcels_total" in pv.columns:
        return list(pv["parcels_total"].sort_values(ascending=False).index)
    return list(pv.index)


def _prov_bar(pv, kpi, title, cid, pct=False, height=210):
    """Bar chart (charts 1,2,3,5,6,8,9,21): one dataset, one bar per real
    provider (sorted by parcels_total desc), per-bar colors via `__slots`
    (gray for unmapped/"other" providers)."""
    if pv is None or pv.empty or kpi not in pv.columns:
        return None
    order = _prov_order(pv)
    vals = [pv.loc[p, kpi] for p in order]
    vals = [round(float(v) * 100, 2) if pct else round(float(v), 3) for v in vals]
    ds = {"label": title, "data": vals, "__slots": [provider_slot(p) for p in order],
          "borderRadius": 4, "maxBarThickness": 28}
    cfg = {"type": "bar", "data": {"labels": order, "datasets": [ds]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _pv_derived(pv):
    """pv copy with two per-provider columns computed only for charts 8/9 (Ø
    Tourdistanz, Ø Stopps je Tour) -- 0.0 (not NaN) when tours is 0, since
    NaN is not valid JSON."""
    pv = pv.copy()
    if "km" in pv.columns and "tours" in pv.columns:
        pv["_km_per_tour"] = [(km / tours) if tours else 0.0
                              for km, tours in zip(pv["km"], pv["tours"])]
    if "stops" in pv.columns and "tours" in pv.columns:
        pv["_stops_per_tour"] = [(s / tours) if tours else 0.0
                                 for s, tours in zip(pv["stops"], pv["tours"])]
    return pv


def _cost_stack(pv, cid, title="Kosten je Provider (Komponenten)", height=220):
    """Stacked bar (chart 4): cost_fixed/cost_dist/cost_time per provider,
    fixed `__slot` 0/1/2 (not a per-entity color -- every provider gets the
    same 3-color legend), legend on."""
    need = ["cost_fixed", "cost_dist", "cost_time"]
    if pv is None or pv.empty or not all(k in pv.columns for k in need):
        return None
    order = _prov_order(pv)
    datasets = []
    for i, (kpi, label) in enumerate(
            [("cost_fixed", "Fixkosten"), ("cost_dist", "Distanzkosten"),
             ("cost_time", "Zeitkosten")]):
        vals = [round(float(pv.loc[p, kpi]), 2) for p in order]
        datasets.append({"label": label, "data": vals, "stack": "s", "__slot": i,
                          "borderRadius": 4, "maxBarThickness": 28})
    cfg = {"type": "bar", "data": {"labels": order, "datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}},
                       "scales": {"x": {"stacked": True}, "y": {"stacked": True}}}}
    return (title, cid, cfg, height)


def _stack100(rows_labels, datasets, title, cid, height=220):
    """Horizontal 100%-stacked bar (chart 7): `datasets` is a list of
    (label, values, slot) aligned index-for-index to `rows_labels`; values
    are already percent-of-row shares (0-100). Legend on (>=2 series)."""
    if not rows_labels or not datasets:
        return None
    ds = [{"label": lbl, "data": vals, "stack": "s", "__slot": slot}
          for lbl, vals, slot in datasets]
    cfg = {"type": "bar", "data": {"labels": rows_labels, "datasets": ds},
           "options": {"indexAxis": "y", "responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}},
                       "scales": {"x": {"stacked": True, "max": 100, "grid": {"display": False}},
                                  "y": {"stacked": True}}}}
    return (title, cid, cfg, height)


def _time_split_chart(pv, cid, title="Zeitaufteilung Fahren vs. Service", height=220):
    """Chart 7 data prep: travel = travel_hours share of tour_hours, service
    = the (clamped >= 0) remainder, per provider -- then handed to
    `_stack100`."""
    if pv is None or pv.empty or "travel_hours" not in pv.columns or "tour_hours" not in pv.columns:
        return None
    order = _prov_order(pv)
    travel_pct, service_pct = [], []
    for p in order:
        trav = float(pv.loc[p, "travel_hours"])
        tour = float(pv.loc[p, "tour_hours"])
        if tour > 0:
            travel_pct.append(round(trav / tour * 100, 2))
            service_pct.append(round(max(tour - trav, 0.0) / tour * 100, 2))
        else:
            travel_pct.append(0.0)
            service_pct.append(0.0)
    return _stack100(order, [("Fahrzeit", travel_pct, 0), ("Servicezeit", service_pct, 1)],
                      title, cid, height=height)


# Common prefix stripped for a shorter, legacy-flavored label on the CEP-van
# size type_ids ("ct_cep_size_s" -> "S"); any type_id that doesn't match
# falls back to the full id, so the label is always informative.
_CEP_SIZE_PREFIX = "ct_cep_size_"


def _typeid_label(type_id, cap):
    """Readable label for a `vtype:<type_id>` bar: strip the common CEP-size
    prefix (if present) and append the real per-type capacity, e.g.
    "ct_cep_size_s" + 100.0 -> "S (Kap. 100)"; other type_ids keep their
    full id, e.g. "cargoBike_t (Kap. 30)"."""
    label = (type_id[len(_CEP_SIZE_PREFIX):].upper() if type_id.startswith(_CEP_SIZE_PREFIX)
             else type_id)
    return label + " (Kap. " + _fmt_num(cap) + ")"


def _vtype_ordered(vtypes, kpi):
    """Shared prep for the `vtype:` fine-row charts (v2 Plan C Task 12 / Plan
    D Task D3): the `kpi` value per type_id plus its label, sorted by real
    capacity ascending (so CEP sizes read s -> m -> l). Returns
    `(order, labels, by_tid)` with `order` a list of type_ids -- empty
    list/dict when `kpi` isn't present on any `vtype:` row. Used identically
    by `_vtype_bar` (charts 10-12) and `_fleet_donut` (chart 8) so both share
    the exact same size-ascending index order, and therefore the exact same
    `size_marker` colors per size."""
    rows = vtypes[vtypes["kpi_name"] == kpi]
    if not len(rows):
        return [], [], {}
    by_tid = {r["provider"].split(":", 1)[1]: float(r["value"]) for _, r in rows.iterrows()}
    cap_rows = vtypes[vtypes["kpi_name"] == "capacity"]
    cap_by_tid = {r["provider"].split(":", 1)[1]: float(r["value"]) for _, r in cap_rows.iterrows()}
    order = sorted(by_tid, key=lambda tid: cap_by_tid.get(tid, 0.0))
    labels = [_typeid_label(tid, cap_by_tid.get(tid, 0.0)) for tid in order]
    return order, labels, by_tid


def _vtype_bar(vtypes, kpi, title, cid, pct=False, height=210):
    """Bar chart (charts 10,11,12): one bar per `vtype:<type_id>` row present
    (fine vehicle-type granularity -- v2 Plan C Task 12), sorted by real
    capacity ascending (so CEP sizes read s -> m -> l). Colored via the D1
    size marker (`__sizes`, v2 Plan D Task D3) -- three distinct teal shades
    by size, matching the fleet donut (chart 8) exactly, and distinct from
    every provider hue (replaces the old sequential-blue `__seq`, which
    collided with the dhl provider-blue slot)."""
    if vtypes is None or not len(vtypes):
        return None
    order, labels, by_tid = _vtype_ordered(vtypes, kpi)
    if not order:
        return None
    vals = [round(by_tid[tid] * 100, 2) if pct else round(by_tid[tid], 3) for tid in order]
    ds = {"label": title, "data": vals, "borderRadius": 4, "maxBarThickness": 28}
    ds.update(size_marker(range(len(order))))
    cfg = {"type": "bar", "data": {"labels": labels, "datasets": [ds]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _fleet_donut(vtypes, cid, title="Flottenzusammensetzung", height=200):
    """Fleet-composition donut (chart 8, v2 Plan D Task D3), "auf einen
    Blick" at the top of Fahrzeugtyp-Analytik: one segment per `vtype:
    <type_id>` row's `vehicles` count, sorted by real capacity ascending --
    the SAME order `_vtype_bar` uses for charts 10-12 (via `_vtype_ordered`)
    -- so the size marker assigns segment i and bar i the identical teal
    shade. Center hole shows the total vehicle count. Pie/donut is allowed
    here per the 2026-07-16 user override (fleet composition, alongside
    D2's modal split)."""
    if vtypes is None or not len(vtypes):
        return None
    order, labels, by_tid = _vtype_ordered(vtypes, "vehicles")
    if not order:
        return None
    values = [round(by_tid[tid], 2) for tid in order]
    color_marker = size_marker(range(len(order)))
    return _donut(cid, title, labels, values, color_marker, height=height,
                  center_label=_fmt_de(sum(values)))


def _dist_bar(dist, series, title, cid, unit_div=1, height=210):
    """Bar chart over `data.distributions` bins (charts 13,14,20). `__seq`
    color."""
    if dist is None or "series" not in dist.columns:
        return None
    rows = dist[dist["series"] == series].sort_values("bin_lo")
    if not len(rows):
        return None
    labels = [_bin_label(r["bin_lo"], r["bin_hi"], unit_div) for _, r in rows.iterrows()]
    vals = [round(float(v), 3) for v in rows["value"]]
    ds = {"label": title, "data": vals, "__seq": True, "borderRadius": 4, "maxBarThickness": 24}
    cfg = {"type": "bar", "data": {"labels": labels, "datasets": [ds]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _ts_bar(ts, series, title, cid, height=210):
    """Bar chart over `data.ts` hours for one series (chart 15). `__seq`
    color, no legend (single series)."""
    hrs, vals = _series(ts, series)
    if not hrs:
        return None
    ds = {"label": title, "data": vals, "__seq": True, "borderRadius": 4, "maxBarThickness": 18}
    cfg = {"type": "bar", "data": {"labels": hrs, "datasets": [ds]},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": False}}}}
    return (title, cid, cfg, height)


def _series_float(ts, name):
    """Module-local float-hour reader for the multi-provider hourly charts.

    The shared `render._series` casts `hour` to int -- correct for integer
    hour bins, but it TRUNCATES the fractional hours emitted by
    `freight_active_vehicles_` (t/3600 in 1/12-h steps), collapsing every
    step within an hour onto one label. This reads `hour` (and value) as
    float, sorted by hour, so fractional hours survive. Do NOT swap the
    shared `_series` -- many integer-hour charts depend on its int cast."""
    m = ts[ts["series"] == name].sort_values("hour")
    return list(m["hour"].astype(float)), list(m["value"].astype(float))


def _fmt_hour(h):
    """Union x-label: integral -> "8", fractional -> "8.08" (2-dp, trimmed)."""
    h = float(h)
    return str(int(h)) if h.is_integer() else ("%g" % round(h, 2))


def _union_hours(series_list):
    """Align a list of (provider, hrs, vals) onto the sorted UNION of every
    series' hours, 0-filling each series at hours it lacks. Returns
    (labels, [(provider, aligned_vals)]) -- each aligned_vals has one entry
    per union hour, so a value that belongs to hour H always lands at the
    union index for H (never positionally against another series' hours)."""
    all_hours = sorted({h for _, hrs, _ in series_list for h in hrs})
    idx = {h: i for i, h in enumerate(all_hours)}
    labels = [_fmt_hour(h) for h in all_hours]
    aligned = []
    for provider, hrs, vals in series_list:
        row = [0] * len(all_hours)
        for h, val in zip(hrs, vals):
            row[idx[h]] = val
        aligned.append((provider, row))
    return labels, aligned


def _hourly_provider_stack(ts, prefix, title, cid, height=220):
    """Stacked bar over all `<prefix><provider>` series present in `ts`
    (chart 16). Plan-D-only series -- absent until then, returns None with
    no error. Each provider's dataset is one flat color across every hour
    (`__slots` with the same slot repeated len(hours) times -- see the
    color-safety note above the chart-builder section). X-labels are the
    sorted UNION of every provider's hours, each dataset 0-filled/aligned to
    it (providers with differing hour ranges no longer render positionally)."""
    if ts is None or "series" not in ts.columns:
        return None
    names = sorted(s for s in ts["series"].unique() if s.startswith(prefix))
    if not names:
        return None
    series_list = []
    for name in names:
        provider = name[len(prefix):]
        hrs, vals = _series_float(ts, name)
        if not hrs:
            continue
        series_list.append((provider, hrs, vals))
    if not series_list:
        return None
    labels, aligned = _union_hours(series_list)
    datasets = []
    for provider, row in aligned:
        slot = provider_slot(provider)
        datasets.append({"label": provider, "data": row, "stack": "s",
                          "__slots": [slot] * len(row),
                          "borderRadius": 4, "maxBarThickness": 18})
    # legend on: x-axis is HOURS, so color is the only channel identifying
    # which series is which provider (>=2 series once Plan D populates this).
    cfg = {"type": "bar", "data": {"labels": labels, "datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}},
                       "scales": {"x": {"stacked": True}, "y": {"stacked": True}}}}
    return (title, cid, cfg, height)


def _hourly_provider_lines(ts, prefix, title, cid, height=220):
    """Line chart, one line per `<prefix><provider>` series present in `ts`
    (chart 17). Plan-D-only -- absent until then. Same per-provider flat-
    color `__slots` treatment as `_hourly_provider_stack`, and the same
    sorted-UNION x-label alignment. Reads FRACTIONAL hours via `_series_float`
    (freight_active_vehicles_ uses 1/12-h steps) so they are not collapsed."""
    if ts is None or "series" not in ts.columns:
        return None
    names = sorted(s for s in ts["series"].unique() if s.startswith(prefix))
    if not names:
        return None
    series_list = []
    for name in names:
        provider = name[len(prefix):]
        hrs, vals = _series_float(ts, name)
        if not hrs:
            continue
        series_list.append((provider, hrs, vals))
    if not series_list:
        return None
    labels, aligned = _union_hours(series_list)
    datasets = []
    for provider, row in aligned:
        slot = provider_slot(provider)
        datasets.append({"label": provider, "data": row, "borderWidth": 2, "pointRadius": 0,
                          "tension": 0.25, "__slots": [slot] * len(row)})
    # legend on: x-axis is HOURS, color is the only per-provider channel.
    cfg = {"type": "line", "data": {"labels": labels, "datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}}}}
    return (title, cid, cfg, height)


def _depot_chart(ts, cid, title="Depot-Abfahrten/-Ankünfte", height=210):
    """Two fixed lines (chart 18): freight_depot_departures/arrivals, fixed
    `__slot` 0/1 (not per-entity -- there are only ever these two named
    series), legend on. Plan-D-only -- absent until then."""
    hrs_d, dep = _series(ts, "freight_depot_departures")
    hrs_a, arr = _series(ts, "freight_depot_arrivals")
    if not hrs_d and not hrs_a:
        return None
    # Departures (morning) and arrivals (the afternoon returns) live in
    # disjoint hour ranges -- align both onto the sorted UNION so every return
    # survives and each series lands at the union index for ITS hour, not
    # positionally against the other's (see `_union_hours` / chart 16-17).
    series_list = []
    if hrs_d:
        series_list.append(("Abfahrten", hrs_d, dep))
    if hrs_a:
        series_list.append(("Ankünfte", hrs_a, arr))
    labels, aligned = _union_hours(series_list)
    slot = {"Abfahrten": 0, "Ankünfte": 1}
    datasets = []
    for label, row in aligned:
        datasets.append({"label": label, "data": row, "borderWidth": 2, "pointRadius": 0,
                          "tension": 0.25, "__slot": slot[label]})
    cfg = {"type": "line", "data": {"labels": labels, "datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}}}}
    return (title, cid, cfg, height)


def _iter_series(it, name):
    if it is None or "series" not in it.columns:
        return [], []
    m = it[it["series"] == name].sort_values("iteration")
    return list(m["iteration"].astype(int)), list(m["value"].astype(float))


def _score_iters(it, cid, title="Carrier-Scores über Iterationen", height=210):
    """Line chart (chart 19): carrier_score_executed/worst/avg/best over
    iterations, fixed `__slot` 0-3, legend on. carrier_scores.txt is
    optional -- absent series are skipped individually, and the whole chart
    is skipped (None) if none of the four are present."""
    series_list = [("carrier_score_executed", "Executed", 0), ("carrier_score_worst", "Worst", 1),
                   ("carrier_score_avg", "Avg", 2), ("carrier_score_best", "Best", 3)]
    datasets = []
    labels = None
    for name, label, slot in series_list:
        iters, vals = _iter_series(it, name)
        if not iters:
            continue
        if labels is None:
            labels = iters
        datasets.append({"label": label, "data": vals, "borderWidth": 2, "pointRadius": 0,
                          "tension": 0.25, "__slot": slot})
    if not datasets:
        return None
    cfg = {"type": "line", "data": {"labels": labels, "datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}}}}
    return (title, cid, cfg, height)


def _scatter(vehicles, xcol, ycol, title, cid, xlabel, ylabel, height=260):
    """Scatter (charts 22,23): one dataset per provider, points from
    `data.vehicles` (role=="freight", excluded==0). Per-provider flat color
    via `__slots` with the same slot repeated for every point in that
    provider's dataset -- deliberately NOT `__slot` (singular): `__slot`
    doesn't null-check, so an unmapped/"other" provider's `None` slot would
    resolve to `CAT[0]` (dhl-blue) via JS's `null % len === 0`, rather than
    gray. `__slots` already maps `None` -> OTHER (see resolveColors), so
    reusing it here keeps color resolution to the one existing rule."""
    need = ["role", "provider", "excluded", xcol, ycol]
    if vehicles is None or not all(c in vehicles.columns for c in need):
        return None
    veh = vehicles[(vehicles["role"] == "freight") & (vehicles["excluded"] == 0)]
    veh = veh.dropna(subset=[xcol, ycol, "provider"])
    if not len(veh):
        return None
    datasets = []
    for provider in sorted(veh["provider"].unique()):
        sub = veh[veh["provider"] == provider]
        pts = [{"x": round(float(x), 3), "y": round(float(y), 3)}
               for x, y in zip(sub[xcol], sub[ycol])]
        slot = provider_slot(provider)
        datasets.append({"label": provider, "data": pts, "__slots": [slot] * len(pts),
                          "pointRadius": 4, "pointHoverRadius": 6})
    if not datasets:
        return None
    # legend on: one dataset per provider, no per-point x label -> color is
    # the only channel identifying the provider.
    cfg = {"type": "scatter", "data": {"datasets": datasets},
           "options": {"responsive": True, "maintainAspectRatio": False,
                       "plugins": {"legend": {"display": True}},
                       "scales": {"x": {"title": {"display": True, "text": xlabel}},
                                  "y": {"title": {"display": True, "text": ylabel}}}}}
    return (title, cid, cfg, height)


def _render_group(title_h2, charts):
    """Wrap a list of (title, cid, cfg, height) chart tuples (Nones allowed
    and filtered out) in a `<h2>` + `.grid2` section. Empty groups render
    nothing."""
    charts = [c for c in charts if c]
    if not charts:
        return "", ""
    panels = [_panel(t, cid, h) for t, cid, _cfg, h in charts]
    js = [chart_js(cid, cfg) for _, cid, cfg, _h in charts]
    html = "<h2>" + title_h2 + '</h2><div class="grid2">' + "".join(panels) + "</div>"
    return html, "\n".join(js)


# ---------------------------------------------------------------------------
# Tables (Task 9). Reuses `.panel tablewrap` / `table.kpis` CSS and the
# `toggleVeh`/`tr.vehrow` drilldown JS+CSS already injected globally by
# render.render_page (Task 4) -- neither is redefined here. Rendered only
# when `not compact` (compact mode is charts-only, see build_tab).
# ---------------------------------------------------------------------------

# Strips the "freight_<carrier>_veh_" prefix off an event-vehicle-id (see
# carriers_parse.CarrierTour.event_vehicle_id: "freight_" + carrier_id +
# "_veh_" + vehicle_id + "_" + tour_id) -- non-greedy so it stops at the
# FIRST "_veh_" regardless of how many underscores the carrier id itself
# contains. IDs that don't match the pattern (e.g. plain test fixture ids)
# pass through unchanged.
_VEH_ID_RE = re.compile(r"^freight_.+?_veh_")


def _short_veh_id(vid):
    return _VEH_ID_RE.sub("", str(vid), count=1)


def _cell(v, digits=0):
    """German-formatted table cell, "-" for None/NaN (guards div-by-zero
    callers that pass None, and pandas NaN from missing per-vehicle columns)."""
    if v is None:
        return "-"
    try:
        if pd.isna(v):
            return "-"
    except TypeError:
        pass
    return _fmt_de(float(v), digits)


def _table_vrp(pv):
    """Table 1: VRP-Effizienz je Provider -- one row per real provider
    (parcels_total-desc order, matching the Provider-Analytik charts' own
    `_prov_order`), a footnote row, "-" for absent/zero-guarded cells. Empty
    string when `pv` has no real-provider rows."""
    if pv is None or pv.empty:
        return ""
    order = _prov_order(pv)
    rows = []
    for p in order:
        tours = float(pv.loc[p, "tours"]) if "tours" in pv.columns else None
        km = float(pv.loc[p, "km"]) if "km" in pv.columns else None
        km_per_tour = (km / tours) if (km is not None and tours) else None
        cells = [
            _cell(tours),
            _cell(km, 1),
            _cell(km_per_tour, 1),
            _cell(pv.loc[p, "stops_per_h"] if "stops_per_h" in pv.columns else None, 1),
            _cell(pv.loc[p, "stops_per_km"] if "stops_per_km" in pv.columns else None, 2),
            _cell(pv.loc[p, "parcels_per_km"] if "parcels_per_km" in pv.columns else None, 2),
            _cell(pv.loc[p, "cost_per_parcel"] if "cost_per_parcel" in pv.columns else None, 2),
        ]
        rows.append("<tr><td>" + p + "</td><td>" + "</td><td>".join(cells) + "</td></tr>")
    header = ("<tr><th>Provider</th><th>Touren</th><th>km</th><th>km/Tour</th>"
               "<th>Stopps/h</th><th>Stopps/km</th><th>Pakete/km</th><th>€/Paket</th></tr>")
    footnote = '<tr><td colspan="8">stem% folgt in Plan D</td></tr>'
    return ('<h2>VRP-Effizienz je Provider</h2><div class="panel tablewrap">'
            '<table class="kpis">' + header + "".join(rows) + footnote + "</table></div>")


def _table_provider_drilldown(pv, vehicles):
    """Table 2: Provider-Übersicht mit Fahrzeug-Drilldown -- a clickable
    summary row per real provider (`toggleVeh('p<i>')`, ▸ marker) followed
    immediately by its hidden `tr.vehrow` rows (`data-drill="p<i>"`) built
    from `data.vehicles` filtered to role=="freight" and matching provider.
    The p<i> key is the provider's position in `_prov_order(pv)` -- IDENTICAL
    between a summary row and its vehrows, by construction. Vehrows are
    simply absent (summary row still renders) when `vehicles` carries no
    matching rows for a provider. Empty string when `pv` has no real-
    provider rows."""
    if pv is None or pv.empty:
        return ""
    order = _prov_order(pv)
    has_veh = (vehicles is not None and not vehicles.empty
               and {"role", "provider"}.issubset(vehicles.columns))
    rows = []
    for i, p in enumerate(order):
        key = "p" + str(i)
        vehicles_n = pv.loc[p, "vehicles"] if "vehicles" in pv.columns else None
        parcels_total = pv.loc[p, "parcels_total"] if "parcels_total" in pv.columns else None
        parcels_missed = pv.loc[p, "parcels_missed"] if "parcels_missed" in pv.columns else None
        cost_total = pv.loc[p, "cost_total"] if "cost_total" in pv.columns else None
        avg_lf = pv.loc[p, "avg_load_factor"] if "avg_load_factor" in pv.columns else None
        cost_cell = (_cell(cost_total) + " EUR") if cost_total is not None else "-"
        lf_cell = _fmt_pct(float(avg_lf)) if avg_lf is not None and not pd.isna(avg_lf) else "-"
        rows.append(
            '<tr onclick="toggleVeh(\'' + key + '\')" style="cursor:pointer">'
            "<td>▸ " + p + "</td><td>" + _cell(vehicles_n) + "</td><td>" + _cell(parcels_total)
            + "</td><td>" + _cell(parcels_missed) + "</td><td>" + cost_cell + "</td><td>"
            + lf_cell + "</td></tr>")
        if has_veh:
            sub = vehicles[(vehicles["role"] == "freight") & (vehicles["provider"] == p)]
            for _, vr in sub.iterrows():
                excluded = vr.get("excluded")
                excl_mark = "✓" if excluded == 1 else ""
                lf = vr.get("load_factor")
                lf_v = _fmt_pct(float(lf)) if lf is not None and not pd.isna(lf) else "-"
                rows.append(
                    '<tr class="vehrow" data-drill="' + key + '">'
                    "<td>" + _short_veh_id(vr.get("vehicle_id")) + "</td><td>"
                    + str(vr.get("vehicle_type", "")) + "</td><td>"
                    + _cell(vr.get("distance_km"), 1) + "</td><td>"
                    + _cell(vr.get("duration_h"), 1) + "</td><td>"
                    + _cell(vr.get("parcels")) + "</td><td>" + _cell(vr.get("stops"))
                    + "</td><td>" + lf_v + "</td><td>" + excl_mark + "</td></tr>")
    header = ("<tr><th>Provider</th><th>Fahrzeuge</th><th>Pakete</th><th>verpasst</th>"
               "<th>Kosten</th><th>Ø Auslastung</th></tr>")
    return ('<h2>Provider-Übersicht mit Fahrzeug-Drilldown</h2>'
            '<div class="panel tablewrap"><table class="kpis">' + header + "".join(rows)
            + "</table></div>")


def _low_util_notice(pv):
    """Table 3: Low-Util-Hinweis -- a plain `.panel` note, rendered only when
    `sum(excluded_vehicles) > 0`, with per-provider counts inline. Empty
    string (no notice) when the column is absent or the sum is 0."""
    if pv is None or pv.empty or "excluded_vehicles" not in pv.columns:
        return ""
    total = _pv_sum(pv, "excluded_vehicles")
    if not total:
        return ""
    parts = [p + ": " + _fmt_de(pv.loc[p, "excluded_vehicles"]) for p in _prov_order(pv)
             if float(pv.loc[p, "excluded_vehicles"]) > 0]
    detail = " (" + ", ".join(parts) + ")" if parts else ""
    text = (_fmt_de(total) + " Fahrzeuge mit Auslastung < 5 % ausgeschlossen" + detail
            + "; variable Kosten und verpasste Pakete wurden proportional reallokiert.")
    return '<div class="panel">' + text + "</div>"


def build_tab(data, uid, compact=False, map_block=None):
    """LMD tab body: 20-tile legacy headline set + a fleet-composition donut
    + 23 charts + optional map block. Tables (Task 9) render directly after
    Provider-Analytik / before Fahrzeugtyp-Analytik (moved up in v2 Plan D
    Task D3 -- previously trailed after all charts).

    `compact=True` renders only charts 1, 2, 4 (Provider-Analytik) and 13,
    14 (Tour-Struktur) -- no vehicle-type, Tagesverlauf, Scoring, Scatter
    charts, or tables. Every chart is guarded individually by its source
    KPI/series/rows being absent (e.g. no CARGOBIKE fleet, carrier_scores.txt
    not written, or the Plan-D-only hourly-provider/depot series not emitted
    yet)."""
    tiles = _tiles(data)

    map_html, map_js = "", ""
    if map_block is not None:
        map_html = map_block.get("html", "")
        map_js = map_block.get("js", "")

    provider, ts = data.provider, data.ts
    dist, iters, vehicles = data.distributions, data.iterations, data.vehicles
    pv = _pv(provider)
    types = _types(provider)
    vtypes = _vtypes(provider)

    groups_html, groups_js = [], []

    # --- Provider-Analytik (charts 1-9) ---
    prov_charts = [
        _prov_bar(pv, "parcels_total", "Pakete je Provider", "c_p_parcels_" + uid),
        _prov_bar(pv, "vehicles", "Fahrzeuge je Provider", "c_p_veh_" + uid),
    ]
    if not compact:
        prov_charts.append(_prov_bar(pv, "avg_load_factor", "Auslastung je Provider",
                                      "c_p_util_" + uid, pct=True))
    prov_charts.append(_cost_stack(pv, "c_p_cost_" + uid))
    if not compact:
        pvd = _pv_derived(pv)
        prov_charts += [
            _prov_bar(pv, "stops_per_h", "Stopps je Stunde je Provider", "c_p_stoph_" + uid),
            _prov_bar(pv, "parcels_per_km", "Pakete je km je Provider", "c_p_pkm_" + uid),
            _time_split_chart(pv, "c_p_time_" + uid),
            _prov_bar(pvd, "_km_per_tour", "Ø Tourdistanz je Provider [km]", "c_p_tourkm_" + uid),
            _prov_bar(pvd, "_stops_per_tour", "Ø Stopps je Tour je Provider", "c_p_stops_" + uid),
        ]
    h, j = _render_group("Provider-Analytik", prov_charts)
    if h:
        groups_html.append(h)
        groups_js.append(j)

    if not compact:
        # --- Provider overview tables (v2 Plan D Task D3, #10) --- moved up
        # from the trailing tables_html position to directly after Provider-
        # Analytik / before Fahrzeugtyp-Analytik, so the "Provider-Übersicht
        # mit Fahrzeug-Drilldown" (+ VRP-Effizienz) render near the top.
        # Render ORDER only -- the toggleVeh keys / tr.vehrow rows / JS/CSS
        # are unchanged (defined globally, not here).
        tables_html = (_table_vrp(pv) + _table_provider_drilldown(pv, vehicles)
                       + _low_util_notice(pv))
        if tables_html:
            groups_html.append(tables_html)

        # --- Fahrzeugtyp-Analytik (charts 8-12) ---
        # Chart 8: fleet-composition donut, "auf einen Blick" at the top of
        # the section. Charts 9(bars)-12: fine vtype:<type_id> rows (v2 Plan
        # C Task 12) -- e.g. one bar per ct_cep_size_s/m/l instead of the
        # broad type:VAN row's average. Both the donut and the bars color by
        # the D1 size marker (v2 Plan D Task D3) so S/M/L share the same
        # three teal shades across donut and bars.
        type_charts = [
            _fleet_donut(vtypes, "c_t_fleet_" + uid),
            _vtype_bar(vtypes, "distance_km", "Distanz je Fahrzeugtyp [km]", "c_t_km_" + uid),
            _vtype_bar(vtypes, "load_factor", "Auslastung je Fahrzeugtyp", "c_t_lf_" + uid, pct=True),
            _vtype_bar(vtypes, "km_per_tour", "km je Tour je Typ", "c_t_kmt_" + uid),
            _vtype_bar(vtypes, "stops_per_tour", "Stopps je Tour je Typ", "c_t_st_" + uid),
        ]
        h, j = _render_group("Fahrzeugtyp-Analytik", type_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

    # --- Tour-Struktur (charts 13-14; renders in compact mode too) ---
    struct_charts = [
        _dist_bar(dist, "lmd_tour_distance", "Tourdistanz-Verteilung [km]", "c_d_km_" + uid),
        _dist_bar(dist, "lmd_tour_duration", "Tourdauer-Verteilung [h]", "c_d_h_" + uid),
    ]
    h, j = _render_group("Tour-Struktur", struct_charts)
    if h:
        groups_html.append(h)
        groups_js.append(j)

    if not compact:
        # --- Tagesverlauf (charts 15-18) ---
        tag_charts = [
            _ts_bar(ts, "freight_service_stops", "Service-Starts je Stunde", "c_h_stops_" + uid),
            _hourly_provider_stack(ts, "freight_parcels_h_", "Pakete je Stunde je Provider",
                                    "c_h_parcels_" + uid),
            _hourly_provider_lines(ts, "freight_active_vehicles_", "Aktive Fahrzeuge (5-min)",
                                    "c_h_active_" + uid),
            _depot_chart(ts, "c_h_depot_" + uid),
        ]
        h, j = _render_group("Tagesverlauf", tag_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

        # --- Scoring (charts 19-21) ---
        score_charts = [
            _score_iters(iters, "c_s_it_" + uid),
            _dist_bar(dist, "lmd_carrier_score", "Score-Verteilung (letzte Iteration)",
                      "c_s_dist_" + uid),
            _prov_bar(pv, "score", "Score je Provider", "c_s_prov_" + uid),
        ]
        h, j = _render_group("Scoring", score_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

        # --- Operational Scatter (charts 22-23) ---
        veh_pct = vehicles.copy()
        if "load_factor" in veh_pct.columns:
            veh_pct["load_factor_pct"] = veh_pct["load_factor"] * 100
        scatter_charts = [
            _scatter(veh_pct, "load_factor_pct", "distance_km", "Auslastung vs. Tourdistanz",
                     "c_sc1_" + uid, "Auslastung [%]", "Tourdistanz [km]"),
            _scatter(vehicles, "parcels", "duration_h", "Pakete vs. Tourdauer",
                     "c_sc2_" + uid, "Pakete", "Tourdauer [h]"),
        ]
        h, j = _render_group("Operational Scatter", scatter_charts)
        if h:
            groups_html.append(h)
            groups_js.append(j)

    charts_html = "".join(groups_html)
    charts_js = "\n".join(g for g in groups_js if g)

    html = '<div class="tiles">' + tiles + "</div>" + map_html + charts_html
    js = charts_js + map_js
    return html, js
