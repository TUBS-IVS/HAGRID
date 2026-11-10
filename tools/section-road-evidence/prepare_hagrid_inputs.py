"""Halt preparation from existing HAGRID demand curves and network topology.

The source street part must match link endpoints and length. An unsupported
job prevents publication of the entire manifest.
"""

import argparse
import gzip
import hashlib
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

import geopandas as gpd
import networkx as nx
import pandas as pd
from scipy.spatial import cKDTree
from shapely.geometry import LineString, Point, box
from shapely.ops import nearest_points, substring
from shapely.strtree import STRtree


ENDPOINT_TOLERANCE_M = 0.02
LENGTH_TOLERANCE_M = 0.01
CHORD_TOLERANCE_M = 0.02
STOP_JOIN_TOLERANCE_M = 1e-5
COORD_RE = re.compile(r"\(\s*([-+\d.eE]+)\s*;\s*([-+\d.eE]+)\s*\)")
OOH_STOP_TYPES = frozenset(('locker', 'shared_locker', 'counter', 'shop'))


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def _open_xml(path):
    return gzip.open(path, 'rb') if str(path).endswith('.gz') else open(path, 'rb')


def _network_lane_width(path):
    with _open_xml(path) as stream:
        for event, element in ET.iterparse(stream, events=('start', 'end')):
            if event == 'start' and element.tag == 'links':
                width = float(element.get('effectivelanewidth', 'nan'))
                if not math.isfinite(width) or width <= 0:
                    raise ValueError('Network links lacks a positive effectivelanewidth')
                return width
            if event == 'end' and element.tag == 'node':
                element.clear()
    raise ValueError('Network links container not found')


def _attributes(element):
    return {a.get('name'): (a.text or '').strip() for a in element.findall('./{*}attributes/{*}attribute')}


def read_carriers(paths):
    carriers = []
    for path in map(Path, paths):
        with _open_xml(path) as stream:
            tree = ET.parse(stream)
        for carrier in tree.getroot().findall('.//{*}carrier'):
            services = carrier.find('./{*}services')
            vehicles = carrier.findall('./{*}capabilities/{*}vehicles/{*}vehicle')
            if services is None:
                raise ValueError(f'{path}: carrier {carrier.get("id")} has no services')
            jobs = []
            for service in services.findall('./{*}service'):
                attrs = _attributes(service)
                match = COORD_RE.fullmatch(attrs.get('coord', ''))
                jobs.append({'id': service.get('id'), 'linkId': service.get('to'),
                             'point': Point(float(match[1]), float(match[2])) if match else None,
                             'stopId': attrs.get('stop_id') or attrs.get('stopId'),
                             'mode': attrs.get('carrierMode', ''), 'attributes': attrs, 'element': service})
            carriers.append({'id': carrier.get('id'), 'path': path, 'tree': tree,
                             'services': services, 'vehicles': vehicles, 'jobs': jobs})
    ids = [c['id'] for c in carriers]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate carrier ID in input XML')
    return carriers


def read_network(path, raw_ids):
    """Two streaming passes retain only requested links and their reverse candidates."""
    nodes, links = {}, {}
    with _open_xml(path) as stream:
        for _, element in ET.iterparse(stream, events=('end',)):
            if element.tag == 'node':
                nodes[element.get('id')] = Point(float(element.get('x')), float(element.get('y')))
                element.clear()
            elif element.tag == 'link':
                if element.get('id') in raw_ids:
                    links[element.get('id')] = {**element.attrib, 'attributes': _attributes(element)}
                element.clear()
    wanted_pairs = {(link['to'], link['from']) for link in links.values()}
    reverse = defaultdict(list)
    with _open_xml(path) as stream:
        for _, element in ET.iterparse(stream, events=('end',)):
            if element.tag == 'node':
                element.clear()
            elif element.tag == 'link':
                key = (element.get('from'), element.get('to'))
                if key in wanted_pairs:
                    item = {**element.attrib, 'attributes': _attributes(element)}
                    reverse[(item['to'], item['from'])].append(item)
                    links[item['id']] = item
                element.clear()
    return nodes, links, reverse


def _road_record(link, nodes):
    a, b = nodes[link['from']], nodes[link['to']]
    return {'fromNodeId': link['from'], 'toNodeId': link['to'],
            'lengthMeters': float(link['length']), 'geometryLengthMeters': a.distance(b),
            'orientedXY': [[a.x, a.y], [b.x, b.y]]}


def _prepare_fixed_ooh(job, row, catalog, stop_by_id, links, depots, report):
    """Keep HAGRID's assigned link service for a registered pickup point.

    The POI is a facility coordinate, not a proved vehicle halt or street side.
    The Section adapter interprets fraction 1 as the original link endpoint.
    """
    reasons = row['reasons']
    if job['mode'] not in ('PARCEL_LOCKER', 'PARCEL_LOCKER_EXISTING'):
        reasons.append('UNSUPPORTED_NON_HOME_SERVICE_MODE')
    point = job['point']
    if point is None or not math.isfinite(point.x) or not math.isfinite(point.y):
        reasons.append('MISSING_SOURCE_COORDINATE')
    link = links.get(job['linkId'])
    if link is None:
        reasons.append('RAW_LINK_MISSING')
    elif 'car' not in link.get('modes', '').split(','):
        reasons.append('RAW_LINK_NOT_CAR')
    if len(depots) != 1 or next(iter(depots), None) not in links:
        reasons.append('DEPOT_LINK_UNAVAILABLE')
    stop_id = job['stopId']
    if not stop_id:
        reasons.append('OOH_STOP_ID_REQUIRED')
    elif not stop_id.startswith('ooh:') or stop_id not in stop_by_id.index:
        reasons.append('OOH_STOP_ID_NOT_FOUND')
    else:
        stop = stop_by_id.loc[stop_id]
        row['stopJoin'] = 'EXPLICIT_XML_ID'
        row['sourceStopId'] = stop_id
        if stop.get('stop_type') not in OOH_STOP_TYPES or stop.get('str_idx') != -1:
            reasons.append('OOH_STOP_REGISTER_TYPE_MISMATCH')
        if 'point_id' in stop.index and stop.point_id != stop_id[4:]:
            reasons.append('OOH_POINT_ID_MISMATCH')
        if not pd.isna(stop.get('part')) or not pd.isna(stop.get('side')):
            reasons.append('OOH_HAS_STREET_SIDE_METADATA')
        if point is not None and (stop.geometry is None or stop.geometry.is_empty or
                                  stop.geometry.geom_type != 'Point' or
                                  stop.geometry.distance(point) > STOP_JOIN_TOLERANCE_M):
            reasons.append('STOP_COORDINATE_MISMATCH')
    if reasons:
        return
    approach = {'linkId': link['id'], 'fraction': 1.0}
    row.update(status='supported', approaches=[approach],
               haltSemantics='ORIGINAL_ASSIGNED_LINK_ENDPOINT',
               facilityPositionStatus='POI_COORDINATE_NOT_VEHICLE_HALT',
               crossingPolicy='NO_APPROACH_SWITCH')
    catalog['jobs'].append({'jobId': job['id'], 'rawLinkId': link['id'],
                            'sourceXY': [point.x, point.y], 'sourceStopId': stop_id,
                            'serviceMode': job['mode'], 'fixedOriginalLink': True,
                            'approaches': [approach]})
    report['supportedJobs'] += 1


