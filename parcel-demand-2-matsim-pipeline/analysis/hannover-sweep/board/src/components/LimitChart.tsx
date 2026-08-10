// Tour-limit classification over capacity: stacked areas (share or count).
// Stack order = validated palette adjacency: capa, worktime, both, neither.
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { LIMIT_CLASSES, fmt, limitRows, type Series } from "@/lib/data";

const INK2 = "var(--chart-ink2)";

interface Props {
  series: Series;
  share: boolean;
}

export default function LimitChart({ series, share }: Props) {
  const rows = limitRows(series, share);
  return (
    <div>
      {/* explicit swatch legend (HTML, deterministic) — one row above the chart */}
      <div className="mb-3 flex flex-wrap gap-x-5 gap-y-1">
        {LIMIT_CLASSES.map((c) => (
          <span key={c.key} className="inline-flex items-center gap-1.5 text-xs" style={{ color: "var(--chart-ink2)" }}>
            <span className="inline-block h-3 w-3 rounded-sm" style={{ background: `var(${c.cssVar})`, opacity: 0.85 }} />
            {c.label}
          </span>
        ))}
      </div>
      <ResponsiveContainer width="100%" height={320}>
        <AreaChart data={rows} margin={{ top: 12, right: 24, bottom: 8, left: 8 }}>
        <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
        <XAxis
          dataKey="cap"
          type="number"
          domain={["dataMin", "dataMax"]}
          ticks={series === "v1" ? [30, 50, 100, 150, 200, 250, 300, 350, 400] : [30, 50, 70, 90, 110, 130, 150]}
          stroke="var(--chart-axis)"
          tick={{ fill: INK2, fontSize: 12 }}
          label={{ value: "Fahrzeugkapazität [Pakete]", position: "insideBottom", offset: -4, fill: INK2, fontSize: 12 }}
        />
        <YAxis
          stroke="var(--chart-axis)"
          tick={{ fill: INK2, fontSize: 12 }}
          tickFormatter={(v: number) => fmt(v, 0)}
          width={56}
          domain={share ? [0, 100] : [0, "auto"]}
          label={{ value: share ? "% der Touren" : "Touren", angle: -90, position: "insideLeft", fill: INK2, fontSize: 12 }}
        />
        <Tooltip
          contentStyle={{
            backgroundColor: "var(--chart-surface)",
            border: "1px solid var(--chart-grid)",
            borderRadius: 6,
            color: "var(--chart-ink)",
            fontSize: 12,
          }}
          formatter={(v, name) => [`${fmt(Number(v), share ? 1 : 0)} ${share ? "%" : "Touren"}`, name]}
          labelFormatter={(cap) => `Kapazität ${cap}`}
        />
        {LIMIT_CLASSES.map((c) => (
          <Area
            key={c.key}
            name={c.label}
            dataKey={c.key}
            stackId="limits"
            fill={`var(${c.cssVar})`}
            fillOpacity={0.85}
            stroke="var(--chart-surface)" /* 2px surface gap between stacked fills */
            strokeWidth={2}
            isAnimationActive={false}
          />
        ))}
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
