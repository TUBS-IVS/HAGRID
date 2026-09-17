# HAGRID Backlog

Zentrale Sammelstelle für **offene** Arbeitspunkte quer über die HAGRID- und
Lausitz-DRT-Freight-Projekte. Sachen, die man "irgendwann mal hat", aber nicht aktiv
weiterführt, weil gerade nicht 100 % Prio — hier gehen sie nicht verloren.

**Abgrenzung:**

| Dokument | Inhalt | Lebenszyklus |
|---|---|---|
| **BACKLOG.md** (hier) | was noch **zu tun** ist | stirbt beim Erledigen |
| [METHODS-LOG.md](METHODS-LOG.md) | was wir **wissen/entschieden** haben und was davon nicht trägt | wird nie gelöscht, nur annotiert |
| [BACKLOG-DONE.md](BACKLOG-DONE.md) | was **erledigt** wurde, mit Nachweis/Commit | Archiv |

Detail-Kontext zu laufender Arbeit steht im jeweils verlinkten Plan/Spec unter
`docs/superpowers/`. Dieses Dokument bleibt auf Roadmap-Höhe: Status, offene Arbeit, ein Link —
**keine Findings-Narrative** (die gehören ins METHODS-LOG).

**Prioritäten:** `[H]` High · `[M]` Medium · `[L]` Low.
User-bestätigt High (2026-07-14): **Shared-Use, Modular, Nachhaltigkeit**. Alle anderen
Einstufungen sind mein Vorschlag und jederzeit anpassbar.

**Pflege:** wird im Arbeits-Workflow mitgepflegt. Erledigtes wandert nach BACKLOG-DONE (mit
Nachweis), methodische Substanz ins METHODS-LOG, der Rest wird gestrichen.
_Zuletzt aktualisiert: 2026-08-26. Durchsicht + Kürzung **838 → 495 Zeilen**: das Dokument hatte
seine eigene Abgrenzungsregel verletzt (35 Bullets mit ≥8 Zeilen = 59 % der Datei, überwiegend
Findings-Narrative). Erledigtes entfernt, Befundtexte nach METHODS-LOG verschoben oder gestrichen,
wo sie dort schon standen; **30 `file:line`-Referenzen geprüft, 20 waren verschoben**; ein
Strukturschaden repariert, drei neue Defekte aufgenommen → Nachweis in
[BACKLOG-DONE](BACKLOG-DONE.md) 2026-08-17._

---

## High

### `[M]` Stem-KPI: Neuzahlen fahren und in den Sweep ziehen

Die Definition ist seit 2026-08-28 korrigiert (METHODS-LOG §2.49), Zahlen dazu gibt es noch keine.
Offen: (1) ein Hannover-Board mit dem neuen Code erzeugen und den Zuwachs durch das Rückleg
messen; (2) `extract_sweep.py` um `ROUT_EFF` erweitern (`stem_pct_network`,
`stem_pct_provider_max`), damit die Zahl nicht wieder von Hand aus einer CSV-Zeile abgelesen wird;
(3) `stemInPct` als Vintage-Weiche einbauen, sonst mischt der Extractor beide Definitionen.

### `[H]` Shared-Use / Cargo-Hitching (Szenario 1c)

Minibus mit 2D-Kapazität (Sitze + Pakete), Online-DVRP-Insertion.
**Status: implementiert, Betriebspunkt in Messung** (2026-09-01). Implementierung, Reviews und
χ→0-Validierung → [BACKLOG-DONE](BACKLOG-DONE.md); Architektur- und Parameter-Entscheidungen
→ [METHODS-LOG](METHODS-LOG.md) §1.1/§1.2.
→ [1c-Plan](superpowers/plans/2026-07-06-1c-shareduse-cargo-hitching.md) ·
[Spike](superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md) _(added 2026-07-14)_

Stand: χ-Raster (150/300/450/600/900 bei f140) und Iso-Service-Punkt f140 sind gemessen, durch die
Segmentaufteilung `2ff5dbb` überholt und für χ = 600/900/∞ nachgemessen
(→ [METHODS-LOG](METHODS-LOG.md) §2.46). **χ=900 ist der Betriebspunkt** (→ [METHODS-LOG](METHODS-LOG.md) §2.57): dort wird jedes
Paket zugestellt, das eine gültige Anfrage wurde, und zwar seed-unabhängig (n=5, sd null auf der
Frachtseite), während χ=600 im Mittel 331 ± 64 Pakete liegen lässt. Der Pax-Preis ist mit n=5
gegen n=5 nicht messbar (−84 Fahrten, t=1,06).

Damit besteht die restliche Lücke zu 100 % nur noch aus zwei χ-unabhängigen Kanälen:

- **`[H]` Flotten-Sweep über alle drei Szenarien statt kalibrierter Einzelpunkte** — der
  Baseline-Fächer (§2.65) hat gezeigt, dass f140 bei 250 Iterationen um 166 Fahrten
  überversorgt ist; die ganze Szenarienaussage hängt damit an einer Kalibrierung, die im
  falschen Iterationsregime gemacht wurde. Kurvenvergleich statt Punktvergleich macht das
  robust. **Rasterweite mindestens 10 Fahrzeuge** — 5 Fahrzeuge sind 326 kg = 2,0 sd des
  Laufrauschens und bei n=1 unsichtbar. ~~Vorbedingung: die Paketbasis vereinheitlichen~~ — **erledigt
  2026-09-10** (§2.66): das Not-at-home-Overlay ist aus `parcels_handled`,
  `parcels_per_vehicle_km` und dem Kostennenner gestrichen, alle drei Arme stehen brutto.
  ⚠️ Bereits vorhandene `kpis_long.csv` tragen noch die alte Basis und müssen für den
  Kurvenvergleich neu gerechnet werden (`build_kpis.py --run-dir ...`). 9 neue Läufe unter
  Wiederverwendung von b120rgs@120 (n=5) und 1c f140@140 (n=5), ~4 Tage. **Noch nicht
  freigegeben.** _(added 2026-09-09)_
- **`[H]` ~~Baseline-Seed-Fächer bei 250 Iterationen~~ ERLEDIGT 2026-09-09** — 1c
  liegt bei 250 Iterationen 79,6 Fahrten über der Baseline. Gegen den Baseline-Wert als
  Konstante ist das grenzwertig trennbar (t=2,86 gegen 2,78); unter **jeder** plausiblen
  Baseline-Streuung nicht mehr (t=1,05–2,03). Der Baseline-Wert bei 250 ist n=1, ihre
  Seed-Streuung ist nie gemessen worden — das Urteil hängt also an einer Zahl, die fehlt
  (§2.62). Drei zusätzliche Seeds `b120rgs` bei 250, ~10,75 h je Lauf, rund 32 h. Bis dahin
  ist **weder** „iso-service bei 250“ **noch** das Gegenteil belegt. _(added 2026-09-07)_
- **`[H]` `vmargs_lausitz.txt` versionieren — eine ungetrackte Datei bestimmt ein
  ergebnisänderndes Modellparameter** — `-XX:ActiveProcessorCount=12` darin deckelt
  `Runtime.availableProcessors()`, und daraus zieht `DrtConfigGroup` seinen Default für
  `numberOfThreads`. Die Threadzahl ist NICHT ergebnisneutral: 12 gegen 14 sind −103 Fahrten
  bei sonst identischer Config (§2.59). Die Datei ist ungetrackt und auf dem **Dev gar nicht
  vorhanden** — genau so ist basew21 auf 14 gerutscht. Entweder in die Versionskontrolle oder
  den Wert explizit in die Config schreiben, wo er sichtbar ist. _(added 2026-09-06)_