def _curve_record(link, shape):
    return {'fromNodeId': link['from'], 'toNodeId': link['to'],
            'lengthMeters': float(link['length']), 'geometryLengthMeters': shape.length,
            'orientedXY': [[float(x), float(y)] for x, y in shape.coords]}


def _match_geometry(link, line, point, nodes):
    reasons = []
    a, b = nodes[link['from']], nodes[link['to']]
    chord = LineString((a, b))
    length = float(link['length'])
    pa, pb, hp = line.project(a), line.project(b), line.project(point)
    arc = abs(pb - pa)
    section = substring(line, min(pa, pb), max(pa, pb))
    if section.geom_type == 'LineString':
        coordinates = list(section.coords)
        if pb < pa: coordinates.reverse()
        coordinates[0] = (a.x, a.y)
        coordinates[-1] = (b.x, b.y)
        shape = LineString(coordinates)
    else:
        shape = chord
    metrics = {'endpointResidualMeters': max(a.distance(line), b.distance(line)),
               'arcLengthResidualMeters': abs(arc - length),
               'networkChordResidualMeters': abs(length - chord.length),
               'sourceChordHausdorffMeters': section.hausdorff_distance(chord),
               'haltAxisResidualMeters': point.distance(line),
               'haltChordResidualMeters': point.distance(chord),
               'haltCurveResidualMeters': point.distance(shape),
               'constructedLengthResidualMeters': abs(shape.length - length)}
    if metrics['endpointResidualMeters'] > ENDPOINT_TOLERANCE_M:
        reasons.append('SOURCE_ENDPOINT_MISMATCH')
    if metrics['arcLengthResidualMeters'] > LENGTH_TOLERANCE_M:
        reasons.append('SOURCE_ARC_LENGTH_MISMATCH')
    if metrics['constructedLengthResidualMeters'] > ENDPOINT_TOLERANCE_M:
        reasons.append('CONSTRUCTED_CURVE_LENGTH_MISMATCH')
    if metrics['haltAxisResidualMeters'] > ENDPOINT_TOLERANCE_M or metrics['haltCurveResidualMeters'] > CHORD_TOLERANCE_M:
        reasons.append('HALT_OFF_CERTIFIED_LINE')
    if hp < min(pa, pb) - ENDPOINT_TOLERANCE_M or hp > max(pa, pb) + ENDPOINT_TOLERANCE_M:
        reasons.append('HALT_OUTSIDE_LINK_ARC')
    if chord.length <= 0 or abs(pb - pa) <= 0:
        reasons.append('DEGENERATE_LINK_GEOMETRY')
    return reasons, metrics, shape, pb > pa


def _match_multiline(link, geometry, source_part, point, nodes):
    """Find one length-matched path through connected H03 parts of one row."""
    a, b = nodes[link['from']], nodes[link['to']]
    if a.distance(geometry) > ENDPOINT_TOLERANCE_M or b.distance(geometry) > ENDPOINT_TOLERANCE_M:
        return None
    parts = list(geometry.geoms)
    graph = nx.Graph()
    locations = []

    def vertex(coordinate):
        candidate = Point(coordinate)
        for index, previous in enumerate(locations):
            if candidate.distance(previous) <= ENDPOINT_TOLERANCE_M:
                return index
        index = len(locations)
        locations.append(candidate)
        graph.add_node(index)
        return index

    terminals = {}
    for part_index, line in enumerate(parts):
        positions = [0.0, line.length]
        for label, target in (('from', a), ('to', b), ('halt', point)):
            if target.distance(line) <= ENDPOINT_TOLERANCE_M and (label != 'halt' or part_index == source_part):
                positions.append(line.project(target))
        for other in parts:
            for xy in (other.coords[0], other.coords[-1]):
                candidate = Point(xy)
                if candidate.distance(line) <= ENDPOINT_TOLERANCE_M:
                    positions.append(line.project(candidate))
        positions = sorted(set(round(x, 8) for x in positions))
        for start, end in zip(positions, positions[1:]):
            if end - start <= 1e-7:
                continue
            segment = substring(line, start, end)
            if segment.geom_type != 'LineString':
                continue
            u, v = vertex(segment.coords[0]), vertex(segment.coords[-1])
            if u == v:
                continue
            if graph.has_edge(u, v):
                return None  # competing source segments between the same junctions
            graph.add_edge(u, v, length=segment.length, geometry=segment,
                           part=part_index, positionStart=start, positionEnd=end, fromVertex=u)
        for label, target in (('from', a), ('to', b), ('halt', point)):
            if target.distance(line) <= ENDPOINT_TOLERANCE_M and (label != 'halt' or part_index == source_part):
                position = round(line.project(target), 8)
                terminals.setdefault(label, set()).add(vertex(line.interpolate(position).coords[0]))
    if any(len(terminals.get(label, set())) != 1 for label in ('from', 'to', 'halt')):
        return None
    start, end, halt = (next(iter(terminals[label])) for label in ('from', 'to', 'halt'))
    try:
        first = nx.shortest_path(graph, start, halt, weight='length')
        second = nx.shortest_path(graph, halt, end, weight='length')
    except nx.NetworkXNoPath:
        return None
    route = first + second[1:]
    if len(route) != len(set(route)):
        return None
    length = sum(graph[u][v]['length'] for u, v in zip(route, route[1:]))
    if abs(length - float(link['length'])) > LENGTH_TOLERANCE_M:
        return None
    coordinates = []
    directions = []
    for u, v in zip(route, route[1:]):
        edge = graph[u][v]
        points = list(edge['geometry'].coords)
        forward = u == edge['fromVertex']
        if not forward: points.reverse()
        if edge['part'] == source_part and (u == halt or v == halt):
            directions.append(forward)
        coordinates.extend(points if not coordinates else points[1:])
    if len(set(directions)) != 1 or not coordinates:
        return None
    coordinates[0] = (a.x, a.y)
    coordinates[-1] = (b.x, b.y)
    shape = LineString(coordinates)
    if abs(shape.length - float(link['length'])) > ENDPOINT_TOLERANCE_M or point.distance(shape) > CHORD_TOLERANCE_M:
        return None
    metrics = {'endpointResidualMeters': max(a.distance(geometry), b.distance(geometry)),
               'arcLengthResidualMeters': abs(length - float(link['length'])),
               'constructedLengthResidualMeters': abs(shape.length - float(link['length'])),
               'haltCurveResidualMeters': point.distance(shape),
               'sourcePartCount': len(parts), 'sourcePartsTraversed': [graph[u][v]['part'] for u, v in zip(route, route[1:])]}
    return [], metrics, shape, directions[0]


