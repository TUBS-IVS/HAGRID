import numpy as np
import pandas as pd
import pytest


def _sites():
    return pd.DataFrame({
        "site_id": ["h1", "h2", "b1", "b2", "lost"],
        "plz": ["10000", "20000", "10000", "20000", None],
        "segment": ["private", "private", "business", "business", "private"],
        "population": [10, 20, 0, 0, 3],
        "employees": [np.nan, np.nan, 10, 100, np.nan],
        "branch": [None, None, "retail", "office", None],
        "allocation_status": ["located", "unlocated", "located", "located", "unlocated"],
    })


def _profiles():
    # The validated LSP label, rather than carrier position, makes eta identifiable.
    return {"conditional": np.array([[.6, .4], [.2, .8]]), "carriers": ["DHL", "Other"]}


def test_reconciliation_and_infeasible_bounds():
    from hagrid_demand.baseline.reference import reconcile_carriers

    m = np.array([.6, .4]); prior = np.array([.2, .8])
    result = reconcile_carriers(m, prior, .3, np.zeros(2), np.ones(2), np.ones(2))
    assert np.isclose(m @ result["q"], .3)
    assert np.allclose(result["conditional"].sum(axis=1), 1)
    with pytest.raises(ValueError, match="infeasible"):
        reconcile_carriers(m, prior, .1, np.full(2, .5), np.ones(2), np.ones(2))


def test_reconciliation_normalizes_an_accepted_near_simplex_before_boundary_checks():
    from hagrid_demand.baseline.reference import reconcile_carriers

    result = reconcile_carriers(np.array([.999999995]), np.array([.5]), 1.,
                                np.array([0.]), np.array([1.]), np.array([1.]))
    assert result["q"] == pytest.approx([1.])
    assert np.allclose(result["conditional"], [[0.], [1.]])
    assert result["diagnostics"]["market_weight_input_sum"] == pytest.approx(.999999995)
    assert result["diagnostics"]["market_weight_normalization_factor"] == pytest.approx(1 / .999999995)
    assert result["diagnostics"]["market_weight_normalized_sum"] == 1.


def test_reconciliation_has_quadratic_optimum_and_boundary_conditionals():
    from hagrid_demand.baseline.reference import reconcile_carriers

    m = np.array([.5, .5]); prior = np.array([.1, .9]); scale = np.array([1., 2.])
    optimum = reconcile_carriers(m, prior, .45, np.zeros(2), np.ones(2), scale)
    assert optimum["q"] == pytest.approx([.08, .82], abs=1e-10)
    assert optimum["diagnostics"]["objective"] == pytest.approx(.002)
    assert optimum["diagnostics"]["conditioning_objective"] == pytest.approx(.008)
    private = reconcile_carriers(m, prior, 0., np.zeros(2), np.ones(2), scale)
    business = reconcile_carriers(m, prior, 1., np.zeros(2), np.ones(2), scale)
    assert private["q"] == pytest.approx([0., 0.])
    assert np.allclose(private["conditional"], [[.5, .5], [0., 0.]])
    assert business["q"] == pytest.approx([1., 1.])
    assert np.allclose(business["conditional"], [[0., 0.], [.5, .5]])


def test_reconciliation_releases_nonoptimal_clamp_with_scalar_lagrange_solution():
    from hagrid_demand.baseline.reference import reconcile_carriers

    result = reconcile_carriers(np.array([.5, .5]), np.array([1., 0.]), .2,
                                np.zeros(2), np.array([.6, 1.]), np.ones(2))
    assert result["q"] == pytest.approx([.4, 0.], abs=1e-10)
    assert np.dot([.5, .5], result["q"]) == pytest.approx(.2)


