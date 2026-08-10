// Δ between two series at the same capacity for the selected KPI, absolute (KPI
// units) or relative (% of the reference). Neutral gray bars, zero line, direct
// value labels. The pair is chosen by the parent: v2/v3 vs v1 reads as the effect
// of the merger-split fix, v3 vs v2 as the pure reseed noise floor.
import {
  Bar,
  BarChart,
  CartesianGrid,
  LabelList,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { deltaRows, fmt, type KpiDef, type Series } from "@/lib/data";

const INK2 = "var(--chart-ink2)";

interface Props {
  kpi: KpiDef;
  rel: boolean;
  series?: Series;
  ref?: Series;
}

export default function DeltaChart({ kpi, rel, series = "v2", ref = "v1" }: Props) {
  const rows = deltaRows(kpi.key, rel, series, ref);
  const digits = rel ? 1 : kpi.digits;
  const unit = rel ? "%" : kpi.unit;
  const signed = (v: number) => `${v > 0 ? "+" : ""}${fmt(v, digits)}`;
  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={rows} margin={{ top: 20, right: 24, bottom: 8, left: 8 }}>
        <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
        <XAxis
          dataKey="cap"
          type="category"
          stroke="var(--chart-axis)"
          tick={{ fill: INK2, fontSize: 12 }}
          label={{ value: "Fahrzeugkapazität [Pakete]", position: "insideBottom", offset: -4, fill: INK2, fontSize: 12 }}
        />
        <YAxis
          stroke="var(--chart-axis)"
          tick={{ fill: INK2, fontSize: 12 }}
          tickFormatter={(v: number) => `${fmt(v, 0)}${unit ? ` ${unit}` : ""}`}
          width={72}
        />
        <Tooltip
          cursor={{ fill: "var(--band)" }}
          contentStyle={{
            backgroundColor: "var(--chart-surface)",
            border: "1px solid var(--chart-grid)",
            borderRadius: 6,
            color: "var(--chart-ink)",
            fontSize: 12,
          }}
          formatter={(v) => [`${signed(Number(v))}${unit ? ` ${unit}` : ""}`, `Δ ${kpi.label} (${series} vs. ${ref})`]}
          labelFormatter={(cap) => `Kapazität ${cap}`}
        />
        <ReferenceLine y={0} stroke="var(--chart-axis)" />
        <Bar dataKey="delta" fill="var(--delta)" radius={[4, 4, 0, 0]} isAnimationActive={false} maxBarSize={40}>
          <LabelList
            dataKey="delta"
            position="top"
            formatter={((v: number) => signed(v)) as never}
            style={{ fill: "var(--chart-ink)", fontSize: 11 }}
          />
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