def _source_part_index(observations):
    parts, provenance = [], []
    for row in observations.itertuples():
        geometry = row.geometry
        if geometry is None or geometry.is_empty:
            continue
        lines = list(geometry.geoms) if geometry.geom_type == 'MultiLineString' else [geometry]
        for part, line in enumerate(lines):
            if line.geom_type == 'LineString' and line.length > 0:
                parts.append(line)
                provenance.append((int(row.Index), part))
    return parts, provenance, STRtree(parts)


def _match_two_source_chain(link, point, source_row, source_part, nodes, part_index):
    """Recover a two-constituent simplified link from connected H03 streets."""
    if '-' not in link['id']:
        return None
    parts, provenance, tree = part_index
    a, b = nodes[link['from']], nodes[link['to']]
    starts = [int(i) for i in tree.query(a.buffer(ENDPOINT_TOLERANCE_M))
              if parts[int(i)].distance(a) <= ENDPOINT_TOLERANCE_M]
    ends = [int(i) for i in tree.query(b.buffer(ENDPOINT_TOLERANCE_M))
            if parts[int(i)].distance(b) <= ENDPOINT_TOLERANCE_M]
    results = []
    for ai in starts:
        for bi in ends:
            if ai == bi or provenance[ai][0] == provenance[bi][0]:
                continue
            if (source_row, source_part) not in (provenance[ai], provenance[bi]):
                continue
            first, second = parts[ai], parts[bi]
            left, right = nearest_points(first, second)
            if left.distance(right) > ENDPOINT_TOLERANCE_M:
                continue
            junction = Point((left.x + right.x) / 2, (left.y + right.y) / 2)
            if min(*(junction.distance(Point(line.coords[k])) for line in (first, second) for k in (0, -1))) > ENDPOINT_TOLERANCE_M:
                continue
            ap, ac = first.project(a), first.project(junction)
            bc, bp = second.project(junction), second.project(b)
            span1, span2 = abs(ac - ap), abs(bp - bc)
            if span1 <= 0 or span2 <= 0:
                continue
            if abs(span1 + span2 - float(link['length'])) > LENGTH_TOLERANCE_M:
                continue
            seg1 = substring(first, min(ap, ac), max(ap, ac))
            seg2 = substring(second, min(bc, bp), max(bc, bp))
            if seg1.geom_type != 'LineString' or seg2.geom_type != 'LineString':
                continue
            coords1, coords2 = list(seg1.coords), list(seg2.coords)
            if ac < ap: coords1.reverse()
            if bp < bc: coords2.reverse()
            coords1[0], coords1[-1] = (a.x, a.y), (junction.x, junction.y)
            coords2[0], coords2[-1] = (junction.x, junction.y), (b.x, b.y)
            shape = LineString(coords1 + coords2[1:])
            source_segment = seg1 if provenance[ai] == (source_row, source_part) else seg2
            if point.distance(source_segment) > CHORD_TOLERANCE_M or point.distance(shape) > CHORD_TOLERANCE_M:
                continue
            if abs(shape.length - float(link['length'])) > ENDPOINT_TOLERANCE_M:
                continue
            same_direction = ac > ap if provenance[ai] == (source_row, source_part) else bp > bc
            metrics = {'endpointResidualMeters': max(a.distance(first), b.distance(second)),
                       'arcLengthResidualMeters': abs(span1 + span2 - float(link['length'])),
                       'constructedLengthResidualMeters': abs(shape.length - float(link['length'])),
                       'haltCurveResidualMeters': point.distance(shape),
                       'junctionGapMeters': left.distance(right),
                       'sourceRowsTraversed': [provenance[ai][0], provenance[bi][0]],
                       'sourcePartsTraversed': [provenance[ai][1], provenance[bi][1]]}
            results.append(([], metrics, shape, same_direction))
    if len(results) != 1:
        return None
    return results[0]


def _same_street_name(observed, network_name):
    if not network_name:
        return False
    pattern = ''.join('.' if char == '\ufffd' else re.escape(char) for char in str(observed))
    return re.fullmatch(pattern, network_name, flags=re.IGNORECASE) is not None