def test_reconciliation_preserves_near_endpoint_targets_and_rejects_nearby_infeasible_bounds():
    from hagrid_demand.baseline.reference import reconcile_carriers

    for target in (1e-9, 1 - 1e-9):
        solved = reconcile_carriers(np.array([1.]), np.array([.5]), target,
                                    np.array([0.]), np.array([1.]), np.array([1.]))
        assert solved["q"] == pytest.approx([target], abs=1e-14)
        assert np.dot([1.], solved["q"]) == pytest.approx(target, abs=1e-14)
        assert solved["diagnostics"]["target_requested"] == target
        assert solved["diagnostics"]["target_adjustment"] == 0.
    for target in (.500000005, .499999995):
        with pytest.raises(ValueError, match="infeasible"):
            reconcile_carriers(np.array([1.]), np.array([.5]), target,
                               np.array([.5]), np.array([.5]), np.array([1.]))


def test_reconciliation_solves_a_forced_change_when_scale_squared_underflows():
    from hagrid_demand.baseline.reference import reconcile_carriers

    solved = reconcile_carriers(np.array([.5, .5]), np.array([.5, .5]), .4,
                                np.array([0., .5]), np.array([1., .5]), np.array([1e-300, 1.]))
    assert solved["q"] == pytest.approx([.3, .5], abs=1e-12)
    assert np.dot([.5, .5], solved["q"]) == pytest.approx(.4, abs=1e-12)
    wide = reconcile_carriers(np.array([.5, .5]), np.array([.5, .5]), .4,
                              np.array([0., .5]), np.array([1., .5]), np.array([1e-300, 1e300]))
    assert wide["q"] == pytest.approx([.3, .5], abs=1e-12)
    assert wide["diagnostics"]["objective"] is None
    assert wide["diagnostics"]["objective_status"] == "overflow_log_retained"
    assert wide["diagnostics"]["conditioning_objective"] is None
    assert wide["diagnostics"]["conditioning_objective_status"] == "overflow_log_retained"


def test_default_potentials_count_people_and_company_locations_once():
    from hagrid_demand.baseline.potentials import build_potentials

    result = build_potentials(_sites())
    assert result.set_index("site_id").loc[["h1", "h2"], "weight"].tolist() == [10.0, 20.0]
    assert result.set_index("site_id").loc[["b1", "b2"], "weight"].tolist() == [1.0, 1.0]
    assert result.loc[result.site_id.eq("lost"), "plz"].isna().all()
    employee_candidate = build_potentials(_sites(), branch_multipliers={"retail": 2, "office": 1})
    assert employee_candidate.set_index("site_id").loc["b1", "weight"] == 20.0
    assert employee_candidate.set_index("site_id").loc["b2", "weight"] == 100.0
    assert build_potentials(_sites().drop(columns="allocation_status"))["allocation_status"].eq("located").all()


def test_canonical_potentials_only_require_canonical_fields_and_validate_candidates():
    from hagrid_demand.baseline.potentials import build_potentials

    canonical = pd.DataFrame({"site_id": ["p", "c"], "plz": ["1", "1"],
                              "segment": ["private", "business"], "population": [2., np.nan]})
    assert build_potentials(canonical).weight.tolist() == [2., 1.]
    assert build_potentials(canonical.iloc[[1]].drop(columns="population")).weight.tolist() == [1.]
    with pytest.raises(ValueError, match="power.*candidate"):
        build_potentials(canonical, power=2.)
    with pytest.raises(ValueError, match="employees"):
        build_potentials(canonical, branch_multipliers={})
    with pytest.raises(ValueError, match="population"):
        build_potentials(canonical.assign(population=np.inf))


def test_historical_q75_identity_and_nonnegative_mean_labels():
    from hagrid_demand.baseline.potentials import historical_quantreg_predict, fit_nonnegative_mean

    persons = np.array([0., 3., 20.])
    companies = np.array([1., 2., 4.])
    expected = 8.1078087809025286e-07 + .15205915924489793 * persons + .89862556511587188 * companies
    assert np.allclose(historical_quantreg_predict(persons, companies), expected, atol=1e-10, rtol=0)
    support = pd.DataFrame({"persons": persons, "companies": companies,
                            "target": .5 * persons + 2 * companies})
    fitted = fit_nonnegative_mean(support)
    assert fitted["model"] == "nonnegative_mean"
    assert fitted["coefficient_semantics"] == "LSP-response rates"
    assert fitted["rates"]["persons"] == pytest.approx(.5)
    assert fitted["rates"]["companies"] == pytest.approx(2.)
    with pytest.raises(ValueError, match="all-zero response"):
        fit_nonnegative_mean(support.assign(target=0.))


