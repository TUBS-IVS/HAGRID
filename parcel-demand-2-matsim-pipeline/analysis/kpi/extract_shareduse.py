# -*- coding: utf-8 -*-
"""Shared-Use KPI rows: channel/delta stats from shareduse_channel_stats.csv
(1c Task 7) plus pax-only corrected passenger KPIs. The stock DRT aggregates
(drt_customer_stats, extract_drt.py) mix parcel_ persons into their
rides/wait numbers on a DRT_SHAREDUSE run, since parcels ride as DVRP
passengers too -- this module re-derives the pax-only numbers straight from
the per-leg CSV instead (D10 of the 1c plan).

Classifier (D10(b)): PARCEL_PREFIX mirrors the Java single source of truth
hagrid.integrated.shareduse.SharedUse.PARCEL_PERSON_PREFIX exactly. MATSim's
own DRT-legs analysis writer does not serialize a per-leg Goods-load column,
so the personId-prefix predicate is the only classifier signal available
here (the Java side additionally carries the dvrp:load attribute on the
Population -- that is where SharedUseKpiHandler sources its LOAD-weighted
parcel counts, already pre-aggregated for us in shareduse_channel_stats.csv).

Best-effort D10(c) fare split: output_drt_legs_drt.csv already carries a
native `fareForLeg` column (MATSim's own per-leg DRT-fare output, populated
by PtAndDrtFareModule at fare-computation time when fares are configured for
the run; 0.0 per leg otherwise). Summing it split by the same parcel/pax
predicate is equivalent to summing the underlying PersonMoney(DRT-fare)
events without opening the (large, gzipped) events file -- 1e has no
existing personMoney reader (extract_drt.py/economics.py do not touch fares
at all), so this leg-column sum is the only readily-available source and is
emitted here, guarded by the column's presence. economic/parcel_fare_revenue
is the PPC-enabling field (1c Task 8 brief); PPC itself is a deferred
post-hoc computation on kpis_long.csv, not built here.

D10(a) system/freight_veh_km is deliberately NOT emitted (deferred, see the
Task 8 report): the legs CSV's travelDistance_m is a per-PERSON trip
distance (pickup to dropoff), not a vehicle-km. When a pax and a parcel are
pooled aboard the same vehicle for an overlapping segment -- the entire
point of shared-use -- each gets its own leg row with its own
travelDistance_m, so naively summing parcel-leg distances would double-count
vehicle-km already attributed to the pax side whenever they overlap. A
correct decomposition needs per-link, per-identity occupancy reconstruction
(which link segments had a parcel aboard, not just how many riders);
geometry.reconstruct_drt_paths only tracks an occupancy COUNT per link, not
rider identity, so extending it is exactly the kind of new events-parsing
project the task brief says to avoid here.
"""
import gzip
import xml.etree.ElementTree as ET
from pathlib import Path

import pandas as pd

import pax_only
from common import row
from pax_only import PARCEL_PREFIX  # noqa: F401  (re-exported; canonical def lives there)

# Task 10: mirrors the Java single source of truth hagrid.integrated.shareduse.SharedUse
# .LOAD_ATTRIBUTE exactly (same precedent as PARCEL_PREFIX above mirroring PARCEL_PERSON_PREFIX).
_LOAD_ATTRIBUTE = "dvrp:load:parcels"


def has_shareduse_stats(run_dir, meta):
    # MATSim writes run outputs run-ID-prefixed, so the handler's CSV lands as
    # "{prefix}.shareduse_channel_stats.csv" (e.g. DRT_SHAREDUSE_..._suchi0.shareduse_channel_stats.csv),
    # NOT a bare "shareduse_channel_stats.csv". Match the real filename or the extractor
    # is silently skipped on every real run.
    return (Path(run_dir) / (meta.prefix + ".shareduse_channel_stats.csv")).exists()


