"""Candidate geographic links, explicitly not measurement allocation weights."""

import geopandas as gpd
import pandas as pd


def candidate_links(sites, streets, postal, max_distance):
    if max_distance <= 0:
        raise ValueError("max_distance must be positive")
    if sites.crs != streets.crs or sites.crs != postal.crs or sites.crs.is_geographic:
        raise ValueError("Linking requires one projected CRS")
    if any(axis.unit_conversion_factor != 1 for axis in sites.crs.axis_info[:2]):
        raise ValueError("Linking distance thresholds require metre units")
    eligible = sites.loc[sites.location_status.eq("source_point_unverified"), ["site_id", "geometry"]].copy()
    membership = gpd.sjoin(eligible, postal[["plz", "geometry"]], how="left", predicate="intersects")
    membership = membership[["site_id", "plz"]].drop_duplicates()
    counts = membership.groupby("site_id").plz.count()
    membership["postal_candidates"] = membership.site_id.map(counts)
    unique_postal = membership.loc[membership.postal_candidates.eq(1)].set_index("site_id").plz
    eligible["plz"] = eligible.site_id.map(unique_postal)
    parts = []
    # Match only within a uniquely assigned postal area; never snap across uncertain coverage.
    for plz, subset in eligible.dropna(subset=["plz"]).groupby("plz"):
        lines = streets.loc[streets.plz.eq(plz) & streets.geometry_usable,
                            ["observation_id", "repeated_street_key", "geometry"]]
        if lines.empty:
            continue
        joined = gpd.sjoin_nearest(subset, lines, how="inner", max_distance=max_distance, distance_col="distance_m")
        if not joined.empty:
            parts.append(pd.DataFrame(joined.drop(columns=["geometry", "index_right"])))
    columns = ["site_id", "plz", "observation_id", "repeated_street_key", "distance_m"]
    links = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame(columns=columns)
    if len(links):
        links["candidate_count"] = links.groupby("site_id").observation_id.transform("size")
        links["link_status"] = "nearest_within_postal_unverified"
        links.loc[links.repeated_street_key, "link_status"] = "repeated_street_definition_unresolved"
        links.loc[links.candidate_count > 1, "link_status"] = "equidistant_candidates"
    else:
        links["candidate_count"] = pd.Series(dtype="int64")
        links["link_status"] = pd.Series(dtype="string")
    # Deliberately no A weight: nearest geometry is not proof of observation support.
    status = sites[["site_id", "recipient_type", "population", "location_status"]].copy()
    status["postal_candidates"] = status.site_id.map(counts).fillna(0).astype(int)
    status["dhl_candidates"] = status.site_id.map(links.groupby("site_id").size()).fillna(0).astype(int)
    status["link_status"] = "no_street_within_threshold_or_coverage"
    status.loc[status.postal_candidates.eq(0), "link_status"] = "no_postal_coverage"
    status.loc[status.postal_candidates.gt(1), "link_status"] = "ambiguous_postal_boundary"
    if len(links):
        labels = links.drop_duplicates("site_id").set_index("site_id").link_status
        matched = status.site_id.isin(labels.index)
        status.loc[matched, "link_status"] = status.loc[matched, "site_id"].map(labels)
    bad_location = ~status.location_status.eq("source_point_unverified")
    status.loc[bad_location, "link_status"] = "unresolved_site_location"
    return links.sort_values(["site_id", "observation_id"]).reset_index(drop=True), membership, status
