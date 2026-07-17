# HAGRID Backlog

Zentrale Sammelstelle für offene, **nicht akut verfolgte** Arbeitspunkte quer über die
HAGRID- und Lausitz-DRT-Freight-Projekte. Sachen, die man "irgendwann mal hat", aber nicht
aktiv weiterführt, weil gerade nicht 100 % Prio — hier gehen sie nicht verloren.

**Abgrenzung:** aktiv laufende Arbeit lebt in den Specs/Plänen unter `docs/superpowers/`.
Dieses Dokument ist die grobe Roadmap darüber; Detail-Kontext steht im jeweils verlinkten Plan.

**Prioritäten:** `[H]` High · `[M]` Medium · `[L]` Low.
User-bestätigt High (2026-07-14): **Shared-Use, Modular, Nachhaltigkeit**. Alle anderen
Einstufungen sind mein Vorschlag und jederzeit anpassbar.

**Pflege:** wird im Arbeits-Workflow mitgepflegt — aufgeschobene Punkte und neue Findings
kommen mit Datum hier rein; Erledigtes wandert nach `## Erledigt` (kurzer Nachweis) oder wird
gestrichen. _Zuletzt aktualisiert: 2026-07-17._

---

## High

- **`[H]` Shared-Use / Cargo-Hitching (Szenario 1c)** — Minibus mit 2D-Kapazität (Sitze+Pakete),
  Online-DVRP-Insertion. Plan geschrieben, **noch nicht ausgeführt**.
  Gate: MATSim-Core-Bump `2025.0-PR3552` → `2025.0` (siehe eigener Punkt unten). Vor Ausführung:
  unterbrochenen Grilling-Pass auf dem Plan fortsetzen.
  → [1c-Plan](superpowers/plans/2026-07-06-1c-shareduse-cargo-hitching.md), [Spike](superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md)
  _(added 2026-07-14)_
  - **Design-Input: Passenger-Parcel Compensation (PPC)** — Profit-Redistribution-Mechanismus
    (Calabro et al. 2026): Fahrgäste, die paketbedingte Umwege tolerieren, werden aus dem
    Netto-Gewinn der Paketintegration per Fahrpreis-Rabatt entschädigt
    (PPC = (Netto-Gewinn − aggregierter Fahrgast-Diskomfort) / Fahrgastzahl). Adressiert
    Nutzerakzeptanz — eine der zwei Standing-Barrieren für Passenger-Freight-Integration
    (die andere: Regulierung). Synthetisches Fallbeispiel: PPC ≈ 0,11–0,24 €/Fahrgast bei
    100 Paketen/Tag (500 Fahrgastanfragen/Tag); bei 200 Paketen/Tag steigt PPC auf
    ≈ 0,21–0,28 €, aber Zustellerfolgsquote fällt auf 70–76 % (Zuverlässigkeitsproblem).
    Autoren empfehlen öffentliche statt Operator-Finanzierung. HAGRIDs Shared-Use-Szenario
    misst bislang nur operative Machbarkeit, keine Fahrgast-Kompensation — vor der
    Evaluation als Erweiterung prüfen. _(added 2026-07-15)_

- **`[H]` Modular / U-Shift (Szenario 1d)** — Kapsel-Tausch, Offline-jsprit + Pax-Priorität.
  **Plan noch nicht geschrieben** (soll nach dem 1c-Plan entstehen, erbt Infra-Entscheidungen von 1c).
  _(added 2026-07-14)_

- **`[H]` Nachhaltigkeitsparameter einbauen** — Emissions-/CO₂-/Energie-KPIs und -Parameter
  ins Modell + Dashboard. Noch **kein Design/Spec** — Brainstorming-Kandidat. Berührt Autonomie-Switch
  (E-Antrieb) und die Kostenfunktion.
  _(added 2026-07-14)_

- **`[H]` Kostenfunktion reviewen** — `analysis/kpi/economics.py` ist ein **Platzhalter**
  (25 €/Fahrzeug-Schicht-h = 20 Arbeit + 5 Fahrzeug); DRT-Dashboard hat zwei Platzhalter-Karten
  (Bottom-up 25 € / Literatur-Benchmark 68 €). Muss vor der Headline-Evaluation ein belastbares
  Modell werden (autonomie-zerlegbar, Overhead). _(Prio-Vorschlag: eng an Nachhaltigkeit gekoppelt.)_
  _(added 2026-07-14)_
  - **Subtask: DRT-Kosten-KPI im v2-Dashboard klären + einbauen** — v2 hat aktuell **null**
    DRT-Kosten-KPIs (Passenger-Gruppe rein operativ). Legacy-Python zeigte "Kosten Literatur-Benchmark"
    408.000 € / 35,25 €-pro-Fahrt; User erinnert einen pauschalen Ansatz **150.000 € mit Verweis auf
    Currie/Fournier** — welcher Wert/welche Quelle stimmt (150k pauschal vs 408k Benchmark vs die
    68 €/25 €-Platzhalter oben)? Einmal sauber klären, dann als Teil der Kostenfunktion in v2
    aufnehmen. Aufgedeckt beim Plan-D-maps Task-10 §5-Legacy-Vergleich (married250). _(added 2026-07-17)_

