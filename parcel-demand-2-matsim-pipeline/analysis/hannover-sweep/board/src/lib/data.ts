// Data layer: typed access to sweep_data.json + chart-row builders.
// Normalisation happens here at render time (each series to its own c=30 mean).
import raw from "@/data/sweep_data.json";

export type Series = "v1" | "v2" | "v3";

/** Every series in fixed display order. v3 is the 2026-08 sim-PC arm; it runs the
    SAME Hannover code as v2 (verified identical for CarrierServiceMerger, Router,
    CarrierGenerator, DemandProcessor, DashboardGenerator, HAGRIDSimulationRunner),
    so v2 and v3 at the same capacity are REPLICATES, not a version comparison:
    the tag is part of the runId and runId.hashCode() reseeds the demand layer. */
export const SERIES: Series[] = ["v1", "v2", "v3"];

export interface Run {
  series: Series;
  cap: number;
  replicate: string | null;
  file: string;
  kpis: {
    tour_km: number;
    tour_h: number;
    cost_eur: number;
    vehicles: number;
    parcels: number;
    parcels_per_vehicle: number;
    utilization: number;
  };
  limits: {
    worktime_only: number;
    capa_only: number;
    both: number;
    neither: number;
    total_tours: number;
  };
}

export const RUNS: Run[] = raw.runs as Run[];

export interface KpiDef {
  key: keyof Run["kpis"];
  label: string;
  unit: string;
  digits: number;
  scale: number;
}

export const KPIS: KpiDef[] = [
  { key: "tour_km", label: "Tour-km", unit: "km", digits: 0, scale: 1 },
  { key: "tour_h", label: "Tour-h", unit: "h", digits: 0, scale: 1 },
  { key: "cost_eur", label: "Kosten", unit: "€", digits: 0, scale: 1 },
  { key: "vehicles", label: "Fahrzeuge", unit: "", digits: 0, scale: 1 },
  // one decimal: the extractor already rounds parcels/vehicles to 0.1, and at
  // ~30-160 parcels a whole number hides differences between the arms
  { key: "parcels_per_vehicle", label: "Pakete je Fahrzeug", unit: "", digits: 1, scale: 1 },
  { key: "utilization", label: "Auslastung", unit: "%", digits: 1, scale: 100 },
];

export type KpiKey = KpiDef["key"];

export const LIMIT_CLASSES = [
  { key: "capa_only", label: "Kapazität limitiert (>90 % Kapa)", short: "Kapazität", cssVar: "--lim-capa" },
  { key: "worktime_only", label: "Arbeitszeit limitiert (>7 h)", short: "Arbeitszeit", cssVar: "--lim-wt" },
  { key: "both", label: "beides", short: "beides", cssVar: "--lim-both" },
  { key: "neither", label: "ohne Bindung", short: "ohne Bindung", cssVar: "--lim-none" },
] as const;

export type LimitKey = (typeof LIMIT_CLASSES)[number]["key"];

// min == max digits on purpose: a column where 29,1 sits above 29 reads as a
// different precision rather than the same number, so trailing zeros are kept.
const nf = (digits: number) =>
  new Intl.NumberFormat("de-DE", { maximumFractionDigits: digits, minimumFractionDigits: digits });

export function fmt(value: number, digits = 0): string {
  return nf(digits).format(value);
}

export function kpiValue(run: Run, key: KpiKey): number {
  const def = KPIS.find((k) => k.key === key)!;
  return run.kpis[key] * def.scale;
}

const capsOf = (s: Series) =>
  [...new Set(RUNS.filter((r) => r.series === s).map((r) => r.cap))].sort((a, b) => a - b);

export const V1_CAPS = capsOf("v1");
export const V2_CAPS = capsOf("v2");
export const V3_CAPS = capsOf("v3");
export const ALL_CAPS = [...new Set(RUNS.map((r) => r.cap))].sort((a, b) => a - b);

/** Caps present for a series. Derived, not hard-coded: the arms have different
    and changing coverage (v2 lacks 70; v3 lacks the runs still pending). */
export function capsFor(series: Series): number[] {
  return series === "v1" ? V1_CAPS : series === "v2" ? V2_CAPS : V3_CAPS;
}

/** X-axis ticks for one series: every multiple of 50 inside its actual capacity
    range, plus both endpoints. Derived on purpose — a hard-coded list stops
    labelling the moment a series grows (v2 went 150 -> 280, v3 reaches 380), and
    an unlabelled axis tail reads as if the data ended there. */
