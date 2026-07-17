# -*- coding: utf-8 -*-
"""Tidy hourly time series per run -> kpi_timeseries.csv
(run_id;series;hour;value;unit). Sources: drt legs CSV (rides, mean wait),
drt rejections CSV, freight service-start cache (stops)."""
import csv
import re
from pathlib import Path

import pandas as pd

RE_TIME = re.compile(r'time="([^"]+)"')


def _ts(series, hour, value, unit):
    return {"series": series, "hour": int(hour), "value": value, "unit": unit}


def extract(run_dir, prefix, freight_cache=None):
    run_dir = Path(run_dir)
    rows = []

    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs = pd.read_csv(legs_f, sep=";")
        legs["hour"] = (legs["departureTime"] // 3600).astype(int)
        g = legs.groupby("hour")
        for h, n in g.size().items():
            rows.append(_ts("drt_rides", h, int(n), "trips/h"))
        for h, wm in g["waitTime"].mean().items():
            rows.append(_ts("drt_wait_mean", h, float(wm), "s"))

        if "submissionTime" in legs.columns:
            sub_hour = (legs["submissionTime"] // 3600).astype(int)
            for h, n in sub_hour.value_counts().sort_index().items():
                rows.append(_ts("drt_requests_submitted", h, int(n), "requests/h"))

    rej_f = run_dir / (prefix + ".output_drt_rejections_drt.csv")
    if rej_f.exists():
        rej = pd.read_csv(rej_f, sep=";")
        if len(rej):
            for h, n in (rej["time"] // 3600).astype(int).value_counts().sort_index().items():
                rows.append(_ts("drt_rejections", h, int(n), "requests/h"))

    trips_f = run_dir / (prefix + ".output_trips.csv.gz")
    if trips_f.exists():
        trips = pd.read_csv(trips_f, sep=";")
        if "modes" in trips.columns and "dep_time" in trips.columns:
            modes = trips["modes"].astype(str)
            feeder = trips[modes.str.contains("drt", na=False) & modes.str.contains("pt", na=False)]
            if len(feeder):
                # dep_time is "HH:MM:SS" (possibly >24h) in MATSim's output_trips.csv, not raw
                # seconds -- convert via to_timedelta before bucketing, same // 3600 as elsewhere.
                dep_s = pd.to_timedelta(feeder["dep_time"], errors="coerce").dt.total_seconds()
                feeder_hour = (dep_s // 3600).dropna().astype(int)
                for h, n in feeder_hour.value_counts().sort_index().items():
                    rows.append(_ts("drt_feeder_trips", h, int(n), "trips/h"))

    if freight_cache is not None and Path(freight_cache).exists():
        counts = {}
        with open(freight_cache, "r", encoding="utf-8") as f:
            for line in f:
                if not ('type="actstart"' in line and 'actType="service"' in line):
                    continue
                m = RE_TIME.search(line)
                if m:
                    h = int(float(m.group(1)) // 3600)
                    counts[h] = counts.get(h, 0) + 1
        for h in sorted(counts):
            rows.append(_ts("freight_service_stops", h, counts[h], "stops/h"))
    return rows


def write(series_rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "series", "hour", "value", "unit"])
        for r in series_rows:
            v = r["value"]
            w.writerow([meta.run_id, r["series"], r["hour"],
                        "{:.6g}".format(v) if isinstance(v, float) else v, r["unit"]])
