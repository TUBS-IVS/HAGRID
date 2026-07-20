# -*- coding: utf-8 -*-
"""Leaflet map blocks for the run dashboard (v2 KPI dashboard, Plan D Task 8).

`build_blocks(map_data, uid)` turns the map_data dict (maps.build_map_data
output, Tasks 6/7) into the two per-tab `map_block` dicts consumed unchanged
by render_drt.build_tab / render_lmd.build_tab (each reads `.get("html")` /
`.get("js")`), plus a shared `head` string that render.render_page injects
into the HEAD region: the inlined vendored Leaflet CSS+JS (read once) followed
by `<script>const MAP_DATA_<uid> = {...};</script>`.

The vendor files are pre-vendored under kpi/vendor/ (leaflet 1.9.4 +
leaflet.heat 0.2.0 + leaflet.markercluster 1.5.3). Inline order matters:
leaflet.js FIRST, then leaflet-heat.js + leaflet.markercluster.js (both need
`L`). All map JS lives inside the returned blocks, so map-free pages (which
never call build_blocks) carry zero Leaflet bytes.

Colors are computed in JS on the page's theme tokens (the JS globals `V`,
`alphaSeq`, `CAT`, `DARK`, `OTHER` are shipped by render.JS_SETUP/JS_RESOLVE
in every page): DRT occupancy reuses the Plan-C alpha ramp on `--seq` (occ 0
gray), LMD provider colors resolve PROVIDER_SLOTS -> CAT (unknown -> gray).
Only the CARTO basemap tiles are an online dependency."""
import json
from pathlib import Path

from render import PROVIDER_SLOTS

VENDOR = Path(__file__).parent / "vendor"
_CSS_FILES = ["leaflet.css", "MarkerCluster.css", "MarkerCluster.Default.css"]
# leaflet.js MUST come first (heat + markercluster both extend `L`).
_JS_FILES = ["leaflet.js", "leaflet-heat.js", "leaflet.markercluster.js"]

# map container height + rounded clip (leaflet needs an explicit height).
_MAP_STYLE = "height:480px;border-radius:10px;overflow:hidden;"
_CTRL_STYLE = ("margin-bottom:8px;display:flex;gap:14px;flex-wrap:wrap;"
               "align-items:center;font-size:13px;")


def _read(name):
    return (VENDOR / name).read_text(encoding="utf-8")


def build_head(map_data, uid):
    """Inlined vendor CSS+JS + the per-uid MAP_DATA constant, for render_page's
    `extra_head` (goes in the HEAD, before body_html, so L/MAP_DATA exist
    before the body_js map JS runs)."""
    css = "".join(_read(n) for n in _CSS_FILES)
    scripts = "".join("<script>" + _read(n) + "</script>" for n in _JS_FILES)
    data_js = ("<script>const MAP_DATA_" + uid + " = "
               + json.dumps(map_data, separators=(",", ":")) + ";</script>")
    return "<style>" + css + "</style>" + scripts + data_js


def _options(pairs, all_value, all_label):
    opts = ['<option value="' + all_value + '">' + all_label + "</option>"]
    for value in pairs:
        opts.append('<option value="' + str(value) + '">' + str(value) + "</option>")
    return "".join(opts)


def _drt_html(drt, uid):
    veh_opts = _options(list((drt.get("vehicles") or {}).keys()), "__all__", "Alle")
    return (
        '<div class="panel"><div style="' + _CTRL_STYLE + '">'
        '<label>Fahrzeug <select id="drt_sel_' + uid + '">' + veh_opts + "</select></label>"
        '<label><input type="checkbox" id="drt_depots_' + uid + '" checked> Depots</label>'
        '<label><input type="checkbox" id="drt_heatpu_' + uid + '"> Heatmap Einstiege</label>'
        '<label><input type="checkbox" id="drt_heatdo_' + uid + '"> Heatmap Ausstiege</label>'
        "</div>"
        '<div id="map_drt_' + uid + '" style="' + _MAP_STYLE + '"></div></div>')


def _lmd_html(lmd, uid):
    tours = lmd.get("tours") or []
    stops = lmd.get("stops") or []
    providers = sorted({t.get("provider") for t in tours if t.get("provider")}
                       | {s.get("provider") for s in stops if s.get("provider")})
    carriers = sorted({t.get("carrier") for t in tours if t.get("carrier")})
    vehicles = sorted({t.get("veh") for t in tours if t.get("veh")}
                      | {s.get("veh") for s in stops if s.get("veh")})
    name = "lmd_mode_" + uid
    return (
        '<div class="panel"><div style="' + _CTRL_STYLE + '">'
        "<span>"
        '<label><input type="radio" name="' + name + '" value="tours" checked> Touren</label> '
        '<label><input type="radio" name="' + name + '" value="stops"> Stopps</label> '
        '<label><input type="radio" name="' + name + '" value="heat"> Heatmap</label>'
        "</span>"
        '<label>Provider <select id="lmd_prov_' + uid + '">'
        + _options(providers, "all", "Alle") + "</select></label>"
        '<label>Carrier <select id="lmd_carr_' + uid + '">'
        + _options(carriers, "all", "Alle") + "</select></label>"
        '<label>Fahrzeug <select id="lmd_veh_' + uid + '">'
        + _options(vehicles, "all", "Alle") + "</select></label>"
        "</div>"
        '<div id="map_lmd_' + uid + '" style="' + _MAP_STYLE + '"></div></div>')


