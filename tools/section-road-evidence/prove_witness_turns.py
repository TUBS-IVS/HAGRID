"""Certify directional raw turns from OSM node and way chains."""

import argparse
from collections import Counter, defaultdict
from functools import lru_cache
from heapq import heappop, heappush
from itertools import pairwise
import json
import math
from pathlib import Path

from pyproj import Transformer

from extract_witness_sources import BASE, HERE, read, required_bounds, sha256, witness_rows

# Network lengths preserve double-precision sums. Centimetre tolerance can
# conflate distinct curves; this is a numerical equality check, not a snap.
LENGTH_TOLERANCE = 0.00001
CATALOG_LENGTH_TOLERANCE = 0.02
ENDPOINT_TOLERANCE = 0.02
MIN_SEPARATION = 0.10
MAX_EXPANSIONS = 100_000
CAR_WAYS = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    "service", "living_street", "road", "track",
}

def direction(tags):
    value = tags.get("oneway")
    if value in ("yes", "1", "true"):
        return 1
    if value == "-1":
        return -1
    if value in ("no", "0", "false"):
        return 0
    return 1 if tags.get("junction") in ("roundabout", "circular") else 0


class RoadSource:
    def __init__(self, path, required, overlay_path=None, minimum_link_length=0.0):
        path = path.resolve()
        self.path = str(path)
        if not math.isfinite(minimum_link_length) or minimum_link_length < 0:
            raise ValueError('Minimum link length must be finite and nonnegative')
        self.minimum_link_length = minimum_link_length
        self.binding_endpoints = None
        data = read(path)
        self.bounds = data["EPSG25832Bounds"]
        self.provenance = {"path": str(path), "sha256": sha256(path), "pbf": data["source"],
                           "EPSG25832Bounds": self.bounds}
        self.provenance['networkLengthTransform'] = {'minimumLinkLengthMeters': minimum_link_length}
        self.provenance['bindingTolerancesMeters'] = {
            'length': LENGTH_TOLERANCE, 'endpoint': ENDPOINT_TOLERANCE}
        if any(self.bounds[i] > required[i] for i in (0, 1)) or any(
                self.bounds[i] < required[i] for i in (2, 3)):
            raise ValueError(f"Source extent does not cover every witness: {path}")
        if overlay_path is not None:
            overlay_path = overlay_path.resolve()
            overlay = read(overlay_path)
            self.path += f"#{overlay_path.name}"
            self.binding_endpoints = frozenset(overlay["bindingEndpoints"])
            self.provenance["overlay"] = {
                "path": str(overlay_path), "sha256": sha256(overlay_path), "snapshot": overlay["snapshot"],
                "scope": overlay["scope"], "bindingEndpoints": overlay["bindingEndpoints"],
            }
            data["nodes"].update(overlay["nodes"])
            data["ways"].update(overlay["ways"])
        project = Transformer.from_crs(4326, 25832, always_xy=True)
        self.coords = {node: project.transform(*position) for node, position in data["nodes"].items()}
        self.ways = {}
        self.edges = defaultdict(dict)
        self.eligible = defaultdict(set)
        for way, item in data["ways"].items():
            tags = item["tags"]
            nodes = tuple(map(str, item["nodes"]))
            self.ways[way] = nodes
            if tags.get("highway") not in CAR_WAYS and tags.get("motor_vehicle") not in ("yes", "designated"):
                continue
            oneway = direction(tags)
            for a, b in pairwise(nodes):
                if a == b or a not in self.coords or b not in self.coords:
                    continue
                length = math.dist(self.coords[a], self.coords[b])
                if length <= 0:
                    continue
                self.edges[a][b] = self.edges[b][a] = length
                if oneway >= 0:
                    self.eligible[a, b].add(way)
                if oneway <= 0:
                    self.eligible[b, a].add(way)

    def lower_distances(self, end, cutoff):
        distances, queue = {end: 0}, [(0, end)]
        while queue:
            length, node = heappop(queue)
            if length != distances[node]:
                continue
            for other, edge_length in self.edges[node].items():
                cost = length + edge_length
                if cost <= cutoff and cost < distances.get(other, math.inf):
                    distances[other] = cost
                    heappush(queue, (cost, other))
        return distances

    def paths(self, start, end, maximum, minimum=None):
        if minimum is None:
            minimum = maximum - 2 * LENGTH_TOLERANCE
        lower = self.lower_distances(end, maximum)
        if start not in lower:
            return [], 0
        stack, finished, expanded = [(0, (start,), frozenset())], [], 0
        while stack:
            cost, path, used_edges = stack.pop()
            tail = path[-1]
            if tail == end:
                if cost >= minimum:
                    finished.append(path)
            expanded += 1
            if expanded > MAX_EXPANSIONS:
                return None, expanded
            for other, edge_length in sorted(self.edges[tail].items()):
                if ((tail, other) in used_edges or (len(path) > 1 and other == path[-2])
                        or not self.eligible.get((tail, other))):
                    continue
                advanced = cost + edge_length
                if advanced + lower.get(other, math.inf) <= maximum + 1e-9:
                    stack.append((advanced, (*path, other), used_edges | {(tail, other)}))
        return finished, expanded

    def endpoint(self, raw, xy, corroborated):
        if raw in self.coords:
            return raw
        if not corroborated:
            return None
        aliases = [node for node, candidate in self.coords.items() if math.dist(candidate, xy) <= ENDPOINT_TOLERANCE]
        return aliases[0] if len(aliases) == 1 else None

    @lru_cache(maxsize=None)
    def bind(self, raw_start, raw_end, start_xy, end_xy, raw_length, known_way, start_corroborated, end_corroborated):
        start = self.endpoint(raw_start, start_xy, start_corroborated)
        end = self.endpoint(raw_end, end_xy, end_corroborated)
        if start == end or start is None or end is None:
            return {"status": "source-endpoint-unavailable"}
        offsets = [math.dist(self.coords[start], start_xy), math.dist(self.coords[end], end_xy)]
        if max(offsets) > ENDPOINT_TOLERANCE:
            return {"status": "source-endpoint-offset", "endpointOffsets": offsets}
        if known_way:
            way = self.ways.get(known_way, ())
            candidates = [
                way[i:j + 1] if i < j else way[j:i + 1][::-1]
                for i, node in enumerate(way) if node == start
                for j, other in enumerate(way) if other == end
            ]
            expanded = 0
        else:
            minimum = 0.0 if abs(raw_length - self.minimum_link_length) <= LENGTH_TOLERANCE else None
            candidates, expanded = self.paths(start, end, raw_length + LENGTH_TOLERANCE, minimum)
            if candidates is None:
                return {"status": "path-expansion-limit", "pathsExpanded": expanded}
        bound = []
        for path in sorted(set(candidates)):
            if len(path) < 2 or any(node not in self.coords for node in path):
                continue
            length = math.fsum(math.dist(self.coords[a], self.coords[b]) for a, b in pairwise(path))
            modeled_length = max(length, self.minimum_link_length)
            if abs(modeled_length - raw_length) > LENGTH_TOLERANCE:
                continue
            ways = [sorted(self.eligible.get((a, b), ())) for a, b in pairwise(path)]
            legal = all(known_way in options if known_way else options for options in ways)
            bound.append({
                "nodes": path,
                "directionalWays": ways,
                "firstXY": self.coords[path[1]],
                "lastXY": self.coords[path[-2]],
                "lengthMeters": length,
                "modeledLengthMeters": modeled_length,
                "modeledLengthErrorMeters": modeled_length - raw_length,
                "lengthErrorMeters": length - raw_length,
                "supportedDirection": legal,
            })
        return {"status": "bound" if any(path["supportedDirection"] for path in bound) else "no-exact-directional-chain",
                "sourceEndpoints": [start, end], "endpointOffsets": offsets, "pathsExpanded": expanded, "chains": bound}