- **`[H]` Paarvergleiche mit vollem Config-Diff absichern, nicht nur mit dem POPHASH** — der
  POPHASH-Check prüft die Eingangspopulation; bei basew21 waren die Populationen byteidentisch
  und der Defekt saß trotzdem in der Config (§3.14). Ein Diff über alle 310 Config-Pfade hätte
  ihn sofort gezeigt. Werkzeug liegt jetzt unter `analysis/lausitz/kpi/config_diff.py` (selbstgetestet
  am basew21-Paar: 1 substanzielle Abweichung; Negativkontrolle gegen sich selbst: 0). Offen
  ist nur noch, es in die Auswertung fest einzuhängen. _(added 2026-09-06)_
- **`[H]` Walk-Fallback abstellen oder ausweisen — 91 Pakete (1,5 %)** — der DRT-Router lehnt bei
  der Routensuche ab, MATSim setzt ein Walk-Leg, das Paket zählt als zugestellt, und **nichts wird
  geloggt** (§2.50). 41 der 91 laufen 2,3–6,7 km. Flotten- und χ-invariant, also nicht durch
  Kapazität oder χ zu beheben. Entweder Ursache am Ort beheben oder als eigener Kanal im
  Dashboard ausweisen — aber nicht stillschweigend mitzählen. _(added 2026-09-03)_
- **`[M]` Hoftor-Verwurf beheben — 15 Pakete (0,25 %)** — zwei gepoolte Stopps schnappen auf ihren
  eigenen Depot-Link, `from == to` ist keine gültige DVRP-Anfrage, im Preprocessing verworfen
  (§2.50). Auf eine Nachbarkante snappen statt verwerfen; die Baseline kennt die Schranke nicht,
  also ist das ein reiner 1c-Nachteil aus dem Modell. _(added 2026-09-03)_
- **`[H]` Segment-Größentabelle auf der KPI-Basis neu ableiten** — die Verteilungstabelle in
  [METHODS-LOG](METHODS-LOG.md) §2.46 (Ausfälle je Segmentgröße) stammt aus einem Skript, dessen
  Paketsummen sich nicht mit der KPI-Schicht verrechnen ließen — die Totale sind dort auf 395/268
  korrigiert, die Verteilung ist noch nicht nachgerechnet. Bis dahin **nicht zitierbar**. Reines
  Postprocessing, 0 Runs. _(added 2026-09-02)_
- **`[M]` Paketausfälle nie aus einem Einzellauf berichten** — der Fächer zeigt für
  `parcels_undelivered` einen Variationskoeffizienten von **19,3 %** (258–399 bei n=5), gegen 1,1 %
  bei der Quote und 1,4 % bei den Pax-Fahrten (§2.52). Aussagen der Form „χ kostet N Pakete“
  brauchen Replikate; Quotenaussagen nicht. Betrifft alle 1c-Tabellen im Paper und die
  Dashboard-Kacheln, die absolute Ausfallzahlen zeigen. _(added 2026-09-02)_
- **`[H]` Ausgabe-Root kürzen — die 252-Zeichen-Falle entschärfen** — jeder bestehende Tag steht
  exakt auf der letzten funktionierenden Länge; ein Zeichen mehr und ein fertiger Lauf stirbt still
  im Shutdown, nach voller Rechenzeit (→ [METHODS-LOG](METHODS-LOG.md) §2.51). Betrifft 1c, 1d und
  Hannover gleichermaßen. Der Fix gehört an den Pfad, nicht an die Tags. _(added 2026-09-01)_
- **`[M]` 250-Iterationen-Kontrollpunkt für 1c** — vertagt (Nutzerentscheidung 2026-09-01). Für 1c
  ist der Iterationseffekt nie gemessen; die −219 stammen aus 1d (§2.47). Ein Lauf f140/χ=600 mit
  `maxIter=250` gegen den vorhandenen 150er wäre dasselbe Versuchsdesign wie die 1d-Messung.
  Solange er fehlt, steht der Betriebspunkt auf einer Basis, die nicht die publizierte ist.
  _(added 2026-09-01)_
- **`[M]` Laufzeit-Regression 7,0 h → 11,9 h klären** — identische 1c-Config, gleicher Rechner,
  **+70 %**. Diskriminator: zwei 5-Iterations-Läufe mit/ohne `recordEvaluation`, ~1 h.
  Kandidatenliste → [METHODS-LOG](METHODS-LOG.md) §2.35. _(added 2026-08-13)_
- **Offen: Shared-Use-Hälfte des Nachfrage-Bandes** — an den nächsten Sweep hängen, kostet dort
  nur einen zusätzlichen Punkt.
- **Offen: PPC (Passenger-Parcel Compensation) prüfen** — vor der Evaluation entscheiden, ob der
  Mechanismus reinkommt → [METHODS-LOG](METHODS-LOG.md) §4.1. _(added 2026-07-15)_

### `[H]` Multi-Run-Aggregation für km-basierte KPIs

Der Rauschboden der jsprit-Heuristik auf der Fahrleistung ist **gemessen: 6,5 %** — km/Paket liegt
mit S/R 0,5× darunter, Kosten und Fahrzeugzahl tragen. Entschieden ist die Antwort
(**≥10 Sim-Runs, Mittelwert + Min/Max reporten**); die **Umsetzung fehlt** und ist im
Emissions-Plan bewusst ausgeklammert. Betrifft 1c, 1d, alle km-basierten KPIs und die
distanzbasierten Emissionen direkt.
Zahlen, Signal-Rausch-Tabelle, Seed-Schalter und der Nachbau-Gotcha:
[METHODS-LOG](METHODS-LOG.md) §2.1/§2.2/§1.5. _(added 2026-07-28)_

- **Zu bauen:** Run-Fächer über `-Dhagrid.jsprit.seed`, Aggregation (Mittelwert + Min/Max +
  Streuung) in die KPI-Extraktion, Ausweisung im Dashboard. Prozess-Parallelität (N Seeds =
  N JVMs) ist hier die richtige Achse, nicht Carrier-Parallelität.
- **`[H]` Erster Bedarf: der Tourdauer-Sweep — jetzt nicht mehr Vorsichtsmaßnahme, sondern
  gemessen nötig.** Der 4,0-h-Lauf hat die Vorhersage um 632 €/Tag verfehlt, also um **so viel wie
  der Unterschied, der die Punkte trennt** (612 €/Tag); die Fläche ist auf der Skala der berichteten
  Differenzen unglatt und nicht konvex. Ohne Bänder ist die Scheitellage in [3,5 h; 4,5 h] nicht
  bestimmbar. ≥3 Seeds je Cap auf {12600, 14400, 16200} → [METHODS-LOG](METHODS-LOG.md)
  §2.36/§2.17. Die θ-Kurve braucht das nicht mehr (Gewinner steht). _(added 2026-08-17)_
- **Wo Bänder nötig sind und wo nicht** (§2.17): nötig für absolute km-/Stunden-Niveaus, jeden
  Cap-Vergleich, den Headline-Vergleich und die distanzbasierten Emissionen. Nicht nötig für den
  χ-Sweep (1c fährt gar kein jsprit, `SimulationRunnerUtils.runsCarrierModules:274-278`) und für
  gepaarte θ-Vergleiche innerhalb eines Caps.

### `[H]` Modular / U-Shift (Szenario 1d)

Kapsel-Tausch, Offline-jsprit + Pax-Priorität. **Status: codeseitig paper-fertig** — Implementierung,
θ-Sweep und Kostenkampagne sind durch, Nachweise → [BACKLOG-DONE](BACKLOG-DONE.md), Ergebnisse →
[METHODS-LOG](METHODS-LOG.md) §1.2 (θ-Kurve, Konzeptparameter) und §2.36–§2.38 (drei Hebel,
Gewinner-θ = 0,15, bester zulässiger Punkt `f150t015`). POC läuft auf dem aktuellen PANDA-Stand.
→ [Plan](superpowers/plans/2026-07-27-1d-modular-capsule-swap.md) ·
[Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md) ·
[Spike](superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md)
_(added 2026-07-14, aktualisiert 2026-08-17)_