def extract(run_dir, prefix):
    run_dir = Path(run_dir)
    rows = []

    stats = dict(pd.read_csv(run_dir / (prefix + ".shareduse_channel_stats.csv"), sep=";").values)

    # I1: export ALL of the handler's segment counters, not just a 7-metric
    # subset -- the delivered/delivered_late/window_expired/pending decomposition
    # is the honest delta accounting and must reach kpis_long. Every read is
    # tolerant (stats.get): old CSVs from finished runs lack the newer keys
    # (injected/never_submitted/delivered_late/delivery_rate_total), and a
    # missing key must drop ONE row, never KeyError the extractor.
    def stat(kpi_group, name, cast, unit):
        if name in stats:
            rows.append(row(kpi_group, name, cast(stats[name]), unit,
                            "shareduse_channel_stats"))

    # segment counters + rates (kpi_group "channel")
    stat("channel", "segments_injected", int, "segments")
    stat("channel", "segments_submitted", int, "segments")
    stat("channel", "segments_never_submitted", int, "segments")
    stat("channel", "segments_delivered", int, "segments")
    stat("channel", "segments_delivered_late", int, "segments")
    stat("channel", "segments_window_expired", int, "segments")
    stat("channel", "segments_pending_open", int, "segments")
    stat("channel", "segments_rejected_final", int, "segments")
    stat("channel", "segments_pending_eod", int, "segments")
    # M6 χ-gate instrumentation. These answer "does the χ threshold bind at all", which
    # segments_rejected_final CANNOT: a χ-blocked parcel is never terminally rejected, it
    # retries until its window closes and then drops with no event, landing in
    # segments_window_expired next to segments that failed for unrelated reasons. Absent from
    # every run before 2026-07-29, hence tolerant (stat()) like the rest.
    # chi_blocked_insertion_attempts counts EVALUATIONS (many per request per dispatch round),
    # so it is an order-of-magnitude signal only -- never use it as a rate denominator.
    stat("channel", "chi_blocked_insertion_attempts", int, "attempts")
    stat("channel", "chi_blocked_segments", int, "segments")
    stat("channel", "segments_window_expired_chi_blocked", int, "segments")
    stat("channel", "undelivered_rate", float, "share")
    stat("channel", "delivery_rate_total", float, "share")
    stat("channel", "share_channel_door", float, "share")
    stat("channel", "share_channel_locker", float, "share")
    # parcel counts (kpi_group "freight")
    stat("freight", "parcels_injected", int, "parcels")
    stat("freight", "parcels_submitted", int, "parcels")
    stat("freight", "parcels_never_submitted", int, "parcels")
    stat("freight", "parcels_delivered", int, "parcels")
    stat("freight", "parcels_delivered_late", int, "parcels")
    stat("freight", "parcels_undelivered", int, "parcels")

    # Zustellquoten-Konvention (2026-08-10, METHODS-LOG 2.21): EIN Name, EINE Basis über alle
    # drei Arme -- zugestellt / injizierte Nachfrage, Not-at-home-Overlay NICHT abgezogen
    # (dieser Arm hat ohnehin keines, M10). Wertgleich mit delivery_rate_total; der Alias
    # existiert, weil Baseline und 1d unter `delivery_rate` melden und eine Vergleichstabelle
    # sonst drei verschiedene Namen auflösen müsste. delivery_rate_total bleibt stehen, damit
    # bestehende 1c-Dashboards nicht brechen -- die zwei Zeilen sind KEIN versehentliches
    # Duplikat, wer eine davon aufräumt, muss beide Konsumenten prüfen.
    rows.extend(_delivery_rate_rows(run_dir, prefix, stats))

    # C1 delay rename: the handler now writes mean_time_to_delivery_s (in-window
    # deliveries only) and OMITS the line when nothing was delivered -- never
    # re-materialize a 0.0 pseudo-result here. Legacy CSVs carry the old
    # mean_delivery_delay_s key instead; emit it under the NEW name so the
    # kpis_long schema is uniform across old and new runs -- BUT (review pass):
    # the OLD handler wrote `mean_delivery_delay_s;0.0` even with ZERO deliveries
    # (the chi=0 probe case the C1 fix kills), and its value included late
    # deliveries. So the legacy fallback (a) skips the row when the same CSV
    # says nothing was delivered, and (b) carries an honest pre-I1 source label
    # instead of claiming in-window-only semantics it never had.
    if "mean_time_to_delivery_s" in stats:
        rows.append(row("freight", "mean_time_to_delivery_s",
                        float(stats["mean_time_to_delivery_s"]), "s",
                        "shareduse_channel_stats (delivered in-window only, right-censored)"))
    elif "mean_delivery_delay_s" in stats and int(stats.get("parcels_delivered", 0)) > 0:
        rows.append(row("freight", "mean_time_to_delivery_s",
                        float(stats["mean_delivery_delay_s"]), "s",
                        "shareduse_channel_stats (legacy mean_delivery_delay_s, "
                        "pre-I1 semantics: incl. late deliveries, right-censored)"))

    pax_rides = None

    legs_f = run_dir / (prefix + ".output_drt_legs_drt.csv")
    if legs_f.exists():
        legs = pd.read_csv(legs_f, sep=";")
        pax, parcel = pax_only.split_parcels(legs, "personId")
        pax_rides = int(len(pax))

        rows.append(row("passenger", "drt_rides_pax_only", pax_rides,
                         "trips", "output_drt_legs pax-filter"))
        if len(pax):
            # Guard against an empty pax slice: pandas .mean()/.median() on
            # zero rows return NaN, which must never be emitted as a KPI
            # (mirrors extract_freight's avg_max_load NaN guard). Every column
            # below is also presence-guarded: the legs CSV schema varies across
            # MATSim versions, and a missing column must drop ONE KPI, never
            # abort the extractor.
            rows += [
                row("passenger", "wait_mean_pax_only",
                    float(pax["waitTime"].mean()), "s", "output_drt_legs pax-filter"),
                row("passenger", "wait_median_pax_only",
                    float(pax["waitTime"].median()), "s", "output_drt_legs pax-filter"),
                # NB estimator caveat: MATSim's own wait_p95 / percentage_WT_below_*
                # come from its internal accumulators; these are pandas
                # re-derivations over the same served-leg population. On a
                # pure-pax run they agree to ~1e-4 -- far below the parcel
                # contamination they replace -- and the `source` column keeps
                # the basis switch visible in kpis_long.csv.
                row("passenger", "wait_p95_pax_only",
                    float(pax["waitTime"].quantile(0.95)), "s", "output_drt_legs pax-filter"),
                row("passenger", "wait_below_10min_pax_only",
                    float((pax["waitTime"] <= 600).mean()), "share", "output_drt_legs pax-filter"),
                row("passenger", "wait_below_15min_pax_only",
                    float((pax["waitTime"] <= 900).mean()), "share", "output_drt_legs pax-filter"),
            ]
            if "inVehicleTravelTime" in legs.columns:
                rows.append(row("passenger", "in_vehicle_time_mean_pax_only",
                                 float(pax["inVehicleTravelTime"].mean()), "s",
                                 "output_drt_legs pax-filter"))
            if "travelDistance_m" in legs.columns:
                rows.append(row("passenger", "drt_trip_distance_mean_pax_only",
                                 float(pax["travelDistance_m"].mean()) / 1000.0, "km",
                                 "output_drt_legs pax-filter"))
                if "directTravelDistance_m" in legs.columns:
                    direct_mean = float(pax["directTravelDistance_m"].mean())
                    if direct_mean > 0:
                        # Same ratio-of-means form as extract_drt's detour_factor
                        # (distance_m_mean / directDistance_m_mean), not a mean of ratios.
                        rows.append(row("passenger", "detour_factor_pax_only",
                                         float(pax["travelDistance_m"].mean()) / direct_mean,
                                         "ratio", "output_drt_legs pax-filter"))

        if "fareForLeg" in legs.columns:
            pax_fare = float(pax["fareForLeg"].sum())
            parcel_fare = float(parcel["fareForLeg"].sum())
            if pax_fare == 0.0 and parcel_fare == 0.0:
                # I3: fareForLeg is 0.0 per leg whenever no DRT fare is configured
                # for the run, so an all-zero sum is a NOT-CONFIGURED signal, not a
                # measured "0 EUR revenue" -- emitting the fare rows would render as
                # a spurious zero-revenue finding downstream. Flag it instead.
                rows.append(row("meta", "fare_not_configured", 1, "flag",
                                "fareForLeg sums to 0 for pax AND parcels -- no DRT"
                                " fare configured; fare_revenue_pax_only/"
                                "parcel_fare_revenue suppressed"))
            else:
                rows += [
                    row("economic", "fare_revenue_pax_only",
                        pax_fare, "EUR", "output_drt_legs pax-filter"),
                    row("economic", "parcel_fare_revenue",
                        parcel_fare, "EUR", "output_drt_legs pax-filter"),
                ]

    rows += _rejection_rows(run_dir, prefix, pax_rides)
    rows += _modal_share_rows(run_dir, prefix)
    rows += _district_rows(run_dir, prefix)
    return rows


