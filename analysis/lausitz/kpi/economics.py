# -*- coding: utf-8 -*-
# analysis/lausitz/kpi/economics.py
"""Economic KPIs.

TWO models live here, and which one runs is decided by study area:

* **Lausitz** -> the unified DIRECT OPERATING COST from `cost_model.py`,
  parameterised by `cost_parameters.csv`. Rows carry no `_placeholder` suffix.
* **everything else (Hannover)** -> the legacy bottom-up placeholder,
  unchanged (user decision 2026-08-28: "Hannover zunaechst so lassen").

**What VHT is, and why it is the whole ballgame.** `vht_basis = durH` in the
parameter file. For an LMD van durH is the tour duration; a DRT vehicle has no
tour, so the analogue is `drt_tour_hours_total` -- the sum over vehicles of the
span from first to last task, dwell included (identical to the `active_h`
column of kpi_vehicles.csv). The alternatives were measured and rejected by the
user on 2026-08-28:

    basis        1d f135    Baseline   Delta      what it assumes
    shift_h      3240.0 h   2880.0 h   1d +4307   24 h availability = paid; makes
                                                  fleet size the only cost driver
    active_h     2178.9 h   1953.0 h   1d  -180   <- CHOSEN (durH)
    occupied_h   1394.7 h   1401.1 h   1d -7949   idle time at the wheel unpaid

The basis flips the SIGN of the headline, which is why it is a recorded
decision and not a default. **Limitation that travels with it:** DRT vehicles
are active 16.1 h on average (max 20.7 h), well past the 10 h ArbZG maximum, so
every vehicle needs at least two drivers per day. Charging `c_time * active_h`
assumes drivers can be swapped freely -- no minimum shift, no paid handover.
That is optimistic and must be stated wherever these numbers appear.

**Not instrumented** (emitted as explicit zero rows, never silently dropped):
the overtime sensitivity and the evening surcharge both need per-vehicle durH
and time-of-day, which are not available at the point this extractor runs. The
headline is unaffected -- `overtime_factor` is 0.0 by user decision -- but the
sensitivity is real (~16 % of labour at factor 0.30) and is a BACKLOG item.
"""
from common import row

LABOUR_EUR_PER_H = 20.0
VEHICLE_EUR_PER_H = 5.0

#: kpi_name -> which cost_model rate prices the fleet's vehicle capital.
_DRT_VEH_RATE = {"DRT_MODULAR": "modular", "DRT_SHAREDUSE": "pax",
                 "DRT_BASELINE": "pax"}


def _get(rows, name):
    for r in rows:
        if r["kpi_name"] == name:
            return r["value"]
    return None


def _num(rows, name):
    v = _get(rows, name)
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


# --------------------------------------------------------------------------
# Legacy placeholder -- Hannover only. Do not extend.
# --------------------------------------------------------------------------
def _legacy(all_rows, fleet_size):
    """PLACEHOLDER: 25 EUR/veh-shift-h split labour 20 / vehicle 5 (Rudolph
    LMD breakdown, ~80/20). Every KPI carries `_placeholder` in its name."""
    rows = []
    shift_h = _get(all_rows, "fleet_shift_hours")
    if shift_h is None and fleet_size:
        shift_h = fleet_size * 24.0  # DVRP shift 0..86400 per vehicle
    if shift_h:
        labour = shift_h * LABOUR_EUR_PER_H
        total = shift_h * (LABOUR_EUR_PER_H + VEHICLE_EUR_PER_H)
        rows.append(row("economic", "drt_cost_bottom_up_placeholder",
                        total, "EUR", "placeholder 25 EUR/veh-shift-h"))
        rides = _get(all_rows, "drt_rides")
        if rides:
            rows.append(row("economic", "drt_cost_per_ride_placeholder",
                            total / rides, "EUR/trip", "computed"))
        rows.append(row("economic", "drt_labour_share_placeholder",
                        labour / total, "share", "placeholder Rudolph 80/20"))
    fc = _get(all_rows, "freight_total_costs")
    parcels = _get(all_rows, "parcels_handled")
    if fc is not None and parcels:
        rows.append(row("economic", "freight_cost_per_parcel",
                        fc / parcels, "EUR/parcel", "computed"))
    return rows


