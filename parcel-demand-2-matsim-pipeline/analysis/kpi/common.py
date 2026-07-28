# -*- coding: utf-8 -*-
"""Shared helpers for the canonical KPI pipeline (1e)."""

# "meta" carries provenance rows (which fallback fired, what stayed
# contaminated) rather than a measured quantity -- see pax_only.apply_overrides
# and run_meta.load_run_meta. Keeping it in the same schema means provenance
# travels with the KPIs instead of living only in stdout.
KPI_GROUPS = ("system", "passenger", "freight", "economic", "channel", "modular", "meta")


def row(kpi_group, kpi_name, value, unit, source):
    """One KPI observation. run_id/study_area/scenario/operation_mode are
    added by the writer from RunMeta — extractors stay run-agnostic."""
    assert kpi_group in KPI_GROUPS, kpi_group
    return {"kpi_group": kpi_group, "kpi_name": kpi_name,
            "value": value, "unit": unit, "source": source}