- **✅ 7,0-h-Kontrollarm gefahren (2026-08-18)** — `f150d70` liefert **exakt 41 Touren**, die Zahl
  der Baseline: die Cap-Asymmetrie erklärt die Tourenzahl vollständig. Der Frachtaufschlag je Paket
  fällt von +33,9 % auf +12,6 %, die **Pax-Seite verbessert sich nicht** (+4,5–7,4 % je Fahrt über
  alle Caps). Ergebnis und M11-Zerlegung → [METHODS-LOG](METHODS-LOG.md) §2.38.
- **✅ Flottenkalibrierung auf der Depotstufe abgeschlossen (2026-08-27)** — neue Baseline
  `basew21_it250` = 9.183 Fahrten (Plateau 226–249); 1d trifft sie bei **135 Fahrzeugen**
  (9.214, +31 = unter dem Seed-Abstand 48). Gemessene Steigung 48,2 Fahrten/Fahrzeug, exakter
  Treffpunkt 134,4. 150 Iterationen sind unkonvergiert, 250 ist Standard → METHODS-LOG §2.47;
  `basew21`@150 als Anker zurückgezogen → §2.48. _(added 2026-08-27)_
- **✅ θ ist kein Feinregler, sondern ein harter Riegel VOR dem Budget (2026-09-08)** —
  `idleThreshold` ist eine Konjunktion der `while`-Bedingung in `ModularTourDispatcher` und steht
  vor dem Budget-Gate: faellt der Leerlaufanteil darunter, wird der Schleifenkoerper nie betreten
  und Rampe wie Verfall-Override sind unerreichbar. θ=0,15 und `budgetHeadroom`=0,15 reservieren
  dieselben 19,5 Fahrzeuge doppelt. Mit θ=0,02 faellt der Verfall von 2.167 auf 207 (kumuliert bis
  it.182) und der Arm stellt alle 6.052 Pakete zu → [METHODS-LOG](METHODS-LOG.md) §2.61, §2.64.
  _(added 2026-09-08)_

- **`[H]` θ-Sensitivität auf der Depotstufe — LÄUFT seit 2026-08-27** — θ=0,15 wurde unbesehen aus
  der alten Kampagne übernommen, das Gate bindet auf der neuen Stufe anders (46/46 Touren
  disponiert, aber nur 15 um 07:16, Rest bis 11:45). Kette `run_theta1d_chain.bat`: θ=0,20 dann
  θ=0,10 bei f135/iter250. Auswerten gegen 9.214. _(added 2026-08-27)_
- **`[H]` Seed-Faecher 1d NEU AUFSETZEN — Lauf am 09.09. gestorben** — `d1d_f130_d30_s2337`
  starb bei it.170/250 ohne Spur (kein Reboot, keine `hs_err`, kein OOM, kein Kernel-Power-Ereignis;
  ganzer Prozessbaum inkl. `cmd.exe` weg). `s3337` startete deshalb nie. **MATSim hat hier keinen
  Wiederaufsetzpunkt** — `setFirstIteration(0)` steht hart in `LausitzDrtConfigurator:141` und
  `HAGRIDScenarioBuilder:145`, `output_plans` wird erst am Ende geschrieben. Neu starten mit
  `run_seedfan1d.bat`. ⚠️ **Vorher KEIN `mvn package`**: der Faecher muss denselben JAR benutzen wie
  `th02` (06.09. 13:55), sonst ist es ein Codevergleich statt eines Seed-Faechers.
  ⚠️ **Auch die Emissionsseite haengt daran** (2026-09-10): das Diesel-Delta 1d gegen
  Baseline ist 329,5 kg, die Baseline-Seedspanne 328,7 kg — Verhaeltnis 1,002, und 1c ueberlappt
  die Baseline ohnehin. Ohne Faecher ist keine der beiden Emissionsdifferenzen zitierfaehig,
  siehe [METHODS-LOG](METHODS-LOG.md) §2.67. _(added 2026-09-09)_

- **`[H]` Ankerlauf auf dem AKTUELLEN JAR** — der Anker `d1d_dep7_f130_it250` stammt vom 25.08.
  und damit von einem aelteren Codestand als `th02`. Die Pax-Differenz in
  [METHODS-LOG](METHODS-LOG.md) §2.64 (c) vergleicht deshalb zwei Codestaende. Ein Ankerlauf
  (seed=1337, `idleThreshold=0.15`, `maxTourDuration=12600`, kein Budget) auf dem 06.09.-JAR klaert
  in einem 14-h-Lauf, ob der alte Anker als Vergleichsbasis haltbar ist. _(added 2026-09-09)_

- **`[H]` Flotten-Nachjustierung bei 7 h** — `f150d70` bedient **3,87 % mehr Fahrten** als die
  Baseline, die Systemsumme mischt dort also „kostet mehr" mit „leistet mehr". Faire Cap-Parität
  braucht bei 7 h die kleinste Flotte, die 9.076 Fahrten noch trifft. ⚠️ **Nicht hochrechenbar** —
  die Fahrzeugstunden skalieren nicht mit der Flottengröße (150→140: +0,7 %, 140→130: −13,1 %),
  das ist zu messen. Kandidaten 140/135/130 bei `maxTourDuration=25200`. _(added 2026-08-18)_

- **`[H]` Demand-Input-Sync Dev-PC↔Sim-PC entscheiden** — `hagrid/input/**` ist git-ignoriert
  und synchronisiert nicht über Maschinen; die beiden PCs fahren 0,53 % verschiedene Nachfrage
  (6052 vs. 6020 Pakete, anderes Muster). Baseline↔1c intern konsistent (beide Dev-PC), die
  1d-Kurve intern konsistent (alle Sim-PC), Baseline↔1d nicht exakt. Entweder Sync + Rerun am
  Gewinnerpunkt (sauber für die Paper-Headline) oder Caveat im Methods-Kapitel →
  [METHODS-LOG](METHODS-LOG.md) §2.30. ⚠️ **Präzisierung 2026-08-17:** die neue
  Kostenkampagne (`b120rg` + alle `f150*`) lief **vollständig auf dem Dev-PC**, ist also intern
  konsistent — die Drift trifft nur den Anschluss an die älteren Sim-PC-θ-Läufe.
  _(added 2026-07-31, aktualisiert 2026-08-17)_

- **Zurückgestellte Sensitivitätsidee (User 2026-07-27):** jsprit-Tourplanung als **EIN Pool**
  (ein Carrier, Fahrzeuge an allen 7 Depots, freie Depotwahl je Paket = stärkstes
  Einheitsunternehmen) und/oder beide Varianten als Zerlegung Konsolidierungs- vs.
  Integrationseffekt. Verwandt mit `[L]` Consolidated-Operator-Baseline.

### `[H]` Nachhaltigkeitsparameter einbauen

Emissions-/CO₂-/Energie-KPIs und -Parameter ins Modell + Dashboard. Berührt Autonomie-Switch
(E-Antrieb) und die Kostenfunktion. **Status: Plan 2026-07-28 AUSGEFÜHRT (Tasks 1–9), 2026-07-31;
Kaltstart-Zuschlag + STAY-Ladefenster-Analyse AUSGEFÜHRT, 2026-08-26.**
→ [Plan 2026-07-28](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md),
[Design 2026-08-26](superpowers/specs/2026-08-26-coldstart-stay-analysis-design.md). Ergebnis: KPI-Gruppe
`environment` in `build_kpis` (drei Arme freight / freight_modular / drt, Diesel + BEV,
Non-Exhaust segmentdifferenziert, EV-Reichweiten-Sweep, `kpi_emissions_vehicles.csv`), plus
Kaltstart-Zuschlag und `drive_block_max_km_*`. Methodenwahl, Klassenmapping, Systemgrenze und
Caveats: [METHODS-LOG](METHODS-LOG.md) §1.4/§2.7/§2.26–§2.29; Faktor-Provenance und
Limitations-Rohtext: `analysis/lausitz/kpi/data/README.md`. Nachweis: [BACKLOG-DONE](BACKLOG-DONE.md).
_(added 2026-07-14, abgeschlossen 2026-07-31, erweitert 2026-08-26)_

