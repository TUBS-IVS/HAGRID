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
_Zuletzt aktualisiert: 2026-07-31._

---

## High

### `[H]` Shared-Use / Cargo-Hitching (Szenario 1c)

Minibus mit 2D-Kapazität (Sitze + Pakete), Online-DVRP-Insertion.
**Status: implementiert, in Validierungs-/Run-Phase** (2026-07-28). Implementierung, Reviews und
χ→0-Validierung sind durch → [BACKLOG-DONE](BACKLOG-DONE.md); Architektur- und
Parameter-Entscheidungen → [METHODS-LOG](METHODS-LOG.md) §1.1/§1.2.
→ [1c-Plan](superpowers/plans/2026-07-06-1c-shareduse-cargo-hitching.md) ·
[Spike](superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md) _(added 2026-07-14)_

- **✅ 1c-Lieferfenster auf 07:30–21:00 angehoben (2026-07-30)** — dabei fiel auf, dass auch die
  **Baseline** mit muss (fuhr 08:00–20:00, also enger als beide integrierten Szenarien → verzerrte
  den Zustellquoten-Vergleich zu ihren Lasten). Alle drei Arme hängen jetzt an einer Konstante
  `DeliveryDay` → [BACKLOG-DONE](BACKLOG-DONE.md), [METHODS-LOG](METHODS-LOG.md) §1.2.
- **`[H]` Offen: Baseline + 1c neu fahren** — Folge der Fenster-Vereinheitlichung. Betroffen:
  `base10c` (Baseline, 08:00–20:00), `chid600`/`chid600i` (1c, alte M5-Fenster) und alle älteren
  Baseline-Läufe. **Nicht** betroffen: `ctrl1d`/`m1d050` (fuhren 21:00 schon). Reihenfolge: erst
  Baseline neu (sie ist Referenz für 1c *und* 1d), dann die 1c-Punkte. _(added 2026-07-30)_
- **🔄 Läuft (gestartet 2026-07-28, Sim-PC, detached):** `chid600` (χ=600, neue
  Detour-only-Semantik, level_central, fleet120, iter150) → informiert die
  **χ-Sweep-Rasterentscheidung**; danach automatisch `base10c` (10-Sitzer married-Baseline,
  fleet120, iter150, jspritIter=100) = die 10-Sitze-Re-Baseline.
  ETA `chid600` ≈ 17:40–18:00 Uhr am 28.07., `base10c` grob Nacht 28./29.07.
- **Offen: χ-Sweep fahren** (M6 — Sweep statt Einzelpunkt), Raster nach `chid600` festlegen.
- **Offen: Shared-Use-Hälfte des Nachfrage-Bandes** — an den χ-Sweep hängen, kostet dort nur
  einen zusätzlichen Punkt. Nichtlinearität ist genau dort plausibel (χ-Gate: weniger Pakete →
  überproportional höheres δ).
- **Offen: PPC (Passenger-Parcel Compensation) prüfen** — vor der Evaluation entscheiden, ob der
  Mechanismus als Erweiterung reinkommt. Kontext, Zahlen und Quelle:
  [METHODS-LOG](METHODS-LOG.md) §4.1. _(added 2026-07-15)_
- **Offen: 10-Sitze-Re-Baseline** — läuft als `base10c` (s. o.). Voraussetzung für die
  Headline-Vergleiche von **1c und 1d**. Alte married-Runs (married120/250) fahren cap=8; der
  `DrtInputsFingerprint`-Guard blockiert einen Rerun darauf hart (`prepared=8 but run wants=10`)
  → braucht zwingend ein vorheriges `PrepareLausitzDrtInputs`. _(added 2026-07-20)_

### `[H]` Multi-Run-Aggregation für km-basierte KPIs

Der Rauschboden der jsprit-Heuristik auf der Fahrleistung ist **gemessen: 6,5 %** — km/Paket liegt
mit S/R 0,5× darunter, Kosten und Fahrzeugzahl tragen. Entschieden ist die Antwort
(**≥10 Sim-Runs, Mittelwert + Min/Max reporten**); die **Umsetzung fehlt** und ist im
Emissions-Plan bewusst ausgeklammert. Betrifft 1c, 1d, alle km-basierten KPIs und die
distanzbasierten Emissionen direkt.
Zahlen, Signal-Rausch-Tabelle, Seed-Schalter und der Nachbau-Gotcha:
[METHODS-LOG](METHODS-LOG.md) §2.1/§2.2/§1.5. _(added 2026-07-28)_

- Zu bauen: Run-Fächer über `-Dhagrid.jsprit.seed`, Aggregation (Mittelwert + Min/Max + Streuung)
  in die KPI-Extraktion, Ausweisung im Dashboard.
- **✅ Alternative „Iterations-Hochlauf" ist endgültig erledigt (gemessen 2026-07-30)** — und zwar
  aus dem besseren Grund: **sie wirkt nicht.** 3 Seeds @`jspritIter=1000` auf dem größten Carrier
  ergeben **7,61 % km-Spanne bei 0,00 % Tourenspanne** — also keine Verbesserung gegenüber dem
  6,5-%-Rauschboden bei 100 Iterationen. Damit ist **der Multi-Seed-Fächer die einzige Antwort**,
  nicht bloß die billigere. → [METHODS-LOG](METHODS-LOG.md) §2.1/§2.2/§3.8.
- **Reihenfolge-Präzisierung (2026-07-30):** für die **Kalibrierung** ist der Fächer *nicht*
  Voraussetzung — der χ-Sweep (1c) enthält überhaupt kein jsprit (`DRT_SHAREDUSE` überspringt es,
  `SimulationRunnerUtils:322`), und der θ-Sweep innerhalb eines Caps ist ein **gepaarter**
  Vergleich auf einer Realisierung (§2.17). Bänder braucht: absolute km-/Stunden-Niveaus, der
  Cap-Vergleich 12600↔25200, Headline-Vergleich und die distanzbasierten Emissionen.
- **✅ Vorgeschaltete Messung erledigt (2026-07-30)** — die Frage „kollabiert `jspritIter=1000` die
  km-Streuung?" ist mit **nein** beantwortet (s. o.). Der Paper-Runplan ist damit entschieden:
  Iterationen wie gehabt, **Streuung über Seeds ausweisen**. Reproduktionsrezept und Vorbehalte
  (n=3, ein Carrier, andere Maschine) → [METHODS-LOG](METHODS-LOG.md) §2.2.