def _district_rows(run_dir, prefix):
    """Task 10 (spec 2026-08-17, "make the idealisations measurable"): per-district catchment
    size for 1c. Unlike 1d there are no MATSim freight Carriers here at all -- ParcelAgentGenerator
    turns each pooled stop's sub-load into a dummy PARCEL-PERSON instead, carrying a "district"
    person attribute and a dvrp:load:parcels load (one person = one pooled-stop sub-load = one
    segment, the M2 segment-split convention). This is the "parcel persons" half of the brief's
    "routed carriers / parcel persons" instruction -- 1d reads carriers (extract_modular), 1c
    reads the final population instead, because that is where 1c's per-district identity lives.

    district_segments_<id> is a PERSON count (one row per segment); district_parcels_<id> is the
    summed dvrp:load:parcels load. Degrades to NO rows (not a meta flag) when
    output_plans.xml.gz is missing/unreadable -- same additive-metric tolerance as
    extract_modular's carriers-XML counterpart; a run with zero parcel-persons (no Shared-Use
    demand at all) also yields no rows, which is the correct "nothing to report" answer, not a
    degraded one.
    """
    plans_path = Path(run_dir) / (prefix + ".output_plans.xml.gz")
    parcels_by_district = {}
    segments_by_district = {}
    try:
        with gzip.open(plans_path, "rb") as f:
            for _, el in ET.iterparse(f):
                if not el.tag.endswith("person"):
                    continue
                person_id = el.get("id") or ""
                if not person_id.startswith(pax_only.PARCEL_PREFIX):
                    el.clear()
                    continue
                district = None
                load = 0
                for child in el:
                    if child.tag.endswith("attributes"):
                        for a in child:
                            if not a.tag.endswith("attribute"):
                                continue
                            name = a.get("name")
                            if name == "district":
                                district = (a.text or "").strip()
                            elif name == _LOAD_ATTRIBUTE:
                                try:
                                    load = int(float((a.text or "0").strip()))
                                except ValueError:
                                    load = 0
                el.clear()
                if district is None:
                    continue
                parcels_by_district[district] = parcels_by_district.get(district, 0) + load
                segments_by_district[district] = segments_by_district.get(district, 0) + 1
    except (FileNotFoundError, OSError, ET.ParseError) as e:
        print("[shareduse] district load rows unavailable (output_plans.xml.gz missing or "
              "unreadable): " + str(e).encode("ascii", "replace").decode("ascii"))  # ASCII only
        return []
    rows = []
    # Sorted by district id -- determinism, same convention extract_modular's _district_rows uses.
    for district in sorted(parcels_by_district):
        rows.append(row("freight", "district_parcels_" + district, parcels_by_district[district],
                        "parcels", "output_plans (ParcelAgentGenerator parcel-persons)"))
        rows.append(row("freight", "district_segments_" + district, segments_by_district[district],
                        "segments", "output_plans (ParcelAgentGenerator parcel-persons)"))
    return rows


