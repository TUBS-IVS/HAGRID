# -*- coding: utf-8 -*-
"""Single pass over output_events.xml.gz producing two per-run line caches:
- <prefix>.drt_events_filtered.txt   (same name + filter as build_drt_dashboard.py)
- <prefix>.freight_service_starts.txt (freight 'service' actstart lines)
If either cache is missing, BOTH are rebuilt in one pass (~1-2 min on a 90 MB
events file; the drt rebuild is byte-identical, so sharing with the legacy
dashboard stays safe)."""
from pathlib import Path
import gzip

DRT_SUFFIX = ".drt_events_filtered.txt"
FREIGHT_SUFFIX = ".freight_service_starts.txt"


def ensure_caches(run_dir, prefix):
    run_dir = Path(run_dir)
    drt = run_dir / (prefix + DRT_SUFFIX)
    frt = run_dir / (prefix + FREIGHT_SUFFIX)
    if drt.exists() and frt.exists():
        return drt, frt
    events = run_dir / (prefix + ".output_events.xml.gz")
    if not events.exists():
        raise FileNotFoundError(str(events))
    with gzip.open(events, "rt", encoding="utf-8") as f, \
            open(drt, "w", encoding="utf-8") as fd, \
            open(frt, "w", encoding="utf-8") as ff:
        for line in f:
            if "drt_" in line:
                fd.write(line)
            if 'type="actstart"' in line and 'actType="service"' in line and "freight" in line:
                ff.write(line)
    return drt, frt