- **1d explizit einbeziehen (Review 2026-07-29):** der 1d-Validierungsplan enthält null
  Replikation, obwohl alle Frachtgeometrie-KPIs am §2.1-Rauschboden hängen. Im Methods-Kapitel
  gepaart vs. ungepaart deklarieren: θ-Sweep innerhalb eines Caps = gepaart auf einer
  Realisierung (trägt), Cap-Vergleich 12600↔25200 = zwei unabhängige jsprit-Solves (trägt ohne
  Bänder nicht) → [METHODS-LOG](METHODS-LOG.md) §2.17.

### `[H]` Modular / U-Shift (Szenario 1d)

Kapsel-Tausch, Offline-jsprit + Pax-Priorität. **Status: Implementierung fertig (2026-07-28),
Ausführung offen.** 14 Tasks auf `hendrik`, jede mit eigenem Review-Pass und behobenen Findings,
volle Regression grün → [BACKLOG-DONE](BACKLOG-DONE.md). Offen bleiben die
**Run-Arbeit**: 10-Sitze-Re-Baseline, Idle-Threshold-Sweep, 7,0-h-Kontrollarm sowie die
Entscheidung zum prädiktiven Dispatch-Gate (θ_hist, s. Medium-Sektion) — dazu die
zurückgestellte Sensitivitätsidee unten. Die Nacharbeiten aus dem Paper-Readiness-Review
2026-07-29 sind **vollständig abgearbeitet** (2026-07-31, nächster Block); 1d ist damit
codeseitig paper-fertig, offen ist nur noch Rechenzeit.
POC läuft auf dem aktuellen PANDA-Stand;
die gematchte Baseline braucht es erst für die Paper-Runs.
→ [Plan](superpowers/plans/2026-07-27-1d-modular-capsule-swap.md) ·
[Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md) ·
[Spike](superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md)
Konzeptparameter und ihre Begründung: [METHODS-LOG](METHODS-LOG.md) §1.2.
_(added 2026-07-14, aktualisiert 2026-07-28)_

- **Paper-Readiness-Review 2026-07-29 — Fixwelle abgearbeitet.** Drei unabhängige
  Reviewer-Läufe (Java / Methodik / Python-KPI; Volltexte
  `.superpowers/sdd/2026-07-27-1d-modular-capsule-swap/paper-review/`, **untracked**) fanden keine
  Architektur-Defekte, nur Export-/Doku-/Test-Arbeit — abgearbeitet in einer eigenen 8-Task-Welle
  (Plan `superpowers/plans/2026-07-29-1d-paper-readiness-fixwave.md`). Erledigt: `[H]`
  δ-Zensur schließen, `[H]` Kontaminations-Fixwelle 2, `[M]` Test-Härtung, `[M]` Paper-Exporte
  (bis auf zwei Ausnahmen: B2B/B2C je Stop wurde durch die 21:00-Fenster-Entscheidung **obsolet**;
  stündliche Pax-Degradation ist als **Befund** notiert, nicht gebaut — `kpi_timeseries.csv` deckt
  sie schon ab, `drt_wait_mean`/Rejections liegen stündlich vor) sowie fast alle `[L]`-Einzeiler
  (Ausnahmen unten). Nachweis, Commits und Test-Endstände je Task →
  [BACKLOG-DONE](BACKLOG-DONE.md); Konsequenzen für die Zahlen →
  [METHODS-LOG](METHODS-LOG.md) §2.13/§2.14/§2.16/§2.18/§2.21–§2.23. Die vier danach noch
  offenen Nacharbeiten (Parkungen P1/P2, F2-Kommentar-Duplikat, Re-Routing) sind **am
  2026-07-31 erledigt** → [BACKLOG-DONE](BACKLOG-DONE.md).
  Die echte km-Korrektur der fahrzeugseitigen KPIs bleibt bewusst **kein** Backlog-Punkt —
  Entscheidung und Begründung stehen in [METHODS-LOG](METHODS-LOG.md) §2.14.
- **Zurückgestellte Sensitivitätsidee (User 2026-07-27):** jsprit-Tourplanung als **EIN Pool**
  (ein Carrier, Fahrzeuge an allen 7 Depots, freie Depotwahl je Paket = stärkstes
  Einheitsunternehmen) und/oder beide Varianten als Zerlegung Konsolidierungs- vs.
  Integrationseffekt. Verwandt mit `[L]` Consolidated-Operator-Baseline.

### `[H]` Nachhaltigkeitsparameter einbauen

Emissions-/CO₂-/Energie-KPIs und -Parameter ins Modell + Dashboard. Berührt Autonomie-Switch
(E-Antrieb) und die Kostenfunktion. **Status: Plan 2026-07-28 AUSGEFÜHRT (Tasks 1–9), 2026-07-31.**
→ [Plan](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md). Ergebnis: KPI-Gruppe
`environment` in `build_kpis` (drei Arme freight / freight_modular / drt, Diesel + BEV,
Non-Exhaust segmentdifferenziert, EV-Reichweiten-Sweep, `kpi_emissions_vehicles.csv`).
Methodenwahl, Klassenmapping, Systemgrenze und Caveats: [METHODS-LOG](METHODS-LOG.md)
§1.4/§2.7/§2.26–§2.29; Faktor-Provenance und Limitations-Rohtext:
`analysis/kpi/data/README.md`. _(added 2026-07-14, abgeschlossen 2026-07-31)_

- ~~**Konkrete Arbeitsschritte aus der Faktor-Sichtung**~~ ✅ 2026-07-31 vollständig in den Plan
  überführt und dort erledigt: Appendix-4-xlsx + `SOURCES.md`, Kapitel 1.A.3.b.vi–vii
  (Non-Exhaust) nachgeladen und segmentaufgelöst umgesetzt, DRT-Klasse ergänzt (M2 → N1-III,
  deklarierter Transfer), Kaltstart quantifiziert (siehe eigener Punkt unten).
  **Bleibt offen:** `_l`-Van optional einmal als HDT „Rigid ≤7,5 t" gegenrechnen (ausgewiesene
  Bandbreite statt versteckter Annahme) · Midi-Bus als Alternativsubstitution für die DRT-Flotte ·
  Multi-Seed-Aggregation über ≥10 Runs (Reporting-Werkzeug, erst bei der Paper-Auswertung).