def test_contiguous_groups_never_use_target_or_cross_postal_boundaries():
    from hagrid_demand.baseline.potentials import build_contiguous_groups

    units = pd.DataFrame({"unit_id": ["a", "b", "c", "d"], "plz": ["1", "1", "2", "2"],
                          "persons": [3, 4, 10, 1], "companies": [0, 1, 0, 0],
                          "dhl_target": [9999, 0, 0, 9999]})
    edges = pd.DataFrame({"left": ["a", "c", "b"], "right": ["b", "d", "c"]})
    grouped = build_contiguous_groups(units, edges, min_persons=5, min_companies=1, max_units=2)
    assert grouped.groupby("unit_id").size().eq(1).all()
    assert grouped.set_index("unit_id").loc["a", "group_id"] == grouped.set_index("unit_id").loc["b", "group_id"]
    assert grouped.set_index("unit_id").loc["b", "group_id"] != grouped.set_index("unit_id").loc["c", "group_id"]
    assert grouped.set_index("unit_id").loc["c", "group_status"].startswith("unresolved")


def test_contiguous_groups_partition_chain_deterministically_without_dhl_target_use():
    from hagrid_demand.baseline.potentials import build_contiguous_groups

    units = pd.DataFrame({"unit_id": list("abcde"), "plz": ["1"] * 5,
                          "persons": [1.] * 5, "companies": [0.] * 5,
                          "dhl_target": [1., 2., 3., 4., 5.]})
    edges = pd.DataFrame({"left": list("abcd"), "right": list("bcde")})
    first = build_contiguous_groups(units, edges, min_persons=2, min_companies=0, max_units=2)
    shuffled = build_contiguous_groups(units.sample(frac=1, random_state=3).assign(dhl_target=[99., 0., 1000., 7., -2.]),
                                       edges.sample(frac=1, random_state=4), min_persons=2, min_companies=0, max_units=2)
    first = first.sort_values("unit_id").reset_index(drop=True)
    shuffled = shuffled.sort_values("unit_id").reset_index(drop=True)
    assert first[["unit_id", "group_id", "group_status"]].equals(shuffled[["unit_id", "group_id", "group_status"]])
    assert first.groupby("group_id").size().max() <= 2
    assert first.set_index("unit_id").loc["a", "group_id"] == first.set_index("unit_id").loc["b", "group_id"]
    assert first.set_index("unit_id").loc["c", "group_id"] == first.set_index("unit_id").loc["d", "group_id"]
    assert first.set_index("unit_id").loc["e", "group_status"].startswith("unresolved")


def test_contiguous_groups_finds_feasible_connected_partition_before_residuals():
    from hagrid_demand.baseline.potentials import build_contiguous_groups

    units = pd.DataFrame({"unit_id": list("abcde"), "plz": ["1"] * 5,
                          "persons": [2., 1., 2., 0., 1.], "companies": [0.] * 5})
    edges = pd.DataFrame({"left": list("abcd"), "right": list("bcde")})
    grouped = build_contiguous_groups(units, edges, min_persons=3, min_companies=0, max_units=3)
    assert grouped.group_status.eq("resolved").all()
    assert grouped.groupby("group_id").size().max() <= 3
    assert grouped.set_index("unit_id").loc["a", "group_id"] == grouped.set_index("unit_id").loc["b", "group_id"]
    assert grouped.set_index("unit_id").loc["c", "group_id"] == grouped.set_index("unit_id").loc["e", "group_id"]
    with pytest.raises(ValueError, match="finite positive integer"):
        build_contiguous_groups(units, edges, min_persons=3, min_companies=0, max_units=np.nan)


