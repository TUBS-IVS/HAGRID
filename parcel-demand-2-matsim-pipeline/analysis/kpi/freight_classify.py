# -*- coding: utf-8 -*-
"""Provider & vehicle-type classification, ported verbatim from the Java
DashboardGenerator/FreightEventHandler/CarrierXmlParser. Kept side-effect free
and network-free so extractors, timeseries and (later) maps share one truth."""

VEHICLE_TYPE_LABELS = {
    "VAN": "CEP Van", "CARGOBIKE": "Cargobike", "TRUCK": "Truck (heavy)",
    "TRUCK_LIGHT": "Truck (light)", "SUPPLY_VAN": "Supply Van",
}


def guess_provider(carrier_id):
    s = (carrier_id or "").lower()
    if "amazon" in s: return "amazon"
    if "dp/dhl" in s or "dp_dhl" in s: return "dp/dhl"
    if "dhl" in s: return "dhl"
    if "dpd" in s: return "dpd"
    if "fedex" in s: return "fedex"
    if "gls" in s: return "gls"
    if "hermes" in s: return "hermes"
    if "ups" in s: return "ups"
    return "other"


def provider_of(carrier_id, provider_attr):
    p = (provider_attr or "").strip()
    if not p:
        p = guess_provider(carrier_id)
    return p or "other"


def carrier_type_of(carrier_id, carrier_type_attr):
    if carrier_type_attr and carrier_type_attr.strip().lower() == "supply":
        return "supply"
    return "supply" if "supply" in (carrier_id or "").lower() else "delivery"


def classify_vehicle(vid):
    v = vid or ""
    if "_Supply_Vehicle_" in v or "_veh_supply_" in v:
        if "supply_light_van" in v: return "SUPPLY_VAN"
        if "light" in v: return "TRUCK_LIGHT"
        return "TRUCK"
    if "_CEP_Vehicle_" in v or "_veh_cep_" in v or "_egrocery_van_" in v:
        return "VAN"
    if "ct_cep_size" in v:
        return "VAN"
    if "_cargoBike_" in v or "_cargobike_" in v:
        return "CARGOBIKE"
    return None