def _rejection_rows(run_dir, prefix, pax_rides):
    """Pax-only rejection count/rate from the per-request rejections CSV.

    The stock `drt_rejections` (drt_customer_stats) counts every rejected DVRP
    request, and on a Shared-Use run a chi-starved parcel can be rejected many
    times over -- so the stock rejection RATE is not a passenger-service signal
    at all. Filtered by the same parcel predicate, it is again."""
    f = run_dir / (prefix + ".output_drt_rejections_drt.csv")
    if not f.exists():
        return []
    rej = pd.read_csv(f, sep=";")
    if "personIds" not in rej.columns:
        return []
    # joined=True: `personIds` holds one id for a single-person request and a joined
    # list for a grouped one (a pax id can never carry the prefix, so substring is safe).
    pax_rej, _ = pax_only.split_parcels(rej, "personIds", joined=True)
    pax_rejections = int(len(pax_rej))
    out = [row("passenger", "drt_rejections_pax_only", pax_rejections,
               "requests", "output_drt_rejections pax-filter")]
    if pax_rides is not None:
        out.append(row("passenger", "drt_rejection_rate_pax_only",
                        pax_rejections / max(1, pax_rides + pax_rejections),
                        "share", "computed(output_drt_rejections pax-filter)"))
    return out


def _modal_share_rows(run_dir, prefix):
    """Pax-only modal shares from output_trips.

    Parcel-persons are a full subpopulation whose single trip is always `drt`,
    so they inflate `modal_share_drt` (modestats) and deflate every other mode.
    The shares therefore have to be recomputed TOGETHER -- correcting drt alone
    would leave the set not summing to 1. Verified against modestats on a
    parcel-free run: agreement to ~1e-4 (main-mode share over trips is the same
    basis), which is negligible next to the contamination removed."""
    f = run_dir / (prefix + ".output_trips.csv.gz")
    if not f.exists():
        return []
    trips = pd.read_csv(f, sep=";")
    if "person" not in trips.columns or "main_mode" not in trips.columns:
        return []
    pax, _ = pax_only.split_parcels(trips, "person")
    if not len(pax):
        return []
    shares = pax["main_mode"].value_counts(normalize=True)
    return [row("system", "modal_share_" + str(mode) + "_pax_only", float(share),
                "share", "output_trips pax-filter")
            for mode, share in shares.items()]