# --------------------------------------------------------------------------
# Unified direct operating cost -- Lausitz
# --------------------------------------------------------------------------
SRC = ("cost_parameters.csv v0.7-draft; DIRECT OPERATING COST (driver hours, "
       "vehicle capital, spare parts, energy) -- NOT total system cost: no "
       "dispatch centre, platform, workshop, depot or administration. "
       "VHT basis = durH = drt_tour_hours_total (first-to-last task, dwell "
       "incl.), user decision 2026-08-28; assumes free driver swap, see "
       "cost_drivers_per_vehicle_min")


def _lmd_type_counts(pf, expected_n):
    """{type_id: n} over the non-excluded freight tours.

    Returns None when the mix cannot be established or does not add up to the
    fleet count the freight extractor reported. No default van mix is
    substituted: a plausible-looking guess here would silently misprice the
    whole baseline freight arm.
    """
    if pf is None or not getattr(pf, "vehrecords", None):
        return None
    counts = {}
    for vr in pf.vehrecords:
        if vr.excluded or not vr.type_id:
            continue
        counts[vr.type_id] = counts.get(vr.type_id, 0) + 1
    if not counts:
        return None
    if expected_n is not None and sum(counts.values()) != int(expected_n):
        return None
    return counts


def _direct_cost(all_rows, meta, pf, params):
    """Evaluate C on fleet-wide aggregates. Returns (rows, problems)."""
    scenario = getattr(meta, "scenario", "") or ""
    rows, problems = [], []

    drt_n = _num(all_rows, "drt_vehicles")
    drt_vht = _num(all_rows, "drt_tour_hours_total")
    drt_vkt = _num(all_rows, "drt_vehicle_km")
    frt_n = _num(all_rows, "freight_vehicles")
    frt_vht = _num(all_rows, "freight_tour_hours")
    frt_vkt = _num(all_rows, "freight_vehicle_km")
    energy = _num(all_rows, "total_energy_final")

    # Both fleets are optional and neither absence is a defect: an LMD-only run
    # has no DRT fleet, an integrated or pax-only run has no vans. Only a run
    # with NEITHER is un-evaluable.
    has_drt = bool(drt_n) and drt_vht is not None and drt_vkt is not None
    has_vans = bool(frt_n) and frt_vht is not None and frt_vkt is not None
    if not has_drt and not has_vans:
        return [], ["no fleet aggregates (neither drt_* nor freight_*)"]

    cap_drt = cap_lmd = lab_drt = lab_lmd = maint = 0.0
    rate_kind = _DRT_VEH_RATE.get(scenario, "pax")
    drt_rate = (params.c_veh_drt_modular[0] if rate_kind == "modular"
                else params.c_veh_drt_pax)
    if has_drt:
        # In 1c/1d the freight rides on the DRT vehicles, so
        # drt_tour_hours_total and drt_vehicle_km already carry it and it is
        # paid at the DRT wage. Only the baseline has a separate van fleet.
        cap_drt = drt_n * drt_rate
        lab_drt = drt_vht * params.c_time_drt
        maint += drt_vkt * params.c_maint_per_km

    if has_vans:
        lab_lmd = frt_vht * params.c_time_lmd
        maint += frt_vkt * params.c_maint_per_km
        mix = _lmd_type_counts(pf, frt_n)
        if mix is None:
            problems.append(
                "LMD vehicle-type mix unavailable or inconsistent with "
                "freight_vehicles=%s -- van capital omitted from C" % frt_n)
        else:
            for type_id, n in sorted(mix.items()):
                rate = params.c_veh_lmd.get(type_id)
                if rate is None:
                    problems.append("no c_veh rate for van type " + str(type_id))
                    continue
                cap_lmd += n * rate

    # -- energy ------------------------------------------------------------
    # total_energy_final is the sum the emissions extractor publishes; fall
    # back to summing its disjoint parts (drt / freight / freight_modular)
    # rather than dropping the term, and say so when neither exists.
    if energy is None:
        parts = [_num(all_rows, n) for n in
                 ("drt_energy_final", "freight_energy_final",
                  "freight_modular_energy_final")]
        parts = [p for p in parts if p is not None]
        energy = sum(parts) if parts else None
    if energy is None:
        problems.append("no ENERGY_MJ rows -- energy term omitted from C")
        energy_eur = 0.0
    else:
        energy_eur = params.energy_eur(energy)

    capital = cap_drt + cap_lmd
    labour = lab_drt + lab_lmd
    total = capital + labour + maint + energy_eur
    total *= (1.0 + params.overhead_factor)   # zero by decision; explicit anyway

    def add(name, value, unit, src=SRC):
        rows.append(row("economic", name, value, unit, src))

    add("cost_total", total, "EUR")
    add("cost_capital", capital, "EUR")
    add("cost_labour", labour, "EUR")
    add("cost_maintenance", maint, "EUR")
    add("cost_energy", energy_eur, "EUR")
    add("cost_labour_share", labour / total if total else 0.0, "share")

    # -- per-unit costs ONLY where the two services are separable -----------
    # In the baseline the passenger fleet and the van fleet are disjoint, so
    # EUR/ride and EUR/parcel each have a real denominator AND a real
    # numerator. In 1c/1d the same vehicle carries both, and splitting the
    # joint cost needs an allocation rule (mass basis, METHODS-LOG 2.26) that
    # is a modelling decision, not an arithmetic one. Dividing the FULL system
    # total by parcels would silently charge the whole passenger operation to
    # the freight side -- 82 % of it here. The valid cross-arm comparison is
    # the daily SYSTEM total at matched service, which the calibration
    # established; that needs no allocation at all.
    rides = _num(all_rows, "drt_rides")
    # Parcel denominator: the NET count actually delivered, so both arms are
    # divided by the same thing (see tests/test_delivery_rate_convention.py).
    parcels = _num(all_rows, "parcels_handled")
    if parcels is None:
        served = _num(all_rows, "parcels_served")
        missed = _num(all_rows, "parcels_missed_overlay")
        if served is not None:
            parcels = served - (missed or 0.0)

    if has_drt and has_vans:
        # Disjoint fleets (baseline): each service has its own numerator.
        drt_energy = _num(all_rows, "drt_energy_final")
        frt_energy = _num(all_rows, "freight_energy_final")
        cost_drt = (cap_drt + lab_drt + drt_vkt * params.c_maint_per_km
                    + (params.energy_eur(drt_energy) if drt_energy is not None else 0.0))
        cost_lmd = (cap_lmd + lab_lmd + frt_vkt * params.c_maint_per_km
                    + (params.energy_eur(frt_energy) if frt_energy is not None else 0.0))
        add("cost_drt_total", cost_drt, "EUR")
        add("cost_lmd_total", cost_lmd, "EUR")
        if rides:
            add("cost_per_ride", cost_drt / rides, "EUR/trip",
                SRC + " [passenger fleet only -- the van fleet is disjoint]")
        if parcels:
            add("cost_per_parcel", cost_lmd / parcels, "EUR/parcel",
                SRC + " [van fleet only -- the passenger fleet is disjoint]")
        if drt_energy is None or frt_energy is None:
            problems.append("per-fleet energy rows missing -- cost_drt_total / "
                            "cost_lmd_total exclude their energy term")
    elif has_vans:
        # Van fleet only: every euro is a freight euro.
        if parcels:
            add("cost_per_parcel", total / parcels, "EUR/parcel",
                SRC + " [van fleet only run -- C is entirely freight]")
    elif parcels:
        # One fleet carrying both services (1c/1d): not separable.
        add("cost_per_unit_separable", 0, "flag",
            "no EUR/ride or EUR/parcel emitted: one fleet carries both "
            "services, so the joint cost needs the mass allocation of "
            "METHODS-LOG 2.26. Compare arms on cost_total at matched service.")
    elif rides:
        # Passenger-only DRT run: every euro is a passenger euro.
        add("cost_per_ride", total / rides, "EUR/trip",
            SRC + " [passenger-only run -- C is entirely passenger]")

    # -- energy price band (the parameter the result is most sensitive to) --
    for label, price in (("low", params.diesel_price_band[0]),
                         ("high", params.diesel_price_band[2])):
        if energy is not None:
            alt = capital + labour + maint + params.energy_eur(energy, price)
            add("cost_total_diesel_" + label, alt * (1.0 + params.overhead_factor),
                "EUR", SRC + " [diesel %.4f EUR/l]" % price)

    # -- modular premium band ---------------------------------------------
    if has_drt and rate_kind == "modular":
        for factor, rate in zip(params.modular_premium_band,
                                params.c_veh_drt_modular):
            if factor == params.modular_premium_band[0]:
                continue
            alt = total + drt_n * (rate - drt_rate)
            add("cost_total_premium_%03d" % round(factor * 100), alt, "EUR",
                SRC + " [modular_premium_factor %.2f]" % factor)

    # -- admissibility / honesty rows -------------------------------------
    # Drivers per vehicle-day implied by the chosen VHT basis against ArbZG.
    # Reported because charging active_h at one wage silently assumes the
    # swap is free; this row makes the assumption countable.
    if has_drt:
        mean_active = drt_vht / drt_n
        import math
        add("cost_drivers_per_vehicle_min",
            math.ceil(mean_active / params.max_daily_working_hours_legal),
            "drivers", "ceil(mean active_h %.2f / ArbZG %.1f h) -- C prices "
                       "hours, not shifts; free driver swap assumed"
                       % (mean_active, params.max_daily_working_hours_legal))
    add("cost_overtime_sensitivity_instrumented", 0, "flag",
        "overtime_factor=%.2f gives an exact zero in C; the %.2f sensitivity "
        "needs per-vehicle durH, not available here (BACKLOG)"
        % (params.overtime_factor, params.overtime_factor_sensitivity))
    add("cost_evening_surcharge_instrumented", 0, "flag",
        "evening_surcharge_factor=%.2f not applied: hours_after_2100 is not "
        "instrumented (BACKLOG)" % params.evening_surcharge_factor)
    return rows, problems


