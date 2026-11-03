# -*- coding: utf-8 -*-
"""Locations OUTSIDE the MATSim run dir that the KPI layer reads: the run's
preprocessing dir (hagrid-output/<run_id>: drt_fleet, carriers, parcel-demand
provenance) and the input root (input/: service area, depots).

HAGRID_OUTPUT_ROOT / -Dhagrid.output.root (spec 2026-11-03 section 3.2) moves
ONLY the MATSim tree, so the old sibling convention
``<run_dir>/../../hagrid-output`` and ``<run_dir>/../../input`` silently misses
under an override root. Resolution order, first hit wins:

1. an explicit path from the caller (where the call site has one),
2. the absolute path RunMetadataWriter records in run_metadata.json
   (``hagrid_output_dir`` / ``input_root``) -- only if it exists on THIS
   machine (a run dir copied from another machine records that machine's path),
3. the old sibling convention (runs written before the keys existed).

At the default root (2) and (3) name the same directory, so no KPI of a
default-root run changes.
"""
from pathlib import Path

from run_meta import read_metadata_json

KEY_HAGRID_OUTPUT_DIR = "hagrid_output_dir"
KEY_INPUT_ROOT = "input_root"


def _ascii(s):
    return str(s).encode("ascii", "replace").decode("ascii")


def _from_metadata(run_dir, key):
    """Existing path recorded under `key`, or None (no file, no key, unreadable
    file, or a path that does not exist here -- the last one says so)."""
    try:
        j = read_metadata_json(run_dir)
    except Exception as e:
        print("[run_paths] run_metadata.json unreadable (" + _ascii(e) + ")"
              + " -- using the sibling convention for " + key)  # ASCII only
        return None
    if not j or not j.get(key):
        return None
    p = Path(j[key])
    if p.exists():
        return p
    print("[run_paths] NOTE: run_metadata.json " + key + " = " + _ascii(p)
          + " does not exist here -- using the sibling convention")  # ASCII only
    return None


def hagrid_output_dir(run_dir, prefix, explicit=None):
    """Preprocessing dir hagrid-output/<prefix> of the run in `run_dir`."""
    if explicit:
        return Path(explicit)
    p = _from_metadata(run_dir, KEY_HAGRID_OUTPUT_DIR)
    if p is not None:
        if p.name == prefix:
            return p
        print("[run_paths] NOTE: run_metadata.json " + KEY_HAGRID_OUTPUT_DIR + " names run "
              + _ascii(p.name) + ", not " + _ascii(prefix)
              + " -- using the sibling convention")  # ASCII only
    return Path(run_dir).parent.parent / "hagrid-output" / prefix


def input_root(run_dir, explicit=None):
    """Input root (the dir holding lausitz/, hannover/) of the run in `run_dir`."""
    if explicit:
        return Path(explicit)
    p = _from_metadata(run_dir, KEY_INPUT_ROOT)
    if p is not None:
        return p
    return Path(run_dir) / ".." / ".." / "input"
