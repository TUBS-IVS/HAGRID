# -*- coding: utf-8 -*-
# analysis/kpi/cost_model.py
"""Unified direct-operating-cost model, parameterised from cost_parameters.csv.

This module owns ONLY the parameter side: it reads the CSV, derives the
`DERIVED` rows from the `SET` ones, and hands out rates. Evaluating the cost of
a run is `economics.py`'s job.

**The headline is DIRECT OPERATING COST, not total system cost** (user decision
2026-08-14, stated in the CSV header). C carries four things -- driver hours,
vehicle capital, spare parts, energy -- and deliberately no dispatch centre,
booking platform, workshop labour, depot or administration. `overhead_factor`
is zero because an arm-independent factor cancels exactly in every ratio
between arms; see the CSV header for why that is algebra and not fairness.

    C = sum_types(c_veh * N_veh) + c_time * VHT + c_maint_per_km * VKT
        + ENERGY_MJ / diesel_lhv_mj_per_l * diesel_price_eur_per_l

**Why c_dist_* is not in that sum.** `fuel_cost_basis = energy_mj`, so fuel
arrives through the emissions extractor's ENERGY_MJ rows, already speed- and
segment-weighted per vehicle. The `c_dist_*` rows in the CSV bundle maintenance
AND fuel and exist as comparison figures against the legacy rates only. Adding
them to C would count fuel twice -- the CSV says so on every one of those rows.

**Scope: Lausitz only** (user decision 2026-08-28). Hannover keeps the legacy
`*_placeholder` KPIs; see `economics.extract`.

Every derivation below reproduces the value documented in the CSV's own note
field -- c_time_lmd 28.99, c_time_drt 33.45, c_veh_ct_cep_size_s 14.80,
c_veh_drt_pax 22.74 EUR/d. `selftest()` asserts exactly that, so a parameter
edit that breaks the arithmetic fails loudly instead of shifting the headline.
"""
import csv
import io
from pathlib import Path

PARAM_FILE = Path(__file__).with_name("cost_parameters.csv")

#: Working days behind a salaried year: 52 weeks x 5 days. NOT 365 - 104, which
#: gives 261 and would silently shift both hourly rates by ~0.5 %.
WORKDAYS_PER_YEAR = 52 * 5