- **`[H]` Energetisches Lademodell für die DRT-Flotte (Ladeleistung, Batteriekapazität,
  SoC-Verlauf)** — Nachfolger von „Ladefenster-Analyse" (BACKLOG-DONE 2026-08-26).
  ⚠️ **Begründung 2026-09-03 nachgezogen:** die ursprüngliche Fassung („Schranke bindend",
  445,5 km gegen 250 km) ist **zurückgezogen** → [METHODS-LOG](METHODS-LOG.md) §3.12; die 250 km
  waren unbelegt und entsprachen 62 kWh. Mit belegten Fahrzeugen reißen im 20-min-Fenster
  **9 von 135** Fahrzeugtagen die 396,4-km-Schwelle. Das Paket bleibt sinnvoll, aber die Frage
  lautet jetzt „wie teuer ist der Ausläufer" statt „ist es überhaupt möglich" — also
  SoC-Nachlauf gegen Ladeleistung und Ladeort, kein Machbarkeitsnachweis. Arbeitsschritte:
  [CHECKLIST-emissions](CHECKLIST-emissions.md) B-1/B-2. _(added 2026-08-26, revidiert 2026-09-03)_
- **`[H]` BEV-Szenario + Planetary-Boundaries-Einbettung (SOS)** — **wäre der methodische
  Aufhänger des Papers.** ~5–8 Tage, **keine neuen Sim-Runs** (reiner Faktortausch; EV-Plumbing
  existiert). Bausteine: BEV-EC-Kurven + Netz-CO₂-Intensität als Sensitivität (dominiert das
  Ergebnis) · Non-Exhaust mit BEV-Korrektur (einzige lokale PM-Quelle im BEV-Arm) ·
  Limitations-Absatz „Elektrifizierung nur auf Emissionsebene" · **SOS-Layer = Hauptaufwand,
  Methodik nicht Code (~3–5 d)**. Scope und AESA-Literatur →
  [METHODS-LOG](METHODS-LOG.md) §4.4. _(added 2026-07-28)_
- **Kleinere Offene aus der Faktor-Sichtung:** `_l`-Van einmal als HDT „Rigid ≤7,5 t" gegenrechnen
  (ausgewiesene Bandbreite statt versteckter Annahme) · Midi-Bus als Alternativsubstitution für die
  DRT-Flotte · Multi-Seed-Aggregation (→ `[H]` Multi-Run-Aggregation).
- **⚠️ Constraint:** `hagrid_output_analysis/emissions.py` **nicht anfassen** (Kollegen-Paper) —
  eingehalten, der Emissionskanal liegt vollständig in
  `analysis/lausitz/kpi/{emissions_emep,extract_emissions}.py` → [METHODS-LOG](METHODS-LOG.md) §1.4.

### `[H]` Kostenfunktion reviewen

**✅ Für LAUSITZ umgesetzt (2026-08-28).** `analysis/lausitz/kpi/cost_model.py` leitet die `DERIVED`-Sätze
aus `cost_parameters.csv` ab (Selbsttest gegen die im CSV dokumentierten 28,99 / 33,45 / 14,80 /
16,60 / 18,80 / 22,74 €), `economics.py` wertet C auf Flotten-Aggregaten aus und ist ins Dashboard
verdrahtet (`render_drt`/`render_lmd`/`render`). **Hannover bleibt bewusst auf dem Platzhalter**
(User-Entscheidung 2026-08-28) — die Umschaltung hängt an `study_area`. Gegenproben: 220,9 €/Tour
gegen die im CSV vermerkten „ca. 221 € für 7 h", 1,60 €/Paket im Literaturband. Tests
`tests/test_cost_model.py` (17). Gemessen am gematchten Paar: Baseline 88.942 € gegen 1d 87.950 €,
also **−1,1 %**, auf allen vier Kanälen kleiner. `*_placeholder`-Vorbehalt → §2.6 gilt nur noch
für Hannover.

**⚠️ Blockiert, nicht offen (User-Entscheidung 2026-08-16/17):** die Original-Kostenfunktion wird
**nicht angefasst**, eine neue Klasse kommt erst **nach dem hendrik→master-Merge (~Oktober) und
nach den Hannover-Läufen**; bis dahin läuft die Bewertung rein post-hoc in Python,
`lmd-vehicle-types.xml` bleibt unberührt. Was fehlt, ist der Merge, nicht die Entscheidung.
_(added 2026-07-14, aktualisiert 2026-08-17)_

- **`[H]` durH-Basis ist eine Setzung mit Vorzeichenwirkung** — Personal = `c_time × durH`, durH =
  `drt_tour_hours_total` (aktive Spanne, Nutzerentscheidung 2026-08-28). `shift_h` (24 h) drehte den
  Armvergleich auf +4.307 €, `occupied_h` auf −7.949 €. Gehört als Sensitivität ins Paper, nicht nur
  in den Code. _(added 2026-08-28)_
- **`[H]` ArbZG-Zulässigkeit der DRT-Schichten** (löst den `REVIEW`-Status von
  `max_daily_working_hours_legal` ab) — Fahrzeuge sind im Mittel **16,1 h** aktiv (max 20,7 h), also
  über dem 10-h-Maximum: mindestens **zwei Fahrer je Fahrzeug und Tag**. C bepreist Stunden, nicht
  Schichten, unterstellt also freien Wechsel ohne Mindestschicht und ohne bezahlte Übergabe
  (`cost_drivers_per_vehicle_min` weist das aus). LMD ist unkritisch (Touren 6,0 h ⌀, 7,06 h max).
  _(added 2026-08-28)_
- **`[M]` Überstunden- und Abendzuschlag nicht instrumentiert** — beide brauchen durH je Fahrzeug
  bzw. Tageszeitauflösung, die `economics.extract` an seiner Stelle in `build_kpis` nicht hat.
  Headline unberührt (`overtime_factor` = 0), die 0,30-Sensitivität wäre aber ~16 % des
  Personalkanals. Als `cost_*_instrumented`-Flags sichtbar. _(added 2026-08-28)_
- **`[M]` `kpi_vehicles.csv` trägt die Frachtseite nicht** — nur **10 von 41** Frachtzeilen haben
  `distance_km`/`duration_h`, Summe 585 km gegen 2.702 km aus `kpis_long`. C rechnet deshalb auf
  Flotten-Aggregaten; als Basis wäre die Datei still um Faktor 4,6 falsch gewesen. _(added 2026-08-28)_
- **`[H]` Kostenmodell-Sektion im METHODS-LOG anlegen** — die Herleitung (Lohn-Vollkosten,
  Overhead-Kürzungsalgebra, M11-Zurechnung) lebt derzeit **nur** im CSV-Kopf. Das ist
  paper-facing Methodik und gehört ins METHODS-LOG, bevor daraus ein Methods-Kapitel wird.
  _(added 2026-08-17)_
- **Beim Neubau umzusetzen:** M11-Marginalzurechnung (Fracht zahlt eigene Fahrzeugstunden + km,
  Pax den Rest samt Fixblock) statt `drt_cost_per_ride_placeholder`, das heute 100 % der
  Flottenkosten den Pax auflädt · Aufspaltung des LMD-Fixsatzes in Fahrzeug-Tag + Fahrer-Stunde
  (heute `costsPerSecond = 0`, Tagessatz je *Tour* — [METHODS-LOG](METHODS-LOG.md) §2.33), das ist
  zugleich Voraussetzung für den Autonomie-Switch · Kapsel-/Swap-Station-Kapital und Handling
  fehlen ganz (post-hoc als `modular_premium_factor`-Band 1,00–1,20 abgebildet).