- **`[H]` Kaltstart-Zuschlag implementieren (~0,5 d)** — die Bound-Rechnung aus Task 9 hat die
  5-%-Schwelle gerissen: NOx auf der Frachtseite **+5,6 %** (ta = 10 °C; Band 4,7–6,0 % über
  ltrip 8–15 km; +6,6 % bei 0 °C), DRT **+1,4 % je Kaltstart**. CO₂/Energie < 1 %, also unkritisch.
  Solange der Zuschlag fehlt, sind **alle berichteten NOx-Zahlen eine Untergrenze** — die
  Abweichung ist einseitig. Umsetzung: EMEP/EEA Gl. (10) in der Euro-6+-Fassung
  (β Tab. 3-39 · bc Tab. 3-46 · Q aus `COLD_EMISSIONS_PARAMETERS`), ein Kaltstart je Tour bzw.
  Fahrzeugtag, Kaltdistanz 3,5 km/Start. Formeln, Zahlen und der ausgewiesene ltrip-Transfer
  stehen fertig in `analysis/kpi/data/README.md` (Abschnitt „Kaltstart"). _(added 2026-07-31)_
- **`[M]` Ladefenster-Analyse für die DRT-Elektrifizierbarkeit (~0,5–1 d)** — die
  `ev_range_exceed_drt_*`-Zeilen sind **keine** Elektrifizierbarkeitsaussage: eine Freight-Tour
  ist eine zusammenhängende Schicht, ein DRT-Fahrzeugtag nicht. Auf `base10c` stehen 3,2 %
  (Fracht, je Tour) neben 96,7 % (DRT, je Fahrzeugtag) — nebeneinander gelesen ergibt das die
  falsche Schlussfolgerung. Die belastbare Größe ist der **längste Fahrblock zwischen zwei
  ausreichend langen STAY-Phasen**; die DVRP-Task-Events liefern beides bereits
  (`dvrpTaskStarted/Ended`, taskType STAY, im DRT-Event-Cache). Erst damit ist die Frage
  „ist DRT hier elektrifizierbar?" überhaupt beantwortbar. Bis dahin trägt jede Flotte ihre
  Einheit-Definition in der Provenance-Spalte. _(added 2026-07-31)_
- **⚠️ Constraint:** `hagrid_output_analysis/emissions.py` **nicht anfassen** (Kollegen-Paper,
  Abstimmung frühestens ~2026-08-11) → Kopie der tragenden Logik nach `analysis/kpi/`.
  Begründung: [METHODS-LOG](METHODS-LOG.md) §1.4. Eingehalten: der Emissionskanal liegt
  vollständig in `analysis/kpi/{emissions_emep,extract_emissions}.py`.
- **`[H]` EV-Reichweiten-Gate — FRACHTSEITE BEANTWORTET 2026-07-31, DRT-Seite offen.**
  Umgesetzt als **Sweep** (150/200/250 km) statt Einzel-Gate, weil das ursprünglich geplante
  250-km-Gate in **jedem** Lauf 0 % liefert und damit ein Nullresultat berichtet, das wie eine
  Prüfung aussieht. Befund Fracht: längste Tour 183 km über alle Läufe, bei 250 km 0 %, bei
  150 km 0–13,4 % → **nicht reichweitenbegrenzt**, EV darf Postprocessing bleiben (METHODS-LOG
  §1.4 hält). Die DRT-Seite ist mit diesen Zeilen **nicht** beantwortet (Fahrzeugtag ≠ Schicht)
  → eigener Punkt „Ladefenster-Analyse" oben.
  Ursprüngliche Einordnung, weiterhin gültig: **LMD = geringes Risiko** (eine Tour/Tag ≤7,5 h, Depot-Übernachtladung — CEP ist der
  Lehrbuchfall der Flotten-Elektrifizierung); **DRT = der riskante Kandidat** (ganztägiger Betrieb,
  ländliche Distanzen — aber Return-to-Depot-Rebalancing schafft natürliche
  Opportunity-Charging-Fenster).
  Eskalationspfad falls bindend: (a) Opportunity-Charging-Plausibilisierung über Idle-Fenster,
  (b) Flottenaufschlag/Schichttausch als Annahme, (c) echte Ladeinfrastruktur-Sim via
  MATSim-`ev`/`edrt`-Contrib (schwergewichtig, nur wenn (a)/(b) nicht tragen). _(added 2026-07-28)_
- **`[H]` BEV-Szenario + Planetary-Boundaries-Einbettung (SOS)** — volle Flotten-Elektrifizierung,
  konsistent über alle Szenarien → nur noch Non-Exhaust (Reifen/Bremse/Straße) + Strom-Vorkette;
  Auswertung als Safe-Operating-Space-Auslastung unter Planetary Boundaries. **Wäre der
  methodische Aufhänger des Papers.**
  **Aufwand ~5–8 Tage zusätzlich zur EMEP/EEA-Basis, KEINE neuen Sim-Runs** (reiner Faktortausch
  im Post-Processing; EV-Plumbing existiert: `EV_CLASSES`/`ENERGY_MJ_PER_KM`/`WTT_CO2E_G_PER_MJ`).
  Bausteine: (1) BEV-EC-Kurven aus Appendix 4 + Netz-CO₂-Intensität als Sensitivitätsparameter
  (~0,5–1 d, dominiert das Ergebnis); (2) Non-Exhaust-Modul aus Guidebook 1.A.3.b.vi–vii mit
  BEV-Korrektur (Mehrgewicht ↑Reifen, Rekuperation ↓Bremse; einzige lokale PM-Quelle im BEV-Arm,
  ~1 d); (3) Reichweiten-/Lade-Plausibilitätscheck + Limitations-Absatz „Elektrifizierung nur auf
  Emissionsebene" (~0,5 d); (4) **SOS-Layer = Hauptaufwand, Methodik nicht Code** (~3–5 d).
  Scope-Empfehlung und AESA-Literatur: [METHODS-LOG](METHODS-LOG.md) §4.4. _(added 2026-07-28)_

### `[H]` Kostenfunktion reviewen

`analysis/kpi/economics.py` ist ein **Platzhalter** — muss vor der Headline-Evaluation ein
belastbares Modell werden (autonomie-zerlegbar, Overhead). Solange das offen ist, stehen **alle
€-KPIs** unter Vorbehalt → [METHODS-LOG](METHODS-LOG.md) §2.6.
_(Prio-Vorschlag: eng an Nachhaltigkeit gekoppelt.)_ _(added 2026-07-14)_

- **Subtask: DRT-Kosten-KPI im v2-Dashboard klären + einbauen** — v2 hat aktuell **null**
  DRT-Kosten-KPIs (Passenger-Gruppe rein operativ). Widersprüchliche Werte auflösen: 150.000 €
  pauschal mit Verweis Currie/Fournier (User-Erinnerung) vs. 408.000 € Literatur-Benchmark /
  35,25 € pro Fahrt (Legacy-Python) vs. die 68 €/25 €-Platzhalterkarten. Aufgedeckt beim
  Plan-D-maps Task-10 §5-Legacy-Vergleich (married250). _(added 2026-07-17)_
- **Subtask: 1d-Kostenallokation (Review 2026-07-29):** `drt_cost_per_ride_placeholder` lädt auf
  einem 1d-Run 100 % der Flottenkosten auf die Passagiere, obwohl ein Teil der Flottenzeit
  Pakete zustellt — die Modularitätskosten je Fahrt sind überzeichnet, ein Fracht-Kostenkredit
  fehlt; Kapsel-/Swap-Station-Kapital und Handling fehlen ganz. Beim Neubau der Kostenfunktion
  die Allokationsregel Fracht↔Pax explizit entscheiden (Zeitanteile aus
  `drt_freight_hours_total` liegen vor). _(added 2026-07-29)_

---

## Medium

### Nachfrage-Modell (PANDA → HAGRID): offene Punkte

Das Nachfrage-Niveau-Band ist gemessen und der Proxy-Fix ist durch → Ergebnisse und
Zurückziehungen in [METHODS-LOG](METHODS-LOG.md) §1.3/§3.1/§3.2, Nachweise in
[BACKLOG-DONE](BACKLOG-DONE.md). Offen bleibt:

- **`[S]` Exogener Altersterm (K6) — vertagt, Einwohnerzahl bleibt wie sie ist**
  (User-Entscheidung 2026-07-29: erstmal lassen) — die CV-Batterie auf
  dem Zensus-Prädiktor (2026-07-29) erfüllt **beide** präregistrierten K6-Bedingungen: die
  exogene Variante kostet in-region nichts (−0,01 pp, verbessert minimal), und die gefittete
  Variante stimmt jetzt im Vorzeichen zu (`a65plus` = 0,59 × `a18_49` statt 1,5 ×). Nach der
  eigenen Regel wäre er zu übernehmen. **Dafür:** Präregistrierung einhalten, und die Lausitz
  ist +11,8 pp stärker 50+ — der Term korrigiert genau diesen Transferunterschied.
  **Dagegen:** die Zustimmung kommt aus einer Randlösung (`a50_64` genau 0) auf kollinearen
  Altersspalten, die gefittete Variante ist blind schlechter (+0,25/+0,42 pp), und der Effekt
  ist mit **−1,3 %** (mild) bzw. −1,8 % (strong) klein gegen das ±10-%-Band. **Kosten:**
  Neuexport der drei Level + Restaging + Band neu (3 × ~1 h 40) — praktisch dieselbe
  Entscheidung wie der Punkt unten, also am besten zusammen entscheiden.
  Details: `PANDA/docs/transferability.md` → B10, [METHODS-LOG](METHODS-LOG.md) §2.9.
  _(added 2026-07-29)_
- **`[S]` Bake-off-Doku nachziehen oder als OSM-Ära kennzeichnen** — `PANDA/docs/`
  `bakeoff_model_selection.md` ist der einzige Teil der Validierung, der *nicht* auf dem
  Zensus-Prädiktor neu gerechnet ist (die Batterie ist es, → §2.9). Der Verdikt-Teil
  („Demografie/Haushalte/Packstationen tragen kein übertragbares Signal") ist plausibel
  unberührt, aber die Zahlen sind alt. Entweder `studies/bakeoff.py` neu laufen lassen oder
  eine Kopfzeile setzen. _(added 2026-07-29)_
- **`[M]` Zustellquoten-Abweichung von −14 % aufklären** — systematisch in beiden Bandarmen,
  hebt sich in Vergleichen weg, macht aber jede *absolut* berichtete Zustellquote zu optimistisch.
  Einstieg: `CarrierGenerator.adjustDeliveryRatesConsideringB2B:252`,
  `determineMissedParcels:1049`, Sollwerte `HagridConfig.java:190-196`.
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

- **`[M]` KPI-Landschaft konsolidieren** — ein Konzept für Kontaminations-Marker,
  `*_pax`-Zusatzzeilen, `pax_only`-Overrides und Meta-Rows, bevor weitere Szenarien dazukommen
  (User 2026-07-29: „damit wir nicht irgendwann in den Dashboards ein KPI-Chaos haben").
  _(added 2026-07-29)_

- **`[M]` 1d: Prädiktiver Dispatch-Gate (θ_hist) aus Vor-Iterations-Nachfrage** — User-Idee
  2026-07-28 (1d-Grilling): dem Modular-Dispatcher historische Anfragen-/Rejection-Raten pro
  Zeitbin aus MATSim-Iteration n−1 mitgeben ("in der Praxis lägen dem Optimizer historische
  Daten vor"), damit der Gate nicht nur den Momentan-Idle-Share sieht, sondern die erwartete
  Pax-Bindung über den Tour-Horizont [now, now+3.5h]. Natives Vorbild existiert:
  `PreviousIterationDrtDemandEstimator` (MinCostFlow-Rebalancing) — Verkabelung ~1 Tag.
  Offener Kern = die Entscheidungsregel (Requests → erwartete Fahrzeugbindung → prognostizierter
  Idle-Share) + Caveats: Iteration 0 ohne Historie (Fallback plain θ), Feedback-Schleife
  Freight↔Rejections über Iterationen. **Entscheidung: erst nach den ersten 1d-Läufen mit dem
  einfachen θ-Gate** (User akzeptiert den 07:16-Surge bewusst, Ergebnisse ansehen). _(added 2026-07-28)_

- **`[S]` LMD 14:00-Welle quasi tot (Restpunkt des Dispatch-Stagger-Fixes)** — Zweitbefund der
  Root-Cause-Analyse 2026-07-30 (→ [BACKLOG-DONE](BACKLOG-DONE.md) „LMD Dispatch-Stunden besser
  streuen"): in `LMD_BASELINE_13052025_bandz_central_seed1234` fahren 61/62 Touren in der
  Morgenwelle (1× 13:54) — jsprit deckt bei `FleetSize.INFINITE` die gesamte Nachfrage mit
  Morgen-Klonen ab, weil die kleine Region ins Morgen-Betriebsfenster passt. Der `LmdTourRetimer`
  spreizt nur Abfahrten, er verschiebt keine Touren zwischen Wellen. Echte Zwei-Wellen-Struktur
  bräuchte `FleetSize.FINITE` mit Flottengrößen-Heuristik pro Welle (Option 2 der Analyse;
  User-Entscheidung 2026-07-30: Option 1/Retimer reicht vorerst, Baseline-Drift vermeiden).
  **Auf frischen Daten bestätigt (3-Seed-Sonde 2026-07-30): 19 von 19 Touren in der Morgenwelle,
  in allen drei Läufen — und zwar bereits mit dem auf 21:00 erweiterten Fenster.** Mehr Zeit am
  Abend hat die Nachmittagswelle also *nicht* wiederbelebt; das Argument „Region passt ins
  Morgenfenster" trägt unabhängig vom Fensterende.
  Beim Anfassen weiterhin: Delegate-Muster wahren (neue Parameter nur über neue Overloads).
  _(added 2026-07-30)_

- **`[M]` Run-Dashboard v2 — Plan D (Karten) + zurückgestellte KPIs** — B ✅ (gepusht), C ✅ (lokal),
  Plan-D **Visual-Polish** ✅ (lokal, 2026-07-16). **Offen:**
  (a) Plan D **Karten** (depot-siting / vehicle-tours in die Tabs);
  (b) zwei bewusst verschobene Datenschicht-KPIs: **`occ_km`** = gefahrene km je Besetzungslevel
  (braucht Netzwerk-km-Rekonstruktion; Legacy `dist_by_occ` aus `build_drt_dashboard.py`
  portieren — Skript geloescht 2026-07-29, Stand in Git-Historie: Parent von
  b639ff30198eafa692b151705be4d073d6f98a5d — `_occ_chart` rendert die Serie bereits, sie wird nur
  nicht emittiert) und
  **ausgelieferte Pakete/h** (Join Freight-Service-actstart-Event → Carrier-Plan-Demand;
  `_hourly_provider_stack` hängt schon bereit);
  (c) **1d-Modular-Karten, sobald 1d-Runs existieren:** C8-Late-Metriken
  (`tours_completed_late`/`parcels_served_late`) + δ-Dekomposition aus `extract_modular.py`
  (User 2026-07-28: „ist auch was fürs Dashboard").
  Subagent-driven empfohlen. → Pläne
  [B](superpowers/plans/2026-07-13-run-dashboard-v2-planB-java-trigger.md) /
  [C](superpowers/plans/2026-07-13-run-dashboard-v2-planC-rendering.md) /
  [D-Polish](superpowers/plans/2026-07-16-run-dashboard-v2-planD-visual-polish.md) /
  [D-Karten](superpowers/plans/2026-07-13-run-dashboard-v2-planD-maps.md)
  _(added 2026-07-14, aktualisiert 2026-07-28)_

- **`[M]` Carrier-Parallelisierung in `routeWithDurationCap`** — die 7 Lausitz-Carrier werden in
  einer sequenziellen `for`-Schleife auf dem Main-Thread gelöst
  ([LausitzFreightPreprocessor.java:313](parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java#L313));
  im Log laufen alle sieben hintereinander auf `HAGRIDSimulationRunner.main()`. Parallel fällt die
  Wall-Clock auf den größten Carrier: **10,7 h → ~3,7 h** @`jspritIter=1000`
  (Messung → [METHODS-LOG](METHODS-LOG.md) §2.2). Obergrenze ist damit ~2,9× (Amdahl: Carrier 1
  ist 35 % der Phase) → 3–4 Threads genügen, mehr bringt nichts.
  **Voraussetzungen geprüft 2026-07-30 (beide grün):**
  (a) *Determinismus bleibt* — jeder Carrier baut seinen eigenen Algorithmus und bekommt eine
  **frische `Random`-Instanz** (`HAGRIDRouterUtils.applySeedOverride:233`, `builder.setRandom(new
  Random(seed))`; das Javadoc hält den Per-Carrier-Charakter explizit fest). Kein geteilter RNG →
  kein Ordering-Effekt.
  (b) *Der geteilte `NetworkBasedTransportCosts` ist thread-safe by design* — Klassen-Javadoc
  „can be used with multiple threads", `costCache` und `routerCache` sind `ConcurrentHashMap`,
  Router-Instanzen pro `Thread.threadId()`.
  **⚠️ Eine reale Lücke:** `NetworkBasedTransportCosts:514` `matsimVehicles` ist eine **plain
  `HashMap`**, die im Kostenpfad lazy beschrieben wird (`:796` get / `:801` put) — unsynchronisiert.
  Werte sind idempotent aus `typeId` abgeleitet (also keine falschen Distanzen), aber ein
  gleichzeitiger Resize kann Einträge verlieren oder werfen. Liegt in **unserem Fork** →
  Einzeiler `HashMap`→`ConcurrentHashMap` als Teil dieses Punktes mitfixen (und upstream melden).
  **Für den Seed-Fächer ist Prozess-Parallelität die bessere Wahl** (N Seeds = N unabhängige JVMs,
  kein geteilter Zustand); Carrier-Parallelität lohnt für die *Latenz eines einzelnen* Laufs.
  _(added 2026-07-30)_

- **`[M]` jsprit-Upgrade 1.8 → 2.x + modulare Operatorauswahl** — Freight-VRP läuft auf
  `jsprit.version=1.8` (`pom.xml:24`), Default-Algorithmus (Algorithm-File im Config leer),
  aufgerufen via `CarriersUtils.runJsprit`. Idee (Kollege Chatty): auf 2.0 heben — mehr Ruin-/
  Insertion-Operatoren + unabhängige Operatorauswahl pro Iteration. Richtung ist sinnvoll: bessere
  VRP-Qualität wirkt direkt auf die Tourgeometrie, also genau auf das, was am stärksten rauscht
  (→ [METHODS-LOG](METHODS-LOG.md) §2.1). Innerhalb jsprit zu bleiben statt VROOM ist der klar
  risikoärmere Schritt. _(added 2026-07-16, verifiziert + neu eingeordnet 2026-07-20)_
  - ✅ **Verifiziert 2026-07-20:** jsprit **2.0.0 existiert** als Vollrelease
    (`com.graphhopper:jsprit-core:2.0.0`); verlangt **Java 21** — HAGRID ist schon auf 21
    (`pom.xml:19-21`), kein Blocker; die **Fluent-API** (`addRuinOperator(w, Ruin.…)`,
    `Insertion.regretFast()`) ist real inkl. Regret-k-Insertion = genau Chattys Ziel;
    **MATSim-Upstream steht selbst noch auf 1.8** → der Core-Bump 2025.0 zieht jsprit **nicht** mit,
    beide Punkte sind unabhängig.
  - ⚠️ **Hauptaufwand (korrigiert frühere Annahme → daher `[L]`-Aufwand, nicht `[M]`):** HAGRID
    nutzt die jsprit-API in **~16 Java-Dateien** direkt und tief (eigene `HardActivityConstraint`/
    `SoftActivityConstraint`, `VehicleRoutingActivityCosts`/`VehicleRoutingTransportCosts`,
    `StateUpdater`/`StateManager`, `ConstraintManager`; u. a. `MaxRouteDurationConstraint`,
    `UTurnSoftConstraint`, `ZoneBasedTransportCosts`, `JspritCarrierTask`) — genau diese
    SPI-Interfaces brechen beim Major-Bump. **Plus:** weil Upstream auf 1.8 bleibt, kompiliert
    MATSims freight-contrib selbst nicht gegen 2.0 → der komplette contrib müsste im Fork
    (`external/matsim-libs/contribs/freight`) portiert **und die Divergenz dauerhaft gepflegt**
    werden.
  - **Vorgehen: zeitboxierter Spike (½–1 Tag) ZUERST**, bevor irgendetwas portiert wird —
    **(i)** Reicht schon 1.8? Property-basierte Strategie-Gewichte via
    `Jsprit.Parameter`/`Jsprit.Strategy` **oder** der Algorithm-XML-Hook (existiert im Config
    bereits) geben Operator-Diversität evtl. ohne jeden Bump (→ 80 % Nutzen, 0 Fork-Port-Last).
    **(ii)** Falls doch 2.0: Kompilier-Spike gegen 2.0.0, um den Fork-Port real zu messen.
    Erst danach Go/No-Go.
  - **Bei tatsächlichem Bump:** berührt `pom.xml` (`jsprit.version`) + drei POMs (root,
    parcel-pipeline, Fork-freight); braucht freight-272-Regression **plus** Re-Run-Vergleich
    married250 (Kosten-/Routen-KPIs verschieben sich → alle Dashboards neu baseline).
    Determinismus: Operator-Gewichte + Seed klären.

- **`[M]` Case-Study-Area erweitern** — Ruhland-Korridor-Entscheidung (aktuell dropped als
  Kurzfix wegen Orphan-Polygon). **Entschieden: bleibt zunächst Hoyerswerda, Erweiterung erst
  ~2027** → Begründung und Rahmenbedingung in [METHODS-LOG](METHODS-LOG.md) §4.6.
  _(added 2026-07-14, aktualisiert 2026-07-20)_
  - **Re-Implementierung „Bahn-Zubringer"-Kartenlayer** — der Layer (Bahnhaltestellen im
    Bediengebiet, gefärbt/skaliert nach Zahl der DRT-Ausstiege im 600-m-Umkreis) wurde
    **2026-07-20 entfernt** (Checkbox + JS aus `render_maps.py`, Producer `maps._rail_stops` +
    `FEED_RADIUS_M` + ungenutzte `gzip`/`ET`-Imports). Er war auf married250 ohnehin leer
    (`rail_stops` fehlte im `map_data.json` — Producer gab still `None` zurück, Ursache nie
    isoliert). **Wieder einbauen, wenn die Case-Study-Area erweitert wird**; dabei die
    stille-`None`-Ursache mit-fixen und den Layer nur zeigen, wenn Daten vorhanden.
    Port-Referenz: Legacy `build_drt_dashboard.py:260-289` (Skript geloescht 2026-07-29, Stand in
    Git-Historie: Parent von b639ff30198eafa692b151705be4d073d6f98a5d). _(added 2026-07-20)_

- **`[M]` hagrid-input Bootstrap (Restructure Schritt 3)** — ~156 MB, größtenteils untracked;
  letzter manueller Transfer-Schritt für "läuft auf jedem neuen PC". Geplant:
  Download-on-first-run mit URL-Liste + Checksums; HAGRID-only-Dateien via
  Uni-Share/Release-Assets. _(added 2026-07-14)_

- **`[M]` Autonomie-Switch-Plan** — Labour aus / Roboter-Dwell / Speed-Cap / Autobahn-Ausschluss,
  orthogonal über beide integrierten Szenarien. **User-Entscheidung 2026-07-30: nicht von
  unmittelbarer Relevanz — bleibt liegen** (Plan weiter erst nach 1c+1d).
  ⚠️ Das „§4.4" dieses Themas meint die **Design-Spec**, nicht METHODS-LOG §4.4 (= AESA/SOS) —
  → [Design-Spec §4.4](superpowers/specs/2026-06-17-lausitz-drt-freight-integration-design.md).
  - **Vorarbeit ✅ 2026-07-30:** `IntegratedScenarioConfig` ist auf genau diese vier Effekte
    eingedampft (sieben duplizierte Konzeptparameter raus) und deklariert sich selbst als
    unverdrahtet → [BACKLOG-DONE](BACKLOG-DONE.md).
  - **Offen: der Rest-Kern.** Er erreicht weiter keinen Run. Kommt der Switch, ist die Klasse sein
    Integrationsort; kommt er nie, ist sie zu **löschen** — es geht nichts verloren, alle
    Begründungen (Rudolph-80/20-Anker, U-Shift-30-km/h-Floor, Sensitivität → 50 km/h,
    Motorway-Ausschluss) stehen in der Design-Spec §4.4/§6.1/§6.3.
  - **Kein Config-Verdrahtungs-Task** — die vier Effekte landen an vier verschiedenen Stellen
    (Netz-Preprocessing, jsprit, DRT-Stop-Dauer, Kostenfunktion); Aufwandsschwerpunkt und
    Begründung: [METHODS-LOG](METHODS-LOG.md) §1.1.
  _(added 2026-07-14, aktualisiert 2026-07-30)_

- **`[M]` Karten-Dropdowns/-Controls noch nicht manuell durchgeklickt** — Plan-D-maps Task 9 hat
  nur Ladezeit/Größe im Browser bestätigt (6.1 MB, married250, "fast & responsive"); die
  interaktiven Elemente selbst (DRT-Fahrzeug-`<select>` inkl. 227 Einträgen + Stop-Badges, LMD
  Touren/Stopps/Heatmap-Modus-Radio, Provider/Carrier/Vehicle-Filter, Depot/Rail/Heat-Checkboxen,
  Light/Dark-Tile-Wechsel) sind noch **nicht** einzeln durchgeklickt/reviewt. Vor "fertig" fürs
  Kartenfeature: einmal jeden Dropdown/Filter/Toggle auf dem married250-Dashboard durchgehen.
  _(added 2026-07-17)_

### Fallback-Audit 2026-07-27 (Medium-Tier)

Ergebnis eines gezielten Durchgangs durch `hagrid/integrated/**` + `analysis/kpi/` nach
Fallbacks, die greifen statt zu scheitern und dabei still falsche Zahlen erzeugen. Die vier
scharfen Befunde sowie M2 und M6 sind erledigt → [BACKLOG-DONE](BACKLOG-DONE.md); hier stehen
die verbleibenden. Positiv-Befund am Rande: im gesamten `integrated`-Baum wirft **jedes** `catch`
weiter — dort gibt es kein Exception-Swallowing.

**Reihenfolge:** ein Punkt hängt an einer Nutzer-Entscheidung (unten mit **ENTSCHEIDUNG OFFEN**);
der Rest ist mechanisch und kann jederzeit am Stück laufen. Der frühere zweite
(`IntegratedScenarioConfig`) ist 2026-07-30 in der Substanz erledigt — der Restentscheid hängt am
`[M]` Autonomie-Switch-Plan und steht dort.

- **`[M]` Mechanischer Restblock (keine Entscheidung nötig)** — in einem Rutsch erledigbar:
  Kompositions-Zweig in `DrtConfigComposer:63` loggen · Depot-Zonen-Fallback in
  `ReturnToDepotRebalancingModule:94-106` loggen · `HagridPaths.copyIfMissing` gegen veraltete
  `shared/`-Inputs absichern · der Low-Tier-Sammelposten (tote Defaults in `LmdCarrierBuilder`,
  `PopulationClipper`-Anker-Semantik, geschluckte Log-Verzeichnis-Exceptions,
  `parseScenario`-Typo-Fallback, `GeoUtils` `Coord(0,0)`, irreführende
  `DrtNetworkPreparer`-Kommentare) · Parse-Assertions für Shapefile/CSV.
  Jeder Einzelpunkt steht unten bzw. unter Low mit `file:line`.
  **Bewusst ausgenommen:** M8 (Kostenbasis-Provenance in `extract_freight.py`) — das sitzt in
  `economics.py`, das laut `[H]` Kostenfunktion ohnehin ersetzt wird. _(added 2026-07-27)_

- **`[M]` Ungeschützter Kompositions-Zweig im DRT-Config-Aufbau** — `DrtConfigComposer.java:63`
  `if (multi.getModalElements().isEmpty())`: bringt die Base-Config je ein `drt`-Element mit, wird
  die **komplette** HAGRID-Komposition übersprungen (ServiceArea, Fleet-File, maxWaitTime,
  Rebalancing, ExtensiveInsertionSearch) und die Base-Werte laufen. Feuert heute nicht, loggt aber
  auch nichts. Vorschlag: gewählten Zweig loggen, oder werfen wenn nicht-leer aber die erwarteten
  Keys fehlen. _(added 2026-07-27)_

- **`[M]` `ct_cep_size_s` im LMD-Flottenmix — Entscheidung faktisch gefallen, Doku nachziehen.**
  `lmd-vehicle-types.xml` enthält drei Van-Typen (`_s`=100 / `_m`=165 / `_l`=230 Pakete) und alle
  drei stehen jsprit zur Verfügung, obwohl `HagridPaths.java:337` „ct_cep_size_m / _l only"
  dokumentiert. **Auf frischen Daten gemessen (3-Seed-Sonde 2026-07-30, Touren je Typ):**
  `_s` trägt **6–8 von 19** Touren, `_m` 10–13, `_l` nur **0–1**. `_s` ist damit kein Randartefakt,
  sondern rund ein Drittel des Arms — die Doku ist einfach falsch, `_s` bleibt. **Zu tun:** nur noch
  `HagridPaths.java:337` korrigieren; ein Entfernen aus der Typdatei stünde einem realen Drittel des
  Flottenmix entgegen. Nebeneffekt bleibt: `LmdCarrierBuilder.jitterSigmaMinutes:160-163` gibt `_s`
  per Durchfall die 15-Min-Sigma des „m"-Zweigs.
  **Und ein neuer Befund:** der Mix selbst ist **seed-instabil** bei konstanter Tourenzahl
  (19/19/19) → zusätzlicher Rauschkanal für die größenklassenabhängigen Emissionsfaktoren,
  → [METHODS-LOG](METHODS-LOG.md) §2.1. _(added 2026-07-27, gemessen 2026-07-30)_

- **`[M]` Windows: `LMD_BASELINE` stirbt nach jsprit am eigenen Logfile** — MATSims
  `OutputDirectoryHierarchy` löscht mit `deleteDirectoryIfExists` sein Output-Verzeichnis, in dem
  HAGRID zuvor sein eigenes **offenes** `logs/hagrid.log` angelegt hat (`SimulationRunnerUtils`
  Log-Setup, Pfad `hagrid-matsim-output/<runId>_iter…/logs/hagrid.log`). Auf Windows ist eine
  offene Datei nicht löschbar → `FileSystemException` → `CreationException: Unable to create
  injector`. **Alle drei Sondenläufe 2026-07-30 sind daran gestorben** — die jsprit-Phase war
  jeweils fertig und das routed Carrier-XML geschrieben, die Messung also unbeschädigt.
  ⚠️ **Zu prüfen, bevor jemand einen LMD-Lauf plant:** das müsste **jeden** `LMD_BASELINE`-Lauf auf
  Windows treffen. Verdacht: frühere LMD-Läufe sind an derselben Stelle gestorben, ohne dass es
  auffiel, weil das gewünschte jsprit-Ergebnis zu dem Zeitpunkt schon auf der Platte lag. Fix-Idee:
  HAGRIDs Log-Verzeichnis **außerhalb** des MATSim-Output-Verzeichnisses anlegen (oder den Appender
  erst nach `new Controler(...)` anhängen). _(added 2026-07-30)_

- **`[M]` Depot-Zonenzuordnung ohne Warnung** — `ReturnToDepotRebalancingModule.java:94-106`:
  ein Depot außerhalb aller Rebalancing-Zonen hängt sich still an die nächstgelegene
  Zentroid-Zone. Zusammen mit `ReturnToDepotTargetCalculator.java:38` (`getOrDefault(zone, 0.0)`)
  zieht das die Abend-Flotte in die falsche Gitterzelle. Vorschlag: Fallback pro Depot loggen,
  Containment für In-Area-Depots assertieren. _(added 2026-07-27)_

- **`[M]` Analyse-Provenance: stiller Kostenbasis-Tausch + nie aufgefrischte `shared/`-Inputs** —
  (a) `extract_freight.py:~40-53`: jede Exception im Provider-Parse tauscht die Kostenbasis still
  auf die TSV-Spalten — andere Zahlen unter gleichem KPI-Namen; Vorschlag: `cost_basis`-
  Provenance-Zeile (analog zu den `meta`-Rows) und im Dashboard zeigen.
  (b) `HagridPaths.java:478-489` `copyIfMissing` aktualisiert nie und warnt bei fehlender Quelle
  nur — ein veraltetes `hagrid-output/shared/sim-config.xml` oder Zonen-Shapefile überlebt
  beliebig lange Input-Änderungen. Vorschlag: Hash/Mtime vergleichen, laut warnen.
  _(added 2026-07-27)_

---

## Low

- **`[L]` DRT-Ein-/Ausstiegs-Punkte: Passagier-ID im Hover/Popup** — beim Hovern über den
  nummerierten Pickup/Dropoff-Stops eines ausgewählten DRT-Fahrzeugs die Person(en) anzeigen,
  die dort ein-/ausgestiegen sind (wie im Legacy-Dashboard per Passagier-ID). Aktuell tragen die
  Stop-Records nur `lat/lon/t/n/kind` (`maps._attach_stops`, [maps.py:126](parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py#L126))
  und der Badge-Marker hat gar kein Popup ([render_maps.py](parcel-demand-2-matsim-pipeline/analysis/kpi/render_maps.py#L143)).
  Machbar: die Personen-ID steht in der Quelle (`*.output_drt_legs_drt.csv` hat `personId`,
  `geometry.py` parst `person=` bereits) — nur bis in den Stop-Record + `bindPopup`/`bindTooltip`
  durchreichen. User-Wunsch 2026-07-20. _(added 2026-07-20)_

- **`[L]` Modul-Split (Restructure Schritt 4)** — Maven-Multi-Module `hagrid-core` / `hagrid-hannover`
  / `hagrid-lausitz`. Reiner Move-Refactor; `HagridPaths`-Root-Detection pro Szenario neu bauen.
  _(added 2026-07-14)_

- **`[L]` Human Visual Pass der married120-Dashboards** — Rendering (Light/Dark, Tab-Wechsel,
  Label-Kollisionen, H-Scroll) kann kein Reviewer aus dem Diff prüfen. _(added 2026-07-14)_

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

- **`[L]` 1d-Modular-Testhygiene (aus der Whole-Branch-Review verschoben)** — drei Punkte, die
  bewusst *nicht* im Review-Fix-Wave erledigt wurden, hier abgelegt statt in einem Arbeits-Ledger,
  das kein BACKLOG-Leser je öffnet (sonst sind sie nicht aufgeschoben, sondern verloren):
  1. **Fixture-Duplikation über fünf Modular-Testdateien** — `buildNetwork()`, das
     `fixtureVehicle`-Idle-Muster und das Splice-Setup stehen jeweils mehrfach
     (`ModularTest`, `ModularTourDispatcherTest`, `ModularTourSchedulerTest`,
     `ModularOptimizerTest`, `ModularStayTaskEndTimeCalculatorTest`). **Jetzt bewusst nicht
     refaktoriert:** fünf angefasste, grüne Testdateien für null Verhaltensgewinn ist der falsche
     Tausch; erst anfassen, wenn ohnehin eine dieser Dateien substanziell geändert wird.
  2. **Namensdrift `link()` vs. `fixtureLink()`** für dieselbe Rolle in denselben Testdateien.
  3. **`ModularKpiHandler.logConservationViolationIfAny` hat 13 positionale `long`-Parameter** —
     eine Vertauschung korrumpiert nur die Stolperdraht-Prüfung, nicht die veröffentlichten
     Zahlen. Genau deshalb aber heikel: dieser Stolperdraht ist die **einzige** Absicherung der
     Fracht-Buchhaltung, weil es (Design D7) keinen zweiten Ereignisstrom zum Gegenrechnen gibt.
     Vorschlag: ein `record` mit benannten Feldern statt der Parameterliste.
  _(added 2026-07-29)_

### Fallback-Audit 2026-07-27 (Low-Tier)

- **`[L]` Shapefile-/CSV-Parsing: „still zu 0/1"-Fallbacks absichern** — vier Stellen, an denen
  ein Schema-Wechsel Daten geräuschlos vernichtet statt zu scheitern. Alle sind **heute nicht
  scharf** (gegen echten Output verifiziert), aber ungeschützt:
  `LmdDemandReader.java:87` `asLong` gibt bei Nicht-Number **und bei fehlender DBF-Spalte** `0`
  zurück → eine umbenannte/als Text typisierte Spalte löscht die Nachfrage eines Providers
  komplett; `LmdDemandReader.java:46` `String.valueOf(getAttribute("id"))` → ohne `id`-Spalte
  heißt jede Delivery `"null_B2C"` (der Kommentar in `ParcelAgentGenerator:66` bestätigt, dass es
  keine gibt), Delivery-IDs sind also nicht eindeutig; `carriers_parse.py:96,200`
  (`capacityDemand` Default 1, `capacity other=` Default 0.0) → ein Writer-Schemawechsel machte
  jeden Service zu 1 Paket und jeden Van zu Kapazität 0; `freight_events.py:102` /
  `maps.py:292` `demand.get(sid, 1)` und `extract_freight_provider.py:136` `get(cid, "other")`.
  Vorschlag: Spaltenexistenz beim Parsen assertieren + Provider-Summen loggen.
  _(added 2026-07-27)_

- **`[L]` Kleine stille Defaults & irreführender toter Code** — Sammelposten:
  `PopulationClipper.java:36-47` — der „Home-Anker" ist faktisch „erste Aktivität *mit*
  Koordinate", Personen ganz ohne Koordinaten fallen unbemerkt aus dem Clip (Drops zählen/loggen);
  `LmdCarrierBuilder.java:89,128` — `DEFAULT_DELIVERY_RATE` und `DEFAULT_DISPATCH_HOURS` sind
  beide unerreichbar (alle 7 Provider stehen in der Map), lesen sich aber wie aktive Konfiguration;
  `SimulationRunnerUtils.java:238` und `:64-73` — die einzigen echten Exception-Swallows im
  Projekt (Log-Verzeichnis); `SimulationRunnerUtils.java:151-155,168-174` — ein vertipptes
  `concept` fällt in `requiresLausitz=false` statt am Tippfehler zu scheitern;
  `GeoUtils.java:363-393` — `Coord(0,0)` wenn kein Service ein `coord`-Attribut hat;
  `DrtNetworkPreparer.java` ist **toter Code**, wird aber in drei Kommentaren
  (`LausitzFreightPreprocessor.java:52,163`, `SimulationRunnerUtils.java:494`) als die lebende
  Implementierung zitiert — real läuft `PrepareNetwork.prepareDrtNetwork`
  (`LausitzDrtPreprocessor.java:80`) mit anderer Semantik (Vollnetz bleibt, drt kommt auf
  Car-Links). _(added 2026-07-27)_
