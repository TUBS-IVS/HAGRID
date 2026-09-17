# -*- coding: utf-8 -*-
"""1c: distribution of the smallest ACHIEVABLE detour per parcel segment.

Reads `<runId>.shareduse_detour_min.csv` (written by SharedUseKpiHandler#writeDetourCsv,
final iteration only) and turns it into the instrument the saturating chi counters could
not be (METHODS-LOG 2.31).

Why it exists
-------------
`chi_blocked_segments` equals `segments_submitted` in every measured run (chid600i
2953/2953, chid600w21 3104/3104): "was ever chi-blocked" is practically guaranteed over a
simulation day and therefore holds for the ~93 % of segments that were DELIVERED too. So
`segments_window_expired_chi_blocked == segments_window_expired` is an identity, not a
measurement, and the inference "the gate is the binding mechanism" was retracted.

The value the gate computes per candidate and used to throw away is what discriminates: the
MINIMUM detour-only value a segment was ever offered is a lower bound on the chi that segment
would have needed. Compare the distribution for `window_expired` against `delivered`:

* expired minima sitting just above chi  -> the threshold binds, and the sweep grid belongs
  around them (read the cumulative counts: "how many segments have a minimum <= x" is a
  first approximation of delta(chi) from a SINGLE run);
* expired minima at several thousand seconds -> chi is not the bottleneck; the lever is
  fleet size, delivery window or segment size, and raising chi buys little.

What it is not
--------------
Not a counterfactual. A higher chi accepts more parcels, which changes vehicle states and
shifts every later minimum -- per dispatch round the value is a bound, over the run the
derived curve is an approximation. Only candidates that survived the insertion search's
earlier feasibility filters (capacity, time windows) and reached the cost calculator are
seen at all. Segments the router downgraded to a walk fallback never emitted a request and
are absent from the file entirely (C2/F5), so this file's denominators are SUBMITTED
segments, not injected ones.

Emission policy: absent file -> ([], []) silently (every non-1c run, and every 1c run from
before 2026-08-10). Present but empty -> the same, since a header-only file legitimately
means "no parcel was ever evaluated" (noParcels arm). Quantile rows are OMITTED rather than
zero-filled when a bucket has no members, following the M4 undefined-is-not-0.0 convention.

File vintage (METHODS-LOG 2.35)
-------------------------------
`min_detour_s` is a DRIVE-only detour only for runs from 2026-08-13 on. Until then the gate
subtracted the segment's own dwell from the SUM of both insertion legs, so an over-subtraction
on a piggybacked leg paid for real driving on the other one and the value is understated by up
to the segment's own dwell (up to 1290 s at n=13). In `chid600det` that put 1096 of 3104
segments (35 %) at exactly 0, which makes `p25 = 0` the clamp boundary rather than a quantile
and `expired_cheapest_s` uninterpretable. Nothing here can detect the vintage -- the file has
no version stamp -- so it is on the reader: pre-fix files support "the minimum was BELOW chi"
(the bias only goes one way) but none of the level numbers.
"""
from __future__ import annotations  # sim-PC runs Python 3.8 (see commit 174d042)

from pathlib import Path

import pandas as pd

from common import row
from distributions import bin_fixed, dist_row

FILE_SUFFIX = ".shareduse_detour_min.csv"

#: Bin width for the exported histograms. 100 s is fine enough to place a sweep point and
#: coarse enough that ~3000 segments do not explode kpi_distributions.csv.
BIN_WIDTH_S = 100.0

#: Both count as "the parcel arrived" for this comparison -- a late delivery still got in
#: under some chi, which is the property the detour distribution is about (delta itself
#: stays in-window only, see extract_shareduse).
DELIVERED_OUTCOMES = ("delivered", "delivered_late")

#: The chi-cost bucket: deadline passed inside the simulation. `pending_open` is excluded on
#: purpose -- its window never closed, so it is not evidence about the threshold.
FAILED_OUTCOMES = ("window_expired",)

_QUANTILES = (("p25", 0.25), ("median", 0.5), ("p75", 0.75), ("p90", 0.90))


def has_detour_csv(run_dir, meta):
    return (Path(run_dir) / (meta.prefix + FILE_SUFFIX)).exists()


def extract(run_dir, prefix):
    """Returns (long_rows, distribution_rows)."""
    path = Path(run_dir) / (prefix + FILE_SUFFIX)
    if not path.exists():
        return [], []
    df = pd.read_csv(path, sep=";")
    if df.empty:
        return [], []

    # min_detour_s is written empty only in a case the Java side documents as unreachable;
    # drop such rows rather than let NaN propagate into a quantile.
    df = df[pd.notna(df["min_detour_s"])]
    if df.empty:
        return [], []

    rows = [
        row("channel", "chi_detour_segments_evaluated", int(len(df)), "segments",
            "shareduse_detour_min"),
        row("channel", "chi_detour_evaluations_total", int(df["evaluations"].sum()),
            "attempts", "shareduse_detour_min"),
    ]
    dist_rows = []

    delivered = df[df["outcome"].isin(DELIVERED_OUTCOMES)]["min_detour_s"]
    failed = df[df["outcome"].isin(FAILED_OUTCOMES)]["min_detour_s"]

    rows += _bucket_rows("delivered", delivered)
    rows += _bucket_rows("expired", failed)
    dist_rows += _histogram("chi_detour_min_delivered", delivered)
    dist_rows += _histogram("chi_detour_min_expired", failed)

    # The headline read: the cheapest expired segment. If even that one sits far above the
    # chi the run used, no plausible threshold would have delivered ANY of them -- the gate
    # is exonerated without needing the full distribution.
    if len(failed):
        rows.append(row("channel", "chi_detour_expired_cheapest_s",
                        float(failed.min()), "s", "shareduse_detour_min"))

    # Parcels per segment against its achievable detour: re-tests the F1 concern (do large
    # segments still fail structurally now that their own dwell is subtracted?) without a
    # second file. Reported as the mean segment size per bucket -- a full 2D histogram would
    # be the next step if this shows a split.
    for label, subset in (("delivered", DELIVERED_OUTCOMES), ("expired", FAILED_OUTCOMES)):
        sel = df[df["outcome"].isin(subset)]["parcels"]
        if len(sel):
            rows.append(row("channel", "chi_detour_" + label + "_mean_parcels",
                            float(sel.mean()), "parcels", "shareduse_detour_min"))

    # Outcome census, so a reader can see what the two buckets above leave out
    # (pending_open, rejected_final, and the should-never-happen "unmatched").
    for outcome, n in sorted(df["outcome"].value_counts().items()):
        rows.append(row("channel", "chi_detour_outcome_" + str(outcome), int(n),
                        "segments", "shareduse_detour_min"))
    return rows, dist_rows


def _bucket_rows(label, values):
    """Quantiles of one bucket. OMITTED entirely when the bucket is empty: a 0.0 median
    would plot as 'these segments could have ridden for free' instead of 'there are none'."""
    if not len(values):
        return []
    out = [row("channel", "chi_detour_" + label + "_segments", int(len(values)), "segments",
               "shareduse_detour_min")]
    for name, q in _QUANTILES:
        out.append(row("channel", "chi_detour_" + label + "_" + name + "_s",
                       float(values.quantile(q)), "s", "shareduse_detour_min"))
    return out


def _histogram(series, values):
    if not len(values):
        return []
    counts = bin_fixed(values, BIN_WIDTH_S)
    return [dist_row(series, lo, hi, n, "segments")
            for (lo, hi), n in sorted(counts.items())]