# --------------------------------------------------------------------------- Zustellquote (2026-08-26)
#
# Was hier repariert wird: der Handler meldete gleichzeitig `parcels_undelivered;0` und
# `delivery_rate_total;0.9849`. Beides kann nicht stimmen. Die fehlenden 1,5 % sind die Pakete,
# deren DRT-Anfrage nie gestellt wurde -- die aber trotzdem ankommen, weil MATSim auf ein
# Walk-Leg zurueckfaellt (am f140-Lauf gemessen: 945 Paket-Personen per drt, 10 per walk, alle
# 955 enden auf parcelDelivery, und in output_drt_rejections steht keine einzige Paketzeile).
# `delivery_rate_total` misst also den DRT-getragenen ANTEIL, nicht die Zustellquote.
#
# Die zweite Haelfte liegt ausserhalb des Run-Verzeichnisses: Stopps, die am eigenen Depot-Link
# haengen, werden im Preprocessing verworfen (from == to ist keine gueltige DVRP-Anfrage) und
# existieren in KEINER Ausgabedatei. Ohne die Provenance-Datei laesst sich die Quote nur auf der
# injizierten Basis bilden -- dort steht dann 100 %, ununterscheidbar von der Baseline, obwohl
# 1c fuenfzehn Pakete weniger zustellt.

PROVENANCE_SUFFIX = "_parcel_demand_provenance.csv"


def _read_provenance(run_dir, prefix):
    """Preprocessing-Verluste aus hagrid-output/<prefix>/ -- dieselbe Schwesterverzeichnis-
    Konvention wie build_kpis.py (drt_fleet) und extract_modular.py (carriers). Fehlt die Datei
    (jeder Lauf vor 2026-08-26), wird None zurueckgegeben und alles bleibt beim Alten."""
    f = Path(run_dir).parent.parent / "hagrid-output" / prefix / (prefix + PROVENANCE_SUFFIX)
    try:
        d = {}
        for line in f.read_text(encoding="utf-8").splitlines():
            parts = line.split(";", 1)
            if len(parts) == 2 and parts[0] != "metric":
                d[parts[0]] = int(parts[1].strip())
        return d or None
    except Exception:
        return None


def _count_walked_parcel_persons(run_dir, prefix):
    """Paket-Personen, die ihr Ziel zu Fuss erreicht haben, aus output_trips. Gemessen, nicht aus
    `never_submitted` umbenannt: nur so laesst sich pruefen, ob die beiden Quellen dasselbe
    meinen. None, wenn die Datei fehlt oder die Spalten nicht da sind -- dann wird nichts
    behauptet."""
    f = Path(run_dir) / (prefix + ".output_trips.csv.gz")
    try:
        df = pd.read_csv(f, sep=";", usecols=["person", "main_mode"])
    except Exception:
        return None
    parcels = df[pax_only.parcel_mask(df["person"])]
    return int(parcels[parcels["main_mode"] == "walk"]["person"].nunique())


