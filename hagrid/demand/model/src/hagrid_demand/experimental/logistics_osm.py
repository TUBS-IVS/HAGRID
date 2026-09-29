"""Historical OSM covariates and a logistics proximity audit, not delivery labels."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
from html import escape
import json
from pathlib import Path
import urllib.parse
import urllib.request

import geopandas as gpd
import numpy as np
import pandas as pd
from shapely import union_all
from shapely.geometry import Point, Polygon

from ..data import write_json
from ..pipeline import digest
from ..scope import filter_dhl
from .provenance import code_hashes as package_code_hashes


def _code_hashes(package_root=None):
    root = Path(package_root).resolve() if package_root is not None else Path(__file__).resolve().parents[1]
    return package_code_hashes(root, "experimental/logistics_osm.py", "data.py", "pipeline.py", "scope.py")

LOCAL_BBOX = (52.425, 9.625, 52.51, 9.78)  # south, west, north, east
PARTS = {
    'warehouse': ['["building"="warehouse"]', '["building:use"="warehouse"]'],
    'industrial': ['["building"="industrial"]'],
    'logistics': ['["industrial"~"^(warehouse|logistics|distribution)$"]',
                  '["office"="logistics"]', '["company"="logistics"]'],
    'names': ['["name"~"logistik|logistics|spedition|dhl|dachser|schenker|fedex|hermes",i]'],
}
LOCAL_EVIDENCE = {
    'way/741012304': {'role': 'DHL Freight road-freight terminal',
        'source': 'https://group.dhl.com/en/media-relations/press-releases/2019/dhl-freight-opens-new-freight-hub-in-hanover-langenhagen.html'},
    'way/193732814': {'role': 'Hermes parcel sorting/transshipment centre',
        'source': 'https://hermesworld.com/int/about-us/history/current-decade/'},
}  # Local audit only; these two manually reviewed IDs NEVER enter regional model features.


def query(snapshot, bbox=LOCAL_BBOX, part=None):
    if snapshot not in {'2021', 'current'}:
        raise ValueError('Snapshot must be 2021 or current')
    south, west, north, east = bbox
    if not (-90 <= south < north <= 90 and -180 <= west < east <= 180):
        raise ValueError('Invalid bbox')
    historical = '[date:"2021-12-31T23:59:59Z"]' if snapshot == '2021' else ''
    selectors = PARTS[part] if part else sum(PARTS.values(), [])
    bounds = ','.join(str(v) for v in bbox)
    return '[out:json][timeout:40]' + historical + ';(' + ''.join(
        'nwr' + selector + '(' + bounds + ');' for selector in selectors) + ');out center geom;'


def fetch_part(output, snapshot, bbox, part, fetch=None):
    """Resume only a successful cache with the same query; never accept error remarks."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    q = query(snapshot, bbox, part)
    stem = output / f'{snapshot}_{part}_{sha256(q.encode()).hexdigest()[:16]}'
    path, manifest = stem.with_suffix('.json'), stem.with_suffix('.fetch.json')
    stem.with_suffix('.overpass').write_text(q, encoding='utf-8')
    if path.exists() and manifest.exists():
        meta = json.loads(manifest.read_text(encoding='utf-8'))
        data = json.loads(path.read_text(encoding='utf-8'))
        if meta.get('sha256') == digest(path) and 'elements' in data and not data.get('remark'):
            return data, meta
    failures = []
    for endpoint in ['https://overpass.private.coffee/api/interpreter', 'https://overpass-api.de/api/interpreter']:
        try:
            if fetch is not None:
                data = fetch(endpoint, q)
            else:
                request = urllib.request.Request(endpoint,
                    data=urllib.parse.urlencode({'data': q}).encode(),
                    headers={'User-Agent': 'HAGRID-demand-research/0.1'})
                with urllib.request.urlopen(request, timeout=50) as response:
                    data = json.load(response)
            if data.get('remark') or not isinstance(data.get('elements'), list):
                raise ValueError(data.get('remark', 'Missing OSM elements'))
            write_json(path, data)
            meta = {'status': 'complete', 'query_sha256': sha256(q.encode()).hexdigest(),
                    'endpoint': endpoint, 'retrieved_at': datetime.now(timezone.utc).isoformat(),
                    'sha256': digest(path), 'failures_before_success': failures}
            write_json(manifest, meta)
            return data, meta
        except (ValueError, OSError) as exc:
            failures.append(str(exc))
    write_json(manifest, {'status': 'failed', 'errors': failures})
    raise ValueError(f'OSM {snapshot}/{part}: {failures}')


