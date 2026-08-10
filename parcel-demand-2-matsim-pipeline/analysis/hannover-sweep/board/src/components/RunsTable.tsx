// All 51 runs x KPIs, sortable columns, client-side CSV download.
import { useMemo, useState } from "react";
import { ArrowUpDown, Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { KPIS, LIMIT_CLASSES, RUNS, fmt, kpiValue, type Run } from "@/lib/data";

type ColKey = "series" | "cap" | "replicate" | (typeof KPIS)[number]["key"] | (typeof LIMIT_CLASSES)[number]["key"];

const COLS: { key: ColKey; label: string; num: boolean }[] = [
  { key: "series", label: "Serie", num: false },
  { key: "cap", label: "Kapa", num: true },
  { key: "replicate", label: "Replikat", num: false },
  ...KPIS.map((k) => ({ key: k.key as ColKey, label: k.unit ? `${k.label} [${k.unit}]` : k.label, num: true })),
  ...LIMIT_CLASSES.map((c) => ({ key: c.key as ColKey, label: c.short, num: true })),
];

function cell(r: Run, key: ColKey): string | number {
  if (key === "series") return r.series;
  if (key === "cap") return r.cap;
  if (key === "replicate") return r.replicate ?? "";
  const kpi = KPIS.find((k) => k.key === key);
  if (kpi) return kpiValue(r, kpi.key);
  return r.limits[key as (typeof LIMIT_CLASSES)[number]["key"]];
}

function display(r: Run, key: ColKey): string {
  const kpi = KPIS.find((k) => k.key === key);
  if (kpi) return fmt(kpiValue(r, kpi.key), kpi.digits);
  const v = cell(r, key);
  return typeof v === "number" ? fmt(v, 0) : String(v);
}

function downloadCsv() {
  const head = COLS.map((c) => c.label).join(";");
  const lines = RUNS.map((r) => COLS.map((c) => cell(r, c.key)).join(";"));
  const blob = new Blob(["﻿" + [head, ...lines].join("\n")], { type: "text/csv;charset=utf-8" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "hannover_sweep_kpis.csv";
  a.click();
  URL.revokeObjectURL(a.href);
}

export default function RunsTable() {
  const [sort, setSort] = useState<{ key: ColKey; asc: boolean }>({ key: "cap", asc: true });

  const rows = useMemo(() => {
    const sorted = [...RUNS].sort((a, b) => {
      const va = cell(a, sort.key);
      const vb = cell(b, sort.key);
      const c = typeof va === "number" && typeof vb === "number" ? va - vb : String(va).localeCompare(String(vb));
      return sort.asc ? c : -c;
    });
    return sorted;
  }, [sort]);

  return (
    <div>
      <div className="mb-2 flex justify-end">
        <Button variant="outline" size="sm" onClick={downloadCsv}>
          <Download className="mr-2 h-4 w-4" /> CSV
        </Button>
      </div>
      <div className="max-h-[480px] overflow-auto rounded-md border">
        <Table>
          <TableHeader className="sticky top-0 bg-card">
            <TableRow>
              {COLS.map((c) => (
                <TableHead key={c.key} className={c.num ? "text-right" : ""}>
                  <button
                    className="inline-flex items-center gap-1 font-medium hover:text-foreground"
                    onClick={() => setSort((s) => ({ key: c.key, asc: s.key === c.key ? !s.asc : true }))}
                  >
                    {c.label}
                    <ArrowUpDown className="h-3 w-3 opacity-50" />
                  </button>
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.file}>
                {COLS.map((c) => (
                  <TableCell key={c.key} className={`${c.num ? "text-right tabular-nums" : ""} py-1.5 text-xs`}>
                    {display(r, c.key)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
