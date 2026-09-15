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

    result = project_annual(_reference(), _series(), [2022], {"memory": {"fixed": 1}})

    assert result.sites.reference_annual.sum() == pytest.approx(1200)
    assert result.sites.groupby("segment").reference_annual.sum().to_dict() == pytest.approx(
        {"private": 840., "business": 360.}
    )
    assert result.sites.set_index("site_id").loc["p1", "reference_annual"] == pytest.approx(210.)
    assert result.postal.reference_annual.sum() == pytest.approx(1200.)


def test_future_carrier_profile_is_label_aligned_and_does_not_change_regional_total():
    from hagrid_demand.baseline.projection import project_annual

    first = project_annual(_reference(), _series(.4), [2022], {"memory": {"fixed": 1}})
    second = project_annual(_reference(), _series(.8), [2022], {"memory": {"fixed": 1}})

    assert first.sites.reference_annual.sum() == second.sites.reference_annual.sum() == pytest.approx(1200.)
    assert first.carrier_profiles.query("segment == 'private'").set_index("carrier").loc["DHL", "market_share"] == pytest.approx(.4)
    assert second.carrier_profiles.query("segment == 'private'").set_index("carrier").loc["DHL", "market_share"] == pytest.approx(.8)


def test_external_annual_series_is_a_direct_total_and_rejects_a_second_growth_channel():
    from hagrid_demand.baseline.projection import project_annual

    result = project_annual(_reference(), _series(), [2022], {
        "memory": {"fixed": 1}, "external_annual_series": {2022: 777.},
    })
    assert result.sites.reference_annual.sum() == pytest.approx(777.)
    with pytest.raises(ValueError, match="external_annual_series.*growth"):
        project_annual(_reference(), _series(), [2022], {
            "memory": {"fixed": 1}, "external_annual_series": {2022: 777.}, "annual_growth": 1.1,
        })
