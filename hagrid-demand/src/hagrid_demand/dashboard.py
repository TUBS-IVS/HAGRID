"""Offline run dashboard built exclusively from saved foundation artifacts."""

from datetime import datetime, timezone
import html
import hashlib
import json
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd

from .data import write_json


LABELS = {
    "nearest_within_postal_unverified": "Unique geometric candidate",
    "equidistant_candidates": "Several equally near candidates",
    "no_street_within_threshold_or_coverage": "No DHL candidate within the search radius",
    "no_postal_coverage": "No PLZ coverage",
    "ambiguous_postal_boundary": "Ambiguous PLZ assignment",
    "unresolved_site_location": "Unresolved site location",
    "repeated_street_definition_unresolved": "Repeated street key",
}
UNIQUE = "nearest_within_postal_unverified"


def aggregate_sites(sites, status, membership, links):
    """Exactly one row per site; ambiguous polygon memberships never duplicate totals."""
    if not sites.site_id.is_unique or not status.site_id.is_unique:
        raise ValueError("Dashboard requires unique site/status IDs")
    if set(sites.site_id) != set(status.site_id):
        raise ValueError("Dashboard site and status identities differ")
    eligible = membership.loc[membership.postal_candidates.eq(1) & membership.plz.notna(), ["site_id", "plz"]]
    if not eligible.site_id.is_unique:
        raise ValueError("Multiple supposedly unique postal assignments")
    frame = sites[["site_id", "recipient_type", "population", "employees", "branch"]].merge(
        status[["site_id", "link_status"]], on="site_id", validate="one_to_one")
    frame = frame.merge(eligible, on="site_id", how="left", validate="one_to_one")
    frame["employees"] = pd.to_numeric(frame.employees, errors="coerce").fillna(0).clip(lower=0)
    frame["unresolved"] = frame.link_status.ne(UNIQUE)
    frame["distance_m"] = frame.site_id.map(links.groupby("site_id").distance_m.min())
    return frame


def records(frame):
    return json.loads(frame.to_json(orient="records"))