def download(output, snapshot, bbox=LOCAL_BBOX, include_names=True):
    elements, manifests = {}, []
    for part in (list(PARTS) if include_names else ['warehouse', 'industrial', 'logistics']):
        cache = Path(output) / 'cache'
        q = query(snapshot, bbox, part)
        previous = cache / f'{snapshot}_{part}_{sha256(q.encode()).hexdigest()[:16]}.fetch.json'
        failed_before = previous.exists() and json.loads(previous.read_text(encoding='utf-8')).get('status') == 'failed'
        parts = []
        try:
            if failed_before:
                raise ValueError('Previously failed full-area query; retry smaller tiles')
            parts = [fetch_part(cache, snapshot, bbox, part)]
        except ValueError:
            south, west, north, east = bbox
            if north-south < .15 and east-west < .2:
                raise
            middle_lat, middle_lon = (south+north)/2, (west+east)/2
            tiles = [(s,w,n,e) for s,n in [(south,middle_lat),(middle_lat,north)]
                     for w,e in [(west,middle_lon),(middle_lon,east)]]
            for tile_id, tile in enumerate(tiles):
                data, meta = fetch_part(cache, snapshot, tile, part)
                parts.append((data, {**meta, 'tile_bbox': tile}))
                print(f'OSM {snapshot}/{part} tile {tile_id+1}/4: {len(data["elements"])} objects', flush=True)
        for data, meta in parts:
            manifests.append({'part': part, **meta, 'osm_base': data.get('osm3s', {})})
            for item in data['elements']:
                elements[(item['type'], item['id'])] = item
        print(f'OSM {snapshot}/{part}: {sum(len(d["elements"]) for d,m in parts)} objects before tile deduplication', flush=True)
    result = {'elements': list(elements.values()), 'snapshot': snapshot, 'bbox': bbox,
              'status': 'complete', 'parts': manifests}
    write_json(Path(output) / f'osm_{snapshot}.json', result)
    return result


def parse(data):
    rows, seen = [], set()
    for item in data['elements']:
        key = f"{item['type']}/{item['id']}"
        if key in seen:
            continue
        seen.add(key)
        tags, coords = item.get('tags', {}), item.get('geometry', [])
        geom, kind = None, 'point'
        if item['type'] == 'way' and len(coords) >= 4 and coords[0] == coords[-1]:
            geom = Polygon([(c['lon'], c['lat']) for c in coords])
            kind = 'polygon'
        elif 'lon' in item:
            geom = Point(item['lon'], item['lat'])
        elif 'center' in item:
            geom = Point(item['center']['lon'], item['center']['lat'])
            kind = 'relation_center' if item['type'] == 'relation' else 'way_center'
        if geom is None:
            continue
        warehouse = tags.get('building') == 'warehouse' or tags.get('building:use') == 'warehouse'
        explicit = warehouse or tags.get('office') == 'logistics' or tags.get('company') == 'logistics' or tags.get('industrial') in {'warehouse', 'logistics', 'distribution'}
        rows.append({'osm_key': key, 'name': tags.get('name', ''), 'building': tags.get('building', ''),
            'industrial': tags.get('industrial', ''), 'office': tags.get('office', ''),
            'operator': tags.get('operator', ''), 'warehouse_building': warehouse,
            'candidate_type': 'explicit_warehouse_or_logistics' if explicit else 'industrial_or_name_candidate',
            'geometry_kind': kind, 'tags_json': json.dumps(tags, ensure_ascii=False), 'geometry': geom})
    columns = ['osm_key', 'name', 'building', 'industrial', 'office', 'operator', 'warehouse_building',
               'candidate_type', 'geometry_kind', 'tags_json', 'geometry']
    result = gpd.GeoDataFrame(rows, columns=columns, geometry='geometry', crs=4326).to_crs(25832)
    result.geometry = result.geometry.make_valid()
    result['area_m2'] = result.geometry.area.where(result.geometry_kind.eq('polygon'), 0)
    return result


