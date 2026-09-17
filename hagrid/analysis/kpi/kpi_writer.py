# -*- coding: utf-8 -*-
"""Canonical KPI CSVs. Long format is the spec schema + source; wide format is
one row per run with kpi_group.kpi_name columns. Conventions: ';', dot
decimals, UTF-8 (matches RoutingStatistics + MATSim DRT CSVs)."""
import csv

COLUMNS = ["run_id", "study_area", "scenario", "operation_mode",
           "kpi_group", "kpi_name", "value", "unit", "source"]


def _fmt(v):
    if isinstance(v, float):
        return "{:.6g}".format(v)
    return str(v)


def write_long(rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(COLUMNS)
        for r in rows:
            w.writerow([meta.run_id, meta.study_area, meta.scenario,
                        meta.operation_mode, r["kpi_group"], r["kpi_name"],
                        _fmt(r["value"]), r["unit"], r["source"]])


def write_wide(rows, meta, out_file):
    names = [r["kpi_group"] + "." + r["kpi_name"] for r in rows]
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "study_area", "scenario", "operation_mode"] + names)
        w.writerow([meta.run_id, meta.study_area, meta.scenario, meta.operation_mode]
                   + [_fmt(r["value"]) for r in rows])
