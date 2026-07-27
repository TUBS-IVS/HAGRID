# -*- coding: utf-8 -*-
"""Build the canonical KPI CSVs (+ dashboard, Task 9) for ONE run directory.

Usage (from analysis/kpi/):
    python -u build_kpis.py --run-dir ../../hagrid-matsim-output/DRT_BASELINE_13052025_married120_iter150_jsprit100
"""
import argparse
import sys
from pathlib import Path

import economics
import extract_drt
import extract_freight
import extract_shareduse
import freight_events
import kpi_writer
import pax_only
import timeseries
from common import row as common_row
from events_cache import ensure_caches
from run_meta import load_run_meta

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "drt-headline"))
import drt_service_time  # noqa: E402

# 1c/1d register their scenario-specific extractors here:
# each entry: (predicate(run_dir, meta) -> bool, extract(run_dir, prefix) -> rows)
EXTRACTORS = []
EXTRACTORS.append((extract_shareduse.has_shareduse_stats, extract_shareduse.extract))


def _default_fleet_file(run_dir, meta):
    # <module-root>/hagrid-output/<run_id>/<run_id>_drt_fleet.xml.gz
    cand = run_dir.parent.parent / "hagrid-output" / meta.run_id / (meta.run_id + "_drt_fleet.xml.gz")
    return cand if cand.exists() else None


