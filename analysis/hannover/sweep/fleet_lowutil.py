# -*- coding: utf-8 -*-
"""Fleet size with and without the dashboard's low-utilisation filter (< 5 % load factor)
for the v2-v4 sweep, per capacity and as three-run mean -> fleet_lowutil_v2_v3_v4.csv.

fleet_net   = SUMMARY vehicles (what Table 2 reports; the filter is already applied there)
excluded    = count from the board's "Low-utilisation filter" notice
fleet_gross = fleet_net + excluded

Run:  python -u fleet_lowutil.py      (~4 min, reads 114 dashboards)
"""
import csv
import re
import statistics as st
from pathlib import Path

import extract_sweep as ex

OUT = Path(r"C:\Users\Hendrik Bimmermann\Documents\Dokumente\Research\Paper\0326_EWGT26\Analysis_v2_v3_v4")
SERIES = ("v2", "v3", "v4")
NOTICE = re.compile(r"Low-utilisation filter \(threshold:.*?</strong>\s*(\d+) vehicle\(s\) were excluded", re.S)

rows = {}
for series, cap, rep, path in ex.run_list():
    if series not in SERIES:
        continue
    text = path.read_text(encoding="utf-8")
    summary = ex.slice_json_array(text, "SUMMARY")
    vehs = [v for p in summary for v in p["vehDetails"]]
    low = sum(1 for v in vehs if v["parcels"] < 0.05 * v["cap"])
    if low:
        raise ValueError(f"{path.name}: {low} tours below 5 % are still in SUMMARY")
    hits = NOTICE.findall(text)
    if len(hits) > 1:
        raise ValueError(f"{path.name}: {len(hits)} filter notices")
    excluded = int(hits[0]) if hits else 0    # no notice = nothing excluded
    net = sum(p["vehicles"] for p in summary)
    rows.setdefault(cap, {})[series] = (net, excluded)
    print(series, cap, net, excluded, flush=True)

exc = [rows[c][s][1] for c in rows for s in SERIES]
pct = [100 * rows[c][s][1] / sum(rows[c][s]) for c in rows for s in SERIES]
print(f"\nexcluded per run: mean {st.mean(exc):.2f}, median {st.median(exc)}, "
      f"share {min(pct):.2f}-{max(pct):.2f} % of tours (paper: 11.21 / 10 / 0.00-7.07 %)")

cols = ["cap"]
for s in SERIES + ("mean",):
    cols += [f"fleet_net_{s}", f"excluded_{s}", f"fleet_gross_{s}"]
with open(OUT / "fleet_lowutil_v2_v3_v4.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(cols)
    for cap in sorted(rows):
        line = [cap]
        for s in SERIES:
            net, e = rows[cap][s]
            line += [net, e, net + e]
        line += [round(st.mean(rows[cap][s][0] for s in SERIES), 1),
                 round(st.mean(rows[cap][s][1] for s in SERIES), 1),
                 round(st.mean(sum(rows[cap][s]) for s in SERIES), 1)]
        w.writerow(line)
print("OK ->", OUT / "fleet_lowutil_v2_v3_v4.csv")