class CostParams:
    """Rates derived from cost_parameters.csv. Read-only after construction."""

    def __init__(self, raw):
        self.raw = raw
        self.c_time_lmd, self.lmd_labour_detail = self._hourly_labour("lmd")
        self.c_time_drt, self.drt_labour_detail = self._hourly_labour("drt")

        # --- vehicle capital, EUR per operating day -----------------------
        ins = self.get("insurance_tax_per_vehicle_year")
        lmd_days = self.get("operating_days_per_year_lmd")
        drt_days = self.get("operating_days_per_year_drt")
        self.c_veh_lmd = {}
        for size in ("s", "m", "l"):
            monthly = self.get("lmd_vehicle_fixed_monthly_size_" + size)
            self.c_veh_lmd["ct_cep_size_" + size] = (monthly * 12 + ins) / lmd_days

        # A purchase build, not a lease: this vehicle runs ~100000 km/a and no
        # standard lease covers that (see the CSV note). Straight-line over
        # vehicle_age_period_years down to vehicle_residual_share.
        purchase = self.get("drt_vehicle_purchase_net")
        residual = self.get("vehicle_residual_share")
        years = self.get("vehicle_age_period_years")
        self.drt_veh_monthly_pax = (purchase * (1.0 - residual) / years + ins) / 12.0
        self.c_veh_drt_pax = self.drt_veh_monthly_pax * 12.0 / drt_days

        # The modular vehicle exists on no market, so the premium carries the
        # assumption rather than an invented price. The CSV ships a BAND; index
        # 0 (=1.00) is the reported point, the rest is the sensitivity. At 1.00
        # driveboard, lifting mechanism and BOTH capsules are assumed
        # cost-neutral against a plain pax van -- an assumption, labelled one.
        self.modular_premium_band = self._band("modular_premium_factor")
        self.c_veh_drt_modular = [self.c_veh_drt_pax * f
                                  for f in self.modular_premium_band]

        # --- distance and energy ------------------------------------------
        self.c_maint_per_km = self.get("c_maint_per_km")
        self.diesel_lhv = self.get("diesel_lhv_mj_per_l")
        self.diesel_price = self.get("diesel_price_eur_per_l")
        self.diesel_price_band = (self.get("diesel_price_eur_per_l_low"),
                                  self.diesel_price,
                                  self.get("diesel_price_eur_per_l_high"))

        # --- surcharges ----------------------------------------------------
        self.overtime_threshold_h = self.get("overtime_threshold_h")
        self.overtime_factor = self.get("overtime_factor")
        self.overtime_factor_sensitivity = self.get("overtime_factor_sensitivity")
        self.evening_surcharge_factor = self.get("evening_surcharge_factor")
        self.overhead_factor = self.get("overhead_factor")
        self.max_daily_working_hours_legal = self.get("max_daily_working_hours_legal")

    # -- helpers ----------------------------------------------------------
    def get(self, param_id):
        r = self.raw.get(param_id)
        if r is None:
            raise KeyError("no such cost parameter: " + param_id)
        v = (r["value"] or "").strip()
        if not v:
            raise ValueError("cost parameter has no value (status %s): %s"
                             % (r["status"], param_id))
        return float(v)

    def _band(self, param_id):
        """A `;`-separated sensitivity band, e.g. modular_premium_factor."""
        raw = (self.raw[param_id]["value"] or "").strip()
        return tuple(float(p) for p in raw.split(";") if p.strip())

    def _hourly_labour(self, arm):
        """Employer cost per PRODUCTIVE hour, from tariff to on-costs.

        gross    = hourly * weekly_h * 52 + special months * monthly + bonus
        employer = gross * (1 + oncost_rate)
        prod. h  = (workdays - vacation - holidays - sick) * (weekly_h / 5)

        The productive-hour denominator is what makes this a real rate: a
        driver is paid for a year but only drives ~204 days of it, so dividing
        by contracted hours would understate labour by about a fifth.
        """
        p = lambda name: self.get(arm + "_" + name)  # noqa: E731
        weekly_h = p("weekly_hours")
        hourly = p("wage_tariff_hourly")
        monthly_salary = hourly * weekly_h * 52.0 / 12.0
        gross = (hourly * weekly_h * 52.0
                 + p("annual_special_payment_months") * monthly_salary
                 + p("annual_special_payment_eur")
                 + p("holiday_bonus"))
        employer = gross * (1.0 + p("employer_oncost_rate"))
        days = WORKDAYS_PER_YEAR - p("vacation_days") - p("public_holidays") - p("sick_days")
        productive_h = days * (weekly_h / 5.0)
        return employer / productive_h, {
            "gross_eur_a": gross, "employer_eur_a": employer,
            "productive_h_a": productive_h, "productive_days_a": days}

    def energy_eur(self, energy_mj, price=None):
        """Fuel cost from ENERGY_MJ (already segment- and speed-weighted)."""
        return energy_mj / self.diesel_lhv * (self.diesel_price if price is None else price)


def load(path=None):
    """Parse cost_parameters.csv -> {param_id: row}. `#` lines are prose."""
    path = Path(path) if path else PARAM_FILE
    text = path.read_text(encoding="utf-8")
    body = "".join(l for l in text.splitlines(True)
                   if not l.startswith("#") and l.strip())
    out = {}
    for r in csv.DictReader(io.StringIO(body)):
        out[r["param_id"]] = r
    if not out:
        raise ValueError("no parameter rows in " + str(path))
    return out


def build(path=None):
    return CostParams(load(path))


#: Values the CSV states in its own note fields. Asserting against them turns a
#: silent arithmetic drift into a failure -- these are the anchors, not the code.
DOCUMENTED = {"c_time_lmd": 28.99, "c_time_drt": 33.45,
              "c_veh_ct_cep_size_s": 14.80, "c_veh_ct_cep_size_m": 16.60,
              "c_veh_ct_cep_size_l": 18.80, "c_veh_drt_pax": 22.74}


def selftest(params=None):
    """Reproduce every documented rate; raise on the first mismatch."""
    p = params or build()
    got = {"c_time_lmd": p.c_time_lmd, "c_time_drt": p.c_time_drt,
           "c_veh_drt_pax": p.c_veh_drt_pax}
    for k, v in p.c_veh_lmd.items():
        got["c_veh_" + k] = v
    bad = []
    for name, want in DOCUMENTED.items():
        have = got[name]
        if abs(have - want) > 0.005:
            bad.append("%s: derived %.4f, CSV documents %.2f" % (name, have, want))
    if bad:
        raise AssertionError("cost model drifted from cost_parameters.csv:\n  "
                             + "\n  ".join(bad))
    return got


if __name__ == "__main__":
    p = build()
    for name, value in sorted(selftest(p).items()):
        print("%-24s %8.2f" % (name, value))
    print("%-24s %8.2f" % ("c_veh_drt_modular[1.00]", p.c_veh_drt_modular[0]))
    print("labour lmd:", p.lmd_labour_detail)
    print("labour drt:", p.drt_labour_detail)
