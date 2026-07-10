# -*- coding: utf-8 -*-
"""Per-provider and per-vehicle-type freight KPIs with low-utilization
re-allocation (v2 Plan A Task 3).

Low-util rule: a tour run by a DELIVERY carrier is "excluded" from the
meaningful fleet when its load factor (parcels carried / vehicle capacity)
falls below LOW_UTIL_THRESHOLD -- typically a near-empty leftover/mop-up
tour that would otherwise distort per-vehicle cost and load-factor
averages. SUPPLY carriers (line-haul into the depot) are never excluded --
only last-mile delivery tours are judged on parcel load factor.

Rather than dropping excluded tours outright (which would silently shrink
totals), their variable costs and missed-parcel counts are re-allocated:
scaled by ratio = survivingTours/allTours per carrier (1.0 for supply, since
supply carriers are never excluded). km/tours/tour_hours are NOT
re-allocated -- they come straight from TimeDistance_perCarrier.tsv, which
already reflects what actually happened on the road.

ParsedFreight.vehrecords is intentionally exposed (not just excluded) so a
later plan's per-vehicle VRP/summary tables and low-util notice can reuse
the same per-tour classification without re-parsing the carriers XML.
"""
import csv
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

from carriers_parse import attr_float, attr_int, parse_carriers, parse_vehicle_types
from freight_classify import carrier_type_of, classify_vehicle, provider_of

LOW_UTIL_THRESHOLD = 0.05


def prow(provider, kpi_name, value, unit, source):
    """One per-provider (or per-vehicle-type, provider='type:<VT>') KPI row."""
    return {"provider": provider, "kpi_name": kpi_name, "value": value,
            "unit": unit, "source": source}


@dataclass
class VehRecord:
    event_vehicle_id: str
    carrier_id: str
    provider: str
    vtype: object  # str|None -- freight_classify.classify_vehicle() result
    parcels: int
    cap: float
    load_factor: float
    excluded: bool
    stops: int = 0
    ctype: str = "delivery"
    type_id: str = None


@dataclass
class ParsedFreight:
    carriers: list
    vtypes: dict
    excluded: set
    vehrecords: list = field(default_factory=list)