def test_contiguous_groups_repairs_small_star_and_path_partitions():
    from hagrid_demand.baseline.potentials import build_contiguous_groups

    star = pd.DataFrame({"unit_id": ["a", "b", "c"], "plz": ["1"] * 3,
                         "persons": [2., 3., 1.], "companies": [0.] * 3})
    star_result = build_contiguous_groups(star, pd.DataFrame({"left": ["a", "a"], "right": ["b", "c"]}),
                                         min_persons=3, min_companies=0, max_units=2).set_index("unit_id")
    assert star_result.group_status.eq("resolved").all()
    assert star_result.loc["a", "group_id"] == star_result.loc["c", "group_id"]
    assert star_result.loc["a", "group_id"] != star_result.loc["b", "group_id"]

    path = pd.DataFrame({"unit_id": ["a", "b", "c", "d"], "plz": ["1"] * 4,
                         "persons": [3., 0., 2., 1.], "companies": [0.] * 4})
    path_result = build_contiguous_groups(path, pd.DataFrame({"left": ["a", "b", "c"], "right": ["b", "c", "d"]}),
                                         min_persons=3, min_companies=0, max_units=2).set_index("unit_id")
    assert path_result.group_status.eq("resolved").all()
    assert path_result.loc["a", "group_id"] == path_result.loc["b", "group_id"]
    assert path_result.loc["c", "group_id"] == path_result.loc["d", "group_id"]


def test_contiguous_groups_encodes_group_ids_without_colon_collisions_and_marks_search_budget():
    from hagrid_demand.baseline.potentials import build_contiguous_groups

    colliding = pd.DataFrame({"unit_id": ["c", "b:c"], "plz": ["a:b", "a"],
                              "persons": [1., 1.], "companies": [0., 0.]})
    distinct = build_contiguous_groups(colliding, pd.DataFrame({"left": [], "right": []}), 0, 0, 1)
    assert distinct.group_id.nunique() == 2

    units = pd.DataFrame({"unit_id": [str(index) for index in range(17)], "plz": ["1"] * 17,
                          "persons": [1.] * 17, "companies": [0.] * 17})
    edges = pd.DataFrame({"left": [str(index) for index in range(16)],
                          "right": [str(index + 1) for index in range(16)]})
    budgeted = build_contiguous_groups(units, edges, min_persons=2, min_companies=0, max_units=2)
    assert "unresolved_search_budget" in set(budgeted.group_status)


def test_contiguous_groups_scale_without_recursive_search_and_require_string_ids():
    from hagrid_demand.baseline.potentials import build_contiguous_groups

    chain = pd.DataFrame({"unit_id": [str(i) for i in range(1100)], "plz": ["1"] * 1100,
                          "persons": [1.] * 1100, "companies": [0.] * 1100})
    edges = pd.DataFrame({"left": [str(i) for i in range(1099)], "right": [str(i + 1) for i in range(1099)]})
    grouped = build_contiguous_groups(chain, edges, min_persons=0, min_companies=0, max_units=1)
    assert len(grouped) == 1100 and grouped.group_status.eq("resolved").all()
    complete = pd.DataFrame({"unit_id": [f"u{i}" for i in range(30)], "plz": ["1"] * 30,
                             "persons": [0.] * 30, "companies": [0.] * 30})
    complete_edges = pd.DataFrame([(f"u{i}", f"u{j}") for i in range(30) for j in range(i + 1, 30)],
                                  columns=["left", "right"])
    assert len(build_contiguous_groups(complete, complete_edges, 0, 0, 15)) == 30
    invalid = pd.DataFrame({"unit_id": [1, "1"], "plz": ["1", "1"], "persons": [1., 1.], "companies": [0., 0.]})
    with pytest.raises(ValueError, match="normalized unique string"):
        build_contiguous_groups(invalid, pd.DataFrame({"left": [], "right": []}), 0, 0, 1)


def test_structure_comparison_uses_complete_held_out_groups_without_claiming_a_winner():
    from hagrid_demand.baseline.potentials import compare_structure_models

    support = pd.DataFrame({"plz": ["a", "a", "b", "b"], "persons": [1, 3, 2, 4],
                            "companies": [2, 1, 1, 3], "target": [5, 4, 3, 8]})
    compared = compare_structure_models(support, folds=[(["a"], ["b"])])
    assert compared["models"]["historical_q75"]["holdouts"] == 1
    assert compared["models"]["nonnegative_mean"]["metrics"]["mae"] is not None
    assert compared["selection"] == "comparison_only"
    with pytest.raises(ValueError, match="at least"):
        compare_structure_models(support, folds=[])