def compare(incoming, outgoing, junction_xy):
    if incoming["nodes"][-2] == outgoing["nodes"][1]:
        return "TURN"
    inbound_ray = [point - center for point, center in zip(incoming["lastXY"], junction_xy)]
    outbound_ray = [point - center for point, center in zip(outgoing["firstXY"], junction_xy)]
    a, b = math.hypot(*inbound_ray), math.hypot(*outbound_ray)
    if min(a, b) == 0:
        return "unresolved-zero-length-terminal-segment"
    dot = sum(left * right for left, right in zip(inbound_ray, outbound_ray))
    cross = abs(inbound_ray[0] * outbound_ray[1] - inbound_ray[1] * outbound_ray[0])
    # Distinct node IDs may still describe overlapping pavement. Require observed
    # separation at the scale of the shorter terminal segment.
    if dot > 0 and cross / max(a, b) < MIN_SEPARATION:
        return "unresolved-nearly-overlapping-rays"
    return "NOT_TURN"


def catalog_neighbor(links, raw, start, end, is_incoming):
    neighbors = set()
    for catalog in links:
        road = catalog.get(raw)
        if not road or abs(road["geometryLengthMeters"] - road["lengthMeters"]) > CATALOG_LENGTH_TOLERANCE:
            continue
        nodes = road["geometrySource"].get("nodeIds", ())
        if len(nodes) >= 2 and str(nodes[0]) == start and str(nodes[-1]) == end:
            neighbors.add(str(nodes[-2] if is_incoming else nodes[1]))
    return neighbors


