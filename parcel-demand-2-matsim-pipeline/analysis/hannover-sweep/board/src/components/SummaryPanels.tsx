// Sweep summary: one small multiple per KPI, showing the pooled mean of the
// replicate arms (v2+v3) with their min-max spread as the uncertainty band, and
// v1 as an optional overlay.
//
// Why the mean is pooled over v2/v3 only: those two arms run the SAME Hannover
// code and differ only in their seed, so averaging them is averaging replicates
// and their spread IS the uncertainty. v1 is a different code version — mixing
// it into the mean would turn a version difference into fake noise, so it stays
// a separate line that can be switched off.
//
// Why there is no single mean "over the sweep": every KPI changes by a factor of
// several across 30-400 parcels, so an average across capacities would describe
// the chosen capacity range, not the system. The mean is per capacity.
//
// Why the RELATIVE mode is the default: on an absolute axis spanning 20k-100k
// tour-km, a ~1 % spread is a hairline - the band would be present but invisible,
// which is worse than not drawing it. Plotting the deviation from the pooled mean
// makes the band the subject and, when v1 is switched on, puts the code effect and
// the noise floor on ONE axis in the same unit: any v1 line inside the band is an
// effect this sweep cannot resolve. Absolute levels stay one click away (and are
// the subject of the sweep chart above).
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { KPIS, SERIES_VAR, fmt, pooledCoverage, spreadStat, summaryRows, type KpiDef } from "@/lib/data";

const INK2 = "var(--chart-ink2)";
const TICKS = [30, 100, 200, 300, 400];

/** Compact axis labels — these panels are ~330 px wide, so 642.097 does not fit. */
function short(v: number): string {
  if (Math.abs(v) >= 10000) return `${fmt(v / 1000, 0)}k`;
  return fmt(v, Math.abs(v) < 10 ? 1 : 0);
}

function Swatches({ showV1, rel }: { showV1: boolean; rel: boolean }) {
  return (
    <div className="mb-4 flex flex-wrap gap-x-5 gap-y-1" style={{ color: INK2 }}>
      <span className="inline-flex items-center gap-1.5 text-xs">
        <span className="inline-block h-0.5 w-4 rounded-full" style={{ background: `var(${SERIES_VAR.v2})` }} />
        Mittelwert v2/v3{rel && " (= 0-Linie)"}
      </span>
      <span className="inline-flex items-center gap-1.5 text-xs">
        <span
          className="inline-block h-3 w-4 rounded-sm"
          style={{ background: `var(${SERIES_VAR.v2})`, opacity: 0.22 }}
        />
        Streuband der Replikate (min–max)
      </span>
      {showV1 && (
        <span className="inline-flex items-center gap-1.5 text-xs">
          <span className="inline-block h-0.5 w-4 rounded-full" style={{ background: `var(${SERIES_VAR.v1})` }} />
          v1 {rel ? "(Abweichung vom Mittelwert)" : "(anderer Codestand)"} — nicht im Mittelwert
        </span>
      )}
    </div>
  );
}

interface PanelRow {
  cap: number;
  mean: number | null;
  band: [number, number] | null;
  v1: number | null;
  n: number;
}