def _corridor_match(link, metrics, failed, model_lane_width):
    try:
        lanes = float(link['permlanes'])
    except (KeyError, TypeError, ValueError):
        return False
    if not math.isfinite(lanes) or lanes <= 0:
        return False
    half_width = model_lane_width * lanes / 2
    return (metrics['endpointResidualMeters'] <= half_width and
            metrics['arcLengthResidualMeters'] <= 2 * half_width + LENGTH_TOLERANCE_M and
            metrics['constructedLengthResidualMeters'] <= LENGTH_TOLERANCE_M and
            metrics['haltCurveResidualMeters'] <= CHORD_TOLERANCE_M and
            'HALT_OUTSIDE_LINK_ARC' not in failed)


def _remap_exact(network, pending, nodes, report, allow_street_crossing, model_lane_width, part_index):
    """Find a unique named H03-matching car road for a misassigned raw link."""
    if not pending:
        return
    bounds = [box(*item['line'].bounds) for item in pending]
    tree = STRtree(bounds)
    matches = defaultdict(list)
    with _open_xml(network) as stream:
        for _, element in ET.iterparse(stream, events=('end',)):
            if element.tag == 'node':
                element.clear()
            elif element.tag == 'link':
                start, end = nodes.get(element.get('from')), nodes.get(element.get('to'))
                if start is not None and end is not None:
                    center = Point((start.x + end.x) / 2, (start.y + end.y) / 2)
                    try:
                        lanes = float(element.get('permlanes', 'nan'))
                    except ValueError:
                        lanes = math.nan
                    search_width = model_lane_width * lanes / 2 if math.isfinite(lanes) and lanes > 0 else ENDPOINT_TOLERANCE_M
                    indices = set(int(i) for i in tree.query(box(center.x - search_width, center.y - search_width,
                                                                  center.x + search_width, center.y + search_width)))
                    if part_index is not None and '-' in element.get('id', ''):
                        for endpoint in (start, end):
                            indices.update(int(i) for i in tree.query(box(endpoint.x - ENDPOINT_TOLERANCE_M,
                                                                            endpoint.y - ENDPOINT_TOLERANCE_M,
                                                                            endpoint.x + ENDPOINT_TOLERANCE_M,
                                                                            endpoint.y + ENDPOINT_TOLERANCE_M)))
                    for index in indices:
                        item = pending[int(index)]
                        line = item['line']
                        if 'car' not in element.get('modes', '').split(','):
                            continue
                        link = {**element.attrib, 'attributes': _attributes(element)}
                        if (start.distance(line) <= search_width and end.distance(line) <= search_width and
                                link['attributes'].get('osm:way:id') and
                                _same_street_name(item['source'].street, link['attributes'].get('osm:way:name'))):
                            failed, metrics, shape, direction = _match_geometry(link, line, item['job']['point'], nodes)
                            method = 'EXACT_H03_ENDPOINT_ARCLENGTH' if not failed else 'MODEL_LANE_CORRIDOR_H03_REGISTERED'
                            if not failed or _corridor_match(link, metrics, failed, model_lane_width):
                                metrics['modelHalfWidthMeters'] = search_width
                                matches[int(index)].append((link, metrics, shape, direction, method))
                        if part_index is not None and '-' in link['id'] and min(start.distance(line), end.distance(line)) <= ENDPOINT_TOLERANCE_M:
                            joined = _match_two_source_chain(link, item['job']['point'], int(item['stop'].str_idx),
                                                             item['part'], nodes, part_index)
                            if joined is not None:
                                _, metrics, shape, direction = joined
                                metrics['modelHalfWidthMeters'] = search_width
                                matches[int(index)].append((link, metrics, shape, direction, 'TWO_CONNECTED_H03_STREETS'))
                element.clear()
    for index, item in enumerate(pending):
        row, job, stop, raw, catalog = (item[key] for key in ('row', 'job', 'stop', 'raw', 'catalog'))
        if not job['stopId']:
            row['reasons'].append('REMAP_REQUIRES_EXPLICIT_STOP_ID')
            continue
        candidates = matches[index]
        exact = [candidate for candidate in candidates if candidate[4] in ('EXACT_H03_ENDPOINT_ARCLENGTH', 'TWO_CONNECTED_H03_STREETS')]
        if exact:
            candidates = exact
        groups = defaultdict(list)
        for candidate in candidates:
            link = candidate[0]
            # An inferred H03 chain is not a shared source-way identity. Keep
            # such directed links separate instead of certifying them as a pair.
            way = link['attributes'].get('osm:way:id') or ('UNVERIFIED_DIRECTED_LINK', link['id'])
            key = (tuple(sorted((link['from'], link['to']))), way)
            groups[key].append(candidate)
        if len(groups) != 1:
            row['alternatePhysicalRoadGroups'] = len(groups)
            row['reasons'].append('UNIQUE_HAGRID_ALTERNATIVE_NOT_FOUND')
            continue
        group = next(iter(groups.values()))
        if len(group) > 2 or (len(group) == 2 and (group[0][0]['from'] != group[1][0]['to'] or group[0][0]['to'] != group[1][0]['from'])):
            row['reasons'].append('ALTERNATIVE_DIRECTION_AMBIGUOUS')
            continue
        raw_start, raw_end = nodes[raw['from']], nodes[raw['to']]
        raw_dx, raw_dy = raw_end.x - raw_start.x, raw_end.y - raw_start.y
        def orientation(candidate):
            link = candidate[0]
            start, end = nodes[link['from']], nodes[link['to']]
            dx, dy = end.x - start.x, end.y - start.y
            return (raw_dx * dx + raw_dy * dy) / max(1e-9, math.hypot(raw_dx, raw_dy) * math.hypot(dx, dy))
        group.sort(key=orientation, reverse=True)
        anchor, metrics, shape, same_direction, method = group[0]
        if orientation(group[0]) < 0.8:
            row['reasons'].append('ALTERNATIVE_DIRECTION_UNRESOLVED')
            continue
        other = group[1] if len(group) == 2 else None
        if other is not None and (abs(float(other[0]['length']) - float(anchor['length'])) > LENGTH_TOLERANCE_M or
                                  shape.hausdorff_distance(other[2]) > ENDPOINT_TOLERANCE_M or
                                  other[3] == same_direction):
            row['reasons'].append('ALTERNATIVE_REVERSE_MISMATCH')
            continue
        fraction = shape.project(job['point']) / shape.length
        raw_side = stop.side if same_direction else ('left' if stop.side == 'right' else 'right')
        if not allow_street_crossing and raw_side == 'left' and other is None:
            row['reasons'].append('CUSTOMER_SIDE_REQUIRES_REVERSE_ONLY_UNSUPPORTED')
            continue
        approaches = [{'linkId': anchor['id'], 'fraction': fraction}]
        if other is not None:
            reverse_approach = {'linkId': other[0]['id'], 'fraction': 1.0 - fraction}
            if allow_street_crossing:
                approaches.append(reverse_approach)
            elif raw_side == 'left':
                approaches = [reverse_approach]
        row.update(status='supported', reasons=[], anchorLinkId=anchor['id'], reverseAvailable=other is not None,
                   sourceXY=[job['point'].x, job['point'].y], anchorOffsetMeters=job['point'].distance(shape),
                   originalLinkEndpointChordOffsetMeters=job['point'].distance(LineString((raw_start, raw_end))),
                   physicalOriginalLinkOffsetStatus='UNKNOWN_NO_ORIGINAL_CURVE',
                   anchorEvidence=method, approaches=approaches,
                   anchorGeometry=metrics)
        report['supportedJobs'] += 1
        catalog['links'][anchor['id']] = _curve_record(anchor, shape)
        if other is not None:
            catalog['links'][other[0]['id']] = _curve_record(other[0], LineString(list(shape.coords)[::-1]))
        catalog['jobs'].append({'jobId': job['id'], 'rawLinkId': raw['id'], 'anchorLinkId': anchor['id'],
                                'sourceXY': [job['point'].x, job['point'].y], 'sourceStopId': stop.stop_id,
                                'approaches': approaches, 'serviceMode': job['mode'],
                                'anchorEvidence': {'sourceStreetRow': int(stop.str_idx), 'sourcePart': item['part'],
                                                   'sourcePositionMeters': float(item['line'].project(job['point'])),
                                                   'anchorOffsetMeters': job['point'].distance(shape),
                                                   'modelHalfWidthMeters': metrics['modelHalfWidthMeters'],
                                                   'method': method}})


