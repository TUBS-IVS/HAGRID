# -*- coding: utf-8 -*-
# analysis/kpi/extract_emissions.py
"""Emission KPI rows (kpi_group="environment") for the Lausitz runs.

Tier-3 method: per freight TOUR / per DRT VEHICLE, evaluate the EMEP/EEA
speed curves at the entity's mean travelling speed (distance / driving
time, service dwell excluded -- engine-off assumption at stops), multiply
by its km. Both powertrain arms (diesel primary + BEV variant) are always
computed from the same runs -- electrification is a factor swap, no re-run.

Fleet mapping (Rev. B, 2026-07-31): all vehicles are LCV (N1) Euro 7; only
the SEGMENT varies, because that is where EMEP/EEA carries the vehicle-mass
effect for light vehicles (no load dimension exists for LCV). See
segment_for_type() and data/README.md. An unmappable type raises rather
than being silently priced as N1-III.
"""
from pathlib import Path

import pandas as pd

import emissions_emep as em

# Explicit reference-mass assumption per named van type -> N1 segment.
# The named types are what the LMD carriers use; CarrierVehicleFactory
# additionally creates ct_cep_<cap>_<tpl> at runtime (see CAP_SEGMENT_LIMIT).
SEGMENT_BY_TYPE = {"ct_cep_size_s": "N1-II",     # ~1700 kg reference mass
                   "ct_cep_size_m": "N1-III",    # ~2000 kg
                   "ct_cep_size_l": "N1-III"}    # ~2400 kg
CAP_SEGMENT_LIMIT = 120.0      # parcels; <= -> N1-II, > -> N1-III
DRT_SEGMENT = "N1-III"         # M2 (cap 10) substituted by N1-III, see docs
POWERTRAINS = ("diesel", "bev")

# output keys of emissions_emep.vehicle_emissions
EMIS_KEYS = ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O", "CO2",
             "CO2E_TTW", "CO2E_WTW", "ENERGY_MJ", "PM10_TYRE", "PM10_BRAKE",
             "PM10_ROAD", "PM10_NONEXHAUST")


def segment_for_type(type_id, capacity=None):
    """N1 segment for a carrier vehicleTypeId.

    Named types resolve from SEGMENT_BY_TYPE. Runtime-generated sweep types
    (ct_cep_<cap>_<tpl>, CarrierVehicleFactory.java:204-210) resolve from
    `capacity` against CAP_SEGMENT_LIMIT -- the boundary sits between the
    two named capacities 100 (N1-II) and 165 (N1-III).

    Raises ValueError when neither applies: a new vehicle type must fail
    loudly, not inherit N1-III by accident.
    """
    tid = str(type_id)
    if tid in SEGMENT_BY_TYPE:
        return SEGMENT_BY_TYPE[tid]
    if capacity is not None and tid.startswith("ct_cep_"):
        return "N1-II" if float(capacity) <= CAP_SEGMENT_LIMIT else "N1-III"
    raise ValueError("no N1 segment mapping for vehicle type '" + tid
                     + "' (capacity=" + str(capacity) + "); extend "
                     "SEGMENT_BY_TYPE in extract_emissions.py")


def _zero_totals():
    return {pt: {k: 0.0 for k in EMIS_KEYS} for pt in POWERTRAINS}


def _add_entity(totals, detail, fleet, entity, vtype, segment, km, v_kmh, fac):
    for pt in POWERTRAINS:
        out = em.vehicle_emissions(km, v_kmh, pt, segment, fac)
        for k in EMIS_KEYS:
            totals[pt][k] += out[k]
        d = {"fleet": fleet, "entity": entity, "vehicle_type": vtype,
             "segment": segment, "km": km, "v_kmh": v_kmh, "powertrain": pt}
        d.update({k: out[k] for k in EMIS_KEYS})
        detail.append(d)


def _capacities(run_dir):
    """{typeId: capacity} from the run's carrier vehicle types, for the
    capacity rule on generated sweep types. Empty dict if unavailable --
    the named types resolve without it."""
    try:
        import carriers_parse
    except ImportError:
        return {}
    for name in ("*output_carriersVehicleTypes.xml.gz",
                 "*carriersVehicleTypes.xml*"):
        for p in Path(run_dir).glob(name):
            try:
                vt = carriers_parse.parse_vehicle_types(p)
                return {k: v.capacity for k, v in vt.items()}
            except Exception as e:
                print("[emissions] vehicle types unreadable: " + str(e))
    return {}


def freight_arm(run_dir, fac):
    """Per-tour emissions from the CarriersAnalysis TSV (conventional LMD
    arm). Returns (totals, detail) or None if the TSV is absent -- that is
    the normal case for pax-only runs AND for the modular/shared-use arms,
    which are handled by modular_freight_arm()."""
    tsv = Path(run_dir) / "analysis" / "freight" / "TimeDistance_perVehicle.tsv"
    if not tsv.exists():
        return None
    td = pd.read_csv(tsv, sep="\t")
    caps = _capacities(run_dir)
    totals, detail = _zero_totals(), []
    for _, r in td.iterrows():
        vtype = str(r["vehicleTypeId"])
        km = float(r["travelDistance[km]"])
        tt_h = float(r["travelTime[s]"]) / 3600.0
        if km <= 0 or tt_h <= 0:
            continue
        segment = segment_for_type(vtype, caps.get(vtype))   # raises if unknown
        _add_entity(totals, detail, "freight", str(r["vehicleId"]), vtype,
                    segment, km, km / tt_h, fac)
    return totals, detail