function Panel({ kpi, showV1, rel }: { kpi: KpiDef; showV1: boolean; rel: boolean }) {
  const stat = spreadStat(kpi.key);
  const rows: PanelRow[] = summaryRows(kpi.key).map((r) => {
    if (!rel) return { cap: r.cap, mean: r.mean, band: r.band, v1: r.v1, n: r.n };
    // relative to the pooled mean; without a mean there is no reference at all
    const pct = (v: number) => ((v - r.mean!) / r.mean!) * 100;
    return {
      cap: r.cap,
      mean: r.mean == null ? null : 0,
      band: r.band && r.mean ? [pct(r.band[0]), pct(r.band[1])] : null,
      v1: r.v1 != null && r.mean ? pct(r.v1) : null,
      n: r.n,
    };
  });
  const digits = rel ? 2 : kpi.digits;
  const unitLabel = rel ? "% vom Mittelwert" : kpi.unit;
  const signed = (v: number) => `${v > 0 ? "+" : ""}${fmt(v, digits)}`;

  return (
    <div className="rounded-lg border p-3">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-sm font-semibold">{kpi.label}</span>
        <span className="text-xs text-muted-foreground">{unitLabel}</span>
      </div>
      <div className="mt-0.5 text-xs tabular-nums text-muted-foreground">
        {stat ? (
          <>
            Ø Spanne {fmt(stat.meanPct, 2)} % · max {fmt(stat.maxPct, 2)} % (Kapa {stat.maxCap})
          </>
        ) : (
          "keine Replikate"
        )}
      </div>
      <ResponsiveContainer width="100%" height={170}>
        <ComposedChart data={rows} margin={{ top: 10, right: 10, bottom: 4, left: 0 }}>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis
            dataKey="cap"
            type="number"
            domain={[20, 410]}
            ticks={TICKS}
            stroke="var(--chart-axis)"
            tick={{ fill: INK2, fontSize: 11 }}
          />
          <YAxis
            stroke="var(--chart-axis)"
            tick={{ fill: INK2, fontSize: 11 }}
            tickFormatter={rel ? (v: number) => signed(Number(v)) : short}
            width={rel ? 52 : 44}
            domain={["auto", "auto"]}
          />
          <Tooltip
            contentStyle={{
              backgroundColor: "var(--chart-surface)",
              border: "1px solid var(--chart-grid)",
              borderRadius: 6,
              color: "var(--chart-ink)",
              fontSize: 12,
            }}
            labelFormatter={(cap) => `Kapazität ${cap}`}
            formatter={(value, name) => {
              if (name === "band") {
                const [lo, hi] = value as unknown as [number, number];
                const txt = rel ? `${signed(lo)} … ${signed(hi)} %` : `${fmt(lo, digits)} – ${fmt(hi, digits)}`;
                return [txt, "Spanne v2/v3"];
              }
              const v = Number(value);
              if (name === "v1") return [rel ? `${signed(v)} %` : fmt(v, digits), "v1"];
              return [rel ? "0 % (Referenz)" : fmt(v, digits), "Mittelwert v2/v3"];
            }}
          />
          {rel && <ReferenceLine y={0} stroke={`var(${SERIES_VAR.v2})`} strokeWidth={2} />}
          {/* range area: a two-element value per point; absent where n<2 */}
          <Area
            dataKey="band"
            name="band"
            stroke="none"
            fill={`var(${SERIES_VAR.v2})`}
            fillOpacity={0.22}
            connectNulls={false}
            isAnimationActive={false}
          />
          {showV1 && (
            <Line
              dataKey="v1"
              name="v1"
              stroke={`var(${SERIES_VAR.v1})`}
              strokeWidth={1.5}
              strokeDasharray="4 3"
              dot={false}
              connectNulls={false}
              isAnimationActive={false}
            />
          )}
          {!rel && (
            <Line
              dataKey="mean"
              name="mean"
              stroke={`var(${SERIES_VAR.v2})`}
              strokeWidth={2}
              dot={false}
              connectNulls={false}
              isAnimationActive={false}
            />
          )}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

export default function SummaryPanels({ showV1, rel }: { showV1: boolean; rel: boolean }) {
  const cov = pooledCoverage();
  const nBand = cov.total - cov.n1.length - cov.n0.length;
  return (
    <div>
      <Swatches showV1={showV1} rel={rel} />
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {KPIS.map((k) => (
          <Panel key={k.key} kpi={k} showV1={showV1} rel={rel} />
        ))}
      </div>
      <p className="mt-4 text-xs text-muted-foreground">
        Mittelwert und Band über die Replikat-Arme v2 und v3 bei gleicher Kapazität — beide fahren denselben
        Hannover-Code und unterscheiden sich nur im Seed, ihre Spanne ist daher die Unsicherheit dieses Sweeps und kein
        Codeeffekt. Kein Standardabweichungsband: bei n = 2 schätzt eine SD nichts, die beobachtete Spanne schon.
        {rel ? (
          <>
            {" "}
            In dieser Ansicht ist der Mittelwert die 0-Linie, gezeigt wird die Abweichung davon in Prozent — nur so ist
            ein ~1-%-Band neben einem KPI sichtbar, der sich über den Sweep vervierfacht. Mit eingeblendetem v1 steht der
            Codeeffekt in derselben Einheit wie der Rauschboden: <strong>eine v1-Linie innerhalb des Bandes ist ein
            Effekt, den dieser Sweep nicht auflöst.</strong>
          </>
        ) : (
          <>
            {" "}
            In der Absolutansicht ist das Band bei ~1 % Breite kaum wahrnehmbar — für die Unsicherheit auf
            „relativ" umschalten.
          </>
        )}{" "}
        Band nur, wo zwei Runs vorliegen ({nBand} von {cov.total} Kapazitäten); nur ein Run bei {cov.n1.join("/")} —
        dort ist die Linie ein Einzelwert ohne Unsicherheitsaussage
        {cov.n0.length > 0 && <> und bei {cov.n0.join("/")} fehlt der Arm ganz</>}. v1 ist ein anderer Codestand
        (Merger-Split fehlt) und geht nie in den Mittelwert ein.
      </p>
    </div>
  );
}
