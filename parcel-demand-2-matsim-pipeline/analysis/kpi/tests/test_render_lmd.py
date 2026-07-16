# tests/test_render_lmd.py
import sys
from pathlib import Path
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import render, render_lmd


def _kpi_rows(pairs, group="freight"):
    return [{"kpi_group": group, "kpi_name": n, "value": v, "unit": "", "source": ""}
            for n, v in pairs]


def _data(kpis_rows, provider_rows=None, ts=None, iterations=None, distributions=None,
          vehicles=None):
    kpis = pd.DataFrame(kpis_rows)
    provider = pd.DataFrame(provider_rows) if provider_rows else pd.DataFrame()
    empty = pd.DataFrame()
    return render.RunData(
        kpis=kpis,
        ts=ts if ts is not None else pd.DataFrame(columns=["series", "hour", "value"]),
        provider=provider,
        iterations=iterations if iterations is not None else empty,
        distributions=distributions if distributions is not None else empty,
        vehicles=vehicles if vehicles is not None else empty)


def _prow(provider, kpi_name, value):
    return {"provider": provider, "kpi_name": kpi_name, "value": value, "unit": "", "source": ""}


def _full_kpis():
    return _kpi_rows([
        ("freight_vehicles", 67), ("parcels_total", 6372), ("freight_vehicle_km", 5000),
        ("freight_tours", 80), ("freight_tour_hours", 500), ("freight_total_costs", 12000),
        ("carriers", 7), ("parcels_missed", 50), ("parcels_unassigned", 20),
        ("delivery_rate", 0.95), ("parcels_handled", 6300),
    ]) + _kpi_rows([("freight_cost_per_parcel", 1.88)], group="economic")


def _full_provider():
    return [
        _prow("dhl", "parcels_total", 4000), _prow("dhl", "cost_fixed", 800),
        _prow("dhl", "cost_dist", 300), _prow("dhl", "cost_time", 150),
        _prow("dhl", "excluded_vehicles", 2), _prow("dhl", "vehicles", 40),
        _prow("dhl", "tours", 10), _prow("dhl", "km", 200), _prow("dhl", "tour_hours", 50),
        _prow("dhl", "travel_hours", 40), _prow("dhl", "stops_per_h", 5),
        _prow("dhl", "parcels_per_km", 20), _prow("dhl", "avg_load_factor", 0.82),
        _prow("dhl", "stops", 500), _prow("dhl", "score", 120.5),
        _prow("amazon", "parcels_total", 2372), _prow("amazon", "cost_fixed", 700),
        _prow("amazon", "cost_dist", 200), _prow("amazon", "cost_time", 100),
        _prow("amazon", "excluded_vehicles", 1), _prow("amazon", "vehicles", 20),
        _prow("amazon", "tours", 8), _prow("amazon", "km", 150), _prow("amazon", "tour_hours", 35),
        _prow("amazon", "travel_hours", 28), _prow("amazon", "stops_per_h", 4),
        _prow("amazon", "parcels_per_km", 15), _prow("amazon", "avg_load_factor", 0.78),
        _prow("amazon", "stops", 400), _prow("amazon", "score", 95.2),
        _prow("type:VAN", "vehicles", 40),
        _prow("type:VAN", "distance_km", 800.0), _prow("type:VAN", "load_factor", 0.75),
        _prow("type:VAN", "km_per_tour", 20.0), _prow("type:VAN", "stops_per_tour", 8.0),
        _prow("type:TRUCK", "vehicles", 10),
        _prow("type:TRUCK", "distance_km", 500.0), _prow("type:TRUCK", "load_factor", 0.65),
        _prow("type:TRUCK", "km_per_tour", 50.0), _prow("type:TRUCK", "stops_per_tour", 4.0),
        _prow("type:TRUCK_LIGHT", "vehicles", 5),
        _prow("type:SUPPLY_VAN", "vehicles", 2),
        _prow("all", "carriers_delivery", 6),
        _prow("all", "carriers_supply", 1),
        _prow("all", "stops", 900),
        _prow("all", "avg_load_factor", 0.81),
        _prow("all", "travel_hours", 300),
    ]


def _full_distributions():
    return pd.DataFrame([
        {"series": "lmd_tour_distance", "bin_lo": 0, "bin_hi": 10, "value": 5, "unit": "km"},
        {"series": "lmd_tour_distance", "bin_lo": 10, "bin_hi": 20, "value": 12, "unit": "km"},
        {"series": "lmd_tour_duration", "bin_lo": 0, "bin_hi": 2, "value": 8, "unit": "h"},
        {"series": "lmd_tour_duration", "bin_lo": 2, "bin_hi": 4, "value": 9, "unit": "h"},
        {"series": "lmd_carrier_score", "bin_lo": -100, "bin_hi": -50, "value": 3, "unit": "score"},
        {"series": "lmd_carrier_score", "bin_lo": -50, "bin_hi": 0, "value": 4, "unit": "score"},
    ])


