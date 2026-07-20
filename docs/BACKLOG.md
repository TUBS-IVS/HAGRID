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
gestrichen. _Zuletzt aktualisiert: 2026-07-20._

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
  - **Entscheidung 2026-07-20: 1c ist echtes Co-Riding** (Pax + Paket *gleichzeitig* an Bord,
    2D-Kapazität wie Spec §4.2/§6.1) — nicht Exklusiv-Phase. Umsetzung = **Option A**
    (Dummy-Parcel-Agenten + `DvrpLoad`, Trennung von Fracht/Pax nur auf **Event-/KPI-Ebene**).
    Begründung: eine *native* Trennung separater Fracht- von Passagieragenten *bei gleichzeitigem*
    Co-Riding existiert in MATSim nicht (siehe Option C unten). Vor Ausführung: D10 des 1c-Plans
    härten (3-Wege-KPI-Taxonomie, Contamination-Checkliste, Fare-Unterdrückung für Pakete,
    χ→0-Validierungslauf als konstruktiver Beweis, dass die Agenten-Mischung die Pax-Buchführung
    nicht verzerrt).
  - **Design-Input: `drt-extensions/reconfiguration` (MOIA-Fork)** — liefert fertige, benannte
    DvrpLoad-Typen `PersonsLoadType`/`GoodsLoadType` + die Bindings `DvrpLoadFromFleet` /
    `DvrpLoadFromDrtPassengers` + Serializer off-the-shelf → deckt die 2D-Kapazitäts-Plumbing aus
    1c **Task 4** ab (statt handgerollter `DvrpLoadFromFleet`-Override) und liefert einen
    **load-basierten Klassifikator** (Goods-Load im Request-Event, robuster als das `parcel_`-Präfix).
    Bonus: dynamischer Laufzeit-Kapazitäts-Umbau = **1d-Enabler**. **Ändert NICHTS am
    Dummy-Agent-Erfordernis** (Goods-Request kommt weiter über die Passenger-Engine). **Offen/VERIFY:**
    ist die Extension im **2025.0-Release-JAR** enthalten oder zieht ihre Nutzung einen größeren
    Versions-Bump nach sich (matsim-lausitz-Binärkompat-Risiko)? Vor Adoption verifizieren — sonst
    Person/Goods-Namenskonvention + load-Klassifikator nur *nachbauen* beim minimalen 2025.0-Bump.
    _(added 2026-07-20)_
  - **Alternative Architektur (zurückgestellt) — vollsimultaner nativer `CargoRequest`-Fork
    (Option C):** Der DRT-Request-Lebenszyklus ist durchgängig personengebunden
    (`DrtRequest implements PassengerRequest`, `DefaultPassengerEngine`, `DrtStopActivity`/
    Passenger-Handler; leere Passenger-ID-Liste wird per `Preconditions` abgelehnt, künstliche ID
    ohne Agent crasht beim Pickup). Ein eigener `CargoRequest`-Typ, der *gleichzeitig* mit Pax auf
    demselben Fahrzeug fährt, erfordert die Generalisierung von `InsertionGenerator` /
    `VehicleEntry` / `StopWaypoint` / Occupancy — ein **tiefer Fork des drt/dvrp-Contribs**
    (verliert Upstream-Wartung, Forschungs-Softwareaufwand von Monaten). Die native
    `drt-extensions/services`-Vorlage (`DrtServiceDynActionCreator`, blockierende `EntryFactory`,
    eigene Events) deckt nur die **exklusive** Phase ab → gehört zu **Modular (1d)**, nicht zu
    Co-Riding-1c. Quelle des Vorschlags: externes Dokument „3.2 Fracht-Requests nicht als
    Dummy-Passagiere modellieren" (in `Research/Paper/City Logistics/Dummy_Chat.txt`) — technisch
    korrekt, adressiert aber den exklusiven Fall, nicht Co-Riding (§3.2.7 räumt das selbst ein).
    **Reaktivieren, falls** die Event-/KPI-Kontamination in der Evaluation trotz gehärtetem D10
    untragbar wird. _(added 2026-07-20)_
  - **Grilling-Review 2026-07-20 → 12 Methodik-Verfeinerungen (M1–M12) in den 1c-Plan eingearbeitet**
    (Sektion „Methodology refinements"): Sitz-Basis 10/8+20 (M1), Segment-Split über Kapazität (M2),
    δ-Dekomposition (M3), skalierte Depot-Pickup-Dwell + Provider-Depot-Zuordnung (M4), per-Typ-Zeitfenster
    B2B 07:30–17 / B2C 07:30–20 (M5), χ-Sweep statt Einzelpunkt auf rohem `totalTimeLoss` (M6),
    Pax-only-Rebalancing (M7), präzises „passenger-primary"-Wording (M8), δ-Konvergenzcheck (M9),
    not-at-home beidseitig 100 % konsistent (M10), marginale Joint-Cost-Allokation definiert (M11).
    _(added 2026-07-20)_
  - **Re-Baseline auf 10 Sitze (Folge von M1) — Sim-Run ausstehend.** Die existierenden married-Runs
    (married120/250) fahren cap=8; für den Headline-Vergleich Baseline vs. Shared-Use muss die **Baseline
    auf 10 Sitze** neu laufen (Shared-Use = 8). Kein Code-Problem, ein Overnight-Run. User 2026-07-20:
    **nicht jetzt.** _(added 2026-07-20)_
  - **DOF-Kontrollarm (M12) — langfristig / Paper-Extension.** „Dedicated Online Freight" = Shared-Use-Dispatch
    + Fahrzeug, nur Pakete, keine Pax. Isoliert den **reinen** Integrationseffekt (Shared-Use vs. DOF) vom
    Tooling-/Fahrzeug-Effekt (DOF vs. offline-Baseline) und vervollständigt eine faktorielle Zerlegung
    (Pax-allein = χ=0-Lauf / Fracht-allein = DOF / beide = Shared-Use). Priorität liegt auf Systemebene →
    aufgreifen, wenn fürs Paper eine mechanistische Zusatzaussage gebraucht wird. _(added 2026-07-20)_

- **`[H]` Modular / U-Shift (Szenario 1d)** — Kapsel-Tausch, Offline-jsprit + Pax-Priorität.
  **Plan noch nicht geschrieben** (soll nach dem 1c-Plan entstehen, erbt Infra-Entscheidungen von 1c).
  - **Design-Input (2026-07-20):** die *exklusive* Pax-oder-Fracht-Phase des Kapsel-Tauschs passt
    zur nativen `drt-extensions/services`-Vorlage (`DrtServiceDynActionCreator` + blockierende
    `EntryFactory` + eigene Events = **echte** Fracht-Agent-Trennung ohne Dummy-Passagiere) und zum
    dynamischen Laufzeit-Kapazitäts-Umbau der `drt-extensions/reconfiguration`-Extension. Beim
    1d-Plan prüfen, ob das den geplanten Offline-jsprit-Pfad ergänzt/ersetzt. Kontext siehe
    Co-Riding-1c-Notizen oben (Option C). _(added 2026-07-20)_
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
    Schritt.
  - ✅ **Verifiziert 2026-07-20 (README graphhopper/jsprit + lokale Fakten):** (1) **jsprit 2.0.0 existiert**
    als Vollrelease (`com.graphhopper:jsprit-core:2.0.0`), „unbestätigt" erledigt; (2) verlangt **Java 21** —
    HAGRID ist bereits auf 21 (`pom.xml:19-21`), **kein Blocker**; (3) die **Fluent-API** (`addRuinOperator(w, Ruin.…)`,
    `Insertion.regretFast()`) ist in 2.0 **real** (nicht Pseudocode), inkl. Regret-k-Insertion — genau Chattys Ziel;
    (4) **MATSim-Upstream steht selbst noch auf jsprit 1.8** (master-freight-contrib-POM) → der **Core-Bump 2025.0
    zieht jsprit NICHT mit**.
  - ⚠️ **Neu erkannter Hauptaufwand (korrigiert frühere Annahme):** HAGRID ruft jsprit **nicht nur** via
    `CarriersUtils.runJsprit` — **~16 Java-Dateien** nutzen die jsprit-API direkt und tief (eigene `HardActivityConstraint`/
    `SoftActivityConstraint`, `VehicleRoutingActivityCosts`/`VehicleRoutingTransportCosts`, `StateUpdater`/`StateManager`,
    `ConstraintManager`; u.a. `MaxRouteDurationConstraint`, `UTurnSoftConstraint`, `ZoneBasedTransportCosts`, `JspritCarrierTask`).
    Genau diese SPI-Interfaces brechen typischerweise beim Major-Bump. **Plus:** weil Upstream auf 1.8 bleibt, kompiliert
    MATSims **freight-contrib selbst nicht gegen 2.0** → der komplette contrib müsste im Fork
    (`external/matsim-libs/contribs/freight`) auf die 2.0-API portiert **und die Divergenz dauerhaft gepflegt** werden.
    Damit ist das **L (hoch), nicht M** — Einstufung angehoben.
  - **Empfohlenes Vorgehen (2026-07-20):** (a) **entkoppeln** vom Core-Bump; (b) **zeitboxierter Spike (½–1 Tag) ZUERST**,
    der zwei Fragen klärt, bevor irgendetwas portiert wird: **(i)** Reicht schon **1.8** für Chattys Ziel? — property-basierte
    Strategie-Gewichte via `Jsprit.Parameter`/`Jsprit.Strategy` **oder** der Algorithm-XML-Hook (existiert im Config bereits)
    geben Operator-Diversität evtl. ohne jeden Bump (→ 80 % Nutzen, 0 Fork-Port-Last). **(ii)** Falls doch 2.0: wie groß ist
    der freight-contrib-Fork-Port real (Kompilier-Spike gegen 2.0.0)? Erst danach Go/No-Go.
  - **HAGRID-Spezifika bei tatsächlichem Bump:** berührt `pom.xml` (`jsprit.version`) + drei POMs (root, parcel-pipeline,
    Fork-freight) und braucht freight-272-Regression **plus** Re-Run-Vergleich married250 (Kosten/Routen-KPIs verschieben sich
    → alle Dashboards neu baseline). Determinismus: Operator-Gewichte + fixer Seed klären.
  - **Kopplung:** entfällt — Core-Bump 2025.0 zieht jsprit nachweislich **nicht** mit (Upstream 1.8). Beide Punkte sind
    unabhängig; Core-Bump zuerst (echter 1c-Gate), jsprit separat nach Spike.
  _(added 2026-07-16, verifiziert + neu eingeordnet 2026-07-20)_

- **`[M]` Case-Study-Area erweitern** — konkret: Ruhland-Korridor-Entscheidung (aktuell dropped als
  Kurzfix wegen Orphan-Polygon). Tendenz: DRT-Service-Area zu einem zusammenhängenden
  Hoyerswerda↔Ruhland-Korridor vergrößern (DRT als Bahn-Zubringer = natives Lausitz-Konzept).
  **User-Entscheidung 2026-07-20:** zunächst bei **Hoyerswerda** bleiben, Erweiterung erst
  **~nächstes Jahr** (2027) erwägen — konkret wenn es um Simulation konkreter Policies geht.
  Muss VOR einem finalen Headline-Run entschieden werden (Vergleichbarkeit). _(added 2026-07-14, aktualisiert 2026-07-20)_
  - **Re-Implementierung „Bahn-Zubringer"-Kartenlayer** — der DRT-Karten-Layer „Bahn-Zubringer"
    (Bahnhaltestellen im Bediengebiet, gefärbt/skaliert nach Zahl der DRT-Ausstiege im 600-m-Umkreis;
    natives DRT-als-Bahn-Zubringer-Konzept) wurde **2026-07-20 entfernt** (Checkbox + JS aus
    `render_maps.py`, Producer `maps._rail_stops` + `FEED_RADIUS_M` + ungenutzte `gzip`/`ET`-Imports).
    Grund: bei der aktuellen kleinen geografischen Ausdehnung (nur Hoyerswerda) sind Bahn-Zubringer
    inhaltlich unwichtig, und der Layer war auf married250 ohnehin leer (`rail_stops` fehlte im
    `map_data.json` — Producer gab still `None` zurück, Ursache nie isoliert). **Wieder-Einbauen,
    wenn die Case-Study-Area erweitert wird** (Ruhland-Korridor → Zubringer wird relevant); dabei die
    stille-`None`-Ursache mit-fixen und den Layer nur zeigen, wenn Daten vorhanden. Port-Referenz:
    Legacy `build_drt_dashboard.py:260-289`. _(added 2026-07-20)_

- **`[M]` hagrid-input Bootstrap (Restructure Schritt 3)** — ~156 MB, größtenteils untracked;
  letzter manueller Transfer-Schritt für "läuft auf jedem neuen PC". Geplant:
  Download-on-first-run mit URL-Liste + Checksums; HAGRID-only-Dateien via Uni-Share/Release-Assets.
  → [Restructure-Kontext im Memory / geplant] _(added 2026-07-14)_

- **`[M]` Autonomie-Switch-Plan** — §4.4 (Arbeit aus / Roboter-Dwell / Speed-Cap / Autobahn-Ausschluss),
  orthogonal über beide integrierten Szenarien. Plan **nach** 1c+1d. Integrationspunkt:
  das bislang unverdrahtete `IntegratedScenarioConfig`. _(added 2026-07-14)_

- **`[M]` Karten-Dropdowns/-Controls noch nicht manuell durchgeklickt** — Plan-D-maps Task 9 hat
  nur Ladezeit/Größe im Browser bestätigt (6.1 MB, married250, "fast & responsive"); die
  interaktiven Elemente selbst (DRT-Fahrzeug-`<select>` inkl. 227 Einträgen + Stop-Badges, LMD
  Touren/Stopps/Heatmap-Modus-Radio, Provider/Carrier/Vehicle-Filter, Depot/Rail/Heat-Checkboxen,
  Light/Dark-Tile-Wechsel) sind noch **nicht** einzeln durchgeklickt/reviewt. Vor "fertig" fürs
  Kartenfeature: einmal jeden Dropdown/Filter/Toggle auf dem married250-Dashboard durchgehen.
  _(added 2026-07-17)_

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

- **`[L]` LMD-Karte leer bei DRT-losem Run** — `build_kpis.py:100` gated den Netzwerk-Geometrie-Block
  auf `drt_cache is not None`, d.h. bei einem reinen Freight/LMD-Run (kein DRT) bleibt `link_geo=None`
  → alle LMD-Touren `runs:[]`, Stopps gedroppt, Heat leer (Karte zeigt nur Controls). Betrifft
  married250 NICHT (hat DRT → `freight_used` wird unioniert). Fix: Gate auf
  `(drt_cache is not None or fev is not None) and network.exists()`, `veh_path` weiter nur bei
  vorhandenem `drt_cache`. Vor dem ersten LMD-only-Szenario (vgl. `LMD_BASELINE`) fixen. Gefunden im
  Plan-D-maps Whole-Branch-Review. _(added 2026-07-17)_

---

## Erledigt

- **freight Fork + Submodule (Restructure Schritt 1)** — ✅ 2026-07-14. Copy-Vendoring durch
  Submodule `external/matsim-libs` (Fork `TUBS-IVS/matsim-libs`) ersetzt; sim-PC warm + kalt
  validiert. → [Spec](superpowers/specs/2026-07-13-freight-fork-submodule-design.md) / [Plan](superpowers/plans/2026-07-13-freight-fork-submodule.md)
- **matsim-lausitz aus `libs/` (Restructure Schritt 2)** — ✅ 2026-07-13. Jar+POM als projekt-lokales
  File-Repo committed.
- **1e KPI-CSV + Dashboard** — ✅ 2026-07-09. Kanonisches `analysis/kpi/`-Paket.