def test_structure_comparison_validates_folds_and_pools_heldout_rows():
    from hagrid_demand.baseline.potentials import compare_structure_models

    support = pd.DataFrame({"plz": ["a", "a", "b", "c", "c"], "persons": [1., 0., 1., 1., 0.],
                            "companies": [0., 1., 0., 0., 1.], "target": [1., 2., 4., 1., 3.]})
    compared = compare_structure_models(support, folds=[(["a"], ["b"]), (["a"], ["c"])])
    expected = np.mean(np.abs(np.array([.15205915924489793 + 8.1078087809025286e-07,
                                        .15205915924489793 + 8.1078087809025286e-07,
                                        .89862556511587188 + 8.1078087809025286e-07]) - np.array([4., 1., 3.])))
    assert compared["models"]["historical_q75"]["holdout_rows"] == 3
    assert compared["models"]["historical_q75"]["metrics"]["mae"] == pytest.approx(expected)
    for folds, message in (
        ([( ["a"], ["a"])], "disjoint"),
        ([( ["a"], ["z"])], "unknown"),
        ([( ["a"], ["b"]), (["a"], ["b"])], "repeat"),
        ([( [], ["b"])], "nonempty"),
        ([( ["a"], [])], "nonempty"),
    ):
        with pytest.raises(ValueError, match=message):
            compare_structure_models(support, folds=folds)


def test_structure_comparison_marks_rank_deficient_employee_candidate_noncomparable():
    from hagrid_demand.baseline.potentials import compare_structure_models

    support = pd.DataFrame({"plz": ["a", "a", "b", "b"], "persons": [1., 0., 1., 0.],
                            "companies": [0., 1., 0., 1.], "employees": [0., 0., 0., 0.],
                            "branch": ["x", "x", "x", "x"], "target": [2., 4., 3., 6.]})
    result = compare_structure_models(support, folds=[(["a"], ["b"])])
    candidate = result["models"]["employee_branch_candidate"]
    assert candidate["comparison_status"] == "non_comparable"
    assert candidate["metrics"] is None


def test_reference_retains_1000_and_preserves_unlocated_known_postal_mass():
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    dhl = pd.DataFrame({"observation_id": ["keep", "drop", "unlocated"], "plz": ["10000", "10000", "20000"],
                        "value": [1000., 1000.0001, 5.], "value_status": ["observed"] * 3})
    result = solve_reference(build_potentials(_sites()), dhl, _profiles(), b=.2, operating_days=313)
    postal = result["postal"].set_index("plz")
    assert postal.loc["10000", "dhl_retained_mean"] == 1000.
    assert result["checks"]["scope_ledger"]["excluded_volume"] == pytest.approx(1000.0001)
    sites = result["sites"].set_index("site_id")
    assert sites.loc["h2", "allocation_status"] == "unlocated"
    assert sites.loc["h2", "reference_annual"] > 0
    assert result["source_quality"]["unknown_plz_sites"] == ["lost"]
    assert result["checks"]["dhl_reconstructed_mean"] == pytest.approx(1005.)
    allocated = sites.groupby(["plz", "segment"])["reference_annual"].sum()
    postal = result["postal"].set_index("plz")
    assert allocated.loc[("10000", "private")] == pytest.approx(postal.loc["10000", "private_annual"])
    assert allocated.loc[("20000", "private")] == pytest.approx(postal.loc["20000", "private_annual"])
    assert abs(result["checks"]["allocation_balance"]["regional_error"]) <= 1e-8
    assert result["implied_rates"]["persons_packages_per_operating_day"] > 0


def test_reference_can_reconcile_raw_provider_priors_before_solving_eta():
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["10000"], "value": [10.], "value_status": ["observed"]})
    raw_profiles = {"m": np.array([.52, .48]), "q_prior": np.array([1 / 13, 1 / 3]),
                    "lower": np.zeros(2), "upper": np.ones(2), "scale": np.ones(2),
                    "carriers": ["DHL", "Other"]}
    result = solve_reference(build_potentials(_sites()), dhl, raw_profiles, b=.2, operating_days=313)
    assert result["checks"]["reconciliation"]["market_b2b"] == pytest.approx(.2)