def matches_catalog_segment(chain, expected_neighbors, coords, is_incoming):
    nodes = chain["nodes"][::-1] if is_incoming else chain["nodes"]
    junction = coords[nodes[0]]
    for index, node in enumerate(nodes[1:], start=1):
        if node not in expected_neighbors:
            continue
        x, y = (point - origin for point, origin in zip(coords[node], junction))
        length = math.hypot(x, y)
        if length <= 0:
            continue
        previous = 0
        for between in nodes[1:index]:
            dx, dy = (point - origin for point, origin in zip(coords[between], junction))
            longitudinal = (dx * x + dy * y) / length
            lateral = abs(dx * y - dy * x) / length
            if not previous < longitudinal < length or lateral > ENDPOINT_TOLERANCE:
                break
            previous = longitudinal
        else:
            return True
    return False


def same_terminal_segment(chain, coords, other, other_coords, is_incoming):
    """Require a shared source node and straight, monotone subdivision on both sides."""
    nodes = chain['nodes'][::-1] if is_incoming else chain['nodes']
    other_nodes = other['nodes'][::-1] if is_incoming else other['nodes']
    if math.dist(coords[nodes[0]], other_coords[other_nodes[0]]) > ENDPOINT_TOLERANCE:
        return False
    common = set(nodes[1:]).intersection(other_nodes[1:])
    return any(math.dist(coords[node], other_coords[node]) <= ENDPOINT_TOLERANCE
               and matches_catalog_segment(chain, {node}, coords, is_incoming)
               and matches_catalog_segment(other, {node}, other_coords, is_incoming)
               for node in common)


def classify(row, sources, catalogs):
    start, end = row["fromNode"], row["toNode"]
    endpoints = {start, end}
    sources = [source for source in sources if source.binding_endpoints is None or source.binding_endpoints == endpoints]
    a = float(row["fromX"]), float(row["fromY"])
    b = float(row["toX"]), float(row["toY"])
    from_length, to_length = float(row["fromLength"]), float(row["toLength"])
    incoming_catalog = catalog_neighbor(catalogs, row["fromRaw"], start, end, True)
    outgoing_catalog = catalog_neighbor(catalogs, row["toRaw"], end, start, False)
    start_corroborated = any(start in source.coords and math.dist(source.coords[start], a) <= ENDPOINT_TOLERANCE
                             for source in sources)
    end_corroborated = any(end in source.coords and math.dist(source.coords[end], b) <= ENDPOINT_TOLERANCE
                           for source in sources)
    decisions, observations = set(), []
    incoming_common, outgoing_common = [], []
    for source in sources:
        incoming = source.bind(start, end, a, b, from_length, row["fromWay"], start_corroborated, end_corroborated)
        outgoing = source.bind(end, start, b, a, to_length, row["toWay"], end_corroborated, start_corroborated)
        observed = {"source": str(source.path), "incoming": incoming, "outgoing": outgoing}
        observations.append(observed)
        if "path-expansion-limit" in (incoming["status"], outgoing["status"]):
            decisions.add("unresolved-path-expansion-limit")
        if incoming["status"] != "bound" or outgoing["status"] != "bound":
            continue
        in_chains, out_chains = incoming["chains"], outgoing["chains"]
        incoming_common.extend((chain, source.coords) for chain in in_chains)
        outgoing_common.extend((chain, source.coords) for chain in out_chains)
        if incoming_catalog and any(not matches_catalog_segment(chain, incoming_catalog, source.coords, True)
                                    for chain in in_chains):
            decisions.add("unresolved-incoming-prepared-geometry-conflict")
        if outgoing_catalog and any(not matches_catalog_segment(chain, outgoing_catalog, source.coords, False)
                                    for chain in out_chains):
            decisions.add("unresolved-outgoing-prepared-geometry-conflict")
        local = {
            compare(left, right, source.coords[incoming["sourceEndpoints"][1]])
            for left in in_chains for right in out_chains
        }
        decisions.update(local)
        observed["decisions"] = sorted(local)
    if decisions:
        for source, observed in zip(sources, observations):
            incoming, outgoing = observed["incoming"], observed["outgoing"]
            if incoming["status"] == "bound" and any(not any(
                    same_terminal_segment(chain, source.coords, other, coords, True)
                    for other, coords in incoming_common) for chain in incoming["chains"]):
                decisions.add("unresolved-incoming-source-conflict")
            if outgoing["status"] == "bound" and any(not any(
                    same_terminal_segment(chain, source.coords, other, coords, False)
                    for other, coords in outgoing_common) for chain in outgoing["chains"]):
                decisions.add("unresolved-outgoing-source-conflict")
    if decisions in ({"TURN"}, {"NOT_TURN"}):
        kind = next(iter(decisions))
    else:
        kind = None
    return {
        "kind": kind,
        "status": kind or ("unresolved-competing-geometry" if decisions else "unresolved-no-common-source-chain"),
        "decisions": sorted(decisions),
        "preparedIncomingPreviousNodes": sorted(incoming_catalog),
        "preparedOutgoingNextNodes": sorted(outgoing_catalog),
        "sources": observations,
    }