def build_dashboard(run_dir):
    run = Path(run_dir).resolve()
    summary = json.loads((run / "summary.json").read_text(encoding="utf-8"))
    cfg = json.loads((run / "config.resolved.json").read_text(encoding="utf-8"))
    sources = json.loads((run / "sources.json").read_text(encoding="utf-8"))
    sites = pd.read_parquet(run / "sites.parquet", columns=["site_id", "recipient_type", "population", "employees", "branch"])
    status = pd.read_parquet(run / "site_link_status.parquet")
    membership = pd.read_parquet(run / "site_postal_candidates.parquet")
    links = pd.read_parquet(run / "dhl_candidate_links.parquet")
    frame = aggregate_sites(sites, status, membership, links)
    dhl = pd.read_parquet(run / "dhl_observations.parquet")
    hermes = pd.read_parquet(run / "hermes_observations.parquet")
    postal = gpd.read_parquet(run / "postal_support.parquet")
    views = {}
    for key, subset in [("all", frame), ("private", frame.loc[frame.recipient_type.eq("private")]),
                         ("business", frame.loc[frame.recipient_type.eq("business")])]:
        by_status = subset.groupby("link_status").agg(sites=("site_id", "size"), population=("population", "sum"),
                                                      employees=("employees", "sum")).reset_index()
        by_status["label"] = by_status.link_status.map(LABELS).fillna(by_status.link_status)
        local = subset.assign(unresolved_population=subset.population.where(subset.unresolved, 0),
                              unresolved_employees=subset.employees.where(subset.unresolved, 0))
        plz = local.groupby("plz").agg(sites=("site_id", "size"), population=("population", "sum"),
                                      employees=("employees", "sum"), unresolved=("unresolved", "sum"),
                                      unresolved_population=("unresolved_population", "sum"),
                                      unresolved_employees=("unresolved_employees", "sum"))
        plz["unresolved_pct"] = plz.unresolved / plz.sites * 100
        distances = subset.distance_m.dropna()
        edges = sorted(set([0., min(10., cfg["max_street_distance_m"]), min(25., cfg["max_street_distance_m"]),
                            min(50., cfg["max_street_distance_m"]), float(cfg["max_street_distance_m"])]))
        bins, _ = np.histogram(distances, bins=edges)
        views[key] = {"sites": len(subset), "population": int(subset.population.sum()), "employees": float(subset.employees.sum()),
                      "unresolved": int(subset.unresolved.sum()), "status": records(by_status), "postal": records(plz.reset_index()),
                      "unresolved_population": int(subset.loc[subset.unresolved, "population"].sum()),
                      "unresolved_employees": float(subset.loc[subset.unresolved, "employees"].sum()),
                      "sites_without_unique_plz": int(subset.plz.isna().sum()),
                      "distance": {"count": len(distances), "median": float(distances.median()) if len(distances) else None,
                                   "p95": float(distances.quantile(.95)) if len(distances) else None,
                                   "histogram": [{"label": f"{edges[i]:g}–{edges[i+1]:g} m", "count": int(n)} for i, n in enumerate(bins)]}}
    branches = frame.loc[frame.recipient_type.eq("business")].copy()
    branches["branch"] = branches.branch.fillna("Unknown").astype(str)
    branch_stats = branches.groupby("branch").agg(sites=("site_id", "size"), employees=("employees", "sum"),
                                                  unresolved=("unresolved", "sum")).reset_index()
    branch_stats["unresolved_pct"] = branch_stats.unresolved / branch_stats.sites * 100
    branch_stats = branch_stats.sort_values("unresolved", ascending=False)
    postal_shapes = []
    for row in postal.itertuples():
        geom = row.geometry.simplify(40, preserve_topology=True)
        polygons = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
        paths = []
        for polygon in polygons:
            for ring in [polygon.exterior, *polygon.interiors]:
                paths.append("M" + " L".join(f"{x:.0f},{-y:.0f}" for x, y in ring.coords) + " Z")
        postal_shapes.append({"plz": str(row.plz), "path": " ".join(paths)})
    bounds = postal.total_bounds.tolist()
    repeated = dhl.loc[dhl.repeated_street_key].groupby(["plz", "street"], dropna=False).agg(
        rows=("observation_id", "size"), distinct_values=("value", "nunique")).reset_index()
    tied = links.loc[links.candidate_count.gt(1)].merge(dhl[["observation_id", "street", "value"]], on="observation_id", validate="many_to_one")
    tie_groups = tied.groupby("site_id").agg(streets=("street", "nunique"), values=("value", "nunique"))
    tie_info = {"sites": len(tie_groups), "same_street_name": int(tie_groups.streets.eq(1).sum()),
                "same_name_same_value": int((tie_groups.streets.eq(1) & tie_groups['values'].eq(1)).sum()),
                "same_name_different_values": int((tie_groups.streets.eq(1) & tie_groups['values'].gt(1)).sum())}
    data = {"run_id": summary["run_id"], "generated_at": datetime.now(timezone.utc).isoformat(),
            "calibration_ready": summary["calibration_ready"], "counts": summary["counts"], "views": views,
            "branches": records(branch_stats), "shapes": postal_shapes,
            "map_box": [bounds[0], -bounds[3], bounds[2] - bounds[0], bounds[3] - bounds[1]],
            "threshold_m": cfg["max_street_distance_m"], "repeated_dhl_keys": records(repeated), "tie_diagnostics": tie_info,
            "hermes_years": sorted(hermes.year.unique().astype(int).tolist()),
            "sources": [{"id": s["id"], "file": s["file"], "kind": s["kind"], "unit": s["unit"],
                         "year": s["year"], "files": len(s["files"]), "hashed": all(f.get("sha256") for f in s["files"])}
                        for s in sources["sources"]]}
    data["renderer_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                               [Path(__file__), Path(__file__).with_name("dashboard.html")]}
    write_json(run / "dashboard_data.json", data)
    template = Path(__file__).with_name("dashboard.html").read_text(encoding="utf-8")
    # Script-safe JSON: never let a source label close its data element.
    embedded = json.dumps(data, ensure_ascii=False, allow_nan=False).replace("<", "\\u003c").replace("&", "\\u0026")
    document = template.replace("__RUN_ID__", html.escape(data["run_id"])).replace("__DASHBOARD_DATA__", embedded)
    (run / "dashboard.html").write_text(document, encoding="utf-8")
    return run / "dashboard.html"