_TILE_JS = ("var TILE = DARK ? 'dark_all' : 'light_all';\n"
            "L.tileLayer('https://{s}.basemaps.cartocdn.com/' + TILE + "
            "'/{z}/{x}/{y}{r}.png', {attribution: 'OpenStreetMap, CARTO', "
            "maxZoom: 19, subdomains: 'abcd'}).addTo(map);\n")


_DRT_JS = """
(function(){
  var ROOT = MAP_DATA___UID__ || {};
  var MD = ROOT.drt || {};
  var center = ROOT.center || [51.44, 14.24];
  var map = L.map('map_drt___UID__', {preferCanvas: true}).setView(center, 12);
  __TILE__
  var cap = MD.cap || 8;
  function occColor(occ){
    if (occ <= 0) return OTHER;
    var lvl = occ > cap ? cap : occ;
    return alphaSeq(0.25 + 0.75 * lvl / cap);
  }
  function stopBadge(n, kind){
    var bg = kind === 'pu' ? V('--seq') : OTHER;
    return L.divIcon({className: 'drt-badge', iconSize: [18, 18],
      html: '<div style="background:' + bg + ';color:#fff;border-radius:50%;width:18px;'
          + 'height:18px;line-height:18px;text-align:center;font-size:10px;font-weight:600;">'
          + n + '</div>'});
  }
  var tours = L.layerGroup().addTo(map);
  var depotG = L.layerGroup();
  var heatPu = null, heatDo = null;
  (MD.service_area || []).forEach(function(ring){
    L.polygon(ring, {color: V('--axis'), weight: 1, fill: false, dashArray: '4 3'}).addTo(map);
  });
  function addVehicle(vname, withStops){
    var v = (MD.vehicles || {})[vname];
    if (!v) return;
    var segs = v.segs || {};
    Object.keys(segs).forEach(function(key){
      var color = occColor(parseInt(key, 10));
      (segs[key] || []).forEach(function(pts){
        if (pts && pts.length >= 2)
          L.polyline(pts, {color: color, weight: 3, opacity: 0.85}).addTo(tours);
      });
    });
    if (withStops)
      (v.stops || []).forEach(function(s){
        L.marker([s.lat, s.lon], {icon: stopBadge(s.n, s.kind)}).addTo(tours);
      });
  }
  function renderTours(sel){
    tours.clearLayers();
    if (sel === '__all__')
      Object.keys(MD.vehicles || {}).forEach(function(vn){ addVehicle(vn, false); });
    else
      addVehicle(sel, true);
  }
  renderTours('__all__');
  (MD.depots || []).forEach(function(d){
    L.circleMarker([d.lat, d.lon], {radius: 7, color: V('--ink2'), fillColor: V('--seq'),
      fillOpacity: 0.9, weight: 2}).bindPopup('Depot: ' + d.name).addTo(depotG);
  });
  depotG.addTo(map);
  function ensureHeat(which){
    var pts = MD[which] || [];
    return pts.length ? L.heatLayer(pts, {radius: 14, blur: 18, maxZoom: 15}) : null;
  }
  var elSel = document.getElementById('drt_sel___UID__');
  if (elSel) elSel.addEventListener('change', function(e){ renderTours(e.target.value); });
  var elDep = document.getElementById('drt_depots___UID__');
  if (elDep) elDep.addEventListener('change', function(e){
    if (e.target.checked) depotG.addTo(map); else map.removeLayer(depotG); });
  var elPu = document.getElementById('drt_heatpu___UID__');
  if (elPu) elPu.addEventListener('change', function(e){
    if (e.target.checked){ heatPu = heatPu || ensureHeat('pu'); if (heatPu) heatPu.addTo(map); }
    else if (heatPu) map.removeLayer(heatPu); });
  var elDo = document.getElementById('drt_heatdo___UID__');
  if (elDo) elDo.addEventListener('change', function(e){
    if (e.target.checked){ heatDo = heatDo || ensureHeat('do'); if (heatDo) heatDo.addTo(map); }
    else if (heatDo) map.removeLayer(heatDo); });
  window.addEventListener('resize', function(){ map.invalidateSize(); });
  setTimeout(function(){ map.invalidateSize(); }, 120);
})();
"""