def postal_features(osm, postal):
    """Union footprints before clipping: coincident objects cannot double the area."""
    osm = osm.to_crs(postal.crs)
    if postal.crs.is_geographic or any(a.unit_conversion_factor != 1 for a in postal.crs.axis_info[:2]):
        raise ValueError('Metric projected CRS required')
    masks = {
        'warehouse_m2': osm.warehouse_building.eq(True) & osm.geometry_kind.eq('polygon'),
        'industrial_building_m2': osm.building.eq('industrial') & osm.geometry_kind.eq('polygon'),
    }
    geometries = {key: union_all(osm.loc[mask].geometry.to_numpy()) for key, mask in masks.items()}
    rows = []
    for plz, group in postal.groupby('plz'):
        boundary = group.geometry.union_all()
        row = {'plz': plz}
        for key, geometry in geometries.items():
            row[key] = geometry.intersection(boundary).area
        row['logistics_tag_objects'] = int((osm.candidate_type.eq('explicit_warehouse_or_logistics') & osm.geometry.intersects(boundary)).sum())
        rows.append(row)
    return pd.DataFrame(rows).set_index('plz')


def street_distances(streets, explicit):
    result = streets[['observation_id', 'street', 'value']].copy()
    if explicit.empty:
        result['osm_key'] = None
        result['distance_m'] = np.nan
        result['midpoint_distance_m'] = np.nan
        return result
    nearest = gpd.sjoin_nearest(streets, explicit[['osm_key', 'geometry']], how='left', distance_col='distance_m')
    nearest = nearest.sort_values(['observation_id', 'distance_m', 'osm_key']).drop_duplicates('observation_id')
    result = result.merge(nearest[['observation_id', 'osm_key', 'distance_m']], on='observation_id', validate='one_to_one')
    locations = streets[['observation_id', 'geometry']].copy()
    locations.geometry = locations.geometry.interpolate(.5, normalized=True)
    near_mid = gpd.sjoin_nearest(locations, explicit[['geometry']], how='left', distance_col='midpoint_distance_m')
    return result.merge(near_mid.groupby('observation_id').midpoint_distance_m.min(), on='observation_id', validate='one_to_one')