def _anchor_on_hagrid_branch(pending, nodes, reverse, report, allow_street_crossing, part_index):
    """Place a vehicle halt at an existing source-street/car-road junction."""
    if part_index is None:
        return
    parts, provenance, tree = part_index
    for item in pending:
        row, job, stop, raw, catalog, branch = (item[key] for key in ('row', 'job', 'stop', 'raw', 'catalog', 'line'))
        if not row['reasons'] or '-' in raw['id'] or not job['stopId']:
            continue
        way = raw['attributes'].get('osm:way:id')
        road_name = raw['attributes'].get('osm:way:name')
        if not way or not road_name or _same_street_name(item['source'].street, road_name):
            continue
        a, b = nodes[raw['from']], nodes[raw['to']]
        candidates = []
        for index in tree.query(a.buffer(ENDPOINT_TOLERANCE_M)):
            index = int(index)
            road = parts[index]
            if b.distance(road) > ENDPOINT_TOLERANCE_M or provenance[index][0] == int(stop.str_idx):
                continue
            # The raw link's embedded road name must name this H03 road.
            source_row = item['observations'].loc[provenance[index][0]]
            if not _same_street_name(source_row.street, road_name):
                continue
            contact = branch.intersection(road)
            if contact.geom_type == 'Point':
                junctions = [contact]
            elif contact.geom_type == 'MultiPoint':
                junctions = list(contact.geoms)
            elif branch.distance(road) <= ENDPOINT_TOLERANCE_M:
                junctions = [nearest_points(branch, road)[0]]
            else:
                continue
            for junction in junctions:
                if min(junction.distance(Point(branch.coords[0])),
                       junction.distance(Point(branch.coords[-1]))) > ENDPOINT_TOLERANCE_M:
                    continue
                anchor = road.interpolate(road.project(junction))
                if anchor.distance(branch) > ENDPOINT_TOLERANCE_M:
                    continue
                failed, metrics, shape, same_direction = _match_geometry(raw, road, anchor, nodes)
                if not failed:
                    candidates.append((provenance[index], anchor, metrics, shape, road, junction))
        # Coincident duplicate H03 observations do not create a unique access point.
        if len(candidates) != 1:
            row['branchJunctionCandidates'] = len(candidates)
            continue
        road_source, anchor, metrics, shape, road, junction = candidates[0]
        along = shape.project(anchor)
        before = shape.interpolate(max(0, along - 1.0))
        after = shape.interpolate(min(shape.length, along + 1.0))
        tx, ty = after.x - before.x, after.y - before.y
        jp, cp = branch.project(junction), branch.project(job['point'])
        if abs(cp - jp) <= 0.1:
            row['reasons'].append('BRANCH_SIDE_AMBIGUOUS')
            continue
        outward = branch.interpolate(jp + math.copysign(min(1.0, abs(cp - jp)), cp - jp))
        vx, vy = outward.x - junction.x, outward.y - junction.y
        cross = tx * vy - ty * vx
        if abs(cross) <= 0.1:
            row['reasons'].append('BRANCH_SIDE_AMBIGUOUS')
            continue
        branch_side = 'left' if cross > 0 else 'right'
        reverse_candidates = [candidate for candidate in reverse.get((raw['from'], raw['to']), [])
                              if candidate['id'] != raw['id'] and candidate['attributes'].get('osm:way:id') == way
                              and 'car' in candidate.get('modes', '').split(',')
                              and abs(float(candidate['length']) - float(raw['length'])) <= LENGTH_TOLERANCE_M]
        other = None
        if len(reverse_candidates) == 1:
            candidate = reverse_candidates[0]
            failed, _, other_shape, reverse_direction = _match_geometry(candidate, road, anchor, nodes)
            if not failed and shape.hausdorff_distance(other_shape) <= ENDPOINT_TOLERANCE_M and reverse_direction != same_direction:
                other = candidate
        if not allow_street_crossing and branch_side == 'left' and other is None:
            row['reasons'].append('BRANCH_CUSTOMER_SIDE_REQUIRES_REVERSE')
            continue
        fraction = shape.project(anchor) / shape.length
        approaches = [{'linkId': raw['id'], 'fraction': fraction}]
        if other is not None:
            alternate = {'linkId': other['id'], 'fraction': 1.0 - fraction}
            if allow_street_crossing:
                approaches.append(alternate)
            elif branch_side == 'left':
                approaches = [alternate]
        row.update(status='supported', reasons=[], anchorLinkId=raw['id'],
                   sourceXY=[job['point'].x, job['point'].y], anchorXY=[anchor.x, anchor.y],
                   sourceStreetXY=[[float(x), float(y)] for x, y in branch.coords],
                   sourceGeometryMethod='H03_BRANCH_JUNCTION', anchorEvidence='H03_BRANCH_JUNCTION_TO_ORIGINAL_CAR_LINK',
                   branchStreetToRoadGapMeters=junction.distance(anchor),
                   branchSourceArclengthToJunctionMeters=abs(cp - jp),
                   branchEntranceSide=branch_side, reverseAvailable=other is not None,
                   anchorGeometry=metrics, approaches=approaches,
                   accessStatus='MODEL_HALT_AT_HAGRID_STREET_JUNCTION_NO_EXTRA_TIME')
        report['supportedJobs'] += 1
        catalog['links'][raw['id']] = _curve_record(raw, shape)
        if other is not None:
            catalog['links'][other['id']] = _curve_record(other, LineString(list(shape.coords)[::-1]))
        catalog['jobs'].append({'jobId': job['id'], 'rawLinkId': raw['id'], 'anchorLinkId': raw['id'],
                                'sourceStopId': stop.stop_id, 'serviceMode': job['mode'],
                                'sourceXY': [job['point'].x, job['point'].y],
                                'anchorXY': [anchor.x, anchor.y],
                                'sourceStreetXY': [[float(x), float(y)] for x, y in branch.coords],
                                'approaches': approaches,
                                'anchorEvidence': {'method': 'H03_BRANCH_JUNCTION_TO_ORIGINAL_CAR_LINK',
                                                   'sourceStreetRow': int(stop.str_idx), 'sourcePart': item['part'],
                                                   'carStreetRow': road_source[0], 'carStreetPart': road_source[1],
                                                   'branchEntranceSide': branch_side,
                                                   'branchSourceArclengthToJunctionMeters': abs(cp - jp),
                                                   'branchStreetToRoadGapMeters': junction.distance(anchor),
                                                   'timeModel': 'ORIGINAL_SERVICE_DURATION_UNCHANGED'}})