- **`[M]` DRT-Kosten-KPI im v2-Dashboard klären** — v2 hat **null** DRT-Kosten-KPIs; drei
  widersprüchliche Literaturwerte sind aufzulösen (150.000 € Currie/Fournier vs. 408.000 € /
  35,25 € je Fahrt aus Legacy-Python vs. die 68 €/25 €-Platzhalterkarten). _(added 2026-07-17)_

### `[H]` Hannover-Sweep: Kostenkorrektur im Postprocessing

**Ad-hoc-Korrektur der €-Zahlen auf dem Kapazitäts-Sweep-Board, ohne Re-Runs** — reine
Python-Nachrechnung über vorhandene Artefakte (der `SUMMARY`-Blob trägt je Tour `durH`, `travelH`,
`distKm`, `fixCost`, `costPerKm`; `extract_sweep.py` schneidet ihn schon heraus).
**Befund, beschlossene Regel, Kalibrierung und Darstellungsentscheidung stehen vollständig in
[METHODS-LOG](METHODS-LOG.md) §2.33** — hier bleibt nur die Ausführung.

**Terminierung (User 2026-08-11): erst wenn v2–v4 vollständig sind**, dann gesammelt; alle Läufe
sollen auf derselben Kostenversion stehen.

**Zu tun:**
- `V4_CAPS` in `analysis/hannover/sweep/extract_sweep.py:31-43` anlegen (`EXPECTED_RUNS` in
  derselben Edit mitwachsen lassen, by design) — heute nur `V1`/`V2`/`V3`.
- Korrekturfunktion nach §2.33 implementieren, `sweep_kpis.csv` (`cost_eur`) und das React-Board
  (`board/`, neue Serie oder Toggle) mitziehen, Nachweis in [BACKLOG-DONE](BACKLOG-DONE.md).
- Beim Nachziehen mitentscheiden: gepoolter v2–v4-Mittelwert auf der Kostenkurve (die
  Summary-Sektion kann das bereits), und ob die Korrektur die Reseed-Spanne wie erwartet
  verkleinert.

Die dauerhafte Reparatur im Java-Pfad läuft über `[H]` Kostenfunktion reviewen und ersetzt diesen
Punkt später. _(added 2026-08-11, gekürzt 2026-08-17)_

### `[H]` Pax-Nachfrage im Lausitz-Clip: Kontrolllauf ohne DRT und der kopierte DRT-ASC

Anlass (User 2026-09-01): 9.036 DRT-Fahrten/Tag auf 41.937 Einwohner wirken gegenüber realen
Systemen hoch. Der naheliegende Verdacht — das kommt vom entfernten Linien-ÖV — ist als Treiber
widerlegt (Schiene an/aus bewegt den DRT-Anteil um +0,06 pp; nur 10,6 % der DRT-Fahrten waren im
Input `pt`) → [METHODS-LOG](METHODS-LOG.md) §2.54. Offen bleiben zwei Punkte:

- **`[H]` Kontrolllauf ohne DRT auf demselben Clip fahren** — identische Konfiguration, Bus und
  Schiene an, kein DRT, Iterationen nach §2.47. Liefert zwei Dinge auf einmal: (1) den *kausalen*
  DRT-Effekt statt der heutigen Kompositionsschätzung gegen `plans-initial`; (2) die Probe, ob das
  Clip überhaupt den kalibrierten Lausitz-Modal-Split reproduziert — der Radanteil fällt vom Input
  (19,2 %) auf 6,9 % im Lauf, und der Input ist unkonvergiert (`score="0.0"`, ein Plan/Person).
  Ohne diesen Arm ist jede Aussage über DRT-induzierte Verlagerung unbelegt (§2.54 Messvorbehalt).
  _(added 2026-09-01)_

