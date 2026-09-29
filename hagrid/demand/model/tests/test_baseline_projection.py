import pandas as pd
import pytest


def _reference():
    return {
        "regional_annual": 1_000.0,
        "sites": pd.DataFrame({
            "site_id": ["p1", "p2", "b1"], "plz": ["1", "1", "2"],
            "segment": ["private", "private", "business"],
            "historical_share": [.25, .75, 1.0], "allocation_status": ["located", "located", "located"],
        }),
    }


def _scope_id():
    from hagrid_demand.common.contracts import verified_scope_id

    return verified_scope_id(["1", "2"])


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
    assert result.sites.columns.tolist() == ["year", "site_id", "plz", "segment", "allocation_status", "support_status", "annual_expected", "share"]
    assert result.postal.columns.tolist() == [
        "year", "plz", "segment", "annual_expected", "support_status", "memory_weight", "regional_level_mode", "growth_factor", "b2b_share",
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
            {"year": 2022, "value": 777., "unit": "packages/year", "provenance": "fixture", "scope": _scope_id()},
        ]},
    })
    assert result.sites.annual_expected.sum() == pytest.approx(777.)
    assert result.checks["external_annual_series"]["scope_id"] == _scope_id()
    with pytest.raises(ValueError, match="external_annual_series.*growth"):
        project_annual(_reference(), _series(), [2022], {
            "memory": {"fixed": 1}, "regional_level": {"mode": "external_annual_series", "annual_growth": 1.1,
                "series": [{"year": 2022, "value": 777., "unit": "packages/year", "provenance": "fixture", "scope": _scope_id()}]},
        })


def test_projection_preserves_segments_in_postal_balances_and_is_order_independent():
    from hagrid_demand.baseline.projection import project_annual

    reference, series = _reference(), _series()
    first = project_annual(reference, series, [2022], {"memory": {"fixed": 1}})
    second = project_annual({**reference, "sites": reference["sites"].iloc[::-1]}, {
        **series, "market": series["market"].iloc[::-1], "providers": series["providers"].iloc[::-1],
    }, [2022], {"memory": {"fixed": 1}})

    assert first.postal.groupby("segment").annual_expected.sum().to_dict() == pytest.approx({"private": 840., "business": 360.})
    assert first.sites.equals(second.sites)
    assert first.profiles.equals(second.profiles)
    assert first.postal.equals(second.postal)
    assert first.hashes == second.hashes
    assert first.checks["balances"]["share_sums"]
    assert first.checks["balances"]["postal_site_errors"]


@pytest.mark.parametrize(("b2b", "missing_segment"), [(0., "business"), (1., "private")])
def test_projection_keeps_zero_target_segments_explicit_without_site_support(b2b, missing_segment):
    from hagrid_demand.baseline.projection import project_annual

    reference = _reference()
    reference["sites"] = reference["sites"].query("segment != @missing_segment")
    series = _series()
    series["b2b"] = series["b2b"].assign(share=b2b)
    result = project_annual(reference, series, [2022], {"memory": {"fixed": 1}})

    missing = result.postal.query("segment == @missing_segment")
    assert not missing.empty and missing.annual_expected.eq(0).all()
    assert missing.support_status.eq("zero_target_no_support").all()


def test_zero_target_segment_with_an_existing_zero_share_site_has_unique_postal_keys():
    from hagrid_demand.baseline.projection import project_annual

    reference = _reference()
    reference["sites"].loc[reference["sites"].segment.eq("business"), "historical_share"] = 0.
    series = _series()
    series["b2b"] = series["b2b"].assign(share=0.)
    result = project_annual(reference, series, [2022], {"memory": {"fixed": 1}})

    assert not result.postal.duplicated(["year", "plz", "segment"]).any()
    assert result.postal.query("segment == 'business'").annual_expected.eq(0).all()


def test_external_provenance_and_scope_are_verified_and_change_projection_identity():
    from hagrid_demand.baseline.projection import project_annual

    cfg = {"memory": {"fixed": 1}, "regional_level": {"mode": "external_annual_series", "series": [
        {"year": 2022, "value": 777., "unit": "packages/year", "provenance": "first", "scope": _scope_id()},
    ]}}
    first = project_annual(_reference(), _series(), [2022], cfg)
    cfg["regional_level"]["series"][0]["provenance"] = "second"
    second = project_annual(_reference(), _series(), [2022], cfg)
    assert first.checks["identity_hash"] != second.checks["identity_hash"]
    cfg["regional_level"]["series"][0]["scope"] = "wrong"
    with pytest.raises(ValueError, match="scope"):
        project_annual(_reference(), _series(), [2022], cfg)


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


def test_projection_fixes_dhl_b2b_proportional_to_the_national_trend():
    from hagrid_demand.baseline.projection import _profile
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs

    series = build_series(packaged_series_inputs(), [2021, 2030], volume_fit_policy="observed_only")
    b = series["b2b"].set_index("year").share
    profile, target = _profile(series, 2030, dhl_fixed={"q_2021": .24, "b_2021": float(b[2021])})
    dhl = profile[(profile.carrier == "DHL")].q.iloc[0]
    assert dhl == pytest.approx(.24 * b[2030] / b[2021])
    shares = profile.pivot(index="carrier", columns="segment", values="share")
    market = series["market"].query("year == 2030").set_index("carrier").market_share
    mixed = ((1 - target) * shares.private + target * shares.business).reindex(market.index)
    assert mixed.to_numpy() == pytest.approx(market.to_numpy())


def test_project_annual_with_factors_keeps_balances():
    """Site factors re-weight the historical shares per segment and year; totals stay exact."""
    from hagrid_demand.baseline.projection import project_annual

    reference = _reference()
    reference["sites"] = pd.concat([reference["sites"], pd.DataFrame({
        "site_id": ["new"], "plz": ["2"], "segment": ["private"], "historical_share": [.5], "allocation_status": ["located"]})],
        ignore_index=True)
    plain = project_annual(_reference(), _series(), [2022], {"memory": {"fixed": 1}})
    ones = pd.DataFrame({"year": 2022, "site_id": ["p1", "p2", "b1"], "segment": ["private", "private", "business"], "factor": 1.})
    same = project_annual(_reference(), _series(), [2022], {"memory": {"fixed": 1}}, site_factors=ones)
    pd.testing.assert_frame_equal(plain.sites, same.sites)

    factors = pd.DataFrame({"year": 2022, "site_id": ["p1", "p2", "b1", "new"], "segment": ["private", "private", "business", "private"],
                            "factor": [2., 1., 3., 0.]})
    result = project_annual(reference, _series(), [2022], {"memory": {"fixed": 1}}, site_factors=factors)
    by_site = result.sites.set_index("site_id").annual_expected
    assert result.sites.groupby("segment").annual_expected.sum().to_dict() == pytest.approx({"private": 840., "business": 360.})
    assert "new" not in by_site.index   # a closed site (factor 0) is not part of that year's table
    assert by_site["p1"] == pytest.approx(840. * (.25 * 2.) / (.25 * 2. + .75 * 1.))   # weight = historical share x factor
    opened = factors.assign(factor=[2., 1., 3., 1.])
    result = project_annual(reference, _series(), [2022], {"memory": {"fixed": 1}}, site_factors=opened)
    assert result.sites.set_index("site_id").annual_expected["new"] == pytest.approx(840. * .5 / (.5 + .75 + .5))
    with pytest.raises(ValueError, match="site_factors"):
        project_annual(reference, _series(), [2022], {"memory": {"fixed": 1}}, site_factors=factors.assign(factor=-1.))
