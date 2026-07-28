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
_Zuletzt aktualisiert: 2026-07-28._

---

## High

### `[H]` Shared-Use / Cargo-Hitching (Szenario 1c)

Minibus mit 2D-Kapazität (Sitze + Pakete), Online-DVRP-Insertion.
**Status: implementiert, in Validierungs-/Run-Phase** (2026-07-28). Implementierung, Reviews und
χ→0-Validierung sind durch → [BACKLOG-DONE](BACKLOG-DONE.md); Architektur- und
Parameter-Entscheidungen → [METHODS-LOG](METHODS-LOG.md) §1.1/§1.2.
→ [1c-Plan](superpowers/plans/2026-07-06-1c-shareduse-cargo-hitching.md) ·
[Spike](superpowers/notes/2026-07-06-shareduse-dvrp-insertion-spike.md) _(added 2026-07-14)_

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
- Alternative verworfen: Iterations-Hochlauf (`jspritIter=1000` ≈ 4 h pro Carrier, ~20 h je Arm).

### `[H]` Modular / U-Shift (Szenario 1d)

Kapsel-Tausch, Offline-jsprit + Pax-Priorität. **Status: Spike + Design + Detail-Plan fertig
(2026-07-27/28), Ausführung offen** — nächster Schritt ist die Umsetzung (subagent-driven, auf
User-Go). POC läuft auf dem aktuellen PANDA-Stand; die gematchte Baseline braucht es erst für die
Paper-Runs.
→ [Plan](superpowers/plans/2026-07-27-1d-modular-capsule-swap.md) ·
[Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md) ·
[Spike](superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md)
Konzeptparameter und ihre Begründung: [METHODS-LOG](METHODS-LOG.md) §1.2. _(added 2026-07-14)_

- **Zurückgestellte Sensitivitätsidee (User 2026-07-27):** jsprit-Tourplanung als **EIN Pool**
  (ein Carrier, Fahrzeuge an allen 7 Depots, freie Depotwahl je Paket = stärkstes
  Einheitsunternehmen) und/oder beide Varianten als Zerlegung Konsolidierungs- vs.
  Integrationseffekt. Verwandt mit `[L]` Consolidated-Operator-Baseline.

### `[H]` Nachhaltigkeitsparameter einbauen

Emissions-/CO₂-/Energie-KPIs und -Parameter ins Modell + Dashboard. Berührt Autonomie-Switch
(E-Antrieb) und die Kostenfunktion. **Status: Plan geschrieben 2026-07-28, noch nicht ausgeführt.**
→ [Plan](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md) (9 Tasks: Faktor-Extraktion,
Tier-3-Kern, Freight-/DRT-Arm, BEV, EV-Range-Gate, `build_kpis`-Integration, Kaltstart-Bound;
SOS-Layer + Multi-Seed-Aggregation bewusst ausgeklammert).
Methodenwahl, Klassenmapping, Systemgrenze und Caveats: [METHODS-LOG](METHODS-LOG.md)
§1.4/§2.7. _(added 2026-07-14)_

- **Konkrete Arbeitsschritte aus der Faktor-Sichtung:** Appendix-4-xlsx **ins Repo legen** (liegt
  noch in Downloads) + Quellentabelle dazu · DRT-/Pkw-Klassen in der Pipeline ergänzen (bisher
  freight-only) · unbelegte Idle-/Kaltstart-Parameter belegen · `_l`-Van optional einmal als HDT
  „Rigid ≤7,5 t" gegenrechnen (ausgewiesene Bandbreite statt versteckter Annahme).
- **⚠️ Constraint:** `hagrid_output_analysis/emissions.py` **nicht anfassen** (Kollegen-Paper,
  Abstimmung frühestens ~2026-08-11) → Kopie der tragenden Logik nach `analysis/kpi/`.
  Begründung: [METHODS-LOG](METHODS-LOG.md) §1.4.
