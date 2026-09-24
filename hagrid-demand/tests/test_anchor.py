import numpy as np
import pandas as pd
import pytest


def _table():
    rows = []
    for plz, rate in (("A", .06), ("B", .06), ("C", .156)):
        for index in range(25):
            persons = 100 + index
            rows.append({"sid": len(rows), "plz": plz, "street": f"s{index}", "value": rate * persons,
                         "persons": persons, "companies": 0, "buildings": 3, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "gewerbe", "value": 0.06 * 10 + 0.4 * 20,
                 "persons": 10, "companies": 20, "buildings": 4, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "luecke", "value": 0., "persons": 200, "companies": 0,
                 "buildings": 5, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "leer", "value": 7., "persons": 0, "companies": 0,
                 "buildings": 0, "excluded": False})
    rows.append({"sid": len(rows), "plz": "A", "street": "gross", "value": 5000., "persons": 5, "companies": 1,
                 "buildings": 1, "excluded": True})
    return pd.DataFrame(rows)


def test_level_correction_scales_only_extreme_postal_levels():
    from hagrid_demand.baseline.anchor import AnchorConfig, apply_correction, level_correction

    corrections = level_correction(_table(), AnchorConfig()).set_index("plz")
    assert corrections.loc["C", "applied"] and corrections.loc["C", "factor"] == pytest.approx(2.6)
    assert not corrections.loc["A", "applied"] and corrections.loc["A", "factor"] == 1.
    corrected = apply_correction(_table(), corrections.reset_index())
    assert corrected.loc[corrected.plz.eq("C"), "dhl_corrected"].sum() == pytest.approx(
        _table().loc[lambda t: t.plz.eq("C"), "value"].sum() / 2.6)


def test_rates_decomposition_statuses_and_b2b_share():
    from hagrid_demand.baseline.anchor import (AnchorConfig, apply_correction, decompose, fit_dhl_rates,
                                               level_correction, observed_b2b_share)

    cfg = AnchorConfig()
    table = apply_correction(_table(), level_correction(_table(), cfg))
    rates = fit_dhl_rates(table)
    assert rates["person"] == pytest.approx(.06, rel=1e-6) and rates["company"] == pytest.approx(.4, rel=1e-6)
    parts = decompose(table, rates, cfg).set_index("street")
    assert parts.loc["gewerbe", "dhl_business"] == pytest.approx(8.) and parts.loc["gewerbe", "dhl_private"] == pytest.approx(.6)
    assert parts.loc["luecke", "anchor_status"] == "gap" and parts.loc["luecke", "dhl_private"] == pytest.approx(12.)
    assert parts.loc["leer", "anchor_status"] == "observed_unstructured"
    assert parts.loc["gross", "anchor_status"] == "excluded"
    observed = parts[parts.anchor_status.isin(["observed", "observed_unstructured"])]
    assert (observed.dhl_private + observed.dhl_business).sum() == pytest.approx(observed.dhl_corrected.sum())
    assert 0 < observed_b2b_share(parts.reset_index()) < 1


def test_structure_holdout_reports_street_and_postal_errors():
    from hagrid_demand.baseline.anchor import AnchorConfig, apply_correction, level_correction, structure_holdout

    table = apply_correction(_table(), level_correction(_table(), AnchorConfig()))
    result = structure_holdout(table, seed=1, folds=3)
    assert set(result) == {"M0_persons", "M1_persons_companies"}
    assert result["M1_persons_companies"]["street_wmape"] <= result["M0_persons"]["street_wmape"] + 1e-9