def test_reference_resolves_dhl_by_validated_label_instead_of_carrier_column_order():
    """Reordering carrier columns cannot turn Amazon's share into the LSP divisor."""
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    dhl = pd.DataFrame({"observation_id": ["a", "b"], "plz": ["10000", "20000"],
                        "value": [10., 20.], "value_status": ["observed", "observed"]})
    # LSP is deliberately column one.  Its market share is .32, while Amazon's is .68.
    profiles = {"conditional": np.array([[.8, .2], [.2, .8]]), "carriers": ["Amazon", "DHL"]}
    result = solve_reference(build_potentials(_sites()), dhl, profiles, b=.2, operating_days=313)

    assert result["checks"]["dhl_carrier"] == "DHL"
    assert result["checks"]["dhl_carrier_index"] == 1
    assert result["regional_annual"] == pytest.approx(30. * 313. / .32, rel=1e-10)


@pytest.mark.parametrize("labels", [["Other", "Amazon"], ["DHL", "dhl"]])
def test_reference_rejects_missing_or_ambiguous_dhl_carrier_labels(labels):
    """A reference observation has one explicit carrier identity."""
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["10000"], "value": [10.],
                        "value_status": ["observed"]})
    with pytest.raises(ValueError, match="DHL carrier"):
        solve_reference(build_potentials(_sites()), dhl,
                        {"conditional": np.array([[.6, .4], [.2, .8]]), "carriers": labels},
                        b=.2, operating_days=313)


def test_reference_rejects_unlabeled_multicarrier_profiles_without_explicit_dhl_index():
    """A two-column conditional array alone has no carrier identity."""
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["10000"], "value": [10.],
                        "value_status": ["observed"]})
    with pytest.raises(ValueError, match="carrier labels or dhl_index"):
        solve_reference(build_potentials(_sites()), dhl,
                        {"conditional": np.array([[.6, .4], [.2, .8]])}, b=.2, operating_days=313)


def test_reference_applies_verified_postal_scope_before_threshold_and_anchor_aggregation():
    """Out-of-scope positive, zero, and oversized records stay ledger-only and need no support."""
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    dhl = pd.DataFrame({
        "observation_id": ["inside", "outside-positive", "outside-zero", "outside-large"],
        "plz": ["10000", "99991", "99992", "99993"],
        "value": [10., 7., 0., 1001.],
        "value_status": ["observed"] * 4,
    })
    result = solve_reference(build_potentials(_sites()), dhl, _profiles(), b=.2, operating_days=313,
                             scope_plz=["10000"])

    assert result["postal"].plz.tolist() == ["10000"]
    ledger = result["checks"]["scope_ledger"]
    assert ledger["retained_volume"] == pytest.approx(10.)
    assert ledger["out_of_scope_positive_rows"] == 2
    assert ledger["out_of_scope_zero_rows"] == 1
    assert ledger["out_of_scope_above_threshold_rows"] == 1
    assert ledger["excluded_rows"] == 0


def test_reference_persists_adaptive_eta_endpoints_reachable_range_and_status():
    """The reconciliation ledger distinguishes initial numeric endpoints from adaptive expansion."""
    from hagrid_demand.baseline.reference import solve_reference

    potential = pd.DataFrame({"site_id": ["p", "b"], "plz": ["1", "1"],
                              "segment": ["private", "business"], "weight": [1e100, 1.],
                              "allocation_status": ["located", "located"]})
    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["1"], "value": [1.],
                        "value_status": ["observed"]})
    result = solve_reference(potential, dhl, {"conditional": np.array([[.5, .5], [.5, .5]]), "dhl_index": 0},
                             b=.5, operating_days=313)
    eta = result["checks"]["eta_diagnostics"]
    assert eta["initial_endpoints"] == [-30.0, 30.0]
    assert eta["expanded_endpoints"][1] > 30.0
    assert eta["reachable_range"][0] <= .5 <= eta["reachable_range"][1]
    assert eta["status"] == "root_found"