- **`[H]` EV-Reichweiten-Gate** — sobald Baseline-Runs **aller drei Szenarien** stehen: prüfen, ob
  bei einzelnen Touren die EV-Reichweite limitierend wäre. **Falls ja, darf EV nicht reines
  Postprocessing bleiben** (kippt die Entscheidung in METHODS-LOG §1.4). Check ist billig
  (Post-Processing auf vorhandenen Outputs): Verteilung Tages-km je Fahrzeug (Max/P95) aus
  `drt_vehicle_stats` bzw. jsprit-Tourlängen gegen konservative Real-Reichweite (~250 km Winter,
  e-LCV-Klasse).
  Einordnung: **LMD = geringes Risiko** (eine Tour/Tag ≤7,5 h, Depot-Übernachtladung — CEP ist der
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

---

## Medium

### Nachfrage-Modell (PANDA → HAGRID): offene Punkte

Das Nachfrage-Niveau-Band ist gemessen und der Proxy-Fix ist durch → Ergebnisse und
Zurückziehungen in [METHODS-LOG](METHODS-LOG.md) §1.3/§3.1/§3.2, Nachweise in
[BACKLOG-DONE](BACKLOG-DONE.md). Offen bleibt:

- **`[M]` CV-Batterie nach dem B8-Fix nachziehen** — Bootstrap-KI, Segment-, Cross-Carrier- und
  Transfer-Check im PANDA-README sind noch OSM-Zahlen. Nur der blinde Hannover-wMAPE (9,8 %) ist
  neu gemessen. _(added 2026-07-27)_
- **`[M]` Zustellquoten-Abweichung von −14 % aufklären** — systematisch in beiden Bandarmen,
  hebt sich in Vergleichen weg, macht aber jede *absolut* berichtete Zustellquote zu optimistisch.
  Einstieg: `CarrierGenerator.adjustDeliveryRatesConsideringB2B:252`,
  `determineMissedParcels:1049`, Sollwerte `HagridConfig.java:190-196`.
  Details: [METHODS-LOG](METHODS-LOG.md) §2.5. _(added 2026-07-27)_
- **`[M]` Zentroid-Snapping im Auge behalten** — 281 Zellen / 630 von 6.024 Paketen (10,5 %) ohne
  gewichtetes OSM-Gebäude. Niveau unberührt, Platzierung unter 100 m nicht. Sollte nicht wachsen.
  → [METHODS-LOG](METHODS-LOG.md) §2.8. _(added 2026-07-27)_

### Sonstige Medium-Punkte

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

- **`[M]` LMD Dispatch-Stunden besser streuen** — Abfahrtszeiten der LMD-Fahrzeuge sollten
  gleichmäßiger über den Tag/die Wellen gestreut werden statt sich auf einzelne Minuten/Stunden
  zu häufen. Bereits bekannt: legacy Hannover kollabiert 171/187 Carrier auf EIN Vehicle-Template
  trotz ≥2 verfügbarer (jsprit klont früheste/billigste Kopie bei `FleetSize.INFINITE`); Lausitz'
  4-Kopien-pro-Welle (`VEHICLES_PER_TYPE_PER_WAVE`) ist zwar stärker gegen Kollaps als legacy,
  aber echte Pro-Fahrzeug-Streuung bräuchte `FleetSize.FINITE` oder einen Vehicle-Nutzungs-/
  Tour-Count-Term im jsprit-Objective — siehe [[project_lausitz_drt_freight]] „LMD stagger" Fund.
  _(added 2026-07-21)_
  - **Update 2026-07-28 (1d-Grilling, User):** die Wave-Mechanik generell nochmal prüfen.
    1d/DRT_MODULAR umgeht sie ab sofort komplett (explizites Fenster 07:30–21:00, keine
    Wave-Herleitung, kein Jitter) → betrifft nur noch LMD_BASELINE/married. Beim Anfassen:
    Delegate-Muster aus 1d-Task-1 wahren (neue Parameter nur über neue Overloads, Legacy
    delegiert), sonst Baseline-Regression.

- **`[M]` Run-Dashboard v2 — Plan D (Karten) + zurückgestellte KPIs** — B ✅ (gepusht), C ✅ (lokal),
  Plan-D **Visual-Polish** ✅ (lokal, 2026-07-16). **Offen:**
  (a) Plan D **Karten** (depot-siting / vehicle-tours in die Tabs);
  (b) zwei bewusst verschobene Datenschicht-KPIs: **`occ_km`** = gefahrene km je Besetzungslevel
  (braucht Netzwerk-km-Rekonstruktion; Legacy `dist_by_occ` aus `build_drt_dashboard.py`
  portieren — `_occ_chart` rendert die Serie bereits, sie wird nur nicht emittiert) und
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
    Port-Referenz: Legacy `build_drt_dashboard.py:260-289`. _(added 2026-07-20)_

- **`[M]` hagrid-input Bootstrap (Restructure Schritt 3)** — ~156 MB, größtenteils untracked;
  letzter manueller Transfer-Schritt für "läuft auf jedem neuen PC". Geplant:
  Download-on-first-run mit URL-Liste + Checksums; HAGRID-only-Dateien via
  Uni-Share/Release-Assets. _(added 2026-07-14)_

- **`[M]` Autonomie-Switch-Plan** — §4.4 (Arbeit aus / Roboter-Dwell / Speed-Cap /
  Autobahn-Ausschluss), orthogonal über beide integrierten Szenarien. Plan **nach** 1c+1d.
  Integrationspunkt: das bislang unverdrahtete `IntegratedScenarioConfig` (s. u.).
  _(added 2026-07-14)_

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

**Reihenfolge:** zwei Punkte hängen an einer Nutzer-Entscheidung (unten mit **ENTSCHEIDUNG
OFFEN**); der Rest ist mechanisch und kann jederzeit am Stück laufen.

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

- **`[M]` Locker-Javadoc korrigieren + `IntegratedScenarioConfig` entscheiden** —
  `DeliveryChannelResolver:10-15` behauptet, der Locker-Zweig aktiviere sich „ohne Codeänderung
  hier"; das ist falsch, der Kanal ist strukturell 0 (→ [METHODS-LOG](METHODS-LOG.md) §2.10).
  Zusätzlich: `IntegratedScenarioConfig` (mit `b2cLockerShare=0.7`, Autonomie-Dwell-Faktor,
  Labour-Kosten, Retooling-Zeit) wird **nur vom eigenen Test** referenziert und erreicht keinen
  Run — liest sich aber wie aktive Konfiguration. Verwandt: `[M]` Autonomie-Switch-Plan (dort ist
  `operation_mode` in `RunMetadataWriter.java:31` hart auf `"conventional"`).
  **⚠️ ENTSCHEIDUNG OFFEN (gestellt 2026-07-27):** kommt der Autonomie-Switch (§4.4) in 1c/1d, ist
  `IntegratedScenarioConfig` sein designierter Ort → **verdrahten**; kommt er nicht → **löschen**.
  Das Locker-Javadoc wird in beiden Fällen korrigiert (unabhängig, kann sofort passieren).
  _(added 2026-07-27)_

- **`[M]` Ungeschützter Kompositions-Zweig im DRT-Config-Aufbau** — `DrtConfigComposer.java:63`
  `if (multi.getModalElements().isEmpty())`: bringt die Base-Config je ein `drt`-Element mit, wird
  die **komplette** HAGRID-Komposition übersprungen (ServiceArea, Fleet-File, maxWaitTime,
  Rebalancing, ExtensiveInsertionSearch) und die Base-Werte laufen. Feuert heute nicht, loggt aber
  auch nichts. Vorschlag: gewählten Zweig loggen, oder werfen wenn nicht-leer aber die erwarteten
  Keys fehlen. _(added 2026-07-27)_

- **`[M]` `ct_cep_size_s` im LMD-Flottenmix — ja oder nein? ENTSCHEIDUNG OFFEN.**
  `lmd-vehicle-types.xml` enthält drei Van-Typen und **alle drei werden eingesetzt** (je 56
  Fahrzeuge in `married250` verifiziert), obwohl `HagridPaths.java:337` „ct_cep_size_m / _l only"
  dokumentiert. Kein Fallback, aber ein realer Effekt auf den Flotten-Mix — und damit auf den
  **LMD-Vergleichsarm**, gegen den Shared-Use gemessen wird (deshalb von `[L]` auf `[M]`
  hochgestuft). Entweder `_s` bewusst aufnehmen → Doku korrigieren, oder aus der Typdatei
  entfernen → die LMD-Zahlen ändern sich und die married-Runs müssten neu. Nebeneffekt:
  `LmdCarrierBuilder.jitterSigmaMinutes:160-163` gibt `_s` per Durchfall die 15-Min-Sigma des
  „m"-Zweigs. _(added 2026-07-27)_

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

- **`[L]` `SimulationBatGenerator` JDK-Pfad hartcodiert** — der generierte `run_hagrid_sim.bat`
  setzt `JAVA_EXE` auf einen fest verdrahteten Adoptium-Pfad inkl. Patch-Version
  (`jdk-21.0.3.9-hotspot`). Nach einem JDK-Update auf einer Zielmaschine (z.B. Sim-PC-Bump auf
  `21.0.8.9`, entdeckt 2026-07-21 während des Hannover-Kapa-Sweeps) schlägt der generierte Bat
  sofort mit "Datei nicht gefunden" fehl — jeder Sweep-Neustart braucht einen manuellen Bat-Patch.
  Fix: `%JAVA_HOME%\bin\java.exe` verwenden oder den Pfad zur Build-Zeit via System-Property
  auflösen statt Major.Minor.Patch hartzucodieren. _(added 2026-07-22)_

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
  Integrationseffekt zu trennen. Verwandt mit der EIN-Pool-Sensitivitätsidee unter `[H]` 1d.
  _(added 2026-07-14)_

- **`[L]` Szenario-Naming-Konvention aufräumen** — `LMD_BASELINE`/`requiresLausitz`; User war
  "not happy" damit. _(added 2026-07-14)_

- **`[L]` Phase-2-Deferrals (gesammelt)** — Packstationen/Locker (braucht Standortdaten),
  Ride-and-Collect, mobile-Packstation (Opt 1), verschiedene Van-Größen. Alle bewusst aufgeschoben
  → [METHODS-LOG](METHODS-LOG.md) §4.5. _(added 2026-07-14)_

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
