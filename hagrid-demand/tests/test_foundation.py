import geopandas as gpd
import pandas as pd
import pytest
from shapely.geometry import LineString, Point, box

from hagrid_demand.data import build_residential, read_hermes, read_persons, require_unique
from hagrid_demand.linking import candidate_links
from hagrid_demand.pipeline import load_config


CRS = "EPSG:25832"


def test_population_conservation_missing_ids_and_conflicting_locations():
    p = gpd.GeoDataFrame({"id": ["1", "2", "3", "4"], "Building": pd.array(["A", "A", "B", None], dtype="string"),
                         "Household": pd.array(["h", "h", None, None], dtype="string")},
                        geometry=[Point(0, 0), Point(20, 0), Point(50, 0), Point(60, 0)], crs=CRS)
    sites = build_residential(p, 5)
    assert sites.population.sum() == 4
    assert len(sites) == 3
    assert sites.loc[sites.population.eq(2), "location_status"].item() == "conflicting_building_coordinates"
    assert "missing_building_id" in sites.location_status.tolist()
    reordered = build_residential(p.iloc[::-1], 5)
    assert sites.site_id.tolist() == reordered.site_id.tolist()
    assert sites.geometry.to_wkt().tolist() == reordered.geometry.to_wkt().tolist()


def test_missing_or_duplicate_identity_stops_processing():
    with pytest.raises(ValueError, match="duplicate"):
        require_unique(pd.DataFrame({"id": ["a", "a"]}), "id", "test")


def test_missing_and_malformed_geometries_preserve_population(tmp_path):
    source = tmp_path / "persons.csv"
    source.write_text('id,Building,Household,geometry\n1,A,,\n2,B,,broken\n3,C,,POINT (0 0)\n', encoding="utf-8")
    persons = read_persons(source, CRS, CRS)
    sites = build_residential(persons, 5)
    assert sites.population.sum() == 3
    assert sites.location_status.eq("missing_geometry").sum() == 2


def test_hermes_zero_missing_negative_and_decimal_comma(tmp_path):
    source = tmp_path / "hermes.csv"
    source.write_text("\ufeffPLZ;2020;2021\n1234;0;1,5\n12345;;-2\n", encoding="utf-8")
    data = read_hermes(source)
    assert set(data.plz) == {"01234", "12345"}
    assert set(data.value_status) == {"reported_zero", "missing", "reported_positive", "invalid_negative"}
    assert data.loc[data.observation_id.eq("H04:01234:2021"), "value"].item() == 1.5


def test_spatial_ties_unmatched_and_boundary_are_not_silent_assignments():
    sites = gpd.GeoDataFrame({"site_id": ["tie", "far", "border", "bad"], "recipient_type": ["private"] * 4,
                             "population": [2, 3, 4, 5], "location_status": ["source_point_unverified"] * 3 + ["missing_geometry"]},
                            geometry=[Point(5, 5), Point(90, 90), Point(100, 5), None], crs=CRS)
    streets = gpd.GeoDataFrame({"observation_id": ["a", "b", "outside"], "plz": ["00001", "00001", "00002"],
                               "repeated_street_key": [False] * 3, "geometry_usable": [True] * 3},
                              geometry=[LineString([(0, 0), (0, 10)]), LineString([(10, 0), (10, 10)]),
                                        LineString([(90, 85), (90, 95)])], crs=CRS)
    postal = gpd.GeoDataFrame({"plz": ["00001", "00002"]}, geometry=[box(-1, -1, 100, 100), box(100, -1, 200, 100)], crs=CRS)
    links, membership, status = candidate_links(sites, streets, postal, 10)
    assert len(links) == 2
    assert set(links.site_id) == {"tie"}
    assert set(links.link_status) == {"equidistant_candidates"}
    assert "weight" not in links
    labels = status.set_index("site_id").link_status
    assert labels["border"] == "ambiguous_postal_boundary"
    assert labels["bad"] == "unresolved_site_location"
    assert labels["far"] == "no_street_within_threshold_or_coverage"
    assert status.population.sum() == 14


def test_empty_street_coverage_retains_all_sites():
    sites = gpd.GeoDataFrame({"site_id": ["a"], "recipient_type": ["business"], "population": [0],
                             "location_status": ["source_point_unverified"]}, geometry=[Point(0, 0)], crs=CRS)
    streets = gpd.GeoDataFrame({"observation_id": pd.Series(dtype=str), "plz": pd.Series(dtype=str),
                               "repeated_street_key": pd.Series(dtype=bool), "geometry_usable": pd.Series(dtype=bool)},
                              geometry=[], crs=CRS)
    postal = gpd.GeoDataFrame({"plz": ["00001"]}, geometry=[box(-1, -1, 1, 1)], crs=CRS)
    links, _, status = candidate_links(sites, streets, postal, 10)
    assert links.empty
    assert len(status) == 1
    assert status.dhl_candidates.item() == 0


def test_config_cannot_claim_verified_measurements(tmp_path):
    import json
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"input_dir": ".", "output_dir": "runs", "observation_definitions_confirmed": True}))
    with pytest.raises(ValueError, match="cannot certify"):
        load_config(path)