def build(run_dir, no_events=False, fleet_file=None, out_dir=None):
    run_dir = Path(run_dir)
    meta = load_run_meta(run_dir)
    out = Path(out_dir) if out_dir else run_dir / "analysis"
    out.mkdir(parents=True, exist_ok=True)

    is_drt = (run_dir / (meta.prefix + ".drt_customer_stats_drt.csv")).exists()
    has_freight = (run_dir / "analysis" / "freight" / "TimeDistance_perCarrier.tsv").exists()

    drt_cache = frt_cache = None
    if not no_events and (run_dir / (meta.prefix + ".output_events.xml.gz")).exists():
        drt_cache, frt_cache = ensure_caches(run_dir, meta.prefix)

    fleet = Path(fleet_file) if fleet_file else _default_fleet_file(run_dir, meta)

    # Reconstruct once (~minutes over the ~95 MB DRT event cache) and share the result
    # with every consumer instead of each one re-scanning the events.
    recon = (drt_service_time.reconstruct(str(drt_cache), str(fleet) if fleet else None)
             if drt_cache else None)

    # Parse the freight events cache and the carriers XML (`pf`) once, up
    # front, so both the hourly series below and the provider/vehicles
    # blocks further down can reuse them instead of each re-parsing --
    # graceful degradation preserved: any freight XML problem here just
    # leaves pf None, which skips the hourly series and the provider block
    # below (extract_vehicles falls back to parsing pf itself internally).
    fev = freight_events.parse_freight_cache(frt_cache) if frt_cache is not None else None
    pf = None
    if has_freight:
        try:
            import extract_freight_provider as efp
            pf = efp.parse_run(run_dir, meta.prefix)
        except Exception as e:
            print("[build] freight provider parse skipped: " + str(e))  # ASCII only

    rows = []
    if is_drt:
        rows += extract_drt.extract(run_dir, meta.prefix, fleet_file=fleet, recon=recon)
    if has_freight:
        rows += extract_freight.extract(run_dir, meta.prefix, pf=pf)
    for predicate, extract_fn in EXTRACTORS:
        if predicate(run_dir, meta):
            rows += extract_fn(run_dir, meta.prefix)
    # Promote the Shared-Use pax-only corrections to the canonical KPI names
    # BEFORE economics runs -- economics._get("drt_rides") must see the
    # pax-only ride count, not the parcel-contaminated one.
    pax_only.apply_overrides(rows)
    if meta.meta_source != "run_metadata.json":
        # Degraded metadata changes which KPIs exist at all (no fleet_size ->
        # no economics rows), so it has to travel with the CSV, not just stdout.
        rows.append(common_row("meta", "run_meta_degraded", 1, "flag",
                               "dir-name parsing: " + meta.run_dir_name))
    rows += economics.extract(rows, fleet_size=meta.fleet_size)

    kpi_writer.write_long(rows, meta, out / "kpis_long.csv")
    kpi_writer.write_wide(rows, meta, out / "kpis_wide.csv")
    ts = timeseries.extract(run_dir, meta.prefix, freight_cache=frt_cache)
    if fev is not None and pf is not None:
        ts += freight_events.hourly_series(fev, pf.carriers, pf.excluded)
    timeseries.write(ts, meta, out / "kpi_timeseries.csv")

    print("KPI CSVs written to " + str(out) + " (" + str(len(rows)) + " KPIs, "
          + str(len(ts)) + " timeseries points)")

    import extract_iterations, distributions
    it_rows = extract_iterations.extract(run_dir, meta.prefix)
    extract_iterations.write(it_rows, meta, out / "kpi_iterations.csv")

    # Network-based DRT distributions (drt_tour_distance, occ_km): reconstruct
    # per-vehicle link paths + link geometry ONCE here so Task 6 (maps.py) can
    # reuse the same veh_path/link_geo objects without re-scanning events/network.
    import geometry
    veh_path = link_geo = None
    veh_km = occ_km_shares = None
    network = run_dir / (meta.prefix + ".output_network.xml.gz")
    if drt_cache is not None and network.exists():
        veh_path, used = geometry.reconstruct_drt_paths(drt_cache)
        freight_used = geometry.freight_used_links(fev) if fev is not None else set()
        link_geo = geometry.load_link_geometry(network, used | freight_used)
        veh_km = {}
        dist_by_occ = {}
        for v, path in veh_path.items():
            km = 0.0
            for lid, occ in path:
                lg = link_geo.get(lid)
                if lg is None:
                    continue
                km += lg.length_m / 1000.0
                dist_by_occ[occ] = dist_by_occ.get(occ, 0.0) + lg.length_m
            veh_km[v] = km
        tot = sum(dist_by_occ.values())
        occ_km_shares = {lv: dist_by_occ[lv] / tot for lv in dist_by_occ} if tot else {}
    elif drt_cache is not None:
        print("[build] network file absent -> drt_tour_distance/occ_km skipped")  # ASCII

    dist_rows = distributions.extract(run_dir, meta.prefix, recon=recon,
                                       veh_km=veh_km, occ_km_shares=occ_km_shares)
    distributions.write(dist_rows, meta, out / "kpi_distributions.csv")
    prov_rows = []
    if has_freight and pf is not None:
        try:
            prov_rows = efp.extract(run_dir, meta.prefix, pf=pf)
            efp.write(prov_rows, meta, out / "kpis_provider.csv")
        except Exception as e:
            print("[build] provider KPIs skipped: " + str(e))  # ASCII only
    print("v2 CSVs: iterations={} distributions={} provider={}".format(
        len(it_rows), len(dist_rows), len(prov_rows)))

    import extract_vehicles
    veh_rows = extract_vehicles.extract(run_dir, meta.prefix, recon=recon, pf=pf)
    if veh_rows:
        extract_vehicles.write(veh_rows, meta, out / "kpi_vehicles.csv")
    print("kpi_vehicles: {} rows".format(len(veh_rows)))

    # Plan D Task 8: build the Leaflet map layers ONLY when events were parsed
    # (maps need reconstructed DRT paths / freight events). --no-events (or a
    # run without an events file) leaves both caches None -> blocks stays None
    # -> render_run_page gets maps=None -> no vendored Leaflet bytes.
    import maps, render_maps
    blocks = None
    if not no_events and (drt_cache is not None or frt_cache is not None):
        md = maps.build_map_data(run_dir, meta.prefix, veh_path=veh_path,
                                 link_geo=link_geo, fev=fev,
                                 carriers=pf.carriers if pf is not None else None,
                                 excluded=pf.excluded if pf is not None else None)
        maps.write(md, out / "map_data.json")
        blocks = render_maps.build_blocks(md, uid="m0")

    import render
    data = render.load_run_data(out)
    html = render.render_run_page(data, title=meta.run_id, maps=blocks)
    (out / "kpi_dashboard.html").write_text(html, encoding="utf-8")
    print("dashboard: " + str(out / "kpi_dashboard.html"))
    return out


def main():
    ap = argparse.ArgumentParser(description="Canonical KPI CSVs for one HAGRID run")
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--no-events", action="store_true",
                    help="skip event-based KPIs (service time, freight stops/h)")
    ap.add_argument("--fleet-file", default=None)
    ap.add_argument("--out-dir", default=None)
    a = ap.parse_args()
    build(a.run_dir, no_events=a.no_events, fleet_file=a.fleet_file, out_dir=a.out_dir)


if __name__ == "__main__":
    main()