export function capTicks(series: Series): number[] {
  const caps = capsFor(series);
  const lo = caps[0];
  const hi = caps[caps.length - 1];
  const marks: number[] = [];
  for (let x = Math.ceil(lo / 50) * 50; x <= hi; x += 50) marks.push(x);
  // drop marks that would collide with an endpoint label rather than the endpoint
  return [lo, ...marks.filter((x) => x - lo >= 15 && hi - x >= 15), hi];
}

export const SERIES_LABEL: Record<Series, string> = {
  v1: "v1 (Alt, Feb–Apr 26)",
  v2: "v2 (Merger-Split, Sim+Dev)",
  v3: "v3 (Merger-Split, Replikat)",
};

export const SERIES_VAR: Record<Series, string> = {
  v1: "--c-v1",
  v2: "--c-v2",
  v3: "--c-v3",
};

function runsAt(series: Series, cap: number): Run[] {
  return RUNS.filter((r) => r.series === series && r.cap === cap);
}

/** Mean KPI over all runs (sweep point + replicates) at (series, cap); null if absent. */
export function meanAt(series: Series, cap: number, key: KpiKey): number | null {
  const rs = runsAt(series, cap);
  if (!rs.length) return null;
  return rs.reduce((s, r) => s + kpiValue(r, key), 0) / rs.length;
}

function baseline(series: Series, key: KpiKey): number {
  return meanAt(series, 30, key)!;
}

export type SweepRow = { cap: number } & Record<Series, number | null>;

/** Line rows over capacity; pct=true -> each series normalised to its own c=30 mean (=100). */
export function sweepRows(key: KpiKey, pct: boolean): SweepRow[] {
  const base = Object.fromEntries(SERIES.map((s) => [s, baseline(s, key)])) as Record<Series, number>;
  return ALL_CAPS.map((cap) => {
    const row = { cap } as SweepRow;
    for (const s of SERIES) {
      const m = meanAt(s, cap, key);
      row[s] = m == null ? null : pct ? (m / base[s]) * 100 : m;
    }
    return row;
  });
}

/** Every individual run as a scatter point (replicates become visible as extra dots). */
export function runPoints(series: Series, key: KpiKey, pct: boolean): { cap: number; val: number; replicate: string | null }[] {
  const b = baseline(series, key);
  return RUNS.filter((r) => r.series === series).map((r) => ({
    cap: r.cap,
    val: pct ? (kpiValue(r, key) / b) * 100 : kpiValue(r, key),
    replicate: r.replicate,
  }));
}

/** Delta of `series` against `ref` (same cap, means) — only caps where BOTH exist.
    rel=true -> % of the reference value, else absolute difference in KPI units.

    Two readings are meaningful and both are reachable from the UI:
      v2 or v3 vs v1  -> effect of the merger-split fix (a real code difference)
      v3 vs v2        -> pure reseed spread, i.e. the run-to-run noise floor,
                         since those two arms share the Hannover code path. */
export function deltaRows(
  key: KpiKey,
  rel: boolean,
  series: Series = "v2",
  ref: Series = "v1",
): { cap: number; delta: number }[] {
  const refCaps = capsFor(ref);
  return capsFor(series)
    .filter((cap) => refCaps.includes(cap))
    .map((cap) => {
      const a = meanAt(ref, cap, key)!;
      const b = meanAt(series, cap, key)!;
      return { cap, delta: rel ? ((b - a) / a) * 100 : b - a };
    });
}

/** The arms that carry the current code and may therefore be pooled into one
    mean. v1 is a DIFFERENT code version — it stays a reference line and is never
    averaged in, however the summary section is toggled. */
export const POOLED: Series[] = ["v2", "v3"];

export interface SummaryRow {
  cap: number;
  mean: number | null;
  /** [min, max] over the pooled runs — null where only one run exists, so the
      band is absent rather than drawn as a zero-width fake certainty. */
  band: [number, number] | null;
  n: number;
  v1: number | null;
}

/** Pooled mean of the replicate arms per capacity, with their min-max spread as
    the uncertainty band, plus v1 alongside for the optional overlay.

    Why min-max and not a standard deviation: n is 2 at best, where an SD is not
    an estimate of anything. The observed range between two independently seeded
    runs of identical code is the honest statement of what this sweep resolves. */
export function summaryRows(key: KpiKey): SummaryRow[] {
  return ALL_CAPS.map((cap) => {
    const vals = RUNS.filter((r) => POOLED.includes(r.series) && r.cap === cap).map((r) => kpiValue(r, key));
    return {
      cap,
      mean: vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null,
      band: vals.length > 1 ? [Math.min(...vals), Math.max(...vals)] : null,
      n: vals.length,
      v1: meanAt("v1", cap, key),
    };
  });
}

export interface SpreadStat {
  meanPct: number;
  maxPct: number;
  maxCap: number;
  nCaps: number;
}