def parse_run(run_dir, prefix):
    """Parse carriers + vehicle types and build one VehRecord per tour,
    classifying low-utilization delivery tours into `.excluded`."""
    run_dir = Path(run_dir)
    carriers = parse_carriers(run_dir / (prefix + ".output_carriers.xml.gz"))
    vtypes = parse_vehicle_types(run_dir / (prefix + ".output_carriersVehicleTypes.xml.gz"))

    vehrecords = []
    excluded = set()
    for carrier in carriers:
        ctype = carrier_type_of(carrier.carrier_id, carrier.attrs.get("carrierType"))
        provider = provider_of(carrier.carrier_id, carrier.attrs.get("provider"))
        for tour in carrier.tours:
            vehicle = carrier.vehicles.get(tour.vehicle_id)
            type_id = vehicle.type_id if vehicle else None
            vtype_def = vtypes.get(type_id) if type_id else None
            cap = vtype_def.capacity if vtype_def else 0.0

            parcels = sum(
                carrier.services[sid].capacity_demand if sid in carrier.services else 1
                for sid in tour.service_ids)
            lf = 0.0 if parcels == 0 or cap <= 0 else min(1.0, parcels / cap)
            is_excluded = ctype == "delivery" and lf < LOW_UTIL_THRESHOLD

            # Classify on the plain vehicle-id string (not the vehicle TYPE id):
            # the Java classifyForKpi classifies the event-vehicle id, which
            # embeds the vehicle id and carries the keyword; vehicle-type ids
            # in this fleet (e.g. "cargoBike_t") deliberately lack it.
            vtype = classify_vehicle(tour.vehicle_id)
            event_vehicle_id = tour.event_vehicle_id(carrier.carrier_id)

            vehrecords.append(VehRecord(
                event_vehicle_id=event_vehicle_id, carrier_id=carrier.carrier_id,
                provider=provider, vtype=vtype, parcels=parcels, cap=cap,
                load_factor=lf, excluded=is_excluded, stops=len(tour.service_ids),
                ctype=ctype, type_id=type_id))
            if is_excluded:
                excluded.add(event_vehicle_id)

    return ParsedFreight(carriers=carriers, vtypes=vtypes, excluded=excluded,
                          vehrecords=vehrecords)


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    pf = parse_run(run_dir, prefix)
    excluded = pf.excluded
    vtypes = pf.vtypes

    carrier_provider = {c.carrier_id: provider_of(c.carrier_id, c.attrs.get("provider"))
                         for c in pf.carriers}
    carrier_ctype = {c.carrier_id: carrier_type_of(c.carrier_id, c.attrs.get("carrierType"))
                      for c in pf.carriers}
    vrs_by_carrier = {}
    for vr in pf.vehrecords:
        vrs_by_carrier.setdefault(vr.carrier_id, []).append(vr)

    fr = run_dir / "analysis" / "freight"
    tc_file = fr / "TimeDistance_perCarrier.tsv"
    tv_file = fr / "TimeDistance_perVehicle.tsv"
    tc = pd.read_csv(tc_file, sep="\t") if tc_file.exists() else pd.DataFrame()
    tv = pd.read_csv(tv_file, sep="\t") if tv_file.exists() else pd.DataFrame()

    # km/tours/tour_hours are NOT re-allocated -- straight sums from the TSV,
    # grouped carrierId -> provider.
    prov_km, prov_tours, prov_hours = {}, {}, {}
    unmatched = []
    for _, r in tc.iterrows():
        cid = r["carrierId"]
        if cid not in carrier_provider:
            unmatched.append(cid)
        prov = carrier_provider.get(cid, "other")
        prov_km[prov] = prov_km.get(prov, 0.0) + float(r["travelDistances[km]"])
        prov_tours[prov] = prov_tours.get(prov, 0) + int(r["nuOfTours"])
        prov_hours[prov] = prov_hours.get(prov, 0.0) + float(r["tourDurations[h]"])

    providers = sorted(carrier_provider.values())
    # de-dup while keeping deterministic order
    seen = set()
    providers = [p for p in providers if not (p in seen or seen.add(p))]
    # Observability: TSV carriers absent from the XML land in an unshown "other"
    # bucket and their km is dropped silently -- warn so carrierId drift surfaces.
    if unmatched and "other" not in providers:
        print("[provider] WARN: {} TSV carrier(s) unmatched to XML, km dropped: {}".format(
            len(unmatched), ", ".join(str(c) for c in unmatched)))

    rows = []
    for prov in providers:
        carriers = [c for c in pf.carriers if carrier_provider[c.carrier_id] == prov]

        cost_dist = cost_time = cost_overtime = cost_fixed = 0.0
        parcels_total = 0
        parcels_missed_f = 0.0
        parcels_unassigned = 0
        vehicles_n = 0
        excluded_n = 0
        lf_sum = 0.0
        lf_n = 0
        stops_n = 0

        for c in carriers:
            ctype = carrier_ctype[c.carrier_id]
            vrs = vrs_by_carrier.get(c.carrier_id, [])
            all_tours = len(vrs)
            non_excluded = sum(1 for vr in vrs if not vr.excluded)
            if ctype == "delivery":
                ratio = (non_excluded / all_tours) if all_tours else 0.0
            else:
                ratio = 1.0

            cost_dist += attr_float(c.attrs, "costDistance") * ratio
            cost_time += attr_float(c.attrs, "costTime") * ratio
            cost_overtime += attr_float(c.attrs, "costOvertime") * ratio
            parcels_total += attr_int(c.attrs, "numberOfParcels")
            parcels_missed_f += attr_int(c.attrs, "missedParcels") * ratio
            parcels_unassigned += attr_int(c.attrs, "unassignedParcels")

            for vr in vrs:
                if vr.excluded:
                    excluded_n += 1
                    continue
                vehicles_n += 1
                stops_n += vr.stops
                if vr.type_id in vtypes:
                    cost_fixed += vtypes[vr.type_id].fixed_cost_per_day
                if ctype == "delivery":
                    lf_sum += vr.load_factor
                    lf_n += 1

        parcels_missed = round(parcels_missed_f)
        cost_total = cost_dist + cost_time + cost_fixed + cost_overtime
        km = prov_km.get(prov, 0.0)
        tours = prov_tours.get(prov, 0)
        tour_hours = prov_hours.get(prov, 0.0)
        avg_lf = (lf_sum / lf_n) if lf_n else 0.0
        delivered_net = parcels_total - parcels_missed
        delivery_rate = ((parcels_total - parcels_missed - parcels_unassigned) / parcels_total
                          if parcels_total else 1.0)
        stops_per_h = (stops_n / tour_hours) if tour_hours else 0.0
        stops_per_km = (stops_n / km) if km else 0.0
        parcels_per_km = (delivered_net / km) if km else 0.0
        cost_per_parcel = cost_total / max(1, delivered_net)

        rows += [
            prow(prov, "parcels_total", parcels_total, "parcels", "carrier attributes"),
            prow(prov, "parcels_missed", parcels_missed, "parcels",
                 "carrier attributes (re-allocated)"),
            prow(prov, "parcels_unassigned", parcels_unassigned, "parcels", "carrier attributes"),
            prow(prov, "delivery_rate", delivery_rate, "share", "computed"),
            prow(prov, "vehicles", vehicles_n, "vehicles", "computed"),
            prow(prov, "tours", tours, "tours", "TimeDistance_perCarrier"),
            prow(prov, "km", km, "km", "TimeDistance_perCarrier"),
            prow(prov, "tour_hours", tour_hours, "h", "TimeDistance_perCarrier"),
            prow(prov, "cost_fixed", cost_fixed, "EUR", "carrier vehicle types (re-allocated)"),
            prow(prov, "cost_dist", cost_dist, "EUR", "carrier attributes (re-allocated)"),
            prow(prov, "cost_time", cost_time, "EUR", "carrier attributes (re-allocated)"),
            prow(prov, "cost_total", cost_total, "EUR", "computed (re-allocated)"),
            prow(prov, "avg_load_factor", avg_lf, "share", "computed"),
            prow(prov, "stops", stops_n, "stops", "computed"),
            prow(prov, "stops_per_h", stops_per_h, "stops/h", "computed"),
            prow(prov, "stops_per_km", stops_per_km, "stops/km", "computed"),
            prow(prov, "parcels_per_km", parcels_per_km, "parcels/km", "computed"),
            prow(prov, "cost_per_parcel", cost_per_parcel, "EUR/parcel", "computed"),
            prow(prov, "excluded_vehicles", excluded_n, "vehicles", "computed"),
        ]

    rows += _vehicle_type_rows(pf, tv)

    print("[provider] {} providers, {} excluded delivery vehicles".format(
        len(providers), len(excluded)))
    return rows