def test_reference_rejects_invalid_and_unidentified_inputs():
    from hagrid_demand.baseline.potentials import build_potentials
    from hagrid_demand.baseline.reference import solve_reference

    potentials = build_potentials(_sites())
    good = pd.DataFrame({"observation_id": ["a"], "plz": ["10000"], "value": [5.], "value_status": ["observed"]})
    with pytest.raises(ValueError, match="missing|negative"):
        solve_reference(potentials, good.assign(value=-1.), _profiles(), b=.2, operating_days=313)
    with pytest.raises(ValueError, match="positive LSP.*share"):
        solve_reference(potentials, good, {"conditional": np.array([[0., 1.], [0., 1.]]), "dhl_index": 0}, b=.2, operating_days=313)
    with pytest.raises(ValueError, match="empty|support"):
        solve_reference(potentials.iloc[0:0], good, _profiles(), b=.2, operating_days=313)
    with pytest.raises(ValueError, match="finite"):
        solve_reference(potentials.assign(weight=np.inf), good, _profiles(), b=.2, operating_days=313)
    with pytest.raises(ValueError, match="finite"):
        solve_reference(potentials, good.assign(value=np.inf), _profiles(), b=.2, operating_days=313)


def test_reference_handles_constant_b2b_and_diagnoses_missing_root():
    from hagrid_demand.baseline.reference import solve_reference

    private_only = pd.DataFrame({"site_id": ["h"], "plz": ["1"], "segment": ["private"], "weight": [2.],
                                 "allocation_status": ["located"], "population": [2.], "employees": [np.nan], "branch": [None]})
    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["1"], "value": [4.], "value_status": ["observed"]})
    profiles = {"conditional": np.array([[1., 0.], [1., 0.]]), "dhl_index": 0}
    solved = solve_reference(private_only, dhl, profiles, b=0., operating_days=313)
    assert solved["checks"]["k"] == 1.
    with pytest.raises(ValueError, match="constant|reachable|sign change"):
        solve_reference(private_only, dhl, profiles, b=.2, operating_days=313)
    with pytest.raises(ValueError, match="business support"):
        solve_reference(private_only, dhl, profiles, b=1., operating_days=313)


def test_reference_expands_eta_bracket_and_keeps_true_nonroot_diagnostic():
    from hagrid_demand.baseline.reference import solve_reference

    profiles = {"conditional": np.array([[.8, .2], [.2, .8]]), "dhl_index": 0}
    extreme = pd.DataFrame({"site_id": ["p1", "b1", "p2", "b2"], "plz": ["1", "1", "2", "2"],
                            "segment": ["private", "business", "private", "business"],
                            "weight": [1e20, 1., 1., 1e20], "allocation_status": ["located"] * 4})
    dhl = pd.DataFrame({"observation_id": ["a", "b"], "plz": ["1", "2"], "value": [1., 1.], "value_status": ["observed"] * 2})
    solved = solve_reference(extreme, dhl, profiles, b=6 / 7, operating_days=313)
    assert solved["checks"]["eta"] > 30
    nonroot = extreme.assign(weight=[1., 0., 1., 1.])
    with pytest.raises(ValueError, match="sign change|reachable"):
        solve_reference(nonroot, dhl, profiles, b=.9, operating_days=313)


def test_reference_expands_before_declaring_extreme_mixed_support_constant():
    from hagrid_demand.baseline.reference import solve_reference

    potential = pd.DataFrame({"site_id": ["p", "b"], "plz": ["1", "1"],
                              "segment": ["private", "business"], "weight": [1e100, 1.],
                              "allocation_status": ["located", "located"]})
    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["1"], "value": [1.], "value_status": ["observed"]})
    result = solve_reference(potential, dhl, {"conditional": np.array([[.5, .5], [.5, .5]]), "dhl_index": 0}, b=.5, operating_days=313)
    assert result["checks"]["eta"] == pytest.approx(100 * np.log(10), abs=1e-8)


