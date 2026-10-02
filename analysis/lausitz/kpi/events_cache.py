# -*- coding: utf-8 -*-
"""Single pass over output_events.xml.gz producing two per-run line caches:
- <prefix>.drt_events_filtered.txt      (same name + filter as build_drt_dashboard.py)
- <prefix>.freight_events_filtered.txt  (freight 'entered link' + actstart/actend
  lines -- richer than just service-actstarts, see _freight_wanted() below, to
  support structured freight parsing / maps in later tasks)
If either cache is missing, BOTH are rebuilt in one pass (~1-2 min on a 90 MB
events file; the drt rebuild is byte-identical, so sharing with the legacy
dashboard stays safe). The two predicates are evaluated independently per
line (no elif), so a line matching both (e.g. a shared drt_/freight_ vehicle
id) lands in BOTH caches.

A third file, <prefix>.events_cache_stamp.json, records which events file
(size, mtime, CACHE_VERSION) the pair was built from, and is written LAST,
after both caches were renamed into place. Existing caches are reused only
under a matching stamp (review 2026-10-02 #10): they used to be reused
whenever both files existed, which includes a pair left half-written by an
aborted build and a pair from an events file that was since replaced."""
from pathlib import Path
import gzip
import json
import os

DRT_SUFFIX = ".drt_events_filtered.txt"
FREIGHT_SUFFIX = ".freight_events_filtered.txt"
STAMP_SUFFIX = ".events_cache_stamp.json"
#: Bump when a filter predicate changes, so caches built with the old filter
#: are rebuilt instead of trusted.
CACHE_VERSION = 1


def _freight_wanted(line):
    if "freight" not in line:
        return False
    if 'type="entered link"' in line:
        return True
    return 'type="actstart"' in line or 'type="actend"' in line


def _source_id(events):
    st = events.stat()
    return {"cache_version": CACHE_VERSION, "events_size": st.st_size,
            "events_mtime_ns": st.st_mtime_ns}


def _read_stamp(stamp):
    try:
        return json.loads(stamp.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def ensure_caches(run_dir, prefix):
    run_dir = Path(run_dir)
    drt = run_dir / (prefix + DRT_SUFFIX)
    frt = run_dir / (prefix + FREIGHT_SUFFIX)
    stamp = run_dir / (prefix + STAMP_SUFFIX)
    events = run_dir / (prefix + ".output_events.xml.gz")
    if drt.exists() and frt.exists():
        if not events.exists():
            # Pruned run (events deleted to save disk): the caches cannot be
            # checked against anything any more, and they are all that is left.
            print("[events_cache] " + prefix + ": events file absent, "
                  "reusing caches unverified")  # ASCII only
            return drt, frt
        if _read_stamp(stamp) == _source_id(events):
            return drt, frt
        print("[events_cache] " + prefix + ": caches unstamped or from another "
              "events file -> rebuilding")  # ASCII only
    if not events.exists():
        raise FileNotFoundError(str(events))

    source = _source_id(events)   # taken BEFORE the pass: a file changing under it
    stamp.unlink(missing_ok=True)  # leaves a stamp that no longer matches
    tmp_drt = drt.with_name(drt.name + ".tmp")
    tmp_frt = frt.with_name(frt.name + ".tmp")
    try:
        with gzip.open(events, "rt", encoding="utf-8") as f, \
                open(tmp_drt, "w", encoding="utf-8") as fd, \
                open(tmp_frt, "w", encoding="utf-8") as ff:
            for line in f:
                if "drt_" in line:
                    fd.write(line)
                if _freight_wanted(line):
                    ff.write(line)
    except BaseException:
        tmp_drt.unlink(missing_ok=True)
        tmp_frt.unlink(missing_ok=True)
        raise
    os.replace(tmp_drt, drt)
    os.replace(tmp_frt, frt)
    stamp.write_text(json.dumps(source), encoding="utf-8")
    return drt, frt
