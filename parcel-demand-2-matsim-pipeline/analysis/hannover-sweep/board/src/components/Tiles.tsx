// Capacity profile: the 6 KPIs as stat tiles at the selected capacity, one row
// per series that has a run there, each non-v1 row carrying a Δ% chip vs v1.
import { Card, CardContent } from "@/components/ui/card";
import { KPIS, SERIES, SERIES_VAR, fmt, meanAt, type Series } from "@/lib/data";

function DeltaChip({ delta }: { delta: number }) {
  return (
    <span className="ml-2 whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-semibold bg-muted text-foreground/80">
      {delta > 0 ? "+" : ""}
      {fmt(delta, 1)} %
    </span>
  );
}

export default function Tiles({ cap }: { cap: number }) {
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
      {KPIS.map((k) => {
        const vals = Object.fromEntries(SERIES.map((s) => [s, meanAt(s, cap, k.key)])) as Record<
          Series,
          number | null
        >;
        const ref = vals.v1;
        return (
          <Card key={k.key} className="shadow-none">
            <CardContent className="p-4">
              <div className="text-xs uppercase tracking-wide text-muted-foreground">{k.label}</div>
              {SERIES.map((s) => {
                const v = vals[s];
                // v1 always gets a row (an en dash where absent) so the tiles stay
                // vertically aligned across KPIs; the other arms only when present.
                if (v == null && s !== "v1") return null;
                return (
                  <div key={s} className="mt-1 flex items-baseline">
                    <span
                      className="mr-2 inline-block h-2.5 w-2.5 rounded-full"
                      style={{ background: `var(${SERIES_VAR[s]})` }}
                      title={s}
                    />
                    <span className="text-xl font-bold tabular-nums">{v == null ? "–" : fmt(v, k.digits)}</span>
                    {k.unit && <span className="ml-1 text-xs text-muted-foreground">{k.unit}</span>}
                    {s !== "v1" && v != null && ref != null && <DeltaChip delta={((v - ref) / ref) * 100} />}
                  </div>
                );
              })}
            </CardContent>
          </Card>
        );
      })}
    </div>
  );
}