- **`[H]` DRT-ASC-Sensitivität rechnen und als Limitation ausweisen** — der DRT-ASC wird vom ÖV
  kopiert: `setConstant(ptParams.getConstant())` (−1,2987) und `marginalUtilityOfTraveling = 0`
  ([DrtConfigComposer.java:126-128](../hagrid/src/main/java/hagrid/lausitz/drt/DrtConfigComposer.java#L126)).
  Ein Tür-zu-Tür-Dienst wird damit bewertet wie ein Taktbus, obwohl der ÖV-ASC gerade das
  Unbeobachtete am Bus aufsammelt (Haltestellenweg, Fahrplanbindung, Umsteigen). Zu tun: ASC-Fächer
  auf dem Baseline-Arm, Modal-Split und DRT-Fahrleistung als Bandbreite statt als Punktwert, dann
  benannte Limitation ins METHODS-LOG. _(added 2026-09-01)_

---

## Medium

### Nachfrage-Modell (PANDA → HAGRID): offene Punkte

Das Nachfrage-Niveau-Band ist gemessen und der Proxy-Fix ist durch → Ergebnisse und
Zurückziehungen in [METHODS-LOG](METHODS-LOG.md) §1.3/§3.1/§3.2, Nachweise in
[BACKLOG-DONE](BACKLOG-DONE.md). Offen bleibt:

- **`[S]` Exogener Altersterm (K6) — vertagt** (User 2026-07-29: erstmal lassen). Die CV-Batterie
  erfüllt formal **beide** präregistrierten Bedingungen, die Zustimmung kommt aber aus einer
  Randlösung auf kollinearen Altersspalten, und der Effekt ist mit −1,3 bis −1,8 % klein gegen das
  ±10-%-Band. Kosten bei Umsetzung: 3 × ~1 h 40 (Neuexport + Restaging + Band) — **zusammen mit dem
  Punkt unten entscheiden**, es ist praktisch dieselbe Frage. Abwägung im Detail:
  `PANDA/docs/transferability.md` → B10, [METHODS-LOG](METHODS-LOG.md) §2.9. _(added 2026-07-29)_
- **`[S]` Bake-off-Doku nachziehen oder als OSM-Ära kennzeichnen** — `PANDA/docs/`
  `bakeoff_model_selection.md` ist der einzige Teil der Validierung, der *nicht* auf dem
  Zensus-Prädiktor neu gerechnet ist (die Batterie ist es, → §2.9). Der Verdikt-Teil
  („Demografie/Haushalte/Packstationen tragen kein übertragbares Signal") ist plausibel
  unberührt, aber die Zahlen sind alt. Entweder `studies/bakeoff.py` neu laufen lassen oder
  eine Kopfzeile setzen. _(added 2026-07-29)_
- **`[M]` Zustellquoten-Abweichung von −14 % aufklären** — systematisch in beiden Bandarmen,
  hebt sich in Vergleichen weg, macht aber jede *absolut* berichtete Zustellquote zu optimistisch.
  Einstieg: `CarrierGenerator.adjustDeliveryRatesConsideringB2B:184`,
  `determineMissedParcels:1008`, Sollwerte `HagridConfig.java:174-192`
  (Zeilen 2026-08-17 nachgezogen, alle drei waren verschoben).
  Details: [METHODS-LOG](METHODS-LOG.md) §2.5. _(added 2026-07-27)_
- **`[S]` Entscheiden, ob das Band auf den gefixten Eingaben neu läuft** — der
  Zentroid-Snap-Fix (2026-07-28) platziert **12,6 % der Pakete anders**, bei unverändertem
  Niveau. Die auf Rekord stehenden `bandz_*`-Ergebnisse kamen aus `level_ctrsnap_*` (archiviert,
  nicht überschrieben). Dagegen spricht: die belastbaren Aussagen des Bandes sind die Kosten- und
  Fahrzeug-Elastizitäten, und die km-basierten Kanäle liegen ohnehin am oder unter dem
  Rauschboden ([METHODS-LOG](METHODS-LOG.md) §2.1). Dafür spricht: die drei Szenarienläufe fahren
  ohnehin die gefixte Datei, also wäre das Band sonst auf einem anderen Muster gemessen als die
  Headline-Runs. Kosten: 3 × ~1 h 40. _(added 2026-07-28)_

### Sonstige Medium-Punkte

- **`[M]` Zwei Distanzmaße derselben Touren, 4,6 % auseinander — klären, welches die gefahrenen
  km sind** — `distKm` (Carrier-Plan) gegen `costDist` (MATSim-Scoring), stabil über 14 Boards.
  Relevant nicht für die €-Seite (0,4 % der Kosten), sondern als Input für km/Paket,
  Fahrleistungsaussagen und die distanzbasierten Emissionen. Zwei Kandidatenmechanismen, Messwerte
  und Ausschlüsse → [METHODS-LOG](METHODS-LOG.md) §2.33 Punkt 5. Blockiert die Kostenkorrektur
  **nicht**. _(added 2026-08-11)_

- **`[M]` `hagrid.log.dir` wird unbedingt überschrieben — jede neue Maschine fällt einmal rein**
  — `SimulationRunnerUtils.runSimulation` setzt `hagrid.log.dir` auf `<runDir>/logs`, auch wenn ein
  `-Dhagrid.log.dir` explizit gesetzt wurde. Der MATSim-Controler leert dann dieses Verzeichnis und
  kollidiert mit der offenen `hagrid.log`; der Lauf stirbt in der Guice-Injektion vor Iteration 0.
  Workaround ist ein eigener log4j-Config außerhalb des Run-Verzeichnisses — seit 2026-08-25
  getrackt als `logging/log4j2_runlocal.xml` (vorher nur als ungetracktes `devlog/log4j2_dev.xml`,
  weshalb der Sim-PC am 2026-08-25 beim ersten 1c-Arm genau hier abgebrochen ist). Echter Fix: das
  `setProperty` respektiert eine explizit gesetzte Property, dann wird die Config-Datei unnötig.
  Reine Logging-Semantik, keine Simulationswirkung. _(added 2026-08-25)_

- **`[M]` KPI-Landschaft konsolidieren** — ein Konzept für Kontaminations-Marker,
  `*_pax`-Zusatzzeilen, `pax_only`-Overrides und Meta-Rows, bevor weitere Szenarien dazukommen
  (User 2026-07-29: „damit wir nicht irgendwann in den Dashboards ein KPI-Chaos haben").
  _(added 2026-07-29)_

- **`[L]` 1d: Prädiktiver Dispatch-Gate (θ_hist) aus Vor-Iterations-Nachfrage** — dem
  Modular-Dispatcher historische Anfrage-/Rejection-Raten aus Iteration n−1 mitgeben, damit das
  Gate die erwartete Pax-Bindung über den Tour-Horizont sieht statt nur den Momentan-Idle-Share.
  Natives Vorbild `PreviousIterationDrtDemandEstimator`, Verkabelung ~1 Tag; offener Kern ist die
  Entscheidungsregel. **Nach dem θ-Sweep fürs Paper nicht nötig** (Wartezeit/Rejections sind
  θ-invariant, es gibt keinen Schaden zu heilen → [METHODS-LOG](METHODS-LOG.md) §1.2) — bleibt als
  Erweiterungsidee liegen. _(added 2026-07-28, abgestuft 2026-08-17)_

- **`[S]` LMD 14:00-Welle quasi tot** — bei `FleetSize.INFINITE` deckt jsprit die ganze Nachfrage
  mit Morgen-Klonen ab, weil die kleine Region ins Morgenfenster passt; auf frischen Daten
  bestätigt (19/19 Touren morgens in drei Seeds, auch mit dem 21:00-Fenster). Der `LmdTourRetimer`
  spreizt nur Abfahrten, er verschiebt keine Touren zwischen Wellen. Echte Zwei-Wellen-Struktur
  bräuchte `FleetSize.FINITE` + Flottengrößen-Heuristik je Welle — **User-Entscheidung 2026-07-30:
  Retimer reicht vorerst, Baseline-Drift vermeiden.** Beim Anfassen Delegate-Muster wahren (neue
  Parameter nur über neue Overloads). Analyse → [BACKLOG-DONE](BACKLOG-DONE.md) 2026-07-30.
  _(added 2026-07-30)_

- **`[M]` Run-Dashboard v2 — Plan D (Karten) + drei zurückgestellte KPIs.** B ✅ gepusht, C ✅ und
  D-Visual-Polish ✅ lokal. **Offen:** (a) die **Karten** selbst (depot-siting / vehicle-tours in
  die Tabs); (b) `occ_km` (km je Besetzungslevel — braucht Netz-km-Rekonstruktion, Legacy
  `dist_by_occ` im Parent von `b639ff3`; `_occ_chart` rendert die Serie schon, sie wird nur nicht
  emittiert) und **ausgelieferte Pakete/h** (`_hourly_provider_stack` hängt bereit);
  (c) 1d-Modular-Karten samt C8-Late-Metriken und δ-Dekomposition, jetzt möglich, da 1d-Runs
  existieren. → Pläne
  [D-Karten](superpowers/plans/2026-07-13-run-dashboard-v2-planD-maps.md) ·
  [D-Polish](superpowers/plans/2026-07-16-run-dashboard-v2-planD-visual-polish.md)
  _(added 2026-07-14, aktualisiert 2026-08-17)_

- **`[M]` Carrier-Parallelisierung in `routeWithDurationCap`** — die 7 Lausitz-Carrier lösen
  sequenziell auf dem Main-Thread
  ([LausitzFreightPreprocessor.java:314](../hagrid/src/main/java/hagrid/lausitz/freight/LausitzFreightPreprocessor.java#L314));
  parallel fällt die Wall-Clock auf den größten Carrier, **10,7 h → ~3,7 h** @`jspritIter=1000`
  (§2.2). Obergrenze ~2,9× (Amdahl) → 3–4 Threads genügen. Beide Voraussetzungen 2026-07-30
  geprüft und grün: Determinismus bleibt (frische `Random` je Carrier,
  `HAGRIDRouterUtils.applySeedOverride:246`), `NetworkBasedTransportCosts` ist thread-safe by
  design. Lohnt für die Latenz *eines* Laufs — für den Seed-Fächer ist Prozess-Parallelität besser.
  _(added 2026-07-30)_

- **`[M]` Race in unserem matsim-Fork mitfixen: `NetworkBasedTransportCosts:514` `matsimVehicles`
  ist eine plain `HashMap`**, lazy beschrieben im Kostenpfad (`:796` get / `:801` put),
  unsynchronisiert. Werte sind idempotent aus `typeId` abgeleitet (also keine falschen Distanzen),
  aber ein gleichzeitiger Resize kann Einträge verlieren oder werfen. **Heute nicht scharf** (alles
  single-threaded), wird es mit dem Punkt darüber. Einzeiler `HashMap`→`ConcurrentHashMap`, upstream
  melden. _(added 2026-07-30, herausgelöst 2026-08-17)_

- **`[L]` jsprit-Upgrade 1.8 → 2.x — stark abgekühlt, nur noch ein Regler offen.** Der
  Hauptnutzen ist **schon in 1.8 geholt** (`REGRET_INSERTION`, −21 % Touren → §2.34), und
  Ruin-Operator-Diversität — 2.x' Hauptversprechen — ist auf diesen Daten ausdrücklich
  **widerlegt**. Damit bleibt: **`FAST_REGRET`/`regretFast()` testen** (steht auf jsprit-Default
  `false`, der nächste 1.8-Regler), erst danach Go/No-Go für 2.0.
  **Wenn doch gebumpt wird:** 2.0.0 existiert und braucht Java 21 (haben wir); Kosten sind aber
  ~16 Java-Dateien mit tiefer SPI-Nutzung **plus** der komplette MATSim-freight-contrib im Fork
  portiert und die Divergenz dauerhaft gepflegt, weil Upstream auf 1.8 bleibt. Berührt `pom.xml`
  (`jsprit.version`) + drei POMs, braucht freight-272-Regression und einen married250-Re-Run
  (alle Dashboards neu baseline). Deshalb `[L]`, nicht `[M]`.
  _(added 2026-07-16, neu eingeordnet 2026-08-11, gekürzt 2026-08-17)_

- **`[M]` Case-Study-Area erweitern** — Ruhland-Korridor, aktuell als Kurzfix dropped.
  **Entschieden: bleibt zunächst Hoyerswerda, Erweiterung erst ~2027**
  → [METHODS-LOG](METHODS-LOG.md) §4.6. Mitzuziehen, wenn es passiert: der 2026-07-20 entfernte
  „Bahn-Zubringer"-Kartenlayer (Port-Referenz Legacy `build_drt_dashboard.py:260-289`, Skript
  gelöscht, Stand im Parent von `b639ff3`); dabei die stille-`None`-Ursache mit-fixen und den Layer
  nur bei vorhandenen Daten zeigen. _(added 2026-07-14, aktualisiert 2026-08-17)_

- **`[M]` hagrid/input Bootstrap (Restructure Schritt 3)** — ~156 MB, größtenteils untracked;
  letzter manueller Transfer-Schritt für "läuft auf jedem neuen PC". Geplant:
  Download-on-first-run mit URL-Liste + Checksums; HAGRID-only-Dateien via
  Uni-Share/Release-Assets. _(added 2026-07-14)_

- **`[S]` `hagrid/PIPELINE_DOCUMENTATION.md` und `hagrid/SETUP_TUTORIAL.md` neu schreiben** — beide
  beschreiben noch das Layout vor dem Restructure vom 2026-09-17 und sogar vor dem
  `hagrid-input`-Common/Hannover/Lausitz-Split; tragen bis dahin nur einen Stale-Hinweis. _(added 2026-09-18)_

- **`[M]` Autonomie-Switch-Plan** — Labour aus / Roboter-Dwell / Speed-Cap / Autobahn-Ausschluss,
  orthogonal über beide integrierten Szenarien. **User-Entscheidung 2026-07-30: nicht von
  unmittelbarer Relevanz — bleibt liegen** (Plan erst nach 1c+1d). Die vier Effekte landen an vier
  verschiedenen Stellen (Netz-Preprocessing, jsprit, DRT-Stop-Dauer, Kostenfunktion), es ist also
  **kein** Config-Verdrahtungs-Task → [METHODS-LOG](METHODS-LOG.md) §1.1.
  `IntegratedScenarioConfig` ist auf genau diese vier Effekte eingedampft und erreicht weiter keinen
  Run: kommt der Switch, ist die Klasse sein Integrationsort — kommt er nie, ist sie zu **löschen**
  (alle Begründungen stehen in der [Design-Spec](superpowers/specs/2026-06-17-lausitz-drt-freight-integration-design.md)
  §4.4/§6.1/§6.3; ⚠️ deren §4.4 ≠ METHODS-LOG §4.4). _(added 2026-07-14, aktualisiert 2026-08-17)_

- **`[M]` Karten-Dropdowns/-Controls noch nicht manuell durchgeklickt** — Plan-D-maps Task 9 hat
  nur Ladezeit/Größe im Browser bestätigt (6.1 MB, married250, "fast & responsive"); die
  interaktiven Elemente selbst (DRT-Fahrzeug-`<select>` inkl. 227 Einträgen + Stop-Badges, LMD
  Touren/Stopps/Heatmap-Modus-Radio, Provider/Carrier/Vehicle-Filter, Depot/Rail/Heat-Checkboxen,
  Light/Dark-Tile-Wechsel) sind noch **nicht** einzeln durchgeklickt/reviewt. Vor "fertig" fürs
  Kartenfeature: einmal jeden Dropdown/Filter/Toggle auf dem married250-Dashboard durchgehen.
  _(added 2026-07-17)_

### Fallback-Audit 2026-07-27 (Medium-Tier)

Fallbacks, die greifen statt zu scheitern und dabei still falsche Zahlen erzeugen. Die vier
scharfen Befunde sowie M2 und M6 sind erledigt → [BACKLOG-DONE](BACKLOG-DONE.md); hier stehen die
verbleibenden. Positiv-Befund am Rande: im gesamten `integrated`-Baum wirft **jedes** `catch`
weiter. Alles hier ist mechanisch und kann am Stück laufen. **Bewusst ausgenommen:** M8
(Kostenbasis-Provenance in `extract_freight.py`) — sitzt in `economics.py`, das ohnehin ersetzt wird.

- **`[M]` Mechanischer Restblock, in einem Rutsch erledigbar:** Kompositions-Zweig in
  `DrtConfigComposer:66` loggen (`if (multi.getModalElements().isEmpty())` überspringt sonst
  **stumm** die komplette HAGRID-Komposition — ServiceArea, Fleet-File, maxWaitTime, Rebalancing;
  feuert heute nicht) · Depot-Zonen-Fallback in `ReturnToDepotRebalancingModule:94-106` loggen ·
  `HagridPaths.copyIfMissing` gegen veraltete `shared/`-Inputs absichern · den Low-Tier-Sammelposten
  unten · Parse-Assertions für Shapefile/CSV. _(added 2026-07-27)_

- **`[M]` `ct_cep_size_s` im LMD-Flottenmix — nur noch Doku nachziehen.** Alle drei Van-Typen
  stehen jsprit zur Verfügung, `HagridPaths.java:336` dokumentiert aber „ct_cep_size_m / _l only".
  Gemessen trägt `_s` rund ein Drittel der Touren, ist also kein Randartefakt → **Doku korrigieren,
  `_s` bleibt.** Nebeneffekt: `LmdCarrierBuilder.jitterSigmaMinutes:217` gibt `_s` per Durchfall die
  15-Min-Sigma des „m"-Zweigs. Der Mix ist außerdem **seed-instabil bei konstanter Tourenzahl** →
  zusätzlicher Rauschkanal für die größenklassenabhängigen Emissionsfaktoren,
  [METHODS-LOG](METHODS-LOG.md) §2.1. _(added 2026-07-27, gemessen 2026-07-30)_

- **`[M]` Windows: Läufe sterben am eigenen offenen Logfile — Fix ist eine Zeile.**
  `initLogging()` legt `hagrid.log.dir` korrekt außerhalb des Output-Baums ab
  ([SimulationRunnerUtils.java:64-72](../hagrid/src/main/java/hagrid/core/simulation/SimulationRunnerUtils.java#L64)),
  `runSimulation` biegt es 220 Zeilen später wieder **hinein**
  ([:286-288](../hagrid/src/main/java/hagrid/core/simulation/SimulationRunnerUtils.java#L286)),
  wo `deleteDirectoryIfExists` die offene Datei nicht löschen kann. **Fix = die zweite Zuweisung
  entfernen oder gaten.** Nicht LMD-spezifisch (riss einen `DRT_MODULAR`-Lauf nach 19 min);
  Hannover-`BASECASE` empirisch nicht betroffen. Workaround erprobt und im Repo
  (`devlog/log4j2_dev.xml` + `vmargs_dev.txt`) → [BACKLOG-DONE](BACKLOG-DONE.md) 2026-08-17,
  deshalb `[M]`: nichts ist blockiert, aber jeder ohne Workaround läuft hinein.
  _(added 2026-07-30, Ursache lokalisiert 2026-08-16)_

- **`[M]` Depot-Zonenzuordnung ohne Warnung** — `ReturnToDepotRebalancingModule.java:94-106`:
  ein Depot außerhalb aller Rebalancing-Zonen hängt sich still an die nächstgelegene
  Zentroid-Zone. Zusammen mit `ReturnToDepotTargetCalculator.java:38` (`getOrDefault(zone, 0.0)`)
  zieht das die Abend-Flotte in die falsche Gitterzelle. Vorschlag: Fallback pro Depot loggen,
  Containment für In-Area-Depots assertieren. _(added 2026-07-27)_

- **`[M]` Analyse-Provenance: stiller Kostenbasis-Tausch + nie aufgefrischte `shared/`-Inputs** —
  (a) `extract_freight.py:~40-53`: jede Exception im Provider-Parse tauscht die Kostenbasis still
  auf die TSV-Spalten — andere Zahlen unter gleichem KPI-Namen; Vorschlag: `cost_basis`-
  Provenance-Zeile (analog zu den `meta`-Rows) und im Dashboard zeigen.
  (b) `HagridPaths.java:459-489` `copyIfMissing` aktualisiert nie und warnt bei fehlender Quelle
  nur — ein veraltetes `hagrid-output/shared/sim-config.xml` oder Zonen-Shapefile überlebt
  beliebig lange Input-Änderungen. Vorschlag: Hash/Mtime vergleichen, laut warnen.
  _(added 2026-07-27)_

---

## Low

- **`[L]` DRT-Ein-/Ausstiegs-Punkte: Passagier-ID im Hover/Popup** — beim Hovern über den
  nummerierten Pickup/Dropoff-Stops eines ausgewählten DRT-Fahrzeugs die Person(en) anzeigen,
  die dort ein-/ausgestiegen sind (wie im Legacy-Dashboard per Passagier-ID). Aktuell tragen die
  Stop-Records nur `lat/lon/t/n/kind` (`maps._attach_stops`, [maps.py:132](../analysis/lausitz/kpi/maps.py#L132))
  und der Badge-Marker hat gar kein Popup ([render_maps.py:128](../analysis/lausitz/kpi/render_maps.py#L128)).
  Machbar: die Personen-ID steht in der Quelle (`*.output_drt_legs_drt.csv` hat `personId`,
  `geometry.py` parst `person=` bereits) — nur bis in den Stop-Record + `bindPopup`/`bindTooltip`
  durchreichen. User-Wunsch 2026-07-20. _(added 2026-07-20)_

- **`[L]` Modul-Split (Restructure Schritt 4)** — Maven-Multi-Module `hagrid-core` / `hagrid-hannover`
  / `hagrid-lausitz`. Reiner Move-Refactor; `HagridPaths`-Root-Detection pro Szenario neu bauen.
  _(added 2026-07-14)_

- **`[L]` Inbound Rail-Commuter** — durch Home-Anchor-Clipping aktuell rausgeschnitten; expliziter
  Post-Baseline-Follow-up. _(added 2026-07-14)_

- **`[L]` Consolidated-Operator-Baseline (Phase-2-Variante)** — um den Konsolidierungs- vom
  Integrationseffekt zu trennen. Verwandt mit der EIN-Pool-Sensitivitätsidee unter `[H]` 1d.
  _(added 2026-07-14)_

- **`[L]` Szenario-Naming-Konvention aufräumen** — `LMD_BASELINE`/`requiresLausitz`; User war
  "not happy" damit. _(added 2026-07-14)_

- **`[L]` Phase-2-Deferrals (gesammelt)** — Packstationen/Locker (braucht Standortdaten),
  Ride-and-Collect, mobile-Packstation (Opt 1), verschiedene Van-Größen. Alle bewusst aufgeschoben
  → [METHODS-LOG](METHODS-LOG.md) §4.5. _(added 2026-07-14)_

- **`[L]` 1d-Modular-Testhygiene** — drei bewusst aufgeschobene Punkte: Fixture-Duplikation über
  fünf Modular-Testdateien (`buildNetwork()`, Idle-Muster, Splice-Setup) · Namensdrift `link()` vs.
  `fixtureLink()` · `ModularKpiHandler.logConservationViolationIfAny` hat **13 positionale
  `long`-Parameter** → `record` mit benannten Feldern. Regel für 1 und 2: erst anfassen, wenn eine
  dieser Dateien ohnehin substanziell geändert wird. Punkt 3 ist der heikelste, obwohl `[L]`: eine
  Vertauschung korrumpiert den Stolperdraht, und der ist (Design D7) die **einzige** Absicherung
  der Fracht-Buchhaltung. _(added 2026-07-29)_

- **`[L]` Laufzeit-Logzeile ist unlesbar — Python-Formatstring in einem SLF4J-Logger.**
  `SimulationRunnerUtils.java:712` schreibt `LOG.info("{} completed in {:02d}:{:02d}:{:02d}", …)`;
  SLF4J kennt nur `{}`, also warnt log4j („found 1 argument placeholders, but provided 4") und die
  Zeile erscheint wörtlich als `completed in {:02d}:{:02d}:{:02d}`. **Jeder** Lauf verliert damit
  seine Laufzeitangabe im Log — genau die Zahl, die man beim Planen von Ketten braucht (diese
  Session musste sie aus den Dateizeitstempeln rekonstruieren). Fix: vier `{}` und die Werte
  vorformatieren. _(added 2026-08-17)_

- **`[L]` ctrl1d-Dashboard: Modular-Badges ohne `*_pax`-Companion-Zeilen** — im
  Kontrollarm-Sonderfall (θ=1,0, null Exkursionen) erzeugt `freight_h<=0` planmäßig keine
  `*_pax`-Zeilen, die szenario-gegateten Badges erscheinen aber trotzdem → kosmetische
  Inkonsistenz „Badge ohne Frachtanteil" nur auf ctrl1d. Befund der Dashboard-Verifikation
  2026-07-30 (Fixwave-Ledger); alle anderen Runs unauffällig. _(added 2026-07-31)_

### Fallback-Audit 2026-07-27 (Low-Tier)

- **`[L]` Shapefile-/CSV-Parsing: „still zu 0/1"-Fallbacks absichern** — Stellen, an denen ein
  Schema-Wechsel Daten geräuschlos vernichtet statt zu scheitern; alle **heute nicht scharf**
  (gegen echten Output verifiziert), aber ungeschützt. `LmdDemandReader.java:87` (`asLong` → `0`
  auch bei fehlender DBF-Spalte: löscht die Nachfrage eines Providers komplett) ·
  `LmdDemandReader.java:46` (ohne `id`-Spalte heißt jede Delivery `"null_B2C"`, IDs nicht eindeutig)
  · `carriers_parse.py:96,200` (Default 1 Paket je Service, Kapazität 0 je Van) ·
  `freight_events.py:102` / `maps.py:292` / `extract_freight_provider.py:136`.
  **Fix:** Spaltenexistenz beim Parsen assertieren + Provider-Summen loggen. _(added 2026-07-27)_

- **`[L]` Kleine stille Defaults & irreführender toter Code** — Sammelposten, Anker 2026-08-17
  gegen den Code nachgezogen:
  - `PopulationClipper.java:27,36` — der „Home-Anker" ist faktisch „erste Aktivität *mit*
    Koordinate"; Personen ohne Koordinaten fallen unbemerkt aus dem Clip → Drops zählen/loggen.
  - `LmdCarrierBuilder.java:68,77` — `DEFAULT_DISPATCH_HOURS`/`DEFAULT_DELIVERY_RATE` sind
    unerreichbar (alle 7 Provider stehen in der Map), lesen sich aber wie aktive Konfiguration.
  - `SimulationRunnerUtils.java:68-70` und `:287` — die einzigen echten Exception-Swallows im
    Projekt; `:287` ist zugleich die Ursache der Log-Selbstblockade oben.
  - `SimulationRunnerUtils.java:184-190` — ein vertipptes `concept` fällt in
    `requiresLausitz=false` statt am Tippfehler zu scheitern.
  - `GeoUtils.java:367,383` — `Coord(0,0)`, wenn kein Service ein `coord`-Attribut hat.
  - `DrtNetworkPreparer.java` ist **toter Code** (kein Aufrufer); real läuft
    `PrepareNetwork.prepareDrtNetwork` (`LausitzDrtPreprocessor.java:80`) mit anderer Semantik.
    Nur `SimulationRunnerUtils.java:604` zitiert die Klasse irreführend als *Erzeuger*; die
    beiden Stellen in `LausitzFreightPreprocessor` (`:56`, `:239`) sind bloße Analogien.
  _(added 2026-07-27, Referenzen korrigiert 2026-08-17)_
