// Centrepiece: KPI over capacity, v1/v2 lines (mean) + every run as a dot,
// regime bands, abs/% toggle handled by the parent (rows arrive ready).
import {
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ReferenceArea,
  ResponsiveContainer,
  Scatter,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { SERIES, SERIES_LABEL, SERIES_VAR, fmt, runPoints, sweepRows, type KpiDef, type Series } from "@/lib/data";

// v3 dashed: it shares v2's code path, so the two are replicates of each other.
// A non-colour channel keeps them apart where the curves nearly coincide.
const DASH: Partial<Record<Series, string>> = { v3: "5 4" };

const INK2 = "var(--chart-ink2)";

function tooltipStyle() {
  return {
    backgroundColor: "var(--chart-surface)",
    border: "1px solid var(--chart-grid)",
    borderRadius: 6,
    color: "var(--chart-ink)",
    fontSize: 12,
  } as const;
}

interface Props {
  kpi: KpiDef;
  pct: boolean;
}

export default function SweepChart({ kpi, pct }: Props) {
  const rows = sweepRows(kpi.key, pct);
  const points = Object.fromEntries(SERIES.map((s) => [s, runPoints(s, kpi.key, pct)])) as Record<
    Series,
    ReturnType<typeof runPoints>
  >;
  const lastCapOf = (s: Series) => [...rows].reverse().find((r) => r[s] != null)?.cap;
  const digits = pct ? 1 : kpi.digits;
  const unit = pct ? "% von c=30" : kpi.unit;

  // direct end-of-line labels (legend exists too; <=4 series -> both)
  const endLabel = (s: Series, lastCap?: number) =>
    lastCap == null
      ? undefined
      : ({ index, x, y }: { index?: number; x?: number; y?: number }) =>
          index != null && rows[index]?.cap === lastCap ? (
            <text x={(x ?? 0) + 8} y={(y ?? 0) + 4} fontSize={12} fontWeight={600} fill={`var(${SERIES_VAR[s]})`}>
              {s}
            </text>
          ) : null;

  return (
    <ResponsiveContainer width="100%" height={380}>
      <ComposedChart data={rows} margin={{ top: 12, right: 44, bottom: 8, left: 8 }}>
        <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
        <ReferenceArea
          x1={30}
          x2={110}
          fill="var(--band)"
          label={{ value: "Kapazitätswand", position: "insideTopLeft", fill: INK2, fontSize: 11 }}
        />
        <ReferenceArea
          x1={190}
          x2={400}
          fill="var(--band)"
          label={{ value: "räumlich/zeitlich limitiert", position: "insideTopRight", fill: INK2, fontSize: 11 }}
        />
        <XAxis
          dataKey="cap"
          type="number"
          domain={[20, 410]}
          ticks={[30, 50, 100, 150, 200, 250, 300, 350, 400]}
          stroke="var(--chart-axis)"
          tick={{ fill: INK2, fontSize: 12 }}
          label={{ value: "Fahrzeugkapazität [Pakete]", position: "insideBottom", offset: -4, fill: INK2, fontSize: 12 }}
        />
        <YAxis
          stroke="var(--chart-axis)"
          tick={{ fill: INK2, fontSize: 12 }}
          tickFormatter={(v: number) => fmt(v, 0)}
          width={64}
          label={{ value: unit, angle: -90, position: "insideLeft", fill: INK2, fontSize: 12 }}
          domain={pct ? ["auto", "auto"] : [0, "auto"]}
        />
        <Tooltip
          contentStyle={tooltipStyle()}
          formatter={(v) => [`${fmt(Number(v), digits)} ${unit}`, undefined]}
          labelFormatter={(cap) => `Kapazität ${cap}`}
        />
        <Legend wrapperStyle={{ fontSize: 12, color: INK2 }} />
        {SERIES.map((s) => (
          <Line
            key={s}
            name={SERIES_LABEL[s]}
            dataKey={s}
            stroke={`var(${SERIES_VAR[s]})`}
            strokeWidth={2}
            strokeDasharray={DASH[s]}
            dot={false}
            connectNulls={false}
            isAnimationActive={false}
            label={endLabel(s, lastCapOf(s)) as never}
          />
        ))}
        {SERIES.map((s) => (
          <Scatter
            key={`${s}-pts`}
            name={`${s}-Runs`}
            data={points[s]}
            dataKey="val"
            fill={`var(${SERIES_VAR[s]})`}
            legendType="none"
            tooltipType="none"
            isAnimationActive={false}
          />
        ))}
      </ComposedChart>
    </ResponsiveContainer>
  );
}
