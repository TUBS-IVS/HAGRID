# -*- coding: utf-8 -*-
"""Make the pax-only corrections win under the CANONICAL KPI names.

Why this module exists
----------------------
On a DRT_SHAREDUSE run the parcel segments ride as ordinary DVRP passengers, so
every MATSim DRT aggregate (drt_customer_stats, modestats, ...) mixes them into
what reads like a passenger KPI. `extract_shareduse` re-derives the pax-only
values, but before this module existed nothing consumed them: `render.py`
(HEADLINE_KPIS), `render_drt.py` (tiles) and `economics.py` all looked up the
plain `drt_rides` / `wait_median` / `modal_share_drt`, i.e. the contaminated
numbers -- the correction was emitted and then silently ignored.

Rather than teaching every consumer to prefer a `_pax_only` twin (a rule each
future consumer would have to remember), the swap happens ONCE here, right
after the extractors and BEFORE `economics.extract`:

    drt_rides            (stock, contaminated) -> drt_rides_incl_parcels
    drt_rides_pax_only   (corrected)           -> drt_rides

so the canonical name always carries the honest value and the contaminated one
stays in the CSV under a name that says what it is. A `_pax_only` row with no
stock twin (e.g. `fare_revenue_pax_only`) is left alone -- there is no ambiguity
to resolve, and renaming it would invent a canonical KPI that exists on
Shared-Use runs only.

On a run with no `_pax_only` rows at all (every non-Shared-Use scenario) this is
a no-op, so baseline CSVs keep byte-identical KPI names.
"""

SUFFIX = "_pax_only"
INCL_SUFFIX = "_incl_parcels"

#: Single source of truth for the classifier, mirroring the Java
#: hagrid.integrated.shareduse.SharedUse.PARCEL_PERSON_PREFIX. Every module that
#: separates parcel from passenger rows imports it from here -- four copies of the
#: same predicate is how they drift apart.
PARCEL_PREFIX = "parcel_"


def parcel_mask(ids, joined=False):
    """Boolean mask selecting the parcel rows of an id column.

    `joined=True` for columns that may hold SEVERAL ids in one cell (the rejections
    CSV's `personIds` for a grouped request); a passenger id can never carry the
    prefix, so substring matching is safe there."""
    s = ids.astype(str)
    return s.str.contains(PARCEL_PREFIX, regex=False) if joined \
        else s.str.startswith(PARCEL_PREFIX)


def split_parcels(df, id_column, joined=False):
    """(pax_rows, parcel_rows). On every non-Shared-Use run parcel_rows is empty,
    so callers can filter unconditionally without special-casing the scenario.

    When `id_column` is absent the frame carries no person identity at all (reduced
    test fixtures, older MATSim leg CSVs), so nothing CAN be classified: everything
    is returned as passengers, which is exactly the pre-filter behaviour. Note this
    is the one place the split degrades silently -- it cannot mis-classify a real
    Shared-Use run, because that run's legs CSV always carries `personId` (a parcel
    rides as a DVRP passenger and MATSim writes its id like any other)."""
    if id_column not in df.columns:
        return df, df.iloc[0:0]
    is_parcel = parcel_mask(df[id_column], joined=joined)
    return df[~is_parcel], df[is_parcel]

# Passenger/system KPIs that stay parcel-contaminated on a Shared-Use run
# because their source is VEHICLE-side (drt_vehicle_stats, drt_sharing_metrics,
# event reconstruction), not leg-side -- there is no per-request identity in
# them to filter on, so they cannot be re-derived from the legs CSV. They are
# reported here as provenance instead of being silently presented as passenger
# numbers. Correcting them needs per-link, per-identity occupancy
# reconstruction (see the extract_shareduse module docstring on D10(a)).
UNCORRECTABLE = (
    "pooling_rate",
    "sharing_factor",
    "drt_passenger_km",
    "drt_dp_over_dt",
    "mean_pax_aboard",
    "drt_empty_ratio",
    # Vehicle-side occupancy/utilisation KPIs from the event reconstruction:
    # every rider aboard counts toward the payload, parcel or passenger, and the
    # reconstruction only tracks an occupancy COUNT per segment, never rider
    # identity -- so on a Shared-Use run these mean "incl. parcel payload" and
    # are NOT correctable post-hoc (same D10(a) limitation as drt_passenger_km).
    "service_ratio_active",
    "service_ratio_shift",
    "fleet_utilisation_by_time",
    "fleet_utilisation_by_trips",
    # channel/drt_feeder_share: its DENOMINATOR is every trip with "drt" in
    # modes (extract_drt), so parcel trips deflate the share (~4x at high chi).
    # The NUMERATOR drt_feeder_trips (modes contain drt AND pt) is safe -- a
    # parcel trip is always drt-only, it can never carry pt -- and keeps its
    # canonical name. Correcting the share needs the per-run trips CSV, which
    # this rows-only module never sees (extract_shareduse owns that file).
    "drt_feeder_share",
    # passenger/drt_passengers (customer_stats rides_pax: every parcel counts
    # as a person): normally corrected by _derive_drt_passengers below; listed
    # here so it is reported as still-mixed whenever that derivation's
    # groupSize-1 precondition does not hold on a given run.
    "drt_passengers",
)