def _vehicle_type_rows(pf, tv):
    """Per-vehicle-type rows (provider='type:<VT>'), grouped over SURVIVING
    vehicles only (excluded low-util tours don't count toward fleet stats)."""
    excluded = pf.excluded
    vtypes = pf.vtypes

    groups = {}
    for vr in pf.vehrecords:
        if vr.excluded or vr.vtype is None:
            continue
        groups.setdefault(vr.vtype, []).append(vr)

    # distance_km per vtype: classify each TimeDistance_perVehicle row by its
    # OWN vehicleId column (the event-vehicle id), not by vehicleTypeId --
    # vehicle-type ids in this fleet lack the classification keyword.
    dist_by_vtype = {}
    if len(tv):
        for _, r in tv.iterrows():
            evid = r["vehicleId"]
            if evid in excluded:
                continue
            vt = classify_vehicle(evid)
            if vt is None:
                continue
            dist_by_vtype[vt] = dist_by_vtype.get(vt, 0.0) + float(r["travelDistance[km]"])

    rows = []
    for vt in sorted(groups):
        vrs = groups[vt]
        n = len(vrs)
        prov_name = "type:" + vt

        lf_vals = [vr.load_factor for vr in vrs]
        stops_vals = [vr.stops for vr in vrs]
        caps = [vtypes[vr.type_id].capacity for vr in vrs if vr.type_id in vtypes]
        fixed_costs = [vtypes[vr.type_id].fixed_cost_per_day for vr in vrs if vr.type_id in vtypes]

        distance_km = dist_by_vtype.get(vt, 0.0)

        rows += [
            prow(prov_name, "distance_km", distance_km, "km", "TimeDistance_perVehicle"),
            prow(prov_name, "vehicles", n, "vehicles", "computed"),
            prow(prov_name, "load_factor", (sum(lf_vals) / n) if n else 0.0, "share", "computed"),
            prow(prov_name, "km_per_tour", (distance_km / n) if n else 0.0, "km", "computed"),
            prow(prov_name, "stops_per_tour", (sum(stops_vals) / n) if n else 0.0,
                 "stops", "computed"),
            prow(prov_name, "capacity", (sum(caps) / len(caps)) if caps else 0.0,
                 "parcels", "carrier vehicle types"),
            prow(prov_name, "fixed_cost_per_day", (sum(fixed_costs) / len(fixed_costs))
                 if fixed_costs else 0.0, "EUR", "carrier vehicle types"),
        ]
    return rows


def _fmt(v):
    if isinstance(v, float):
        return "{:.6g}".format(v)
    return str(v)


def write(rows, meta, out_file):
    with open(out_file, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["run_id", "provider", "kpi_name", "value", "unit", "source"])
        for r in rows:
            w.writerow([meta.run_id, r["provider"], r["kpi_name"],
                        _fmt(r["value"]), r["unit"], r["source"]])
