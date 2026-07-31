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
import csv
import gzip
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pandas as pd

import emissions_emep as em
from common import row

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


# ----------------------------------------------------------------- 6: DRT arm

def drt_arm(veh_path, recon, link_len, fac, exclude_windows=None):
    """Per-vehicle emissions for the DRT fleet: km = sum of true link
    lengths along the reconstructed path, mean speed = km / DRIVE task time
    from drt_service_time.reconstruct(). Vehicles without DRIVE time are
    skipped (never moved -> nothing to emit).

    `exclude_windows` (as returned by freight_windows) removes the link
    entries driven inside a MODULAR_FREIGHT_DRIVE window. Pass it for the
    modular (1d) arm: the regime split is residue-free (METHODS-LOG 1.4),
    so every vehicle-km must belong to exactly one side. Without it,
    drt_arm counts the freight km as pax km and modular_freight_arm counts
    them again -- total_* would be too high by the freight distance.

    The time side already separates cleanly: reconstruct() books taskType
    DRIVE into `drive_s` and MODULAR_FREIGHT_DRIVE into `freight_drive_s`
    (drt_service_time.py:411/414), so `drive_s` is pax driving time only.
    Only the distance side needed this.

    Segment is DRT_SEGMENT ("N1-III"): the vehicle has capacity 10, i.e.
    M2 by the guidebook's own definition, and is substituted by N1-III --
    a declared assumption, see data/README.md for the bracketing figures.
    """
    per_veh = recon.get("per_veh", {}) if recon else {}
    totals, detail = _zero_totals(), []
    for veh, path in (veh_path or {}).items():
        drive_s = per_veh.get(veh, {}).get("drive_s", 0.0)
        wins = (exclude_windows or {}).get(veh)
        km = 0.0
        for entry in path:
            if wins and len(entry) > 3 and _in_windows(entry[3], wins):
                continue
            km += link_len.get(entry[0], 0.0) / 1000.0
        if drive_s <= 0 or km <= 0:
            continue
        _add_entity(totals, detail, "drt", veh, "drt_minibus", DRT_SEGMENT,
                    km, km / (drive_s / 3600.0), fac)
    return totals, detail


# ------------------------------------------- 5c: mass allocation (1c arm)

#: Lower edge of the plausible band on the MEAN parcel mass (METHODS-LOG 2.26).
#: Guards against the median trap: Amaral's median 0.6950 kg instead of the
#: mean would understate on-board parcel mass by 58 %.
KG_PER_PARCEL_MIN = 1.3

ALLOC_SRC = (
    "mass allocation on kg*km (EN 16258 / GLEC convention); "
    "kg_per_parcel=1.65 (ASSUMPTION; Amaral et al. 2026 TR Part E Tab.1 + "
    "Rajendran & Harper 2021 TRIP + Mohri et al. 2024 TBS 35:100716, none "
    "German - declared "
    "transfer); kg_per_passenger=80 is a SETTING, not a source. Report the "
    "share alongside the intensity (METHODS-LOG 2.26)")


def _check_mass_constants(sup):
    kg = sup["kg_per_parcel"]
    if kg < KG_PER_PARCEL_MIN:
        raise ValueError(
            "kg_per_parcel=" + str(kg) + " is below the plausible band on the "
            "MEAN parcel mass (>= " + str(KG_PER_PARCEL_MIN) + " kg). A value "
            "this low is typically the MEDIAN (Amaral 0.6950 kg), which "
            "understates total on-board mass by ~58 %: total mass = n x MEAN. "
            "See METHODS-LOG 2.26.")
    return kg, sup["kg_per_passenger"]


def mass_split(n_pax, n_parcels, sup):
    """(share_pax, share_parcels) of the mass aboard.

    The allocation basis is MASS, so the two sourced/declared constants
    kg_per_parcel and kg_per_passenger enter here and only here. Only their
    RATIO matters, which is why the unsourced 80 kg weighs as much as the
    sourced parcel mass -- both must be reported together.

    (0.0, 0.0) when nothing is aboard: there is no basis, and the caller
    must redistribute that leg rather than invent a split.
    """
    kg_parcel, kg_pax = _check_mass_constants(sup)
    m_pax = float(n_pax) * kg_pax
    m_par = float(n_parcels) * kg_parcel
    tot = m_pax + m_par
    if tot <= 0:
        return 0.0, 0.0
    return m_pax / tot, m_par / tot


