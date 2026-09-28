import pandas as pd
import pytest

from hagrid_demand.dashboard import aggregate_sites


def test_dashboard_does_not_double_count_ties_or_postal_boundaries():
    sites = pd.DataFrame({"site_id": ["a", "b"], "recipient_type": ["private", "business"],
                          "population": [3, 0], "employees": [None, 10], "branch": [None, "office"]})
    status = pd.DataFrame({"site_id": ["a", "b"], "link_status": ["equidistant_candidates", "ambiguous_postal_boundary"]})
    membership = pd.DataFrame({"site_id": ["a", "b", "b"], "plz": ["1", "1", "2"], "postal_candidates": [1, 2, 2]})
    links = pd.DataFrame({"site_id": ["a", "a"], "distance_m": [5., 5.]})
    result = aggregate_sites(sites, status, membership, links)
    assert len(result) == 2
    assert result.population.sum() == 3
    assert result.employees.sum() == 10
    assert result.distance_m.notna().sum() == 1
    assert result.plz.notna().sum() == 1
    assert result.unresolved.sum() == 2


def test_dashboard_rejects_incomplete_status_table():
    with pytest.raises(ValueError, match="identities differ"):
        aggregate_sites(pd.DataFrame({"site_id": ["a"]}), pd.DataFrame({"site_id": []}), pd.DataFrame(), pd.DataFrame())
