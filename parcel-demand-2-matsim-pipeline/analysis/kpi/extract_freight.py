# -*- coding: utf-8 -*-
"""Freight KPI rows from MATSim CarriersAnalysis TSVs + carrier attributes.
Carrier attribute names are verbatim from LmdCarrierBuilder (numberOfParcels,
missedParcels) and the unassigned-jobs tracking (unassignedParcels/-Jobs)."""
import gzip
import xml.etree.ElementTree as ET
from pathlib import Path

import pandas as pd

from common import row


def _carrier_attrs(carriers_xml_gz):
    out = {}
    with gzip.open(carriers_xml_gz, "rb") as f:
        for _, el in ET.iterparse(f):
            if el.tag.endswith("carrier"):
                attrs = {a.get("name"): (a.text or "").strip()
                         for a in el.iter() if a.tag.endswith("attribute")}
                out[el.get("id")] = attrs
                el.clear()
    return out


def _int_attr(attrs, name):
    v = attrs.get(name)
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return 0


def _provider_cost_totals(run_dir, prefix, pf):
    """Sum the real (non-'all', non-'type:'/'vtype:') per-provider
    cost_dist/cost_total rows from extract_freight_provider, which computes
    them off the carrier-attribute cost basis (costDistance/costTime/
    costOvertime + vehicle-type fixed cost, with the low-util ratio
    re-allocation) -- the SAME basis the legacy DashboardGenerator.java uses.
    Returns (var_costs_dist, total_costs) or None if the carrier XML /
    provider parse is unavailable (graceful degradation -- the caller falls
    back to the TSV-sourced columns rather than aborting the build)."""
    try:
        import extract_freight_provider as efp
        if pf is None:
            pf = efp.parse_run(run_dir, prefix)
        prov_rows = efp.extract(run_dir, prefix, pf=pf)
    except Exception as e:
        print("[freight] provider cost basis unavailable, "
              "falling back to TSV-sourced costs: " + str(e))  # ASCII only
        return None

    real = [r for r in prov_rows if r["provider"] != "all"
            and not r["provider"].startswith(("type:", "vtype:"))]
    var_costs_dist = sum(r["value"] for r in real if r["kpi_name"] == "cost_dist")
    total_costs = sum(r["value"] for r in real if r["kpi_name"] == "cost_total")
    return var_costs_dist, total_costs


