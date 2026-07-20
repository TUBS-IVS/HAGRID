# -*- coding: utf-8 -*-
"""Tests for render_maps.py (v2 KPI dashboard, Plan D Task 8 -- Leaflet blocks).

The 6 Leaflet vendor files are pre-vendored under kpi/vendor/. build_blocks
turns a map_data dict (maps.build_map_data output, Tasks 6/7) into the two
tab map_block dicts + a shared `head` (inlined vendor CSS+JS + MAP_DATA_<uid>).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import render_maps


def _map_data():
    return {
        "center": [51.44, 14.24],
        "drt": {
            "service_area": [[[51.40, 14.20], [51.50, 14.20], [51.50, 14.30]]],
            "depots": [{"name": "dhl", "lat": 51.40, "lon": 14.20}],
            "vehicles": {
                "drt_veh_1": {
                    "segs": {"0": [[[51.40, 14.20], [51.50, 14.20]]],
                             "1": [[[51.50, 14.20], [51.50, 14.30]]]},
                    "stops": [{"lat": 51.40, "lon": 14.20, "t": 28800, "n": 1, "kind": "pu"}],
                }
            },
            "pu": [[51.40, 14.20]],
            "do": [[51.50, 14.30]],
            "cap": 8,
        },
        "lmd": {
            "tours": [{"veh": "v1", "provider": "dhl", "carrier": "dhl",
                       "runs": [[[51.40, 14.20], [51.50, 14.20]]]}],
            "stops": [{"lat": 51.40, "lon": 14.20, "provider": "dhl", "veh": "v1",
                       "t": 30000, "demand": 5}],
            "heat": [[51.40, 14.20, 1]],
            "depots": [{"name": "dhl", "lat": 51.40, "lon": 14.20}],
        },
    }


def test_build_blocks_returns_drt_lmd_head_keys():
    blocks = render_maps.build_blocks(_map_data(), uid="m0")
    assert set(blocks.keys()) == {"drt", "lmd", "head"}
    assert set(blocks["drt"].keys()) == {"html", "js"}
    assert set(blocks["lmd"].keys()) == {"html", "js"}


def test_drt_block_html_has_map_div_and_vehicle_select():
    blocks = render_maps.build_blocks(_map_data(), uid="m0")
    html = blocks["drt"]["html"]
    assert 'id="map_drt_m0"' in html
    # vehicle <select> with the "Alle" option + one option per vehicle
    assert "<select" in html
    assert "Alle" in html
    assert "drt_veh_1" in html


def test_drt_block_js_creates_leaflet_map():
    blocks = render_maps.build_blocks(_map_data(), uid="m0")
    js = blocks["drt"]["js"]
    assert "L.map(" in js
    assert "MAP_DATA_m0" in js


def test_lmd_block_html_has_map_div_and_mode_radio():
    blocks = render_maps.build_blocks(_map_data(), uid="m0")
    html = blocks["lmd"]["html"]
    assert 'id="map_lmd_m0"' in html
    assert 'type="radio"' in html


def test_lmd_block_js_uses_marker_cluster_and_heat():
    blocks = render_maps.build_blocks(_map_data(), uid="m0")
    js = blocks["lmd"]["js"]
    assert "markerClusterGroup" in js
    assert "heatLayer" in js
    assert "MAP_DATA_m0" in js


def test_head_inlines_vendor_sources_and_map_data():
    blocks = render_maps.build_blocks(_map_data(), uid="m0")
    head = blocks["head"]
    # inlined vendor JS (stable strings in the minified 1.9.4 build)
    assert "Leaflet 1.9.4" in head
    assert "preferCanvas" in head
    assert "MarkerClusterGroup" in head
    assert "simpleheat" in head
    # inlined vendor CSS
    assert "leaflet-container" in head
    # the per-uid map data constant
    assert "MAP_DATA_m0" in head
    assert "51.44" in head  # center made it into the inlined data


def test_head_inline_order_leaflet_before_plugins():
    # leaflet.js must be inlined BEFORE leaflet-heat/markercluster (both need L)
    head = render_maps.build_blocks(_map_data(), uid="m0")["head"]
    assert head.index("Leaflet 1.9.4") < head.index("simpleheat")
    assert head.index("Leaflet 1.9.4") < head.index("MarkerClusterGroup")


def test_uid_is_threaded_through_ids():
    blocks = render_maps.build_blocks(_map_data(), uid="zz")
    assert 'id="map_drt_zz"' in blocks["drt"]["html"]
    assert 'id="map_lmd_zz"' in blocks["lmd"]["html"]
    assert "MAP_DATA_zz" in blocks["head"]


def test_missing_optional_layers_do_not_break_build():
    md = {"center": [51.44, 14.24],
          "drt": {"vehicles": {}, "pu": [], "do": [], "cap": 8},
          "lmd": {}}
    blocks = render_maps.build_blocks(md, uid="m0")
    assert 'id="map_drt_m0"' in blocks["drt"]["html"]
    assert 'id="map_lmd_m0"' in blocks["lmd"]["html"]
    assert "MAP_DATA_m0" in blocks["head"]