def prepare(network, carriers, reference_stops, street_observations,
            allow_street_crossing=False, allow_legacy_coordinate_recovery=False,
            selected_jobs=None):
    """Return a complete audit and catalogs only when every requested job passes.

    `selected_jobs` is reserved for an explicitly labeled offline diagnostic.
    It never appears in the production CLI.
    """
    carrier_rows = read_carriers(carriers)
    raw_ids = {j['linkId'] for c in carrier_rows for j in c['jobs'] if j['linkId']}
    raw_ids.update(v.get('depotLinkId') for c in carrier_rows for v in c['vehicles'])
    model_lane_width = _network_lane_width(network)
    nodes, links, reverse = read_network(network, raw_ids)
    stops = gpd.read_parquet(reference_stops)
    observations = gpd.read_parquet(street_observations).set_index('source_row', verify_integrity=True)
    if stops.crs.to_epsg() != 25832 or observations.crs.to_epsg() != 25832:
        raise ValueError('Both demand geometry inputs must use EPSG:25832')
    stop_by_id = stops.set_index('stop_id', drop=False, verify_integrity=True)
    coordinates = list(zip(stops.geometry.x, stops.geometry.y))
    stop_tree = cKDTree(coordinates)
    report = {'schemaVersion': 1, 'method': 'HAGRID_DEMAND_AXIS_ENDPOINT_ARCLENGTH_MATCH',
              'physicalAccessStatus': 'AXIS_MATCH_INFERRED_NOT_INDEPENDENTLY_CERTIFIED',
              'diagnosticSelection': selected_jobs is not None,
              'allowStreetCrossing': bool(allow_street_crossing),
              'allowLegacyCoordinateRecovery': bool(allow_legacy_coordinate_recovery),
              'networkEffectiveLaneWidthMeters': model_lane_width,
              'totalJobs': 0, 'requestedJobs': 0, 'supportedJobs': 0, 'carriers': []}
    catalogs = {}
    pending_remaps = []
    part_index = None
    for carrier in carrier_rows:
        cid = carrier['id']
        selected = set(selected_jobs.get(cid, [])) if selected_jobs is not None else None
        if selected is not None and not selected <= {j['id'] for j in carrier['jobs']}:
            raise ValueError(f'{cid}: diagnostic selection contains missing jobs')
        depots = {v.get('depotLinkId') for v in carrier['vehicles']}
        carrier_report = {'carrierId': cid, 'input': str(carrier['path']), 'depotLinkIds': sorted(depots), 'jobs': []}
        catalog = {'schemaVersion': 1, 'carrierId': cid, 'depot': {'linkId': next(iter(depots)) if len(depots) == 1 else ''},
                   'links': {}, 'jobs': [], 'provenance': {'source': 'HAGRID_INTERNAL_DEMAND_STREET_CURVE',
                    'physicalAccessStatus': 'INFERRED_FROM_DEMAND_AXIS_AND_NETWORK_TOPOLOGY'}}
        report['carriers'].append(carrier_report)
        for job in carrier['jobs']:
            report['totalJobs'] += 1
            row = {'jobId': job['id'], 'rawLinkId': job['linkId'], 'reasons': [], 'status': 'unsupported'}
            carrier_report['jobs'].append(row)
            if selected is not None and job['id'] not in selected:
                row['status'] = 'excluded_by_explicit_diagnostic_selection'
                continue
            report['requestedJobs'] += 1
            reasons = row['reasons']
            if job['mode'] != 'HOME':
                _prepare_fixed_ooh(job, row, catalog, stop_by_id, links, depots, report)
                continue
            if job['point'] is None: reasons.append('MISSING_SOURCE_COORDINATE')
            link = links.get(job['linkId'])
            if link is None: reasons.append('RAW_LINK_MISSING')
            elif 'car' not in link.get('modes', '').split(','): reasons.append('RAW_LINK_NOT_CAR')
            if len(depots) != 1 or next(iter(depots), None) not in links: reasons.append('DEPOT_LINK_UNAVAILABLE')
            stop = None
            if job['stopId']:
                if job['stopId'] not in stop_by_id.index: reasons.append('STOP_ID_NOT_FOUND')
                else:
                    stop = stop_by_id.loc[job['stopId']]
                    row['stopJoin'] = 'EXPLICIT_XML_ID'
                    if job['point'] is not None and stop.geometry.distance(job['point']) > STOP_JOIN_TOLERANCE_M:
                        reasons.append('STOP_COORDINATE_MISMATCH')
            elif not allow_legacy_coordinate_recovery:
                reasons.append('MISSING_STOP_ID_LEGACY_RECOVERY_DISABLED')
            elif job['point'] is not None:
                neighbors = stop_tree.query_ball_point((job['point'].x, job['point'].y), STOP_JOIN_TOLERANCE_M)
                if len(neighbors) != 1: reasons.append('LEGACY_COORDINATE_STOP_JOIN_NOT_UNIQUE')
                else:
                    stop = stops.iloc[neighbors[0]]
                    row['stopJoin'] = 'UNIQUE_COORDINATE_LEGACY_RECOVERY'
            if stop is not None:
                row.update(sourceStopId=stop.stop_id, sourceRow=int(stop.str_idx), sourceSide=stop.side,
                           sideProvenance='EXPLICIT_XML_VALIDATED' if job['attributes'].get('side') else
                                          'RECOVERED_REFERENCE_STOP_LEGACY_NOT_PHYSICAL_ACCESS_PROOF')
                if stop.str_idx not in observations.index: reasons.append('SOURCE_STREET_ROW_MISSING')
                if stop.side not in ('left', 'right'): reasons.append('UNKNOWN_CUSTOMER_SIDE')
                for attr, expected, code in (('str_idx', int(stop.str_idx), 'EXPORTED_STREET_ROW_MISMATCH'),
                                             ('part', None if math.isnan(stop.part) else int(stop.part), 'EXPORTED_PART_MISMATCH'),
                                             ('side', stop.side, 'EXPORTED_SIDE_MISMATCH')):
                    value = job['attributes'].get(attr)
                    if value is not None:
                        try:
                            actual = int(float(value)) if attr != 'side' else value
                        except (TypeError, ValueError):
                            actual = value
                        if actual != expected: reasons.append(code)
                if not job['attributes'].get('side') and not allow_legacy_coordinate_recovery:
                    reasons.append('MISSING_SOURCE_SIDE_LEGACY_RECOVERY_DISABLED')
            if reasons or stop is None or link is None:
                continue
            source = observations.loc[int(stop.str_idx)]
            geom = source.geometry
            if geom is None or geom.is_empty or geom.geom_type not in ('LineString', 'MultiLineString'):
                reasons.append('SOURCE_STREET_GEOMETRY_INVALID'); continue
            part = int(stop.part) if not math.isnan(stop.part) else 0
            if geom.geom_type == 'MultiLineString':
                if part < 0 or part >= len(geom.geoms): reasons.append('SOURCE_PART_MISSING'); continue
                line = geom.geoms[part]
            elif part != 0:
                reasons.append('SOURCE_PART_MISSING'); continue
            else:
                line = geom
            row.update(sourcePart=part, sourceStreet=source.street)
            position = job['attributes'].get('position_m')
            if position is not None:
                try:
                    if abs(float(position) - line.project(job['point'])) > STOP_JOIN_TOLERANCE_M:
                        reasons.append('EXPORTED_POSITION_MISMATCH')
                except ValueError:
                    reasons.append('EXPORTED_POSITION_MISMATCH')
            if reasons: continue
            if link['from'] not in nodes or link['to'] not in nodes:
                reasons.append('RAW_LINK_NODE_MISSING'); continue
            failed, metrics, shape, same_direction = _match_geometry(link, line, job['point'], nodes)
            multiparts = False
            if failed and geom.geom_type == 'MultiLineString':
                joined = _match_multiline(link, geom, part, job['point'], nodes)
                if joined is not None:
                    failed, metrics, shape, same_direction = joined
                    multiparts = True
            two_streets = False
            if failed and '-' in link['id']:
                if part_index is None:
                    part_index = _source_part_index(observations)
                joined = _match_two_source_chain(link, job['point'], int(stop.str_idx), part, nodes, part_index)
                if joined is not None:
                    failed, metrics, shape, same_direction = joined
                    two_streets = True
            row['rawGeometry'] = metrics
            row['sourceGeometryMethod'] = ('TWO_CONNECTED_H03_STREETS' if two_streets else
                                           'CONNECTED_H03_PARTS' if multiparts else 'SINGLE_H03_PART')
            reasons.extend(failed)
            raw_way = link['attributes'].get('osm:way:id')
            candidates = [r for r in reverse.get((link['from'], link['to']), [])
                          if r['id'] != link['id'] and raw_way and r['attributes'].get('osm:way:id') == raw_way
                          and 'car' in r.get('modes', '').split(',')
                          and abs(float(r['length']) - float(link['length'])) <= LENGTH_TOLERANCE_M]
            other = None
            row['reverseAvailable'] = False
            verified = []
            for candidate in candidates:
                if two_streets:
                    reversed_match = _match_two_source_chain(candidate, job['point'], int(stop.str_idx), part, nodes, part_index)
                    if reversed_match is None:
                        reverse_failed = ['REVERSE_SOURCE_STREET_CHAIN_UNRESOLVED']
                        reverse_metrics, reverse_shape, reverse_same_direction = {}, shape, same_direction
                    else:
                        reverse_failed, reverse_metrics, reverse_shape, reverse_same_direction = reversed_match
                elif multiparts:
                    reversed_match = _match_multiline(candidate, geom, part, job['point'], nodes)
                    if reversed_match is None:
                        reverse_failed = ['REVERSE_SOURCE_PARTS_UNRESOLVED']
                        reverse_metrics, reverse_shape, reverse_same_direction = {}, shape, same_direction
                    else:
                        reverse_failed, reverse_metrics, reverse_shape, reverse_same_direction = reversed_match
                else:
                    reverse_failed, reverse_metrics, reverse_shape, reverse_same_direction = _match_geometry(candidate, line, job['point'], nodes)
                if not reverse_failed and shape.hausdorff_distance(reverse_shape) <= ENDPOINT_TOLERANCE_M and same_direction != reverse_same_direction:
                    verified.append((candidate, reverse_metrics))
            if len(verified) == 1:
                other, row['reverseGeometry'] = verified[0]
                row['reverseAvailable'] = True
                row['reverseLinkId'] = other['id']
            else:
                row['reverseCandidates'] = [r['id'] for r in candidates]
                row['reverseUnavailableReason'] = 'UNIQUE_SAME_WAY_REVERSE_MISSING'
            if reasons:
                if failed:
                    pending_remaps.append({'row': row, 'job': job, 'stop': stop, 'raw': link,
                                           'catalog': catalog, 'source': source, 'line': line, 'part': part,
                                           'observations': observations})
                continue
            fraction = shape.project(job['point']) / shape.length
            fraction = min(1.0, max(0.0, fraction))
            raw_side = stop.side if same_direction else ('left' if stop.side == 'right' else 'right')
            if not allow_street_crossing and raw_side == 'left' and other is None:
                reasons.append('CUSTOMER_SIDE_REQUIRES_REVERSE_ONLY_UNSUPPORTED')
                row['directedCustomerSide'] = raw_side
                continue
            approaches = [{'linkId': link['id'], 'fraction': fraction}]
            if allow_street_crossing and other is not None:
                approaches.append({'linkId': other['id'], 'fraction': 1.0 - fraction})
            elif not allow_street_crossing and raw_side == 'left':
                approaches = [{'linkId': other['id'], 'fraction': 1.0 - fraction}]
            row.update(status='supported', reasons=[], directedCustomerSide=raw_side,
                       crossingPolicy='ALLOWED_FOR_DIAGNOSTIC' if allow_street_crossing else 'CUSTOMER_SIDE_ONLY',
                       approaches=approaches, roadGeometryEvidence='HAGRID_DEMAND_AXIS_ENDPOINT_AND_LENGTH_MATCH')
            report['supportedJobs'] += 1
            catalog['jobs'].append({'jobId': job['id'], 'rawLinkId': link['id'], 'sourceXY': [job['point'].x, job['point'].y],
                                    'approaches': approaches,
                                    'serviceMode': job['mode'], 'sourceStopId': stop.stop_id,
                                    'sideEvidence': {'sourceSide': stop.side, 'rawDirectedSide': raw_side,
                                                     'status': 'DEMAND_LABEL_ORIENTED_NOT_PHYSICAL_LEGALITY_PROOF'}})
            catalog['links'][link['id']] = _curve_record(link, shape)
            if other is not None:
                catalog['links'][other['id']] = _curve_record(other, LineString(list(shape.coords)[::-1]))
        catalogs[cid] = catalog
    if pending_remaps and part_index is None:
        part_index = _source_part_index(observations)
    _remap_exact(network, pending_remaps, nodes, report, allow_street_crossing, model_lane_width, part_index)
    _anchor_on_hagrid_branch(pending_remaps, nodes, reverse, report, allow_street_crossing, part_index)
    if report['requestedJobs'] != report['supportedJobs']:
        catalogs = {}
    return report, catalogs


