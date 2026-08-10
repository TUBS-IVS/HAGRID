// Limiting tours as line chart (paper-figure style): capacity- vs worktime-
// constrained tours over capacity. separateBoth=false folds the "both" tours
// into BOTH curves; separateBoth=true shows them as their own third curve.
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { capTicks, fmt, limitLineRows, type Series } from "@/lib/data";

const INK2 = "var(--chart-ink2)";

interface Props {
  series: Series;
  share: boolean;
  separateBoth: boolean;
}

export default function LimitLines({ series, share, separateBoth }: Props) {
  const rows = limitLineRows(series, share, separateBoth);
  const lines = [
    {
      key: "capa",
      label: separateBoth ? "Kapazität limitiert (nur)" : "Kapazität limitiert (inkl. beides)",
      cssVar: "--lim-capa",
    },
    {
      key: "worktime",
      label: separateBoth ? "Arbeitszeit limitiert (nur)" : "Arbeitszeit limitiert (inkl. beides)",
      cssVar: "--lim-wt",
    },
    ...(separateBoth ? [{ key: "both", label: "beides", cssVar: "--lim-both" }] : []),
  ];
  return (
    <div>
      <div className="mb-3 flex flex-wrap gap-x-5 gap-y-1">
        {lines.map((l) => (
          <span key={l.key} className="inline-flex items-center gap-1.5 text-xs" style={{ color: INK2 }}>
            <span className="inline-block h-0.5 w-4 rounded-full" style={{ background: `var(${l.cssVar})` }} />
            {l.label}
          </span>
        ))}
      </div>
      <ResponsiveContainer width="100%" height={280}>
        <LineChart data={rows} margin={{ top: 12, right: 24, bottom: 8, left: 8 }}>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis
            dataKey="cap"
            type="number"
            domain={["dataMin", "dataMax"]}
            ticks={capTicks(series)}
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
          {lines.map((l) => (
            <Line
              key={l.key}
              name={l.label}
              dataKey={l.key}
              stroke={`var(${l.cssVar})`}
              strokeWidth={2}
              dot={{ r: 3, fill: `var(${l.cssVar})`, strokeWidth: 0 }}
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
