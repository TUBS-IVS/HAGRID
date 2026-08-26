# -*- coding: utf-8 -*-
# analysis/kpi/emissions_emep.py
"""EMEP/EEA Tier-3 emission factor evaluation for the Lausitz KPI stack.

Deliberately independent of src/hagrid_output_analysis/emissions.py (that
module backs a colleague's published paper and is frozen; user decision
2026-07-28). NOTE the methodological difference: that module uses STREAM
(empty, full) factor pairs interpolated by load_pct, this one uses EMEP/EEA
speed curves with the mass effect carried by the N1 SEGMENT and no load
dimension (guidebook restricts load correction to HDV). The two are
independent sources and must NOT be blended -- see the plan's Global
Constraints. Factor data lives in data/*.csv with full provenance columns.

Curve form (COPERT v5, validated against the Appendix-4 EF(v=80) check
column in test_emissions_emep.py):
    EF(v) = (alpha*v^2 + beta*v + gamma + delta/v)
            / (epsilon*v^2 + zita*v + hta) * (1 - rf)
with v clamped to [vmin, vmax]. rf is a FRACTION (xlsx header says '[%]'
but stores 0.282175 for 28.2 %).
"""
import csv
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent / "data"

_NUM = ("alpha", "beta", "gamma", "delta", "epsilon", "zita", "hta",
        "rf", "vmin", "vmax", "ef_check_v", "ef_check")