## Medium

- **`[M]` MATSim-Core-Bump `2025.0-PR3552` → `2025.0`** — kleinster Upgrade-Schritt; Gate für 1c
  (`DvrpLoad`/2D-Kapazität existiert erst im finalen 2025.0-Release). Nebeneffekt: der freight-Fork
  kann den Test-Kompat-Patch-Commit fallen lassen. Risiko: matsim-lausitz-2.0-Binärkompatibilität
  (durch die e2e-Suite zu beweisen). _(added 2026-07-14)_

- **`[M]` Run-Dashboard v2 — Plan D (Karten) + zwei zurückgestellte KPIs** — B ✅ (gepusht), C ✅ (lokal),
  Plan-D **Visual-Polish** ✅ (lokal, 2026-07-16, Tasks D1–D3: Donuts/Größenfarben/0–23-Achse/volle Breite).
  **Offen:** (a) Plan D **Karten** (depot-siting / vehicle-tours in die Tabs); (b) zwei bewusst auf Plan D
  verschobene Datenschicht-KPIs (waren „Round 2", User-Entscheidung 2026-07-16 skippen):
  **`occ_km`** = gefahrene km je Besetzungslevel (braucht Netzwerk-km-Rekonstruktion; Legacy `dist_by_occ`
  aus `build_drt_dashboard.py` portieren — `_occ_chart` rendert die Serie bereits, sie wird nur nicht emittiert)
  und **ausgelieferte Pakete/h** (Join Freight-Service-actstart-Event → Carrier-Plan-Demand; `_hourly_provider_stack`
  hängt schon bereit). Subagent-driven empfohlen. → Pläne [B](superpowers/plans/2026-07-13-run-dashboard-v2-planB-java-trigger.md) / [C](superpowers/plans/2026-07-13-run-dashboard-v2-planC-rendering.md) / [D-Polish](superpowers/plans/2026-07-16-run-dashboard-v2-planD-visual-polish.md) / [D-Karten](superpowers/plans/2026-07-13-run-dashboard-v2-planD-maps.md)
  _(added 2026-07-14, aktualisiert 2026-07-16)_

- **`[M]` jsprit-Upgrade 1.8 → 2.x + modulare Operatorauswahl** — Freight-VRP läuft aktuell auf
  `jsprit.version=1.8` (`pom.xml:24`), **Default-Algorithmus** (Algorithm-File im Config leer), aufgerufen
  via `CarriersUtils.runJsprit` (Builder im geforkten freight-contrib, nicht in HAGRID). Idee (Kollege Chatty):
  auf jsprit 2.0 heben — mehr Ruin-/Insertion-Operatoren + unabhängige Operatorauswahl pro Iteration —,
  zunächst in eigenem Branch mit Regressionstests, statt direkt zu VROOM zu wechseln.
  **Meine Einordnung:**
  - **Richtung sinnvoll:** bessere VRP-Qualität wirkt direkt auf die Tourgeometrie — genau das, was die
    Dashboards messen (Kosten/km/Stopps). Innerhalb jsprit zu bleiben statt VROOM ist der klar risikoärmere
    Schritt. Zustimmung zum Vorgehen (Branch + Regression).
  - ⚠️ **Vor Umsetzung verifizieren:** (1) ob „jsprit 2.0" als Release überhaupt existiert und welche Version
    MATSims freight-contrib mitträgt (jsprit-Releases lagen zuletzt eher im 1.9er-Bereich — „2.0" ist unbestätigt);
    (2) der Beispiel-Code (`addRuinOperator(w, Ruin.random(...))`, `Insertion.regretFast()`) entspricht **nicht**
    der mir bekannten jsprit-API — die nutzt **property-basierte** Strategie-Gewichte über `Jsprit.Parameter`/
    `Jsprit.Strategy`, kein Fluent-`addRuinOperator`. Als Pseudocode behandeln, gegen echte jsprit-Doku/Source prüfen.
  - **HAGRID-Spezifika:** Bump berührt `pom.xml` + Fork-POM (`external/matsim-libs`, contribs/freight) und braucht
    die freight-272-Regression **plus** einen Re-Run-Vergleich auf married250 (Kosten/Routen-KPIs verschieben sich
    → alle Dashboards neu baseline). Custom-Operatoren am elegantesten über das **Algorithm-XML-Hook** im Config
    (existiert bereits), nicht per Java-Patch im Fork. Determinismus: Operator-Gewichte + fixer Seed für
    Reproduzierbarkeit klären.
  - **Kopplung:** beim MATSim-Core-Bump `2025.0` (eigener Punkt) mitprüfen, ob der die jsprit-Version ohnehin mitzieht.
  _(added 2026-07-16)_

- **`[M]` Case-Study-Area erweitern** — konkret: Ruhland-Korridor-Entscheidung (aktuell dropped als
  Kurzfix wegen Orphan-Polygon). Tendenz: DRT-Service-Area zu einem zusammenhängenden
  Hoyerswerda↔Ruhland-Korridor vergrößern (DRT als Bahn-Zubringer = natives Lausitz-Konzept).
  Muss VOR einem finalen Headline-Run entschieden werden (Vergleichbarkeit). _(added 2026-07-14)_

- **`[M]` hagrid-input Bootstrap (Restructure Schritt 3)** — ~156 MB, größtenteils untracked;
  letzter manueller Transfer-Schritt für "läuft auf jedem neuen PC". Geplant:
  Download-on-first-run mit URL-Liste + Checksums; HAGRID-only-Dateien via Uni-Share/Release-Assets.
  → [Restructure-Kontext im Memory / geplant] _(added 2026-07-14)_

- **`[M]` Autonomie-Switch-Plan** — §4.4 (Arbeit aus / Roboter-Dwell / Speed-Cap / Autobahn-Ausschluss),
  orthogonal über beide integrierten Szenarien. Plan **nach** 1c+1d. Integrationspunkt:
  das bislang unverdrahtete `IntegratedScenarioConfig`. _(added 2026-07-14)_

## Low

- **`[L]` Modul-Split (Restructure Schritt 4)** — Maven-Multi-Module `hagrid-core` / `hagrid-hannover`
  / `hagrid-lausitz`. Reiner Move-Refactor; `HagridPaths`-Root-Detection pro Szenario neu bauen.
  _(added 2026-07-14)_

- **`[L]` `.sha1`-Checksums für `libs/`-Artefakte committen** — der Kalt-`mvn`-Build warnt
  "no checksums available from hagrid-local-libs" (harmlos). Fünf-Minuten-Fix. _(added 2026-07-14)_

- **`[L]` Human Visual Pass der married120-Dashboards** — Rendering (Light/Dark, Tab-Wechsel,
  Label-Kollisionen, H-Scroll) kann kein Reviewer aus dem Diff prüfen. _(added 2026-07-14)_

- **`[L]` Inbound Rail-Commuter** — durch Home-Anchor-Clipping aktuell rausgeschnitten; expliziter
  Post-Baseline-Follow-up. _(added 2026-07-14)_

- **`[L]` Consolidated-Operator-Baseline (Phase-2-Variante)** — um den Konsolidierungs- vom
  Integrationseffekt zu trennen. _(added 2026-07-14)_

- **`[L]` Szenario-Naming-Konvention aufräumen** — `LMD_BASELINE`/`requiresLausitz`; User war
  "not happy" damit. _(added 2026-07-14)_

- **`[L]` Phase-2-Deferrals (gesammelt)** — Packstationen/Locker (braucht Standortdaten),
  Ride-and-Collect, mobile-Packstation (Opt 1), verschiedene Van-Größen. Alle bewusst aufgeschoben.
  _(added 2026-07-14)_

- **`[L]` Legacy `drt_service_time.py:194` Sort-Key härten** — `int(v.split("_")[1]) if
  v.split("_")[-1].isdigit() else 0` crasht auf nicht-`drt_<int>`-Fahrzeug-IDs (z.B. `drt_veh_1`);
  reale married-Runs nutzen `drt_<int>` → kein Real-Run-Defekt, aber der KPI-Test-Fixture musste
  die ID in-test umschreiben. Trivialer Defensiv-Fix (trailing-int extrahieren, sonst 0) entfernt
  den Workaround. Entdeckt bei Plan-D-maps Task 5. _(added 2026-07-17)_

---

## Erledigt

- **freight Fork + Submodule (Restructure Schritt 1)** — ✅ 2026-07-14. Copy-Vendoring durch
  Submodule `external/matsim-libs` (Fork `TUBS-IVS/matsim-libs`) ersetzt; sim-PC warm + kalt
  validiert. → [Spec](superpowers/specs/2026-07-13-freight-fork-submodule-design.md) / [Plan](superpowers/plans/2026-07-13-freight-fork-submodule.md)
- **matsim-lausitz aus `libs/` (Restructure Schritt 2)** — ✅ 2026-07-13. Jar+POM als projekt-lokales
  File-Repo committed.
- **1e KPI-CSV + Dashboard** — ✅ 2026-07-09. Kanonisches `analysis/kpi/`-Paket.