/** The noise floor as one number per KPI: band width relative to the mean,
    averaged over every capacity that actually has two runs. */
export function spreadStat(key: KpiKey): SpreadStat | null {
  const pct = summaryRows(key)
    .filter((r) => r.band && r.mean)
    .map((r) => ({ cap: r.cap, p: ((r.band![1] - r.band![0]) / r.mean!) * 100 }));
  if (!pct.length) return null;
  const worst = pct.reduce((a, b) => (b.p > a.p ? b : a));
  return {
    meanPct: pct.reduce((s, x) => s + x.p, 0) / pct.length,
    maxPct: worst.p,
    maxCap: worst.cap,
    nCaps: pct.length,
  };
}

/** Capacities grouped by how many pooled runs they carry — the section states
    these explicitly, so a missing band is never read as a tight one. */
export function pooledCoverage(): { n1: number[]; n0: number[]; total: number } {
  const rows = summaryRows("vehicles");
  return {
    n1: rows.filter((r) => r.n === 1).map((r) => r.cap),
    n0: rows.filter((r) => r.n === 0).map((r) => r.cap),
    total: rows.length,
  };
}

export interface LimitRow {
  cap: number;
  capa_only: number;
  worktime_only: number;
  both: number;
  neither: number;
}

/** Stacked rows per capacity for one series; share=true -> % of tours, else counts. Replicated caps use the mean. */
export function limitRows(series: Series, share: boolean): LimitRow[] {
  const caps = capsFor(series);
  return caps.map((cap) => {
    const rs = runsAt(series, cap);
    const mean = (k: LimitKey) => rs.reduce((s, r) => s + r.limits[k], 0) / rs.length;
    const total = rs.reduce((s, r) => s + r.limits.total_tours, 0) / rs.length;
    const val = (k: LimitKey) => (share ? (mean(k) / total) * 100 : mean(k));
    return {
      cap,
      capa_only: val("capa_only"),
      worktime_only: val("worktime_only"),
      both: val("both"),
      neither: val("neither"),
    };
  });
}

export interface LimitLineRow {
  cap: number;
  capa: number;
  worktime: number;
  both: number | null;
}

/** Line-chart rows for the limiting-tours view.
    separateBoth=false -> "both" tours are counted into BOTH the capacity and the
    worktime curve (paper-figure convention); separateBoth=true -> three curves,
    the single-limit curves stay *_only. share=true -> % of tours, else counts. */
export function limitLineRows(series: Series, share: boolean, separateBoth: boolean): LimitLineRow[] {
  return limitRowsWithTotal(series).map(({ cap, capa_only, worktime_only, both, total }) => {
    const scale = (v: number) => (share ? (v / total) * 100 : v);
    return separateBoth
      ? { cap, capa: scale(capa_only), worktime: scale(worktime_only), both: scale(both) }
      : { cap, capa: scale(capa_only + both), worktime: scale(worktime_only + both), both: null };
  });
}

function limitRowsWithTotal(series: Series) {
  const caps = capsFor(series);
  return caps.map((cap) => {
    const rs = RUNS.filter((r) => r.series === series && r.cap === cap);
    const mean = (k: LimitKey | "total_tours") => rs.reduce((s, r) => s + r.limits[k], 0) / rs.length;
    return {
      cap,
      capa_only: mean("capa_only"),
      worktime_only: mean("worktime_only"),
      both: mean("both"),
      total: mean("total_tours"),
    };
  });
}

const nRuns = (s: Series) => RUNS.filter((r) => r.series === s).length;
const capRange = (s: Series) => {
  const c = capsFor(s);
  return `${c[0]}–${c[c.length - 1]}`;
};
/** Caps inside a series' own range that have no run - stated explicitly so a gap
    in a curve is never mistaken for a result. */
const gapsIn = (s: Series) => {
  const c = capsFor(s);
  return ALL_CAPS.filter((x) => x > c[0] && x < c[c.length - 1] && !c.includes(x));
};
const seriesBadge = (s: Series) => {
  const g = gapsIn(s);
  return `${s}: ${nRuns(s)} Runs (Kapa ${capRange(s)}${g.length ? `, ohne ${g.join("/")}` : ""})`;
};

export const META = {
  region: "Region Hannover (Stadt)",
  demand: "Bedarf 13.05.2025 (Di)",
  config: "iter 150 / jsprit 1000",
  v1: seriesBadge("v1"),
  v2: seriesBadge("v2"),
  v3: seriesBadge("v3"),
  worktimeLimitH: (raw as { worktime_limit_h: number }).worktime_limit_h,
  capaLimitFrac: (raw as { capa_limit_frac: number }).capa_limit_frac,
};