def audit_map(boundary, streets, osm):
    """Offline SVG map; hover geometries for source IDs, amounts and OSM tags."""
    x0, y0, x1, y1 = boundary.bounds
    parts = [f'<svg viewBox="{x0} {-y1} {x1-x0} {y1-y0}" style="width:100%;max-height:620px;background:#f4f6f9" role="img" aria-label="DHL streets and OSM sites in 30855">']
    def add(geom, color, title, fill='none', line=8):
        if geom.geom_type == 'GeometryCollection':
            for part in geom.geoms: add(part, color, title, fill, line)
            return
        svg = geom.svg(scale_factor=line, fill_color=fill) if geom.geom_type in {'Point', 'MultiPoint', 'Polygon', 'MultiPolygon'} else geom.svg(scale_factor=line, stroke_color=color)
        svg = svg.replace('stroke="#555555"', f'stroke="{color}"')
        parts.append('<g transform="scale(1,-1)"><title>' + escape(title) + '</title>' + svg + '</g>')
    add(boundary, '#8c99ad', 'PLZ 30855', line=3)
    for row in streets.itertuples():
        add(row.geometry, '#3b6cbb', f'{row.street}: {row.value:g}; {row.observation_id}', line=3)
    for row in osm.itertuples():
        color = '#df6c20' if row.candidate_type == 'explicit_warehouse_or_logistics' else '#92989d'
        add(row.geometry, color, f'{row.osm_key} {row.name}; {row.building} {row.industrial}', fill=color, line=5)
    return ''.join(parts) + '</svg>'


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--foundation', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--regional', action='store_true', help='Fetch uniform features for every postal area')
    parser.add_argument('--snapshots',nargs='+',choices=['2021','current'],default=['current','2021'],help='Resume selected data snapshots only')
    args = parser.parse_args(argv)
    output, source = Path(args.output), Path(args.foundation)
    output.mkdir(parents=True, exist_ok=True)
    postal = gpd.read_parquet(source / 'postal_support.parquet').to_crs(25832)
    boundary = postal.loc[postal.plz.eq('30855')].geometry.union_all()
    bbox = LOCAL_BBOX
    if args.regional:
        west, south, east, north = postal.to_crs(4326).total_bounds
        bbox = (float(south-.001), float(west-.001), float(north+.001), float(east+.001))
    streets = filter_dhl(gpd.read_parquet(source / 'dhl_observations.parquet').to_crs(25832), 1000)
    streets = streets.loc[streets.plz.eq('30855')].copy()
    sites = gpd.read_parquet(source / 'sites.parquet').to_crs(25832)
    firms = sites.loc[sites.recipient_type.eq('business') & sites.geometry.within(boundary)]
    results, panels, failures = {}, [], {}
    for snapshot in dict.fromkeys(args.snapshots):
        try:
            data = download(output, snapshot, bbox, include_names=not args.regional)
            regional = parse(data)
            regional.to_parquet(output / f'regional_facilities_{snapshot}.parquet', index=False)
            if args.regional:
                postal_features(regional, postal).to_csv(output / f'postal_features_{snapshot}.csv')
                # Named candidates add local context only, never a one-PLZ model feature.
                named, _ = fetch_part(output / 'cache', snapshot, LOCAL_BBOX, 'names')
                regional = pd.concat([regional, parse(named)]).drop_duplicates('osm_key')
            osm = regional.loc[regional.geometry.intersects(boundary)].copy()
            osm['source_supported_role'] = osm.osm_key.map({k:v['role'] for k,v in LOCAL_EVIDENCE.items()}).fillna('')
            osm['external_evidence_url'] = osm.osm_key.map({k:v['source'] for k,v in LOCAL_EVIDENCE.items()}).fillna('')
            osm['nearby_firms_100m'] = [int(firms.geometry.distance(g).le(100).sum()) for g in osm.geometry]
            osm['nearby_employees_100m'] = [float(firms.loc[firms.geometry.distance(g).le(100),'employees'].sum()) for g in osm.geometry]
            osm.to_parquet(output / f'facilities_{snapshot}.parquet', index=False)
            osm.drop(columns='geometry').to_csv(output / f'facilities_{snapshot}.csv', index=False)
            explicit = osm.loc[osm.candidate_type.eq('explicit_warehouse_or_logistics')]
            matches = street_distances(streets, explicit)
            matches.to_csv(output / f'streets_{snapshot}.csv', index=False)
            context = osm.loc[osm.candidate_type.eq('explicit_warehouse_or_logistics') | osm.source_supported_role.ne('')]
            with_evidence = street_distances(streets, context)
            with_evidence.to_csv(output/f'streets_with_source_context_{snapshot}.csv',index=False)
            built = explicit.loc[explicit.geometry_kind.eq('polygon')]
            bases = [m.get('osm_base',{}).get('timestamp_osm_base','unknown') for m in data['parts']]
            if args.regional: bases.append(named.get('osm3s',{}).get('timestamp_osm_base','unknown'))
            results[snapshot] = {'server_data_timestamps': sorted(set(bases)),
                'requested_historical_date': '2021-12-31T23:59:59Z' if snapshot=='2021' else None,
                'candidate_objects': len(osm), 'explicit_objects': len(explicit),
                'warehouse_building_objects': int(osm.warehouse_building.eq(True).sum()),
                'union_explicit_polygon_area_m2': float(union_all(built.geometry.to_numpy()).intersection(boundary).area),
                'street_volume_total': float(streets.value.sum()), 'street_count': len(streets),
                'volume_street_within_100m': float(matches.loc[matches.distance_m.le(100), 'value'].sum()),
                'volume_street_within_250m': float(matches.loc[matches.distance_m.le(250), 'value'].sum()),
                'volume_midpoint_within_100m': float(matches.loc[matches.midpoint_distance_m.le(100), 'value'].sum()),
                'volume_midpoint_within_250m': float(matches.loc[matches.midpoint_distance_m.le(250), 'value'].sum()),
                'firms': len(firms), 'employees': float(firms.employees.sum()),
                'transport_storage_firms': int(firms.branch.eq('H').sum())}
            results[snapshot].update({'source_supported_objects':int(osm.source_supported_role.ne('').sum()),
                'volume_street_within_100m_with_source_context':float(with_evidence.loc[with_evidence.distance_m.le(100),'value'].sum()),
                'volume_midpoint_within_100m_with_source_context':float(with_evidence.loc[with_evidence.midpoint_distance_m.le(100),'value'].sum())})
            print(snapshot, results[snapshot], flush=True)
            panels.append('<h2>OSM data snapshot ' + snapshot + '</h2><p>Blue: DHL streets. Orange: explicit warehouse/logistics tags. Gray: other industrial/name candidates. Details on hover.</p>' + audit_map(boundary, streets, osm)
                + matches.sort_values('value', ascending=False).head(25).to_html(index=False, float_format=lambda v: f'{v:,.1f}'))
        except (OSError, ValueError) as exc:
            failures[snapshot] = str(exc)
            write_json(output / 'failures.json', failures)
            print(f'Snapshot {snapshot} incomplete: {exc}', flush=True)
    write_json(output / 'summary.json', results)
    write_json(output / 'failures.json', failures)
    page = '<!doctype html><meta charset="utf-8"><title>Langenhagen logistics</title><style>body{font:16px system-ui;max-width:1200px;margin:35px auto;padding:0 20px;color:#243246}td,th{padding:7px}table{border-collapse:collapse;font-size:14px}h1,h2{color:#164372}</style><h1>Langenhagen: logistics sites and DHL 2021</h1><p>OSM objects are not unique firms. Warehouse tags establish neither DHL customers nor parcel volume. Proximity of an entire street can capture much more volume than proximity of its midpoint: neither is a delivery assignment. Relations are treated as center points and contribute no area. A historical OSM data snapshot means the map content at that time, not the complete 2021 building stock. The exclusion rule remains >1000; no further volumes removed.</p>'
    page += '<p>DHL Freight already opened its freight center on 13.09.2019: <a href="https://group.dhl.com/en/media-relations/press-releases/2019/dhl-freight-opens-new-freight-hub-in-hanover-langenhagen.html">DHL press release</a>. General cargo/freight transshipment must not be counted as local parcel delivery.</p>'
    page += '<p>The Hermes hub is also documented before 2021: <a href="https://hermesworld.com/int/about-us/history/current-decade/">Hermes company history</a>. For the local check, the matching named OSM areas are additionally taken into account. These manually reviewed site roles do not enter the regional model comparison as a Langenhagen-specific feature. The sources do not confirm any DHL delivery volume at these sites.</p>'
    page += '<p>Geodata: <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors, ODbL</a>. Current data are not output retroactively as historical features. Server states can differ per partial query; the actual data timestamps are shown below. For historical queries, the requested reference date 31.12.2021 applies regardless.</p>'
    page += pd.DataFrame(results).to_html() + ''.join(panels)
    if failures:
        page += '<h2>Incomplete downloads</h2><pre>' + escape(json.dumps(failures, ensure_ascii=False)) + '</pre>'
    (output / 'dashboard.html').write_text(page, encoding='utf-8')
    write_json(output / 'provenance.json', {'code': _code_hashes(),
        'inputs': {n: digest(source/n) for n in ['postal_support.parquet', 'dhl_observations.parquet', 'sites.parquet']},
        'regional': args.regional, 'bbox': bbox, 'snapshots': list(dict.fromkeys(args.snapshots)),
        'status': 'partial' if failures else 'complete',
        'license': 'OpenStreetMap contributors, ODbL; https://www.openstreetmap.org/copyright'})
    with (output/'attempts.jsonl').open('a',encoding='utf-8') as handle:
        handle.write(json.dumps({'finished_at':datetime.now(timezone.utc).isoformat(),
            'requested_snapshots':args.snapshots,'completed_snapshots':list(results),'failures':failures})+'\n')
    if failures:
        raise SystemExit('Some snapshots failed; successful queries are cached. Repeat the same command to resume.')


if __name__ == '__main__':
    main()