def load_factors(data_dir=None):
    """-> {"diesel": {segment: {pollutant: coef}}, "bev": {...}, "sup": {}}"""
    d = Path(data_dir) if data_dir else DATA_DIR
    fac = {"diesel": {}, "bev": {}, "sup": {}}
    with open(d / "emep_hot_factors.csv", newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            coef = {k: float(r[k]) for k in _NUM}
            coef["unit"] = r["unit"]
            fac[r["powertrain"]].setdefault(r["segment"], {})[r["pollutant"]] = coef
    sup_file = d / "emep_supplement.csv"
    if sup_file.exists():
        with open(sup_file, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                fac["sup"][r["name"]] = float(r["value"])
    fac["cold"] = load_cold_factors(d)
    return fac


def ef(v_kmh, coef):
    """Tier-3 hot emission factor at mean travelling speed v [km/h]."""
    v = min(max(float(v_kmh), coef["vmin"]), coef["vmax"])
    num = coef["alpha"] * v * v + coef["beta"] * v + coef["gamma"] + coef["delta"] / v
    den = coef["epsilon"] * v * v + coef["zita"] * v + coef["hta"]
    return (num / den) * (1.0 - coef["rf"])


EXHAUST_KEYS = ("CO", "NOx", "VOC", "PM_EXHAUST", "CH4", "SPN23", "N2O",
                "CO2", "CO2E_TTW")
# xlsx pollutant name -> output key
_POLL_KEY = {"CO": "CO", "NOx": "NOx", "VOC": "VOC",
             "PM Exhaust": "PM_EXHAUST", "CH4": "CH4", "SPN23": "SPN23"}


def _tyre_speed_corr(v):
    """EMEP/EEA GB ch. 1.A.3.b.vi eq. (5), tyre wear speed correction on
    MEAN TRIP speed. Normalised at 80 km/h; plateaus below 40 and above 90
    because the source has no experimental data there."""
    if v < 40.0:
        return 1.39
    if v <= 90.0:
        return -0.00974 * v + 1.78
    return 0.902


def _brake_speed_corr(v):
    """EMEP/EEA GB ch. 1.A.3.b.vi eq. (8), brake wear speed correction on
    MEAN TRIP speed. Normalised at 65 km/h; steeper than the tyre slope
    because brake wear is negligible at motorway speeds."""
    if v < 40.0:
        return 1.67
    if v <= 95.0:
        return -0.027 * v + 2.75
    return 0.185


def _seg_key(prefix, segment):
    """'tsp_brake_g_per_km_' + 'N1-II' -> 'tsp_brake_g_per_km_n1_ii'."""
    return prefix + segment.lower().replace("-", "_")


def non_exhaust_pm10(km, v_kmh, powertrain, segment, sup):
    """PM10 [g] from tyre / brake / road-surface wear over km at mean speed
    v, for an N1 `segment`.

    Segment-resolved because the source is: ch. 1.A.3.b.vi-vii gives TSP
    bases per N1 segment -- Tab. 3-4 (tyre) and Tab. 3-8 (road) group
    N1-II and N1-III into one row, Tab. 3-6 (brake) separates all three.
    That grouping is written out as data (identical values for ii/iii) so
    the source structure stays visible in emep_supplement.csv.

    Speed corrections are the guidebook's own eq. (5) / (8) on MEAN TRIP
    speed; road-surface wear has no speed dependence (eq. 9).

    BEV multipliers are the guidebook's own ICE->BEV ratios for the medium
    passenger car (the source has no BEV row for LCV) -- a declared
    category transfer, not a free assumption. See data/README.md.

    Raises KeyError on an unknown segment: a vehicle type without a
    mapping must fail loudly rather than be silently priced as N1-III.
    """
    bev = powertrain == "bev"
    tyre = (km * sup[_seg_key("tsp_tyre_g_per_km_", segment)]
            * sup["pm10_frac_tyre"] * _tyre_speed_corr(v_kmh)
            * (sup["bev_tyre_mult"] if bev else 1.0))
    brake = (km * sup[_seg_key("tsp_brake_g_per_km_", segment)]
             * sup["pm10_frac_brake"] * _brake_speed_corr(v_kmh)
             * (sup["bev_brake_mult"] if bev else 1.0))
    road = (km * sup[_seg_key("tsp_road_g_per_km_", segment)]
            * sup["pm10_frac_road"]
            * (sup["bev_road_mult"] if bev else 1.0))
    return tyre, brake, road


def vehicle_emissions(km, v_kmh, powertrain, segment, fac):
    """Full pollutant vector [g; ENERGY_MJ in MJ; SPN23 in #] for `km`
    driven at mean travelling speed `v_kmh` by a vehicle of N1 `segment`.

    The segment IS the mass channel: EMEP/EEA resolves vehicle mass for
    light vehicles through the N1 segment and provides no load dimension
    for LCV (guidebook ch. 1.A.3.b.i-iv p. 62 f. restricts the load
    correction to HDV, and ch. 1.A.3.b.vi does the same for tyre/brake
    wear via LCF_T / LCF_B). There is deliberately no load/payload
    argument -- see the plan's Global Constraints. Idle and cold-start are
    NOT modelled (documented limitation: engine-off at service stops
    assumed; cold-start bounded in the plan's docs task).

    Raises KeyError on an unknown segment -- a new vehicle type must fail
    loudly rather than be silently priced as N1-III.
    """
    sup = fac["sup"]
    out = {}
    if powertrain == "diesel":
        coefs = fac["diesel"][segment]
        for poll, key in _POLL_KEY.items():
            out[key] = km * ef(v_kmh, coefs[poll])
        ec = km * ef(v_kmh, coefs["EC"])
        out["ENERGY_MJ"] = ec
        out["CO2"] = ec * sup["ttw_co2_g_per_mj_diesel"]
        out["N2O"] = km * sup["n2o_g_per_km_diesel_lcv"]
        out["CO2E_TTW"] = (out["CO2"] + sup["gwp_ch4"] * out["CH4"]
                           + sup["gwp_n2o"] * out["N2O"])
        out["CO2E_WTW"] = out["CO2E_TTW"] + ec * sup["wtt_co2e_g_per_mj_diesel"]
    elif powertrain == "bev":
        ec = km * ef(v_kmh, fac["bev"][segment]["EC"])
        out = {k: 0.0 for k in EXHAUST_KEYS}
        out["ENERGY_MJ"] = ec
        out["CO2E_WTW"] = ec * sup["grid_co2e_g_per_mj"]
    else:
        raise ValueError("unknown powertrain: " + str(powertrain))
    tyre, brake, road = non_exhaust_pm10(km, v_kmh, powertrain, segment, sup)
    out["PM10_TYRE"], out["PM10_BRAKE"], out["PM10_ROAD"] = tyre, brake, road
    out["PM10_NONEXHAUST"] = tyre + brake + road
    return out


_COLD_NUM = ("a", "b", "c", "vmin", "vmax", "tmin", "tmax")

#: Fuer Euro 7 fuehrt Appendix 4 fuer diese drei KEINE Kaltstartparametrisierung.
#: Ihr Zuschlag ist 0, und das ist eine LUECKE, keine Messung -- die
#: KPI-Quellenstrings muessen das sagen (extract_emissions._COLD_SRC).
COLD_UNPARAMETERISED = ("PM_EXHAUST", "CH4", "SPN23")

#: bc-Koeffizientennamen im Supplement, je Ausgabeschluessel.
_COLD_BC_KEYS = {"CO": "co", "NOx": "nox", "VOC": "voc"}


def load_cold_factors(data_dir=None):
    """-> {segment: {pollutant: coef}} fuer RANGE 1 (ta > 0).

    Nur RANGE 1 wird geladen: bei ambient_temp_c = 10 ist das der gueltige
    Bereich. RANGE 2/3 stehen mit in der CSV, damit eine Winter-Sensitivitaet
    eine Datenauswahl ist und keine Extraktion (Spec L4).
    """
    d = Path(data_dir) if data_dir else DATA_DIR
    cold = {}
    path = d / "emep_cold_factors.csv"
    if not path.exists():
        return cold
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["range"] != "RANGE 1":
                continue
            coef = {k: float(r[k]) for k in _COLD_NUM}
            cold.setdefault(r["segment"], {})[r["pollutant"]] = coef
    return cold


def cold_beta(sup):
    """Anteil kalt gefahrener Strecke an der GESAMTfahrleistung, Tab. 3-39."""
    lt, ta = sup["ltrip_km"], sup["ambient_temp_c"]
    return (sup["cold_beta_a0"] - sup["cold_beta_a1"] * lt
            - (sup["cold_beta_b0"] - sup["cold_beta_b1"] * lt) * ta)


def cold_km(sup):
    """Kaltdistanz je Start [km] -- die uebertragbare Groesse.

    `beta` selbst ist NICHT uebertragbar: es ist fuer ltrip in [8, 15] km
    kalibriert und wird bei unseren ~99-km-Touren negativ. Das Produkt
    beta(ltrip)*ltrip ist ueber das gueltige Band stabil (3.02 / 3.50 / 3.39
    km bei ltrip 8 / 12.4 / 15) und wird als AUSGEWIESENER Transfer benutzt
    -- siehe data/README.md.
    """
    return cold_beta(sup) * sup["ltrip_km"]


def cold_bc(pollutant_key, sup):
    """Euro-6+-Reduktionsfaktor auf den Kaltanteil, Tab. 3-46. 1.0 fuer
    Schadstoffe ohne eigene bc-Zeile (z. B. EC)."""
    k = _COLD_BC_KEYS.get(pollutant_key)
    if k is None:
        return 1.0
    return sup["cold_bc_" + k + "_a"] - sup["cold_bc_" + k + "_b"] * sup["ltrip_km"]


def cold_q(v_kmh, coef, ta):
    """Kalt/Warm-Verhaeltnis Q = a*v + b*ta + c, Boden 1.0.

    v wird auf den Gueltigkeitsbereich der KALTZEILE geclampt, nicht auf den
    der Hot-Kurve: CO/NOx/VOC sind hier nur bis 45 km/h parametrisiert,
    waehrend die Hot-Kurven bis 140 gehen. Ohne diesen eigenen Clamp
    extrapoliert die Formel stillschweigend.
    """
    v = min(max(float(v_kmh), coef["vmin"]), coef["vmax"])
    return max(1.0, coef["a"] * v + coef["b"] * ta + coef["c"])


def cold_start_extra(n_starts, km, v_kmh, powertrain, segment, fac):
    """Additiver Kaltstart-Zuschlag, gleiche Keys wie vehicle_emissions().

        extra = n * cold_km * ef_hot(v) * bc * (Q(v, ta) - 1)

    Das ist EMEP/EEA Gl. (10) in der Euro-6+-Fassung, umgestellt: beta*km
    ist die Kaltdistanz, und die wird je Start angesetzt statt als Anteil
    an der Gesamtfahrleistung (siehe cold_km()).

    BEV bekommt 0, weil das Cold-Sheet keine BEV-Zeilen fuehrt. ACHTUNG,
    das ist einseitig ZUGUNSTEN des BEV-Arms: real hat ein BEV sehr wohl
    einen Kaltverbrauch, dominiert von der Kabinenheizung. Ausgewiesene
    Limitation, siehe data/README.md.

    Abrieb (PM10_*) bekommt keinen Zuschlag -- er ist distanzbasiert.
    """
    sup = fac["sup"]
    out = {k: 0.0 for k in EXHAUST_KEYS}
    out.update({"ENERGY_MJ": 0.0, "CO2E_WTW": 0.0, "PM10_TYRE": 0.0,
                "PM10_BRAKE": 0.0, "PM10_ROAD": 0.0, "PM10_NONEXHAUST": 0.0})
    if powertrain != "diesel" or n_starts <= 0 or km <= 0:
        return out

    coefs = fac["diesel"][segment]
    cold = fac.get("cold", {}).get(segment, {})
    ta = sup["ambient_temp_c"]
    ckm = n_starts * cold_km(sup)

    for poll, key in _POLL_KEY.items():
        c = cold.get(poll)
        if c is None:                      # COLD_UNPARAMETERISED -> Luecke
            continue
        # v wird VOR dem ef()-Aufruf auf die Kaltzeile geclampt, nicht erst
        # in cold_q(): sonst waechst ef_hot(v) oberhalb von vmax der
        # Kaltkurve unbemerkt weiter, waehrend Q schon plateaut -- der
        # Zuschlag wuerde dann trotz Clamp weiter mit v steigen.
        v_c = min(max(float(v_kmh), c["vmin"]), c["vmax"])
        out[key] = ckm * ef(v_c, coefs[poll]) * cold_bc(key, sup) * (
            cold_q(v_c, c, ta) - 1.0)

    ec_cold = cold.get("EC")
    if ec_cold is not None:
        v_c = min(max(float(v_kmh), ec_cold["vmin"]), ec_cold["vmax"])
        ec = ckm * ef(v_c, coefs["EC"]) * (cold_q(v_c, ec_cold, ta) - 1.0)
        out["ENERGY_MJ"] = ec
        out["CO2"] = ec * sup["ttw_co2_g_per_mj_diesel"]
        # N2O bekommt bewusst KEINEN Zuschlag: die Quelle gibt urban cold 9
        # gegen urban hot 11 mg/km, eine Kaltkorrektur wuerde N2O also SENKEN.
        # Wir setzen ohnehin den hoeheren Hot-Wert an -- das bleibt konservativ.
        out["CO2E_TTW"] = out["CO2"] + sup["gwp_ch4"] * out["CH4"]
        out["CO2E_WTW"] = (out["CO2E_TTW"]
                           + ec * sup["wtt_co2e_g_per_mj_diesel"])
    return out