def extract(all_rows, fleet_size=None, meta=None, pf=None):
    """Economic rows for one run. Lausitz -> unified cost model, else legacy."""
    area = str(getattr(meta, "study_area", "") or "")
    if not area.startswith("lausitz"):
        return _legacy(all_rows, fleet_size)

    try:
        import cost_model
        params = cost_model.build()
        cost_model.selftest(params)
        rows, problems = _direct_cost(all_rows, meta, pf, params)
    except Exception as e:  # noqa: BLE001 - degrade visibly, never silently
        note = type(e).__name__ + ": " + str(e)
        print("[economics] cost model unavailable: " + note)  # ASCII only
        return _legacy(all_rows, fleet_size) + [
            row("meta", "cost_model_failed", 1, "flag", note)]

    if not rows:
        note = "; ".join(problems) or "no aggregates"
        print("[economics] cost model not evaluable: " + note)  # ASCII only
        return _legacy(all_rows, fleet_size) + [
            row("meta", "cost_model_failed", 1, "flag", note)]

    # A PARTIAL evaluation rides in the `source` of every affected number, not
    # in a separate meta row. A missing term makes each euro figure wrong, so
    # the caveat has to travel with the figure wherever it is read -- a note in
    # a dashboard panel is easy to read past, and on a --no-events build it
    # would be pure noise. Only an unusable model gets a meta flag (above).
    if problems:
        note = " | CAVEAT: " + "; ".join(problems)
        print("[economics] cost model partial: " + "; ".join(problems))  # ASCII
        for r in rows:
            r["source"] = r["source"] + note
    return rows