def _full_iterations():
    rows = []
    for it in (0, 1):
        rows.append({"series": "carrier_score_executed", "iteration": it, "value": -50.0 + it})
        rows.append({"series": "carrier_score_worst", "iteration": it, "value": -80.0 + it})
        rows.append({"series": "carrier_score_avg", "iteration": it, "value": -60.0 + it})
        rows.append({"series": "carrier_score_best", "iteration": it, "value": -40.0 + it})
    return pd.DataFrame(rows)


def _full_vehicles():
    return pd.DataFrame([
        {"role": "freight", "vehicle_id": "v1", "provider": "dhl", "vehicle_type": "VAN",
         "distance_km": 40.0, "duration_h": 5.0, "travel_h": 4.0, "parcels": 80, "stops": 20,
         "load_factor": 0.8, "excluded": 0},
        {"role": "freight", "vehicle_id": "v2", "provider": "dhl", "vehicle_type": "VAN",
         "distance_km": 30.0, "duration_h": 4.0, "travel_h": 3.0, "parcels": 60, "stops": 15,
         "load_factor": 0.6, "excluded": 0},
        {"role": "freight", "vehicle_id": "v3", "provider": "amazon", "vehicle_type": "VAN",
         "distance_km": 50.0, "duration_h": 6.0, "travel_h": 5.0, "parcels": 90, "stops": 22,
         "load_factor": 0.9, "excluded": 0},
    ])


def _full_ts():
    return pd.DataFrame([
        {"series": "freight_service_stops", "hour": 8, "value": 12},
        {"series": "freight_service_stops", "hour": 9, "value": 18},
    ])


def test_tiles_full_set():
    data = _data(_full_kpis(), _full_provider())
    html, js = render_lmd.build_tab(data, uid="lmd")
    assert "Aktive Fahrzeuge" in html and "67" in html
    assert "CEP-Vans" in html and "40" in html
    # tile 5: Supply-Fahrzeuge = TRUCK(10) + TRUCK_LIGHT(5) + SUPPLY_VAN(2) = 17
    assert "Supply-Fahrzeuge" in html and "17" in html
    # tile 13: Ø Tourlaenge = freight_vehicle_km / freight_tours = 5000/80 = 62,5
    assert "Ø Tourlänge" in html and "62,5" in html
    # tile 14: Ø Geschwindigkeit = freight_vehicle_km / all;travel_hours = 5000/300 = 16,7
    assert "Ø Geschwindigkeit" in html and "16,7" in html
    # tile 2 sub: carriers_delivery / carriers_supply (tightened -- was a "6" in html
    # substring tautology that "67"/"6372" would also satisfy)
    assert "6 Zustellung / 1 Supply" in html
    # tile 18: Fixkosten = sum over real providers of cost_fixed = 800 + 700 = 1500
    assert '<div class="v">1.500 EUR</div><div class="l">Fixkosten</div>' in html
    # tile 20: Low-Util ausgeschlossen = sum over real providers of excluded_vehicles = 2 + 1 = 3
    assert '<div class="v">3</div><div class="l">Low-Util ausgeschlossen</div>' in html
    assert 'title="' in html   # tooltips present


def test_cargobikes_tile_absent_when_source_missing():
    # provider data has no type:CARGOBIKE rows -> tile 4 must simply be absent
    data = _data(_full_kpis(), _full_provider())
    html, js = render_lmd.build_tab(data, uid="lmd")
    assert "Cargobikes" not in html


def test_missing_provider_df_all_provider_tiles_absent():
    data = _data(_full_kpis())   # no provider rows at all
    html, js = render_lmd.build_tab(data, uid="lmd")
    assert "CEP-Vans" not in html
    assert "Cargobikes" not in html
    assert "Supply-Fahrzeuge" not in html
    assert '<div class="l">Fixkosten</div>' not in html
    assert '<div class="l">Low-Util ausgeschlossen</div>' not in html
    # long-sourced tiles still render
    assert "Aktive Fahrzeuge" in html


def test_derived_tiles_guard_division_by_zero():
    rows = _kpi_rows([
        ("freight_vehicles", 67), ("freight_vehicle_km", 5000),
        ("freight_tours", 0), ("freight_tour_hours", 0),
    ])
    provider_rows = [_prow("all", "travel_hours", 0)]
    data = _data(rows, provider_rows)
    html, js = render_lmd.build_tab(data, uid="lmd")
    # freight_tours = 0 -> Ø Tourlaenge (13) and Touren/Fahrzeug sub (19) must not crash / not render
    assert "Ø Tourlänge" not in html
    # all;travel_hours = 0 -> Ø Geschwindigkeit (14) must not render
    assert "Ø Geschwindigkeit" not in html
    # freight_tour_hours = 0 -> Fahranteil (16) and stops/h sub (10) must not render
    assert "Fahranteil" not in html


def test_map_block_inserted():
    data = _data(_full_kpis(), _full_provider())
    html, js = render_lmd.build_tab(data, uid="lmd",
                                    map_block={"html": "<div id='MAPX'></div>", "js": "//mapjs"})
    assert "MAPX" in html and "//mapjs" in js


