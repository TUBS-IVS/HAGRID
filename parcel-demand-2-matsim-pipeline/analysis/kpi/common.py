# -*- coding: utf-8 -*-
"""Shared helpers for the canonical KPI pipeline (1e)."""

KPI_GROUPS = ("system", "passenger", "freight", "economic", "channel")


def row(kpi_group, kpi_name, value, unit, source):
    """One KPI observation. run_id/study_area/scenario/operation_mode are
    added by the writer from RunMeta — extractors stay run-agnostic."""
    assert kpi_group in KPI_GROUPS, kpi_group
    return {"kpi_group": kpi_group, "kpi_name": kpi_name,
            "value": value, "unit": unit, "source": source}
