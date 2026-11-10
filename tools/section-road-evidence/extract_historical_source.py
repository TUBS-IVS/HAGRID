"""Acquire a coherent historical OSM road source, with an optional pinned PBF.

Preparation/routing remains offline. Run this acquisition command separately;
configure the resulting PBF and SHA-256 as the pipeline's geometry source.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from pyproj import Transformer
from extract_witness_sources import sha256


def convert_response(response, snapshot, bounds, provenance):
    cutoff = datetime.fromisoformat(snapshot.replace('Z', '+00:00'))
    if cutoff.tzinfo is None:
        raise ValueError('Snapshot requires a timezone')
    if response.get('remark'):
        raise ValueError('Incomplete Overpass response: ' + response['remark'])
    nodes, ways = {}, {}
    for obj in response['elements']:
        timestamp = obj.get('timestamp')
        if not timestamp or datetime.fromisoformat(timestamp.replace('Z', '+00:00')) > cutoff:
            raise ValueError('Missing timestamp or object newer than requested snapshot')
        key = str(obj['id'])
        if obj['type'] == 'node':
            if key in nodes:
                raise ValueError('Duplicate node version')
            nodes[key] = [obj['lon'], obj['lat']]
        elif obj['type'] == 'way':
            if key in ways:
                raise ValueError('Duplicate way version')
            ways[key] = {'nodes': obj['nodes'], 'tags': obj.get('tags', {})}
    if not ways or any(str(node) not in nodes for way in ways.values() for node in way['nodes']):
        raise ValueError('Empty ways or incomplete way nodes')
    return {'source': {**provenance, 'snapshot': snapshot},
            'EPSG25832Bounds': list(bounds), 'nodes': nodes, 'ways': ways}


def write_pbf(data, path):
    import osmium
    if path.exists():
        raise FileExistsError(path)
    with osmium.SimpleWriter(str(path)) as writer:
        for key in sorted(data['nodes'], key=int):
            writer.add_node(osmium.osm.mutable.Node(id=int(key), location=data['nodes'][key]))
        for key in sorted(data['ways'], key=int):
            way = data['ways'][key]
            writer.add_way(osmium.osm.mutable.Way(id=int(key), nodes=way['nodes'], tags=way['tags']))


def acquire(snapshot, bounds, output, archive, endpoint, pbf=None):
    for path in (output, archive, pbf):
        if path is not None and path.exists():
            raise FileExistsError(path)
    west, south, east, north = Transformer.from_crs(25832, 4326, always_xy=True).transform_bounds(*bounds)
    query = (f'[out:json][timeout:90][date:"{snapshot}"];'
             f'way[highway]({south},{west},{north},{east});(._;>;);out meta;')
    request = Request(endpoint, data=urlencode({'data': query}).encode(),
                      headers={'User-Agent': 'HAGRID historical road evidence acquisition'})
    with urlopen(request, timeout=105) as response:
        payload = response.read()
    raw = json.loads(payload)
    import hashlib
    provenance = {'path': str(archive.resolve()), 'sha256': hashlib.sha256(payload).hexdigest(),
                  'url': endpoint, 'query': query,
                  'acquiredAt': datetime.now(timezone.utc).isoformat()}
    data = convert_response(raw, snapshot, bounds, provenance)
    with archive.open('xb') as stream:
        stream.write(payload)
    if pbf is not None:
        write_pbf(data, pbf)
        data['source']['pbf'] = {'path': str(pbf.resolve()), 'sha256': sha256(pbf)}
    with output.open('x', encoding='utf-8') as stream:
        json.dump(data, stream, separators=(',', ':'))
    return data


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', required=True, help='UTC instant, e.g. 2022-07-11T00:00:00Z')
    parser.add_argument('--bounds', type=float, nargs=4, required=True, metavar=('WEST','SOUTH','EAST','NORTH'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--pbf', type=Path)
    parser.add_argument('--endpoint', default='https://overpass-api.de/api/interpreter')
    args = parser.parse_args()
    data = acquire(args.snapshot, args.bounds, args.output, args.archive, args.endpoint, args.pbf)
    print(json.dumps({'nodes': len(data['nodes']), 'ways': len(data['ways']), 'source': data['source']}))