def _resolve(config_path, value):
    path = Path(value)
    return path if path.is_absolute() else Path(config_path).resolve().parent / path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--network', type=Path, required=True)
    parser.add_argument('--carriers', type=Path, nargs='+', required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-street-crossing', choices=('true', 'false'))
    args = parser.parse_args(argv)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / 'catalogs.json'
    manifest.unlink(missing_ok=True)
    try:
        config = json.loads(args.config.read_text(encoding='utf-8'))
        for key in ('allowStreetCrossing', 'allowLegacyCoordinateRecovery'):
            if key in config and type(config[key]) is not bool:
                raise ValueError(f'{key} must be a JSON boolean')
        if args.allow_street_crossing is not None:
            config['allowStreetCrossing'] = args.allow_street_crossing == 'true'
        report, catalogs = prepare(args.network, args.carriers,
                                   _resolve(args.config, config['referenceStops']),
                                   _resolve(args.config, config['streetObservations']),
                                   allow_street_crossing=config.get('allowStreetCrossing', False),
                                   allow_legacy_coordinate_recovery=config.get('allowLegacyCoordinateRecovery', False))
        report['inputs'] = {str(p): sha256(p) for p in [args.network, *args.carriers,
                            _resolve(args.config, config['referenceStops']),
                            _resolve(args.config, config['streetObservations'])]}
    except Exception as exc:
        report = {'schemaVersion': 1, 'status': 'INPUT_ERROR', 'failures': [f'{type(exc).__name__}: {exc}']}
        catalogs = {}
    success = bool(catalogs) and report['requestedJobs'] == report['supportedJobs']
    report['status'] = 'READY' if success else report.get('status', 'UNSUPPORTED_JOBS')
    (output / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    if success:
        entries = {}
        for cid, catalog in catalogs.items():
            name = f'{cid}.json'
            (output / name).write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
            entries[cid] = name
        manifest.write_text(json.dumps({'catalogs': entries}, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': report['status'], 'report': str(output / 'report.json'),
                      'manifest': str(manifest) if success else None}))
    return 0 if success else 2


if __name__ == '__main__':
    sys.exit(main())
