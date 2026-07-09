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


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []

    fr = run_dir / "analysis" / "freight"
    td = pd.read_csv(fr / "TimeDistance_perCarrier.tsv", sep="\t")
    km = float(td["travelDistances[km]"].sum())
    rows += [
        row("freight", "carriers", len(td), "carriers", "TimeDistance_perCarrier"),
        row("freight", "freight_tours", int(td["nuOfTours"].sum()), "tours", "TimeDistance_perCarrier"),
        row("freight", "freight_vehicle_km", km, "km", "TimeDistance_perCarrier"),
        row("freight", "freight_tour_hours",
            float(td["tourDurations[h]"].sum()), "h", "TimeDistance_perCarrier"),
        row("economic", "freight_fixed_costs",
            float(td["fixedCosts[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_var_costs_dist",
            float(td["varCostsDist[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_var_costs_time",
            float(td["varCostsTime[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
        row("economic", "freight_total_costs",
            float(td["totalCosts[EUR]"].sum()), "EUR", "TimeDistance_perCarrier"),
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
        tv = pd.read_csv(fr / "TimeDistance_perVehicle.tsv", sep="\t")
        rows.append(row("freight", "freight_vehicles", int(tv["vehicleId"].nunique()),
                        "vehicles", "TimeDistance_perVehicle"))

    attrs = _carrier_attrs(run_dir / (prefix + ".output_carriers.xml.gz"))
    total = sum(_int_attr(a, "numberOfParcels") for a in attrs.values())
    missed = sum(_int_attr(a, "missedParcels") for a in attrs.values())
    unassigned = sum(_int_attr(a, "unassignedParcels") for a in attrs.values())
    delivered = total - missed - unassigned
    rows += [
        row("freight", "parcels_handled",
            parcels_handled_lv if parcels_handled_lv is not None else delivered,
            "parcels", "Load_perVehicle" if parcels_handled_lv is not None
            else "carrier attributes (fallback: Load_perVehicle empty for service-based carriers)"),
        row("freight", "parcels_total", total, "parcels", "carrier attributes"),
        row("freight", "parcels_missed", missed, "parcels", "carrier attributes"),
        row("freight", "parcels_unassigned", unassigned, "parcels", "carrier attributes"),
        row("freight", "delivery_rate",
            (delivered / total) if total else 1.0, "share", "computed"),
        row("freight", "parcels_per_vehicle_km",
            (delivered / km) if km else 0.0, "parcels/km", "computed"),
    ]
    return rows