#: KNOWN GAP, tracked in docs/BACKLOG.md (Fallback-Audit 2026-07-27): the PER-ITERATION
#: series in kpi_iterations.csv (drt_rides, drt_rejection_rate, wait_mean, wait_p95,
#: modal_share_*) come from drt_customer_stats rows for EVERY iteration, and MATSim writes
#: the per-leg CSV only for the last one -- so there is no per-iteration parcel identity to
#: filter on. They stay mixed on a Shared-Use run (render_drt labels the Konvergenz
#: section accordingly). kpi_timeseries.csv and
#: kpi_distributions.csv ARE corrected (both read the legs/rejections CSVs, see
#: timeseries.extract / distributions.extract).


def _derive_drt_passengers(rows, first_by_name):
    """Synthesize `drt_passengers_pax_only` when it is provably safe.

    `drt_passengers` comes from customer_stats' rides_pax (persons served), so
    on a Shared-Use run every parcel counts as a person, and there is no
    leg-side re-derivation for it in extract_shareduse. But when the STOCK run
    satisfies rides == rides_pax -- every served request carried exactly ONE
    rider (groupSize 1, which holds for this study: no group requests are
    generated) -- then persons == rides for ANY subset of the requests too, so
    the pax-only passenger count simply equals drt_rides_pax_only. The equality
    is checked against the actual stock values rather than assumed, so a future
    scenario with group requests degrades to the UNCORRECTABLE provenance path
    instead of publishing a wrong derivation."""
    if "drt_passengers" + SUFFIX in first_by_name:
        return  # a real extractor correction exists; never shadow it
    pax_rides = first_by_name.get("drt_rides" + SUFFIX)
    stock_rides = first_by_name.get("drt_rides")
    stock_pax = first_by_name.get("drt_passengers")
    if pax_rides is None or stock_rides is None or stock_pax is None:
        return
    if stock_pax["value"] != stock_rides["value"]:
        return  # groupSize > 1 somewhere -> not derivable, stays UNCORRECTABLE
    derived = {
        "kpi_group": stock_pax["kpi_group"],
        "kpi_name": "drt_passengers" + SUFFIX,
        "value": pax_rides["value"],
        "unit": stock_pax["unit"],
        "source": "derived: = drt_rides_pax_only (rides == rides_pax, groupSize 1)",
    }
    rows.append(derived)
    first_by_name[derived["kpi_name"]] = derived


def apply_overrides(rows):
    """Promote every `<name>_pax_only` to `<name>`, demoting the stock row to
    `<name>_incl_parcels`. Mutates and returns `rows` (order preserved, so the
    wide-CSV column order is unchanged apart from the renames).

    Returns the same list for call-site convenience; appends one
    `meta/parcel_contaminated_kpis` provenance row when any override fired."""
    first_by_name = {}
    for r in rows:
        first_by_name.setdefault(r["kpi_name"], r)

    _derive_drt_passengers(rows, first_by_name)

    overridden = []
    for r in list(rows):
        name = r["kpi_name"]
        if not name.endswith(SUFFIX):
            continue
        base = name[:-len(SUFFIX)]
        stock = first_by_name.get(base)
        if stock is None:
            continue  # Shared-Use-only KPI, nothing to disambiguate
        stock["kpi_name"] = base + INCL_SUFFIX
        stock["source"] = stock["source"] + " (incl. parcel requests)"
        r["kpi_name"] = base
        overridden.append(base)

    if overridden:
        # An UNCORRECTABLE name that DID get overridden (drt_passengers via the
        # groupSize-1 derivation above) is corrected, not still-mixed.
        still_mixed = [n for n in UNCORRECTABLE
                       if n in first_by_name and n not in overridden]
        rows.append({
            "kpi_group": "meta",
            "kpi_name": "parcel_contaminated_kpis",
            "value": len(still_mixed),
            "unit": "kpis",
            "source": ",".join(still_mixed) if still_mixed else "none",
        })
    return rows