def slot_split(n_pax, n_parcels, sup):
    """(share_pax, share_parcels) on CAPACITY slots instead of mass.

    The mandatory companion to mass_split: the equivalence is
    scenario-defined (the 1c vehicle has 8 seats and 20 parcel slots, hence
    slots_per_seat_equiv = 2.5) and depends on NO external mass assumption.
    Reporting both makes the assumption's leverage visible instead of
    hiding it in a single number (METHODS-LOG 2.26).
    """
    per_seat = sup["slots_per_seat_equiv"]
    s_pax = float(n_pax) * per_seat
    s_par = float(n_parcels)
    tot = s_pax + s_par
    if tot <= 0:
        return 0.0, 0.0
    return s_pax / tot, s_par / tot


def allocate_vehicle_by_mass(path, link_len, veh_co2e, sup):
    """Split one vehicle's emission into {"pax": ..., "parcels": ...} on a
    kg*km basis (EN 16258 / GLEC), i.e. mass aboard x link length -- so a
    long loaded link weighs more than a short one.

    `path` are the 4-tuples of geometry.reconstruct_drt_paths_detailed
    ((link_id, occ_pax, occ_parcels, t)). Empty links carry no kg*km basis
    and are spread proportionally over the vehicle's loaded kg*km, which is
    the GLEC convention for empty running; the returned parts therefore sum
    to `veh_co2e` exactly. A vehicle that was never loaded returns zeros:
    there is no basis, and distributing anyway would be arbitrary.

    Unit-agnostic: the parts carry whatever unit `veh_co2e` came in.
    """
    kg_parcel, kg_pax = _check_mass_constants(sup)
    kgkm_pax = 0.0
    kgkm_par = 0.0
    for entry in path:
        length = link_len.get(entry[0])
        if length is None:
            continue
        km = length / 1000.0
        kgkm_pax += entry[1] * kg_pax * km
        kgkm_par += entry[2] * kg_parcel * km
    tot = kgkm_pax + kgkm_par
    if tot <= 0:
        return {"pax": 0.0, "parcels": 0.0}
    return {"pax": veh_co2e * kgkm_pax / tot,
            "parcels": veh_co2e * kgkm_par / tot}


def intensity_rows(alloc, n_pax, n_parcels, sup=None):
    """Specific intensities plus the allocation shares they came from.

    `alloc` values are kg CO2e (the caller converts from the gram-based
    totals). Emits co2e_wtw_per_pax / co2e_wtw_per_parcel in kg, and -- per
    the reporting rule in METHODS-LOG 2.26 -- always the mass share next to
    them, with the mass-free slot share as its companion. An intensity whose
    denominator is zero is OMITTED rather than reported as 0 or inf.
    """
    rows = []
    tot = alloc["pax"] + alloc["parcels"]
    if n_pax:
        rows.append(row("environment", "co2e_wtw_per_pax",
                        alloc["pax"] / float(n_pax), "kg", ALLOC_SRC))
    if n_parcels:
        rows.append(row("environment", "co2e_wtw_per_parcel",
                        alloc["parcels"] / float(n_parcels), "kg", ALLOC_SRC))
    if tot > 0:
        rows.append(row("environment", "alloc_share_parcels_mass",
                        alloc["parcels"] / tot, "share", ALLOC_SRC))
    if sup is not None:
        rows.append(row("environment", "alloc_share_parcels_slots",
                        slot_split(n_pax, n_parcels, sup)[1], "share",
                        ALLOC_SRC))
    return rows


# ------------------------------------------- 7: KPI rows + detail CSV

SRC = ("EMEP/EEA GB 2023 - Update 2025, App.4 Tier-3 (Okt 2025, "
       "COPERT 5.9.1), LCV Euro 7 DPF+SCR, segment per vehicle type")
EV_THRESHOLDS = ("low", "mid", "high")     # -> sup["ev_range_km_<key>"]

