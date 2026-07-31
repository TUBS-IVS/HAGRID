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
import gzip
import re
import xml.etree.ElementTree as ET
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


# ---------------------------------------------------------------- 5b: 1d arm

RE_TASK_TYPE = re.compile(r'taskType="([^"]+)"')
RE_DVRP_VEH = re.compile(r'dvrpVehicle="([^"]+)"')
RE_EV_TYPE = re.compile(r'type="([^"]+)"')
RE_EV_TIME = re.compile(r'\btime="([^"]+)"')

MODULAR_FREIGHT_DRIVE = "MODULAR_FREIGHT_DRIVE"


def load_link_lengths(network_gz, used_links):
    """{link_id: length_m} from the MATSim network's own `length` attribute,
    restricted to `used_links`.

    Deliberately NOT geometry.load_link_geometry's length_m: that one is the
    Euclidean node-to-node distance and runs ~3 % short, because a link's
    modelled length follows the road, not the straight line. Emissions are
    km x factor, so the systematic shortfall would propagate directly."""
    used = set(used_links)
    out = {}
    with gzip.open(network_gz, "rt", encoding="utf-8") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag == "link":
                lid = el.get("id")
                if lid in used:
                    out[lid] = float(el.get("length"))
                el.clear()
    return out


def freight_windows(drt_cache):
    """{vehicle_id: [(t_start, t_end), ...]} for MODULAR_FREIGHT_DRIVE tasks.

    Parsed from the DRT events cache, NOT the freight cache: in the modular
    arm the freight rides in DRT vehicles, so `*.freight_events_filtered.txt`
    is empty (0 bytes in all 1d runs checked 2026-07-31) while the DVRP task
    events sit in the drt cache -- dvrpVehicle="drt_..." passes the "drt_"
    filter. Event types are dvrpTaskStarted / dvrpTaskEnded (verified against
    m1d050).

    Plain DRIVE/STOP tasks are pax operation and are ignored; only the
    MODULAR_FREIGHT_* task type marks freight (Modular.java:38).
    """
    open_at = {}
    windows = {}
    with open(drt_cache, "r", encoding="utf-8") as f:
        for line in f:
            mtt = RE_TASK_TYPE.search(line)
            if not mtt or mtt.group(1) != MODULAR_FREIGHT_DRIVE:
                continue
            met, mv, mtm = (RE_EV_TYPE.search(line), RE_DVRP_VEH.search(line),
                            RE_EV_TIME.search(line))
            if not (met and mv and mtm):
                continue
            v, t = mv.group(1), float(mtm.group(1))
            if met.group(1) == "dvrpTaskStarted":
                open_at[v] = t
            elif met.group(1) == "dvrpTaskEnded" and v in open_at:
                windows.setdefault(v, []).append((open_at.pop(v), t))
    return windows


def _in_windows(t, wins):
    for t0, t1 in wins:
        if t0 <= t <= t1:
            return True
    return False


def modular_freight_arm(veh_path_ts, windows, link_len, fac):
    """Freight emissions for the modular (1d) arm, where freight rides in the
    DRT vehicles and no analysis/freight/ TSV exists.

    The regime split: a link entered INSIDE a MODULAR_FREIGHT_DRIVE window is
    freight km, everything else stays pax km. Residue-free and measured
    within one run -- see METHODS-LOG 1.4. The incremental variant against a
    pax-only twin run is rejected (METHODS-LOG 3.9: the difference sits
    inside the noise band and carries the wrong sign, because freight
    DISPLACES pax service rather than adding km).

    `veh_path_ts` are the 4-tuples of geometry.reconstruct_drt_paths_detailed
    ((link_id, occ_pax, occ_parcels, t)). Mean speed is freight km divided by
    the summed freight window duration, i.e. the same
    distance-over-driving-time definition the conventional arm uses.
    """
    totals, detail = _zero_totals(), []
    for veh, path in (veh_path_ts or {}).items():
        wins = windows.get(veh)
        if not wins:
            continue
        km = 0.0
        for entry in path:
            lid, t = entry[0], entry[3]
            if not _in_windows(t, wins):
                continue
            length = link_len.get(lid)
            if length is None:          # no geometry -> skip, do not count 0
                continue
            km += length / 1000.0
        drive_h = sum(t1 - t0 for t0, t1 in wins) / 3600.0
        if km <= 0 or drive_h <= 0:
            continue
        _add_entity(totals, detail, "freight_modular", veh, "drt_modular",
                    DRT_SEGMENT, km, km / drive_h, fac)
    return totals, detail
