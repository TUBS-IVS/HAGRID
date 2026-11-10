"""Extract all OSM road edges potentially usable in the observed raw-link curves."""

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import sys

from pyproj import Transformer

HERE = Path(__file__).resolve().parent
BASE = HERE.parent

SOURCES = {"2021": "osm2021", "upstream": "upstreamDetailedOsm"}
MARGIN_METERS = 0.10


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def witness_rows(paths):
    for path in paths:
        with path.open(encoding="utf-8", newline="") as source:
            yield from csv.DictReader(source, delimiter="\t")


def required_bounds(witnesses):
    boxes = []
    for row in witnesses:
        x = (float(row["fromX"]) + float(row["toX"])) / 2
        y = (float(row["fromY"]) + float(row["toY"])) / 2
        radius = max(float(row["fromLength"]), float(row["toLength"])) / 2 + MARGIN_METERS
        if not math.isfinite(radius) or radius <= 0:
            raise ValueError(f"Invalid length for {row['fromRaw']} -> {row['toRaw']}")
        boxes.append((x - radius, y - radius, x + radius, y + radius))
    if not boxes:
        raise ValueError("No witnesses")
    return [min(box[0] for box in boxes), min(box[1] for box in boxes),
            max(box[2] for box in boxes), max(box[3] for box in boxes)]


def extract(source, bounds):
    import osmium

    path = Path(source["path"])
    if sha256(path) != source["sha256"]:
        raise ValueError(f"Source hash mismatch: {path}")
    # Any path point P obeys |A-P| + |P-B| <= path length. Therefore each
    # coordinate is within path_length / 2 of the corresponding midpoint.
    geographic = Transformer.from_crs(25832, 4326, always_xy=True).transform_bounds(*bounds)
    longitude0, latitude0, longitude1, latitude1 = geographic

    class ScopedRoads(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.nodes = {}
            self.ways = {}
            self.ways_started = False

        def node(self, node):
            if self.ways_started:
                raise ValueError("Source PBF has nodes after ways. A two-pass extract is required.")
            if node.location.valid():
                longitude, latitude = node.location.lon, node.location.lat
                if longitude0 <= longitude <= longitude1 and latitude0 <= latitude <= latitude1:
                    self.nodes[node.id] = [longitude, latitude]

        def way(self, way):
            self.ways_started = True
            if not way.tags.get("highway"):
                return
            nodes = [node.ref for node in way.nodes]
            if any(left in self.nodes and right in self.nodes for left, right in zip(nodes, nodes[1:])):
                self.ways[way.id] = {
                    "nodes": nodes,
                    "tags": {tag.k: tag.v for tag in way.tags},
                }

    roads = ScopedRoads()
    roads.apply_file(str(path))
    retained = {node for way in roads.ways.values() for node in way["nodes"]}
    return {
        "source": source,
        "EPSG25832Bounds": bounds,
        "WGS84Bounds": geographic,
        "nodes": {node: position for node, position in roads.nodes.items() if node in retained},
        "ways": roads.ways,
    }