# (kpi_metric, emis_key, unit, to_unit_factor)
_KPI_METRICS = [("co2e_wtw", "CO2E_WTW", "kg", 1e-3),
                ("co2e_ttw", "CO2E_TTW", "kg", 1e-3),
                ("co2", "CO2", "kg", 1e-3),
                ("nox", "NOx", "g", 1.0),
                ("pm_exhaust", "PM_EXHAUST", "g", 1.0),
                ("pm10_nonexhaust", "PM10_NONEXHAUST", "g", 1.0),
                ("energy_final", "ENERGY_MJ", "MJ", 1.0)]
_BEV_SKIP = {"co2e_ttw", "co2"}          # im BEV-Arm konstruktionsbedingt 0


def _percentile(sorted_vals, q):
    if not sorted_vals:
        return 0.0
    i = min(len(sorted_vals) - 1, int(round(q * (len(sorted_vals) - 1))))
    return sorted_vals[i]


#: What ONE entity of each fleet is, and therefore what its exceedance share
#: may and may not be read as. The three fleets look comparable in the CSV
#: (`ev_range_exceed_<fleet>_150`) and are NOT: measured on base10c, freight
#: exceeds 150 km on 3.2 % of tours while DRT exceeds it on 96.7 % of
#: vehicle-days. Reading that as "DRT cannot be electrified" is wrong, and the
#: caveat has to travel with the row rather than live in a doc -- a reader of
#: kpis_long.csv never sees the doc.
_RANGE_SRC = {
    "drt": ("per VEHICLE-DAY km vs ev_range_km_* (emep_supplement.csv). NOT "
            "an electrification verdict: a vehicle-day is not a continuous "
            "shift -- it contains STAY phases in which charging is possible. "
            "Comparable to the freight rows only after a charging-window "
            "analysis (BACKLOG)"),
    "freight": ("per TOUR km vs ev_range_km_* (emep_supplement.csv). One "
                "tour IS one continuous shift (depot to depot), so an "
                "exceedance here is a genuine range constraint"),
    "freight_modular": ("per VEHICLE-DAY FREIGHT km vs ev_range_km_* "
                        "(emep_supplement.csv). A component, not a range "
                        "requirement: the same vehicle also drives pax km "
                        "outside the MODULAR_FREIGHT_DRIVE windows -- the "
                        "day's total is ev_range_*_drt"),
}


def _range_rows(detail, sup):
    """EV range SWEEP (Rev. B): per DRT vehicle-day / per freight tour km
    against three thresholds instead of one gate.

    Rationale (measured 2026-07-31 across the runs that have freight tours):
    at 250 km the exceedance is 0 % in EVERY run -- the longest tour anywhere
    is 183.3 km -- so a single 250 km gate reports a null result that looks
    like a check. The discriminating band is ~150 km (0-13.4 % depending on
    the run). Reporting the curve keeps the real finding "freight
    electrification is not range-constrained here" falsifiable instead of
    vacuous.

    Each fleet carries its OWN provenance string (_RANGE_SRC) because the
    denominator differs in kind, not just in value.
    """
    rows = []
    for fleet, label in (("drt", "drt"), ("freight", "freight_tour"),
                         ("freight_modular", "freight_modular")):
        kms = sorted(d["km"] for d in detail
                     if d["fleet"] == fleet and d["powertrain"] == "diesel")
        if not kms:
            continue
        src = _RANGE_SRC[fleet]
        rows += [row("environment", "ev_range_max_km_" + label,
                     max(kms), "km", src),
                 row("environment", "ev_range_p95_km_" + label,
                     _percentile(kms, 0.95), "km", src)]
        for key in EV_THRESHOLDS:
            thr = sup["ev_range_km_" + key]
            exceed = sum(1 for k in kms if k > thr) / len(kms)
            rows.append(row("environment",
                            "ev_range_exceed_" + fleet + "_" + str(int(thr)),
                            exceed, "share",
                            src + " [threshold=" + str(int(thr)) + " km]"))
    return rows