def test_pv_types_all_accessors():
    provider = pd.DataFrame(_full_provider())
    pv = render_lmd._pv(provider)
    assert set(pv.index) == {"dhl", "amazon"}
    assert pv.loc["dhl", "cost_fixed"] == 800
    types = render_lmd._types(provider)
    assert set(types["provider"]) == {"type:VAN", "type:TRUCK", "type:TRUCK_LIGHT", "type:SUPPLY_VAN"}
    assert render_lmd._all(provider, "travel_hours") == 300
    assert render_lmd._all(provider, "nonexistent") is None


# ---------------------------------------------------------------------------
# Task 8: charts
# ---------------------------------------------------------------------------

def test_charts_full_set():
    data = _data(_full_kpis(), _full_provider(), ts=_full_ts(), iterations=_full_iterations(),
                 distributions=_full_distributions(), vehicles=_full_vehicles())
    html, js = render_lmd.build_tab(data, uid="lmd")

    # chart 4: cost-components stacked bar -- exactly 3 datasets (cost_fixed/cost_dist/cost_time)
    cost_line = next(l for l in js.splitlines() if "c_p_cost_lmd" in l)
    assert cost_line.count('"label"') == 3

    # charts 22/23: scatter -- type present, provider labels present
    assert '"scatter"' in js
    assert '"dhl"' in js and '"amazon"' in js

    # charts 16-18: Plan-D hourly-provider series not emitted yet -> absent, no error
    assert "c_h_parcels_lmd" not in html
    assert "c_h_active_lmd" not in html
    assert "c_h_depot_lmd" not in html

    # chart 19: carrier-score iterations present (carrier_scores.txt supplied)
    assert "c_s_it_lmd" in html
    # chart 15 (ts) / 20 (score distribution) / 21 (score per provider) present
    assert "c_h_stops_lmd" in html
    assert "c_s_dist_lmd" in html
    assert "c_s_prov_lmd" in html
    # sections 10-12 (vehicle-type) and 13-14 (tour structure) present
    assert "c_t_km_lmd" in html and "c_t_lf_lmd" in html
    assert "c_t_kmt_lmd" in html and "c_t_st_lmd" in html
    assert "c_d_km_lmd" in html and "c_d_h_lmd" in html


def test_charts_missing_iterations_skip_chart19_gracefully():
    # carrier_scores.txt is optional -- no carrier_score_* series at all -> chart 19 absent,
    # no exception
    data = _data(_full_kpis(), _full_provider(), ts=_full_ts(), vehicles=_full_vehicles())
    html, js = render_lmd.build_tab(data, uid="lmd")
    assert "c_s_it_lmd" not in html


def test_charts_compact_excludes_scatter_and_scoring():
    data = _data(_full_kpis(), _full_provider(), ts=_full_ts(), iterations=_full_iterations(),
                 distributions=_full_distributions(), vehicles=_full_vehicles())
    html, js = render_lmd.build_tab(data, uid="lmd", compact=True)
    # no scatter canvases
    assert "c_sc1_lmd" not in html and "c_sc2_lmd" not in html
    # no scoring canvases
    assert "c_s_it_lmd" not in html and "c_s_dist_lmd" not in html and "c_s_prov_lmd" not in html
    # no vehicle-type / hourly canvases either
    assert "c_t_km_lmd" not in html
    assert "c_h_stops_lmd" not in html
    # only charts 1, 2, 4, 13, 14 render
    assert "c_p_parcels_lmd" in html and "c_p_veh_lmd" in html and "c_p_cost_lmd" in html
    assert "c_d_km_lmd" in html and "c_d_h_lmd" in html
    # the non-compact provider charts (3/5/6/7/8/9) are absent
    assert "c_p_util_lmd" not in html and "c_p_stoph_lmd" not in html
    assert "c_p_pkm_lmd" not in html and "c_p_time_lmd" not in html
    assert "c_p_tourkm_lmd" not in html and "c_p_stops_lmd" not in html


def test_scatter_unknown_provider_uses_null_safe_slots_not_bare_slot():
    # "regiopack" is not in PROVIDER_SLOTS -> must render gray via the __slots null-safe
    # path, never via a bare "__slot": null (which resolveColors resolves to CAT[0], i.e.
    # dhl-blue, because `null % len` === 0 in JS)
    vehicles = pd.DataFrame([
        {"role": "freight", "vehicle_id": "v1", "provider": "dhl", "vehicle_type": "VAN",
         "distance_km": 40.0, "duration_h": 5.0, "travel_h": 4.0, "parcels": 80, "stops": 20,
         "load_factor": 0.8, "excluded": 0},
        {"role": "freight", "vehicle_id": "v2", "provider": "regiopack", "vehicle_type": "VAN",
         "distance_km": 20.0, "duration_h": 3.0, "travel_h": 2.0, "parcels": 40, "stops": 10,
         "load_factor": 0.5, "excluded": 0},
    ])
    data = _data(_full_kpis(), _full_provider(), vehicles=vehicles)
    html, js = render_lmd.build_tab(data, uid="lmd")
    sc_line = next(l for l in js.splitlines() if "c_sc1_lmd" in l)
    assert '"regiopack"' in sc_line
    assert '"__slot": null' not in sc_line
    assert "__slots" in sc_line