def test_reference_recognizes_true_structurally_constant_pure_support():
    from hagrid_demand.baseline.reference import solve_reference

    potential = pd.DataFrame({"site_id": ["p", "b"], "plz": ["1", "2"],
                              "segment": ["private", "business"], "weight": [1., 1.],
                              "allocation_status": ["located", "located"]})
    dhl = pd.DataFrame({"observation_id": ["a", "b"], "plz": ["1", "2"],
                        "value": [1., 1.], "value_status": ["observed", "observed"]})
    result = solve_reference(potential, dhl, {"conditional": np.array([[.6, .4], [.2, .8]]), "dhl_index": 0}, b=.75,
                             operating_days=313)
    assert result["checks"]["eta"] == 0.


def test_reference_all_pure_segments_and_direct_allocation_balances():
    from hagrid_demand.baseline.reference import solve_reference

    private = pd.DataFrame({"site_id": ["p1", "p2", "p3"], "plz": ["1"] * 3,
                            "segment": ["private"] * 3, "weight": [1., 2., 4.],
                            "allocation_status": ["located"] * 3})
    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["1"], "value": [999.], "value_status": ["observed"]})
    profile = {"conditional": np.array([[.001, .999], [0., 0.]]), "dhl_index": 0}
    solved_private = solve_reference(private, dhl, profile, b=0., operating_days=313)
    assert solved_private["regional_annual"] == pytest.approx(999 / .001 * 313)
    assert solved_private["checks"]["allocation_balance"]["regional_error"] == pytest.approx(0.)
    business = private.assign(segment="business", site_id=["b1", "b2", "b3"])
    business_profile = {"conditional": np.array([[0., 0.], [.001, .999]]), "dhl_index": 0}
    solved_business = solve_reference(business, dhl, business_profile, b=1., operating_days=313)
    assert solved_business["regional_annual"] == pytest.approx(999 / .001 * 313)


def test_reference_keeps_extreme_eta_as_log_k_and_avoids_site_allocation_overflow():
    from hagrid_demand.baseline.reference import solve_reference

    extreme = pd.DataFrame({"site_id": ["p", "b"], "plz": ["1", "1"],
                            "segment": ["private", "business"], "weight": [1e300, 1e-300],
                            "allocation_status": ["located", "located"]})
    dhl = pd.DataFrame({"observation_id": ["a"], "plz": ["1"], "value": [1.], "value_status": ["observed"]})
    solved = solve_reference(extreme, dhl, {"conditional": np.array([[.5, .5], [.5, .5]]), "dhl_index": 0}, .5, 313)
    assert solved["checks"]["eta"] == pytest.approx(600 * np.log(10), abs=1e-8)
    assert solved["checks"]["k"] is None
    assert solved["checks"]["k_status"] == "overflow_log_k_retained"
    underflow = extreme.assign(weight=[1e-300, 1e300])
    negative = solve_reference(underflow, dhl, {"conditional": np.array([[.5, .5], [.5, .5]]), "dhl_index": 0}, .5, 313)
    assert negative["checks"]["eta"] == pytest.approx(-600 * np.log(10), abs=1e-8)
    assert negative["checks"]["k"] is None
    assert negative["checks"]["k_status"] == "underflow_log_k_retained"
    assert np.isfinite(negative["checks"]["log_k"])
    huge_site = pd.DataFrame({"site_id": ["p"], "plz": ["1"], "segment": ["private"], "weight": [1e308],
                              "allocation_status": ["located"]})
    allocated = solve_reference(huge_site, dhl, {"conditional": np.array([[1., 0.], [0., 0.]]), "dhl_index": 0}, 0., 313)
    assert np.isfinite(allocated["sites"].reference_annual).all()


def test_notebook_06_business_potential_adds_a_tenth_per_employee():
    from hagrid_demand.baseline.potentials import build_potentials

    result = build_potentials(_sites(), employee_weight=0.1)
    business = result.loc[result.segment.eq("business")]
    assert business.weight.tolist() == pytest.approx((1 + 0.1 * business.employees).tolist())
    assert result.potential_model.eq("company_plus_employees").all()
