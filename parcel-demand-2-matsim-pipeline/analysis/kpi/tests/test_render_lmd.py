# tests/test_render_lmd.py
import sys
from pathlib import Path
import pandas as pd
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import render, render_lmd


def _kpi_rows(pairs, group="freight"):
    return [{"kpi_group": group, "kpi_name": n, "value": v, "unit": "", "source": ""}
            for n, v in pairs]


def _data(kpis_rows, provider_rows=None):
    kpis = pd.DataFrame(kpis_rows)
    provider = pd.DataFrame(provider_rows) if provider_rows else pd.DataFrame()
    empty = pd.DataFrame()
    return render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=provider, iterations=empty, distributions=empty,
                          vehicles=empty)


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
        _prow("dhl", "excluded_vehicles", 2),
        _prow("amazon", "parcels_total", 2372), _prow("amazon", "cost_fixed", 700),
        _prow("amazon", "excluded_vehicles", 1),
        _prow("type:VAN", "vehicles", 40),
        _prow("type:TRUCK", "vehicles", 10),
        _prow("type:TRUCK_LIGHT", "vehicles", 5),
        _prow("type:SUPPLY_VAN", "vehicles", 2),
        _prow("all", "carriers_delivery", 6),
        _prow("all", "carriers_supply", 1),
        _prow("all", "stops", 900),
        _prow("all", "avg_load_factor", 0.81),
        _prow("all", "travel_hours", 300),
    ]


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
    # tile 2 sub: carriers_delivery / carriers_supply
    assert "6" in html and "1 Supply" in html
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
