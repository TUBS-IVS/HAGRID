import pandas as pd
import pytest


def _reference():
    return {
        "regional_annual": 1_000.0,
        "sites": pd.DataFrame({
            "site_id": ["p1", "p2", "b1"], "plz": ["1", "1", "2"],
            "segment": ["private", "private", "business"],
            "historical_share": [.25, .75, 1.0],
        }),
    }


def _series(dhl_share=0.4):
    return {
        "volume": pd.DataFrame({"year": [2021, 2022], "value": [100., 120.]}),
        "market": pd.DataFrame({"year": [2021, 2021, 2022, 2022],
                                "carrier": ["DHL", "Other", "DHL", "Other"],
                                "market_share": [.5, .5, dhl_share, 1 - dhl_share]}),
        "b2b": pd.DataFrame({"year": [2021, 2022], "share": [.2, .3]}),
        "providers": pd.DataFrame({"year": [2021, 2021, 2022, 2022],
                                    "carrier": ["DHL", "Other", "DHL", "Other"],
                                    "q_prior": [.5, .5, .5, .5], "q_scale": [1.] * 4,
                                    "lower": [0.] * 4, "upper": [1.] * 4}),
    }


def test_projection_applies_one_volume_growth_and_normalised_historical_site_shares():
    from hagrid_demand.baseline.projection import project_annual
    from hagrid_demand.common.contracts import AnnualProjection

    result = project_annual(_reference(), _series(), [2022], {"memory": {"fixed": 1}})

    assert isinstance(result, AnnualProjection)
    assert result.sites.columns.tolist() == ["year", "site_id", "plz", "segment", "annual_expected", "share"]
    assert result.postal.columns.tolist() == [
        "year", "plz", "annual_expected", "memory_weight", "regional_level_mode", "growth_factor", "b2b_share",
    ]
    assert result.sites.annual_expected.sum() == pytest.approx(1200)
    assert result.sites.groupby("segment").annual_expected.sum().to_dict() == pytest.approx(
        {"private": 840., "business": 360.}
    )
    assert result.sites.set_index("site_id").loc["p1", "annual_expected"] == pytest.approx(210.)
    assert result.postal.annual_expected.sum() == pytest.approx(1200.)
    assert set(result.checks["hashes"]) == {"sites", "profiles", "postal"}
    assert result.checks["balances"]["regional_error"] == pytest.approx(0.)


def test_future_carrier_profile_is_label_aligned_and_does_not_change_regional_total():
    from hagrid_demand.baseline.projection import project_annual

    first = project_annual(_reference(), _series(.4), [2022], {"memory": {"fixed": 1}})
    second = project_annual(_reference(), _series(.8), [2022], {"memory": {"fixed": 1}})

    assert first.sites.annual_expected.sum() == second.sites.annual_expected.sum() == pytest.approx(1200.)
    assert first.profiles.query("segment == 'private'").set_index("carrier").loc["DHL", "market_share"] == pytest.approx(.4)
    assert second.profiles.query("segment == 'private'").set_index("carrier").loc["DHL", "market_share"] == pytest.approx(.8)


def test_external_annual_series_is_a_direct_total_and_rejects_a_second_growth_channel():
    from hagrid_demand.baseline.projection import project_annual

    result = project_annual(_reference(), _series(), [2022], {
        "memory": {"fixed": 1}, "regional_level": {"mode": "external_annual_series", "series": [
            {"year": 2022, "value": 777., "unit": "packages/year", "provenance": "fixture", "scope": "region"},
        ]},
    })
    assert result.sites.annual_expected.sum() == pytest.approx(777.)
    with pytest.raises(ValueError, match="external_annual_series.*growth"):
        project_annual(_reference(), _series(), [2022], {
            "memory": {"fixed": 1}, "regional_level": {"mode": "external_annual_series", "annual_growth": 1.1,
                "series": [{"year": 2022, "value": 777., "unit": "packages/year", "provenance": "fixture", "scope": "region"}]},
        })


def test_projection_rejects_positive_segment_without_historical_site_support():
    from hagrid_demand.baseline.projection import project_annual

    reference = _reference()
    reference["sites"] = reference["sites"].query("segment == 'private'")
    with pytest.raises(ValueError, match="business.*support"):
        project_annual(reference, _series(), [2022], {"memory": {"fixed": 1}})


def test_projection_rejects_non_integer_years_without_truncating_them():
    from hagrid_demand.baseline.projection import project_annual

    for year in (True, 2022.0, 2020):
        with pytest.raises(ValueError, match="years"):
            project_annual(_reference(), _series(), [year], {"memory": {"fixed": 1}})