def _delivery_rate_rows(run_dir, prefix, stats):
    """Die Zerlegung plus EINE Quote. Die Quote wird nur dann auf die volle Nachfragebasis
    gehoben, wenn die Trips den Fussgaenger-Kanal bestaetigen."""
    out = []
    if "parcels_delivered" not in stats or "parcels_injected" not in stats:
        # Vor-I1-CSV ohne Paketzaehler: nur die Alt-Quote, falls vorhanden.
        if "delivery_rate_total" in stats:
            out.append(row("freight", "delivery_rate", float(stats["delivery_rate_total"]),
                           "share", "shareduse_channel_stats (= delivery_rate_total)"))
        return out

    drt_borne = int(stats["parcels_delivered"])
    injected = int(stats["parcels_injected"])
    walked_parcels = int(stats.get("parcels_never_submitted", 0))
    walk_segments = int(stats.get("segments_never_submitted", 0))

    def legacy_rate():
        if "delivery_rate_total" in stats:
            return row("freight", "delivery_rate", float(stats["delivery_rate_total"]),
                       "share", "shareduse_channel_stats (= delivery_rate_total)")
        if injected:
            return row("freight", "delivery_rate", drt_borne / injected, "share",
                       "computed parcels_delivered/parcels_injected (legacy CSV)")
        return None

    out.append(row("freight", "parcels_drt_borne", drt_borne, "parcels",
                   "shareduse_channel_stats parcels_delivered -- parcels a DRT vehicle carried"))
    if injected:
        out.append(row("freight", "drt_borne_share", drt_borne / injected, "share",
                       "parcels_delivered/parcels_injected -- the DRT-carried share. This is the "
                       "number that used to be published as delivery_rate; it is NOT a delivery "
                       "rate, because the parcels outside it arrive on foot"))

    prov = _read_provenance(run_dir, prefix)
    if prov is not None:
        for key in ("parcels_offered", "parcels_dropped_at_depot_link",
                    "parcels_clipped_outside_area"):
            if key in prov:
                name = "parcels_in_demand" if key == "parcels_offered" else key
                out.append(row("freight", name, prov[key], "parcels",
                               "parcel_demand_provenance (preprocessing)"))

    walk_persons = _count_walked_parcel_persons(run_dir, prefix)
    confirmed = walk_persons is not None and walk_persons == walk_segments

    if not confirmed:
        # Die beiden Quellen sind sich uneinig (oder die Trips fehlen). Dann wird der
        # Fussgaenger-Kanal NICHT behauptet und die Quote bleibt auf der alten Basis -- der
        # Widerspruch wird ausgewiesen, statt still eine Zahl zu heben.
        seen = "no" if walk_persons is None else str(walk_persons)
        out.append(row("meta", "walk_channel_unconfirmed", 1, "flag",
                       "output_trips reports " + seen + " walking parcel persons, the handler "
                       "reports " + str(walk_segments) + " never-submitted segments -- "
                       "parcels_walked withheld and delivery_rate left on the DRT-borne base"))
        r = legacy_rate()
        if r is not None:
            out.append(r)
        return out

    out.append(row("freight", "parcels_walked", walked_parcels, "parcels",
                   "shareduse_channel_stats parcels_never_submitted, confirmed against "
                   "output_trips (" + str(walk_persons) + " parcel persons with main_mode=walk, "
                   "all ending at parcelDelivery) -- delivered, but not by the DRT fleet"))

    if prov is None or "parcels_offered" not in prov:
        # Ohne Provenance ist die Nachfragebasis unbekannt; alte Laeufe behalten ihre Zahl.
        r = legacy_rate()
        if r is not None:
            out.append(r)
        return out

    demand = prov["parcels_offered"]
    if demand:
        out.append(row("freight", "delivery_rate", (drt_borne + walked_parcels) / demand, "share",
                       "(parcels_drt_borne + parcels_walked) / parcels_in_demand -- same "
                       "convention as the Baseline (delivered/demand, no overlay subtracted, "
                       "METHODS-LOG 2.21). The shortfall against 1.0 is the preprocessing "
                       "yard-gate drop, a model artefact, not a delivery failure"))
    return out