def extract(run_dir, prefix, pf=None):
    run_dir = Path(run_dir)
    rows = []

    fr = run_dir / "analysis" / "freight"
    td = pd.read_csv(fr / "TimeDistance_perCarrier.tsv", sep="\t")
    km = float(td["travelDistances[km]"].sum())

    # freight_var_costs_dist/freight_total_costs: re-sourced (v2 Plan D
    # Task 10 #3) from the carrier-attribute cost basis so the aggregate
    # equals the sum of the per-provider parts AND matches the legacy
    # dashboard. MATSim's own TSV varCostsDist[EUR]/totalCosts[EUR] columns
    # are NOT used for these two rows any more: varCostsDist[EUR] is
    # accumulated on LinkEnterEvent only (structurally missing each tour's
    # first link) and totalCosts[EUR] drops the costOvertime component
    # entirely. Falls back to the TSV columns if the carrier XML / provider
    # parse is unavailable for any reason (graceful degradation).
    var_costs_dist = float(td["varCostsDist[EUR]"].sum())
    total_costs = float(td["totalCosts[EUR]"].sum())
    cost_source = "TimeDistance_perCarrier"
    provider_totals = _provider_cost_totals(run_dir, prefix, pf)
    if provider_totals is not None:
        var_costs_dist, total_costs = provider_totals
        cost_source = "carrier attributes (sum of per-provider cost_dist/cost_total, re-allocated)"

    rows += [
        row("freight", "carriers", len(td), "carriers", "TimeDistance_perCarrier"),
        row("freight", "freight_tours", int(td["nuOfTours"].sum()), "tours", "TimeDistance_perCarrier"),
        row("freight", "freight_vehicle_km", km, "km", "TimeDistance_perCarrier"),
        row("freight", "freight_tour_hours",
            float(td["tourDurations[h]"].sum()), "h", "TimeDistance_perCarrier"),
        row("economic", "freight_fixed_costs",
            float(td["fixedCosts[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_var_costs_dist", var_costs_dist, "EUR", cost_source),
        row("economic", "freight_var_costs_time",
            float(td["varCostsTime[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_total_costs", total_costs, "EUR", cost_source),
    ]

    # Load_perVehicle.tsv is only populated by MATSim's CarrierLoadAnalysis for
    # SHIPMENT pickup/delivery events (CarrierShipmentPickupStartEvent /
    # CarrierShipmentDeliveryStartEvent). HAGRID's LMD/DRT-freight carriers are
    # modelled as SERVICES (Carriers_stats.tsv shows nuOfShipments_input == 0,
    # nuOfServices_input > 0 for every real run), so on real runs this file is
    # always header-only (0 data rows) -- fall back to the per-vehicle tour
    # table, which is populated regardless of service/shipment modelling.
    lv = pd.read_csv(fr / "Load_perVehicle.tsv", sep="\t")
    parcels_handled_lv = None
    if len(lv):
        rows += [
            row("freight", "freight_vehicles", len(lv), "vehicles", "Load_perVehicle"),
            row("freight", "avg_max_load",
                float(lv["maxLoadPercentage"].mean()) / 100.0, "share", "Load_perVehicle"),
        ]
        parcels_handled_lv = int(lv["handledDemand"].sum())
    else:
        tv_f = fr / "TimeDistance_perVehicle.tsv"
        if tv_f.exists():
            tv = pd.read_csv(tv_f, sep="\t")
            rows.append(row("freight", "freight_vehicles", int(tv["vehicleId"].nunique()),
                            "vehicles", "TimeDistance_perVehicle"))

    attrs = _carrier_attrs(run_dir / (prefix + ".output_carriers.xml.gz"))
    total = sum(_int_attr(a, "numberOfParcels") for a in attrs.values())
    missed = sum(_int_attr(a, "missedParcels") for a in attrs.values())
    unassigned = sum(_int_attr(a, "unassignedParcels") for a in attrs.values())
    # Zustellquoten-Konvention (Entscheidung 2026-08-10, METHODS-LOG 2.21):
    # `delivery_rate` ist die ARMÜBERGREIFEND vergleichbare, OPERATIVE Quote
    # -- zugestellt / Nachfrage, das Not-at-home-Overlay wird NICHT abgezogen.
    # Grund: das Overlay existiert nur in diesem Arm (LmdCarrierBuilder setzt es,
    # 1c und 1d melden roh) und es ist im POC kosmetisch, weil weder Rücktransport
    # noch Packstation-Zustellung simuliert werden. Unter demselben KPI-Namen las
    # der Vergleich damit netto (Baseline 93,6 %) gegen brutto (1c 93,7 %, 1d 97,9 %)
    # und kehrte das Vorzeichen der Kernaussage um: operativ liegt dieser Arm bei
    # ~100 %, weil jsprit bei FleetSize.INFINITE immer ein Fahrzeug nachlegen kann.
    # Der Netto-Wert geht nicht verloren, er wandert in delivery_rate_net_overlay.
    #
    # Bewusst NICHT mitgezogen: parcels_handled und parcels_per_vehicle_km bleiben
    # netto. parcels_handled ist der Nenner von economics.freight_cost_per_parcel;
    # eine Umstellung würde die €-Kennzahl still mitverschieben, und die
    # Kostenfunktion wird separat überarbeitet (BACKLOG [H] Kostenfunktion).
    delivered_net = total - missed - unassigned
    delivered_operational = total - unassigned
    rows += [
        row("freight", "parcels_handled",
            parcels_handled_lv if parcels_handled_lv is not None else delivered_net,
            "parcels", "Load_perVehicle" if parcels_handled_lv is not None
            else "carrier attributes (fallback: Load_perVehicle empty for service-based carriers)"),
        row("freight", "parcels_total", total, "parcels", "carrier attributes"),
        row("freight", "parcels_missed", missed, "parcels", "carrier attributes"),
        row("freight", "parcels_unassigned", unassigned, "parcels", "carrier attributes"),
        row("freight", "parcels_delivered_operational", delivered_operational, "parcels",
            "carrier attributes (overlay NOT deducted)"),
        row("freight", "delivery_rate",
            (delivered_operational / total) if total else 1.0, "share",
            "computed (operational: overlay NOT deducted)"),
        row("freight", "delivery_rate_net_overlay",
            (delivered_net / total) if total else 1.0, "share",
            "computed (not-at-home overlay deducted -- NOT comparable across arms)"),
        row("freight", "parcels_per_vehicle_km",
            (delivered_net / km) if km else 0.0, "parcels/km", "computed"),
    ]
    return rows
