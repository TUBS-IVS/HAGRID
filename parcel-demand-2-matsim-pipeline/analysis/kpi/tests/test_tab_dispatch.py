# -*- coding: utf-8 -*-
"""Which renderer builds the tab beside DRT (board rework, render._second_tab).

The regression this guards: before the rework a 1c/1d board showed an "LMD" tab, because the
gate asked "are there freight KPIs?" and those arms DO have freight -- it just rides the DRT
fleet. render_lmd is built from providers/vehicle types/carriers, none of which they have, so
the tab was structurally empty.

Labels alone would not discriminate (a wrong renderer under a right label passes), so each case
also asserts on a block only its own renderer can produce.
"""
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import render


def _data(scenario, kpi_rows):
    """Minimal RunData carrying a scenario and just enough KPIs to build blocks."""
    kpis = pd.DataFrame([dict(scenario=scenario, kpi_group=g, kpi_name=n, value=v,
                              unit="", source="test")
                         for g, n, v in kpi_rows])
    empty = pd.DataFrame()
    return render.RunData(kpis=kpis, ts=pd.DataFrame(columns=["series", "hour", "value"]),
                          provider=empty, iterations=empty,
                          distributions=pd.DataFrame(columns=["series", "bin_lo", "bin_hi",
                                                              "value", "unit"]),
                          vehicles=empty)


_MODULAR_KPIS = [("freight", "tours_planned", 127.0), ("freight", "tours_dispatched", 120.0),
                 ("freight", "tours_completed", 118.0), ("modular", "swaps_completed", 178.0)]
_SHAREDUSE_KPIS = [("freight", "delivery_rate", 0.38), ("channel", "segments_injected", 955.0),
                   ("channel", "segments_delivered", 598.0),
                   ("channel", "chi_detour_delivered_median_s", 48.0),
                   ("channel", "chi_detour_expired_median_s", 115.0)]
_FREIGHT_KPIS = [("freight", "delivery_rate", 1.0), ("freight", "parcels_total", 6052.0)]


@pytest.mark.parametrize("scenario, kpi_rows, expected", [
    ("DRT_SHAREDUSE", _SHAREDUSE_KPIS, "Cargo-Hitching"),
    ("DRT_MODULAR", _MODULAR_KPIS, "Kapsel-Tausch"),
    ("DRT_BASELINE", _FREIGHT_KPIS, "LMD"),
    ("LMD_BASELINE", _FREIGHT_KPIS, "LMD"),
])
def test_second_tab_label_per_scenario(scenario, kpi_rows, expected):
    label, _html, _js = render._second_tab(_data(scenario, kpi_rows), "t")
    assert label == expected


def test_unknown_scenario_falls_back_to_lmd():
    """A scenario the table does not know must keep the previous behaviour rather
    than lose its tab -- a board silently missing a tab is worse than an old one."""
    label, _html, _js = render._second_tab(_data("SOME_FUTURE_ARM", _FREIGHT_KPIS), "t")
    assert label == "LMD"


def test_modular_tab_renders_modular_blocks_not_provider_blocks():
    """Discriminates the RENDERER, not just the label: the lifecycle funnel exists
    only in render_modular, and provider analytics only in render_lmd."""
    _label, html, _js = render._second_tab(_data("DRT_MODULAR", _MODULAR_KPIS), "t")
    assert "Tour-Lebenszyklus" in html
    assert "Provider-Analytik" not in html


def test_shareduse_tab_renders_gate_blocks_not_provider_blocks():
    _label, html, _js = render._second_tab(_data("DRT_SHAREDUSE", _SHAREDUSE_KPIS), "t")
    assert "Umweg-Minimum" in html
    assert "Provider-Analytik" not in html


def test_run_page_tabbar_carries_the_mechanism_label():
    """End of the chain: the label has to reach the rendered tab bar, not just
    _second_tab's return value."""
    html = render.render_run_page(_data("DRT_MODULAR", _MODULAR_KPIS), title="t")
    assert "Kapsel-Tausch" in html
    assert ">LMD<" not in html
