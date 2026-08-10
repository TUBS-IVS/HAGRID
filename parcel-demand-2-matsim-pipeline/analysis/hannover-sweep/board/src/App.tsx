import { useEffect, useState } from "react";
import { Info, Moon, Sun } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import DeltaChart from "@/components/DeltaChart";
import LimitChart from "@/components/LimitChart";
import LimitLines from "@/components/LimitLines";
import RunsTable from "@/components/RunsTable";
import SweepChart from "@/components/SweepChart";
import Tiles from "@/components/Tiles";
import { KPIS, META, SERIES, SERIES_VAR, type KpiDef, type Series } from "@/lib/data";

/** The two comparisons worth showing, and why they differ: v2/v3 against v1 is a
    real code difference (merger-split fix), v3 against v2 is the same code twice
    and therefore the run-to-run reseed noise floor. */
const DELTA_PAIRS: { id: string; series: Series; ref: Series; label: string }[] = [
  { id: "v2-v1", series: "v2", ref: "v1", label: "v2 vs v1" },
  { id: "v3-v1", series: "v3", ref: "v1", label: "v3 vs v1" },
  { id: "v3-v2", series: "v3", ref: "v2", label: "v3 vs v2" },
];

function SeriesTabs({ value, onChange }: { value: Series; onChange: (s: Series) => void }) {
  return (
    <Tabs value={value} onValueChange={(v) => onChange(v as Series)}>
      <TabsList className="h-8">
        {SERIES.map((s) => (
          <TabsTrigger key={s} value={s} className="h-6 text-xs">
            {s}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );
}

function useDarkMode() {
  const [dark, setDark] = useState(() => window.matchMedia("(prefers-color-scheme: dark)").matches);
  useEffect(() => {
    document.documentElement.classList.toggle("dark", dark);
  }, [dark]);
  return { dark, toggle: () => setDark((d) => !d) };
}

function KpiChips({ kpi, onSelect }: { kpi: KpiDef; onSelect: (k: KpiDef) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {KPIS.map((k) => (
        <Button
          key={k.key}
          variant={k.key === kpi.key ? "default" : "outline"}
          size="sm"
          className="h-7 rounded-full px-3 text-xs"
          onClick={() => onSelect(k)}
        >
          {k.label}
        </Button>
      ))}
    </div>
  );
}

export default function App() {
  const { dark, toggle } = useDarkMode();
  const [kpi, setKpi] = useState<KpiDef>(KPIS.find((k) => k.key === "vehicles")!);
  const [pct, setPct] = useState(false);
  const [cap, setCap] = useState(100);
  const [limitSeries, setLimitSeries] = useState<Series>("v1");
  const [limitShare, setLimitShare] = useState(true);
  const [separateBoth, setSeparateBoth] = useState(false);
  const [lineSeries, setLineSeries] = useState<Series>("v1");
  const [deltaKpi, setDeltaKpi] = useState<KpiDef>(KPIS.find((k) => k.key === "vehicles")!);
  const [deltaRel, setDeltaRel] = useState(true);
  const [deltaPairId, setDeltaPairId] = useState(DELTA_PAIRS[0].id);
  const deltaPair = DELTA_PAIRS.find((p) => p.id === deltaPairId)!;

  return (
    <TooltipProvider delayDuration={200}>
      <div className="mx-auto max-w-[1180px] px-6 pb-16 pt-8">
        {/* ── Header ── */}
        <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">HAGRID · Kapazitäts-Sensitivität Hannover</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              LMD-Flotte bei variierender Fahrzeugkapazität — jsprit-Tourenplanung, ein Werktag, ~97.500 Pakete
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <Badge variant="secondary">{META.region}</Badge>
              <Badge variant="secondary">{META.demand}</Badge>
              <Badge variant="secondary">{META.config}</Badge>
              {SERIES.map((s) => (
                <Badge
                  key={s}
                  variant="outline"
                  style={{ borderColor: `var(${SERIES_VAR[s]})`, color: `var(${SERIES_VAR[s]})` }}
                >
                  {META[s]}
                </Badge>
              ))}
              <Tooltip>
                <TooltipTrigger asChild>
                  <Badge variant="outline" className="cursor-help gap-1">
                    <Info className="h-3 w-3" /> v2/v3 = Replikate
                  </Badge>
                </TooltipTrigger>
                <TooltipContent className="max-w-80 text-xs">
                  v1 = alter Codestand. v2 und v3 laufen denselben Hannover-Code (Merger-Split-Fix, 0 unzustellbare
                  Pakete) und sind bei gleicher Kapazität echte Replikate: der Tag steckt im runId, und
                  runId.hashCode() setzt den Seed der Bedarfsschicht neu. Ihre Differenz ist also
                  Reseed-Streuung, kein Codeeffekt — das ist der einzige Unsicherheitsschätzer, den dieser Sweep
                  hergibt. Serien werden nie in einer Kurve gemischt.
                </TooltipContent>
              </Tooltip>
            </div>
          </div>
          <Button variant="outline" size="icon" onClick={toggle} aria-label="Farbschema umschalten">
            {dark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </Button>
        </header>

        {/* ── Kapazitäts-Steckbrief ── */}
        <Card className="mb-6 shadow-none">
          <CardHeader className="pb-3">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <CardTitle className="text-base">Steckbrief bei Kapazität {cap}</CardTitle>
              <div className="flex w-72 items-center gap-3">
                <span className="text-xs text-muted-foreground">30</span>
                <Slider min={30} max={400} step={10} value={[cap]} onValueChange={(v) => setCap(v[0])} />
                <span className="text-xs text-muted-foreground">400</span>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            <Tiles cap={cap} />
          </CardContent>
        </Card>

        {/* ── Sweep ── */}
        <Card className="mb-6 shadow-none">
          <CardHeader className="pb-3">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <CardTitle className="text-base">KPI über Fahrzeugkapazität</CardTitle>
              <Tabs value={pct ? "pct" : "abs"} onValueChange={(v) => setPct(v === "pct")}>
                <TabsList className="h-8">
                  <TabsTrigger value="abs" className="h-6 text-xs">absolut</TabsTrigger>
                  <TabsTrigger value="pct" className="h-6 text-xs">% von c=30</TabsTrigger>
                </TabsList>
              </Tabs>
            </div>
            <div className="mt-2">
              <KpiChips kpi={kpi} onSelect={setKpi} />
            </div>
          </CardHeader>
          <CardContent>
            <SweepChart kpi={kpi} pct={pct} />
            <p className="mt-2 text-xs text-muted-foreground">
              Linien = Mittelwert je Kapazität und Serie, Punkte = einzelne Runs. Normierung je Serie auf ihr eigenes
              c=30. v3 gestrichelt, weil es dieselbe Codebasis wie v2 fährt. Lücken sind fehlende Runs, keine
              Ergebnisse: v2-70 nie nachgeholt, v2 endet bei 280 (Dev-PC gestoppt), v3 fehlen 170/270/330 (JVM-Crash,
              Nachlauf angestoßen) sowie 390/400 (zum Extraktionszeitpunkt noch nicht fertig).
            </p>
          </CardContent>
        </Card>

        {/* ── Limit-Analyse ── */}
        <Card className="mb-6 shadow-none">
          <CardHeader className="pb-3">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <CardTitle className="text-base">Was limitiert die Touren?</CardTitle>
              <div className="flex gap-3">
                <SeriesTabs value={limitSeries} onChange={setLimitSeries} />
                <Tabs value={limitShare ? "share" : "count"} onValueChange={(v) => setLimitShare(v === "share")}>
                  <TabsList className="h-8">
                    <TabsTrigger value="share" className="h-6 text-xs">Anteil</TabsTrigger>
                    <TabsTrigger value="count" className="h-6 text-xs">Anzahl</TabsTrigger>
                  </TabsList>
                </Tabs>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            <LimitChart series={limitSeries} share={limitShare} />
            <div className="mt-6 mb-3 flex flex-wrap items-center justify-between gap-4 border-t pt-4">
              <h3 className="text-sm font-semibold">Limitierende Touren als Linien</h3>
              <div className="flex flex-wrap items-center gap-3">
                <SeriesTabs value={lineSeries} onChange={setLineSeries} />
                <div className="flex items-center gap-2">
                  <Switch id="separate-both" checked={separateBoth} onCheckedChange={setSeparateBoth} />
                  <Label htmlFor="separate-both" className="text-xs text-muted-foreground">
                    „beides" als eigene Kurve
                  </Label>
                </div>
              </div>
            </div>
            <LimitLines series={lineSeries} share={limitShare} separateBoth={separateBoth} />
            <p className="mt-2 text-xs text-muted-foreground">
              Klassifikation je Tour: Arbeitszeit limitiert = Tourdauer &gt; {META.worktimeLimitH} h (Overtime wird
              bepreist, kommt aber vor) · Kapazität limitiert = Pakete &gt; {Math.round(META.capaLimitFrac * 100)} % der
              Fahrzeugkapazität. Schalter aus: doppelt limitierte Touren zählen in beiden Kurven mit.
            </p>
          </CardContent>
        </Card>

        {/* ── Alt vs. Neu ── */}
        <Card className="mb-6 shadow-none">
          <CardHeader className="pb-3">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <CardTitle className="text-base">
                Δ {deltaKpi.label} ({deltaPair.series} gegenüber {deltaPair.ref}, gleiche Kapazität)
              </CardTitle>
              <div className="flex flex-wrap gap-3">
                <Tabs value={deltaPairId} onValueChange={setDeltaPairId}>
                  <TabsList className="h-8">
                    {DELTA_PAIRS.map((p) => (
                      <TabsTrigger key={p.id} value={p.id} className="h-6 text-xs">
                        {p.label}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </Tabs>
                <Tabs value={deltaRel ? "rel" : "abs"} onValueChange={(v) => setDeltaRel(v === "rel")}>
                  <TabsList className="h-8">
                    <TabsTrigger value="abs" className="h-6 text-xs">absolut</TabsTrigger>
                    <TabsTrigger value="rel" className="h-6 text-xs">relativ (%)</TabsTrigger>
                  </TabsList>
                </Tabs>
              </div>
            </div>
            <div className="mt-2">
              <KpiChips kpi={deltaKpi} onSelect={setDeltaKpi} />
            </div>
          </CardHeader>
          <CardContent>
            <DeltaChart kpi={deltaKpi} rel={deltaRel} series={deltaPair.series} ref={deltaPair.ref} />
            <p className="mt-2 text-xs text-muted-foreground">
              {deltaPair.ref === "v1" ? (
                <>
                  {deltaPair.series} fährt die in v1 unzustellbaren Stopps mit (Merger-Split-Fix: 4.618 Pakete bei c=30
                  waren in v1 strukturell unassigned) — Mehrleistung erklärt einen Großteil der positiven Deltas.
                </>
              ) : (
                <>
                  v3 gegen v2 ist <strong>kein</strong> Codeeffekt: beide Arme laufen denselben Hannover-Code, nur mit
                  unterschiedlichem Tag und damit unterschiedlichem Seed der Bedarfsschicht. Diese Balken sind der
                  Rauschboden — jeder v2/v1- oder v3/v1-Effekt kleiner als das hier Gezeigte ist nicht belastbar.
                </>
              )}
            </p>
          </CardContent>
        </Card>

        {/* ── Tabelle ── */}
        <Card className="shadow-none">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Alle Runs</CardTitle>
          </CardHeader>
          <CardContent>
            <RunsTable />
          </CardContent>
        </Card>

        <footer className="mt-8 text-xs text-muted-foreground">
          Quelle: HAGRID Java-LMD-Dashboards (SUMMARY-Export), extrahiert am 10.08.2026 · Kosten &amp; Distanzen
          jsprit-planbasiert, deterministisch · Touren = Fahrzeugeinsätze (1 Tour je Fahrzeug und Tag).
        </footer>
      </div>
    </TooltipProvider>
  );
}