#: The only fleet whose segment mix is a RESULT: the conventional vans, where
#: jsprit picks freely from the offered types (FleetSize.INFINITE).
#:
#: "drt" and "freight_modular" are both excluded because both carry the SAME
#: fixed declared M2 -> N1-III substitution (DRT_SEGMENT) -- a share computed
#: over them is 1.0 by construction, i.e. a statement about a constant. The
#: dilution this avoids was measured on base10c, where the DRT arm outweighs the
#: vans ~4:1 in km (47953 vs 6252): including it reported n1_ii = 0.107 for the
#: very same LMD plan that reads 0.926 on the freight-only run bandz_central.
#: Two incomparable numbers for one identical vehicle mix is worse than none.
_MIX_FLEETS = ("freight",)


def _segment_share_rows(detail):
    """km share per N1 segment over the conventional van fleet -- makes the
    (endogenous, jsprit-chosen) mix auditable, so a CO2 delta can be attributed
    to routing vs. to a shifted fleet mix. See the plan's Task-7 interfaces.

    Absent on pax-only AND on modular (1d) runs: neither has a van mix, and
    n1_iii = 1.0 from a fixed substitution would read like a finding."""
    diesel = [d for d in detail if d["powertrain"] == "diesel"
              and d["fleet"] in _MIX_FLEETS]
    total = sum(d["km"] for d in diesel)
    if total <= 0:
        return []
    src = ("sum km per N1 segment / total km of fleet(s) "
           + "/".join(_MIX_FLEETS) + " (diesel arm) -- the CONVENTIONAL VAN "
           "fleet, where jsprit picks the type. DRT and modular are excluded: "
           "their segment is a fixed substitution, not a result")
    rows = []
    for seg, suffix in (("N1-II", "n1_ii"), ("N1-III", "n1_iii")):
        km = sum(d["km"] for d in diesel if d["segment"] == seg)
        rows.append(row("environment", "segment_km_share_" + suffix,
                        km / total, "share", src))
    return rows


def _intensity_rows_for_drt(veh_path, link_len, drt_detail, n_pax, n_parcels,
                            sup):
    """Mass/slot allocation rows for the shared-use (1c) arm.

    Only emitted when the caller supplies the served quantities: the
    extractor stays run-agnostic and does not know how many passengers or
    parcels a run served (build_kpis does). Without them there is no
    denominator, and a wrong denominator is worse than a missing row.

    CAUTION on the reported number: the allocation BASIS moves CO2e per
    parcel by a factor ~26 (mass 0.90 % vs slot 23.64 % freight share on the
    measured 1c run), which is why both shares are always emitted as a pair
    and why total_* stays the uncontested figure. METHODS-LOG 2.26.

    SCENARIO GUARD: mass allocation is a 1c construct, because only there do
    parcels ride as PERSONS (PARCEL_PERSON_PREFIX) and therefore show up in
    occ_parcels. In 1d they ride as a capsule, so occ_parcels is 0 on every
    link and the emitted alloc_share_parcels_mass would be 0 -- i.e. the
    claim "this run's freight is emission-free" on a run that clearly hauls
    freight. No parcel kg*km basis therefore means no allocation row at all;
    1d's honest decomposition is the regime split (freight_modular_* vs
    drt_*), which extract() already emits.
    """
    if not (n_pax or n_parcels):
        return []
    if not any(len(entry) > 2 and entry[2]
               for path in veh_path.values() for entry in path):
        return []
    alloc = {"pax": 0.0, "parcels": 0.0}
    by_veh = {d["entity"]: d["CO2E_WTW"] for d in drt_detail
              if d["powertrain"] == "diesel"}
    for veh, g in by_veh.items():
        part = allocate_vehicle_by_mass(veh_path.get(veh, []), link_len,
                                        g * 1e-3, sup)      # g -> kg
        alloc["pax"] += part["pax"]
        alloc["parcels"] += part["parcels"]
    if alloc["pax"] + alloc["parcels"] <= 0:
        return []
    return intensity_rows(alloc, n_pax, n_parcels, sup)