_LMD_JS = """
(function(){
  var ROOT = MAP_DATA___UID__ || {};
  var MD = ROOT.lmd || {};
  var center = ROOT.center || [51.44, 14.24];
  var map = L.map('map_lmd___UID__', {preferCanvas: true}).setView(center, 12);
  __TILE__
  var PROVIDER_SLOTS = __PROVIDER_SLOTS__;
  function provColor(p){
    var s = PROVIDER_SLOTS[p];
    return (s === undefined || s === null) ? OTHER : CAT[s % CAT.length];
  }
  function pad2(n){ return (n < 10 ? '0' : '') + n; }
  function hhmm(t){ t = t || 0; return pad2(Math.floor(t / 3600)) + ':' + pad2(Math.floor((t % 3600) / 60)); }
  var tourG = L.layerGroup();
  var stopG = L.markerClusterGroup({spiderfyOnMaxZoom: true, disableClusteringAtZoom: 18,
                                    maxClusterRadius: 40});
  var heatLayer = null;
  function pass(o){
    var p = document.getElementById('lmd_prov___UID__').value;
    var c = document.getElementById('lmd_carr___UID__').value;
    var v = document.getElementById('lmd_veh___UID__').value;
    if (p !== 'all' && o.provider !== p) return false;
    if (c !== 'all' && o.carrier !== undefined && o.carrier !== c) return false;
    if (v !== 'all' && o.veh !== v) return false;
    return true;
  }
  function renderTours(){
    tourG.clearLayers();
    (MD.tours || []).forEach(function(t){
      if (!pass(t)) return;
      var col = provColor(t.provider);
      (t.runs || []).forEach(function(run){
        if (run && run.length >= 2)
          L.polyline(run, {color: col, weight: 3, opacity: 0.85})
            .bindPopup('Fzg: ' + t.veh + '<br>Provider: ' + t.provider + '<br>Carrier: ' + t.carrier)
            .addTo(tourG);
      });
    });
  }
  function renderStops(){
    stopG.clearLayers();
    (MD.stops || []).forEach(function(s){
      if (!pass(s)) return;
      var col = provColor(s.provider);
      L.circleMarker([s.lat, s.lon], {radius: 5, color: col, fillColor: col,
        fillOpacity: 0.85, weight: 1})
        .bindPopup('Provider: ' + s.provider + '<br>Fzg: ' + s.veh + '<br>' + hhmm(s.t)
                   + '<br>Nachfrage: ' + s.demand).addTo(stopG);
    });
  }
  function ensureHeat(){
    if (!heatLayer && (MD.heat || []).length)
      heatLayer = L.heatLayer(MD.heat, {radius: 14, blur: 18});
    return heatLayer;
  }
  function currentMode(){
    var r = document.querySelector('input[name="lmd_mode___UID__"]:checked');
    return r ? r.value : 'tours';
  }
  function apply(){
    map.removeLayer(tourG); map.removeLayer(stopG);
    if (heatLayer) map.removeLayer(heatLayer);
    var mode = currentMode();
    if (mode === 'tours'){ renderTours(); tourG.addTo(map); }
    else if (mode === 'stops'){ renderStops(); stopG.addTo(map); }
    else { var h = ensureHeat(); if (h) h.addTo(map); }
  }
  Array.prototype.forEach.call(document.getElementsByName('lmd_mode___UID__'), function(el){
    el.addEventListener('change', apply);
  });
  ['lmd_prov___UID__', 'lmd_carr___UID__', 'lmd_veh___UID__'].forEach(function(id){
    var el = document.getElementById(id); if (el) el.addEventListener('change', apply);
  });
  apply();
  window.addEventListener('resize', function(){ map.invalidateSize(); });
  setTimeout(function(){ map.invalidateSize(); }, 120);
})();
"""


def _drt_js(uid):
    return _DRT_JS.replace("__TILE__", _TILE_JS).replace("__UID__", uid)


def _lmd_js(uid):
    return (_LMD_JS.replace("__TILE__", _TILE_JS)
            .replace("__PROVIDER_SLOTS__", json.dumps(PROVIDER_SLOTS))
            .replace("__UID__", uid))


def build_blocks(map_data, uid):
    """{"drt": {"html","js"}, "lmd": {"html","js"}, "head": "..."}."""
    drt = map_data.get("drt") or {}
    lmd = map_data.get("lmd") or {}
    return {
        "drt": {"html": _drt_html(drt, uid), "js": _drt_js(uid)},
        "lmd": {"html": _lmd_html(lmd, uid), "js": _lmd_js(uid)},
        "head": build_head(map_data, uid),
    }