def extract(run_dir, prefix, recon=None, veh_path=None, network_gz=None,
            drt_task_events=None, n_pax=None, n_parcels=None):
    """Emission KPI rows + per-entity detail for one run.

    Three arms, each optional depending on what the run produced:
      - "freight"         conventional LMD tours (CarriersAnalysis TSV)
      - "freight_modular" 1d: freight km inside MODULAR_FREIGHT_DRIVE windows
      - "drt"             DRT vehicle km (pax side)

    DRT arm only when veh_path/recon/network are supplied by build_kpis
    (they are reused, never recomputed here). n_pax/n_parcels are the served
    quantities for the 1c allocation rows; omit them and those rows are
    skipped rather than divided by a guessed denominator.

    `drt_task_events` is the *.drt_events_filtered.txt cache -- the DVRP task
    events live there, NOT in the freight cache (freight_windows docstring).
    The parameter was called `freight_events` and that name cost the wiring
    step a silent zero: passing the (0-byte) freight cache yields no windows,
    so freight_modular_* vanishes and its km are booked as pax km instead --
    a plausible-looking result with no error anywhere. The name now says which
    file it wants.

    GAP GESCHLOSSEN (2026-07-31, war: "KNOWN GAP METHODS-LOG 2.14"). Der
    Plan notierte hier, der "drt"-Arm ueberlappe im 1d-Fall die modularen
    Freight-km und total_* sei deshalb die einzige belastbare Groesse.
    Das war der Stand VOR der Zurechnungsentscheidung. Die steht inzwischen
    (METHODS-LOG 1.4: 1d = restfreier Regimesplit), also wird die
    Doppelzaehlung behoben statt dokumentiert: die Freight-Fenster werden
    zuerst bestimmt und dann an drt_arm als exclude_windows gegeben.
    Die Zeitseite trennte ohnehin schon (drive_s vs freight_drive_s,
    drt_service_time.py:411/414); nur die Distanzseite fehlte.
    """
    fac = em.load_factors()
    arms = {}
    fr = freight_arm(run_dir, fac)
    if fr is not None:
        arms["freight"] = fr
    link_len = None
    windows = {}
    if veh_path and recon is not None and network_gz is not None:
        used = {p[0] for path in veh_path.values() for p in path}
        link_len = load_link_lengths(network_gz, used)
        # Fenster VOR dem DRT-Arm bestimmen: sie sind dessen Ausschlussmenge.
        if drt_task_events is not None:
            windows = freight_windows(drt_task_events)
        arms["drt"] = drt_arm(veh_path, recon, link_len, fac,
                              exclude_windows=windows)
        if windows:
            arms["freight_modular"] = modular_freight_arm(
                veh_path, windows, link_len, fac)

    rows, detail = [], []
    grand = _zero_totals()
    for fleet, (totals, det) in arms.items():
        detail += det
        for pt in POWERTRAINS:
            for k in EMIS_KEYS:
                grand[pt][k] += totals[pt][k]
            sfx = "_bev" if pt == "bev" else ""
            for metric, key, unit, f in _KPI_METRICS:
                if pt == "bev" and metric in _BEV_SKIP:
                    continue
                rows.append(row("environment", fleet + "_" + metric + sfx,
                                totals[pt][key] * f, unit, SRC))
    # total_* immer emittieren (= Summe der ABGEDECKTEN Flotten; bei
    # Freight-only-Runs also == freight_*) -- Vergleichbarkeit ueber Runs.
    if arms:
        for pt in POWERTRAINS:
            sfx = "_bev" if pt == "bev" else ""
            for metric, key, unit, f in _KPI_METRICS:
                if pt == "bev" and metric in _BEV_SKIP:
                    continue
                rows.append(row("environment", "total_" + metric + sfx,
                                grand[pt][key] * f, unit, SRC))
    rows += _range_rows(detail, fac["sup"])
    rows += _segment_share_rows(detail)
    if "drt" in arms and link_len is not None:
        rows += _intensity_rows_for_drt(veh_path, link_len, arms["drt"][1],
                                        n_pax, n_parcels, fac["sup"])
    return rows, detail


def write_detail(detail, meta, path):
    """kpi_emissions_vehicles.csv -- one row per entity x powertrain,
    ';'-separated like the other kpi_* CSVs."""
    header = ["run_id", "fleet", "entity", "vehicle_type", "segment", "km",
              "v_kmh", "powertrain"] + list(EMIS_KEYS)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(header)
        for d in detail:
            w.writerow([meta.run_id] + [d[k] for k in header[1:]])
