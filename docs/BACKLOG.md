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
gestrichen. _Zuletzt aktualisiert: 2026-07-27._

---

## High

- **`[H]` Shared-Use / Cargo-Hitching (Szenario 1c)** — Minibus mit 2D-Kapazität (Sitze+Pakete),
  Online-DVRP-Insertion. Plan geschrieben, **noch nicht ausgeführt**.
  Gate: MATSim-Core-Bump `2025.0-PR3552` → `2025.0` ✅ **erledigt 2026-07-20** (Branch
  `bump/matsim-2025.0`, Suite grün; siehe `## Erledigt`) — `DvrpLoad`/2D-Kapazität steht jetzt bereit.
  Vor Ausführung: unterbrochenen Grilling-Pass auf dem Plan fortsetzen; offener technischer
  Zwischenschritt = **Kontroll-Sim-Run** married250 auf `2025.0` (Kapazität 10/8 per Spec, User startet auf Zuruf).
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
  - **VERIFIED 2026-07-24 — die 2026-07-20-Notiz war falsch, hier korrigiert:** Das 2D-Kapazitäts-Plumbing
    für 1c **Task 4** ist **nativ im `dvrp`-Core `2025.0`** (schon auf HAGRIDs Classpath via `dvrp:2025.0` —
    KEIN neuer Dependency, KEIN Bump, KEIN MOIA-Fork nötig). Package `org.matsim.contrib.dvrp.load`:
    `DvrpLoad`/`DvrpLoadType`, `IntegersLoad`/`IntegersLoadType` (mehrdimensional → Sitze + Paket-Slots),
    `DvrpLoadFromFleet`/`DefaultDvrpLoadFromFleet`, `DvrpLoadFromVehicle`/`DefaultDvrpLoadFromVehicle`,
    `DvrpLoadModule`, `DvrpLoadParams`; die DRT-Request→Load-Abbildung ist nativ
    `dvrp.passenger.DvrpLoadFromTrip`/`DefaultDvrpLoadFromTrip` (= das, was die alte Notiz
    „`DvrpLoadFromDrtPassengers`" nannte). **`PersonsLoadType`/`GoodsLoadType` existieren NICHT als Klassen**
    (nirgends in dvrp/drt/drt-extensions 2025.0, auch nicht auf master) — die zwei Dimensionen
    (Personen/Güter) definiert man selbst über `IntegersLoadType` (trivial). → **1c Task 4 = native Core-API
    nutzen, kein Fork-Override, keine Serializer-Frage.** (Verifiziert gegen lokales `.m2` `dvrp-2025.0.jar`
    + GitHub-Tag `2025.0`.)
  - **`drt-extensions/reconfiguration` (MOIA-Fork) — was es WIRKLICH ist:** dynamischer Laufzeit-Umbau der
    Fahrzeugkapazität während der Sim (`CapacityReconfigurationEngine` +
    `logic/{,Default,Noop}CapacityReconfigurationLogic` + `run/CapacityReconfiguration{,QSim}Module`).
    Das ist ein **1d-Enabler** (Kapsel-Tausch Personen- ↔ Güter-Konfiguration), NICHT die 1c-Task-4-Abkürzung,
    als die die alte Notiz sie führte (Extension und Core waren verwechselt). **VERIFY erledigt:** die Extension
    IST im `2025.0`-Release enthalten (zwischen PR3552 und 2025.0 hinzugefügt; HAGRIDs `.m2` hat lokal nur die
    veraltete `2025.0-PR3552` OHNE reconfiguration) → Nutzung braucht KEINEN größeren Bump, nur
    `org.matsim.contrib:drt-extensions:2025.0` als Dependency ergänzen (aktuell gar keine HAGRID-Dependency)
    + Binärkompat via Build/e2e bestätigen. Für 1c irrelevant (Core reicht); Cross-Ref: `[H]` 1d unten.
    _(korrigiert 2026-07-24, ersetzt die 2026-07-20-Notiz)_
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

- **`[H]` Nachfrage-Niveau-Band Lausitz (Transferability-Folge)** — Die PANDA-Transferstudie
  (`PANDA/docs/transferability.md`, 2026-07-27) hat gezeigt: die räumliche Verteilung der
  Lausitz-Nachfrage ist belegt, das **Niveau** liegt aber ~22 % über dem einzigen externen
  Anker (BIEK-Kompendium 2021: Sachsen 43 vs. Niedersachsen 47 Sdg./Einw./Jahr). Ursache
  isoliert = **EFH-Term** (28,3 % statt 14,8 % Anteil am Total); **nicht** Demografie
  (−2…−3 %), **nicht** Einkommen (VGR 2023: verfügbares Einkommen je Einwohner
  Bautzen/Region Hannover = 0,990), **nicht** das Dichte-Regime.
  **Entscheidung (User 2026-07-27): minimales Zwei-Run-Band** — je ein Zusatzrun für
  Baseline und Shared-Use auf einem Low-Nachfragestand (×0,819), räumliche Verteilung
  unverändert. Zweck: die Aussage „Ergebnisse robust gegen ±18 % Nachfragefehler" belegen
  statt hoffen.
  - Nachfragedateien liegen exportiert: `PANDA/output/lausitz_central` (7.271 Pakete) und
    `PANDA/output/lausitz_low` (5.956), 1.066 Segmente identisch. Neues `--scale`-Argument
    in `export_demand.py`.
  - **Achtung, gematchtes Paar nötig:** HAGRID fährt noch auf der 6.381-Paket-Datei aus dem
    *zurückgezogenen* 12-Parameter-Modell → Central UND Low müssen neu laufen. Der
    Central-Run ist ohnehin fällig (koppeln an die 10-Sitze-Re-Baseline unten).
  - **Shared-Use-Teil zurückgestellt**, bis 1c-Runs laufen — dann an den χ-Sweep (M6)
    hängen, kostet dort nur einen zusätzlichen Punkt. Nichtlinearität ist genau dort
    plausibel (χ-Gate: weniger Pakete → überproportional höheres δ).
  - **✅ Baseline-Hälfte erledigt (2026-07-27).** `LMD_BASELINE` × {central, low} gelaufen
    (`run_lmd_band.ps1`, Tags `band_central`/`band_low`, je `maxIter=0 jspritIter=100`),
    Ergebnis in `PANDA/docs/transferability.md` → „Ergebnis Baseline-Band". Kurz: −18,1 %
    Pakete ⇒ nur −11,2 % Fahrzeug-km (Elastizität 0,62), −13,8 % Gesamtkosten, **+5,2 %
    Kosten je Paket**. Die Reaktion ist **sublinear**, nicht linear wie ex ante vermutet —
    Ursache ist Flächendegression, nicht die Zeitbindung (`parcels_unassigned = 0`,
    `FleetSize.INFINITE`, Cap 7,5 h nie bindend). Absolute €/Paket-Werte sind damit
    niveaugekoppelt und im Central-Run ~5 % zu optimistisch.
  - **`[M]` Offen aus der Prüfung:** die berichtete Zustellquote weicht in **beiden** Runs
    reproduzierbar um −14 % von den konfigurierten Provider-Quoten ab (475 statt 552 bzw.
    388 statt 452 verpasste Pakete; providerweise z. B. Hermes −8 pp, DHL +3 pp). Weil es
    systematisch ist, hebt es sich in Vergleichen weg und das Band ist unberührt — aber eine
    *absolut* berichtete Zustellquote wäre zu optimistisch. Mechanismus nicht identifiziert;
    Einstieg: `CarrierGenerator.adjustDeliveryRatesConsideringB2B` (Zeile 252) und
    `determineMissedParcels` (1049), Sollwerte `HagridConfig.java:190-196`.
  - **✅ Ursache der +22 % gefunden (2026-07-27, B7).** Der EFH-Term oben ist gegen
    Zensus-2022-Gebäudedaten geprüft und **falsch**: `PANDA/data_loader.py:615` schickt jedes
    `building=yes` ohne `building:levels` per `fillna(2) <= 2` in die EFH-Klasse (und es kann
    nie MFH werden). Dieser Fallback trägt in Hannover 73,6 %, in der Lausitz **92,5 %** der
    EFH-Fläche, weil dort 91,8 % statt 57,8 % der Wohngebäude untypisiert sind. Folge:
    **44,8 %** der Lausitzer OSM-„Wohn"-Geschossfläche liegen in Zellen mit **null**
    gemeldeten Wohnungen (Hannover 28,3 %) — Scheunen und Nebengebäude, abgerechnet mit dem
    größten B2C-Koeffizienten. Korrigiert fällt der Ankerabstand von **+22,2 % auf +5,6 %**.
    Das **stützt den Low-Arm** als realistischeren Bandpunkt (Korrektur ≙ ×0,864, zwischen
    Low und Central, näher an Low); das gemessene Band bleibt gültig. Details:
    `PANDA/docs/transferability.md` → B7, reproduzierbar über
    `python -u studies/run_efh_validation.py`.
  - **✅ Proxy-Fix umgesetzt (2026-07-27, B8 + User-Entscheidung).** Ein blinder Bake-off von
    fünf Kandidaten gegen zwei **vorab** festgelegte Gates (blinder Hannover-CV darf nicht
    schlechter werden UND Anker muss näher an BIEK) hat entschieden: die Wohnfläche kommt
    jetzt komplett aus dem **Zensus-2022-Gebäudebestand × Leerstandskorrektur**
    (`PANDA/zensus_wohnflaeche.py`), nicht mehr aus OSM. Sie gewinnt auf **beiden** Achsen —
    blinder wMAPE **9,8 %** (OSM: 10,1 %) *und* Anker **+0,9 %** (OSM: +22,2 %). Also **auch
    ein Modellfehler**, nicht nur ein Transferfehler; die B7-Frage ist damit beantwortet.
    Wichtig für die Diagnose: nur den *Split* zu ersetzen (Kandidat C2) brachte fast nichts
    (+20,7 %) — der Fehler saß in der **Fläche selbst**, B7-Option (c) wäre zu schwach gewesen.
    Robust über neun Kombinationen der Klassenkonstanten (Anker +3,6…+4,8 %).
    Neue Parameter: `w_efh` 0,20 → **1,28**, `w_mfh` 0,12 → **0,99**, `rate_einwohner`
    0,051 → **0,018** (Zensus zählt Netto-Wohnfläche, OSM zählte Brutto-GF mit Nebengebäuden).
  - **`[H]` Band neu gerechnet — jetzt symmetrisch, drei Arme.** Das Band war eine Korrektur
    einer bekannten Verzerrung; die ist behoben, also ist es jetzt Restunsicherheit um ein
    verankertes Zentralniveau: **low ×0,90 = 5.430 · central ×1,00 = 6.024 · high ×1,10 =
    6.622** Pakete, 1.053 Segmente. ±10 % ist eine *erklärte Sensitivität*, kein KI (deckt
    BIEKs eigene Ungenauigkeit + Bootstrap-KI 7,5–13 % wMAPE grob ab).
    Schöner Konsistenzbefund: das neue Central (6.024) liegt fast auf dem **alten Low-Arm**
    (5.946 gemessen) — was vorher extern erzwungen war, kommt jetzt aus den Daten. Der
    High-Arm ist neu und macht die Elastizität zweiseitig; die Flächendegression sollte nach
    oben abflachen, das war vorher nicht prüfbar.
    Treiber `run_lmd_band.ps1`, Tags `bandz_{central,low,high}` (neu, damit die alten
    `band_*`-Outputs erhalten bleiben); überholte Stände als `level_osm_{central,low}`.
  - **`[M]` Offen nach dem Fix:** (1) die volle CV-Batterie ist **nicht** nachgezogen —
    Bootstrap-KI, Segment-, Cross-Carrier- und Transfer-Check im PANDA-README sind noch
    OSM-Zahlen. (2) Neue Nebenwirkung: **281 Zellen mit 630 von 6.024 Paketen (10,5 %)**
    haben kein gewichtetes OSM-Gebäude und werden per Zellzentroid auf das nächste Segment
    gesnappt — Nachfrage kommt jetzt aus dem Zensus, die Verteilung *innerhalb* der Zelle
    weiter aus OSM. Niveau unberührt, Platzierung unter 100 m nicht; unterhalb der
    dokumentierten ≳300-m-Verlässlichkeit, sollte aber nicht wachsen.
  _(added 2026-07-27)_

- **`[H]` Modular / U-Shift (Szenario 1d)** — Kapsel-Tausch, Offline-jsprit + Pax-Priorität.
  **Spike + Design ✅ 2026-07-27** ([Spike](superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md) /
  [Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md), user-approved). Kernbefunde:
  Kapsel-Tausch = **nativer** drt-Core `DefaultDrtCapacityChangeTask` (keine neue Dependency; die
  2026-07-24-Annahme „braucht drt-extensions:2025.0" ist damit **überholt** — services = Ein-Stopp-Vorlage
  zum Abschreiben, reconfiguration = nur onPrepareSim, beide ungeeignet). Entscheidungen: Pax-Sperre ab
  Dispatch (strikt); Pax-Kapsel 10 Sitze (= 1c-M1-Basis); 7 Depot-Gruppen, nur Fahrzeugtyp getauscht;
  Tour-Cap 3,5 h Konzeptparameter + 7,0-h-Kontrollarm (Reversibilitäts-Argument); Idle-Threshold voll
  gesweept, Cap 2 Punkte. **Detail-Plan noch nicht geschrieben** (nächster Schritt: writing-plans).
  ⚠️ Die 10-Sitze-Re-Baseline (unten) ist jetzt auch 1d-Voraussetzung, nicht nur 1c.
  - **Sensitivitätsidee (User 2026-07-27, zurückgestellt):** jsprit-Tourplanung als EIN Pool
    (ein Carrier, Fahrzeuge an allen 7 Depots, freie Depotwahl je Paket = stärkstes
    Einheitsunternehmen) und/oder beide Varianten als Zerlegung Konsolidierungs- vs.
    Integrationseffekt. Verwandt mit `[L]` Consolidated-Operator-Baseline. _(added 2026-07-27)_
  - **Design-Input (2026-07-20, verifiziert 2026-07-24):** die *exklusive* Pax-oder-Fracht-Phase des
    Kapsel-Tauschs passt zur nativen `drt-extensions/services`-Vorlage (`DrtServiceDynActionCreator` +
    blockierende `EntryFactory` + eigene Events = **echte** Fracht-Agent-Trennung ohne Dummy-Passagiere)
    und zum dynamischen Laufzeit-Kapazitäts-Umbau der `drt-extensions/reconfiguration`-Extension
    (`CapacityReconfigurationEngine`/-`Logic`/-`Module`). **Beide sind im `2025.0`-Release vorhanden**
    (`services` schon seit PR3552, `reconfiguration` erst ab 2025.0) — Nutzung = `org.matsim.contrib:drt-extensions:2025.0`
    als Dependency ergänzen (aktuell keine HAGRID-Dependency; `.m2` hat lokal nur veraltetes PR3552) +
    Binärkompat via Build/e2e bestätigen, KEIN größerer Bump. Beim 1d-Plan prüfen, ob das den geplanten
    Offline-jsprit-Pfad ergänzt/ersetzt. Kontext siehe Co-Riding-1c-Notizen oben (Option C).
    _(added 2026-07-20, verifiziert 2026-07-24)_
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

- **`[M]` LMD Dispatch-Stunden besser streuen** — Abfahrtszeiten der LMD-Fahrzeuge sollten
  gleichmäßiger über den Tag/die Wellen gestreut werden statt sich auf einzelne Minuten/Stunden
  zu häufen. Bereits bekannt: legacy Hannover kollabiert 171/187 Carrier auf EIN Vehicle-Template
  trotz ≥2 verfügbarer (jsprit klont früheste/billigste Kopie bei `FleetSize.INFINITE`); Lausitz'
  4-Kopien-pro-Welle (`VEHICLES_PER_TYPE_PER_WAVE`) ist zwar stärker gegen Kollaps als legacy,
  aber echte Pro-Fahrzeug-Streuung bräuchte `FleetSize.FINITE` oder einen Vehicle-Nutzungs-/
  Tour-Count-Term im jsprit-Objective — siehe [[project_lausitz_drt_freight]] „LMD stagger" Fund.
  Aufgeschoben bei der Marriage, hier erneut aufgenommen als eigener Punkt. _(added 2026-07-21)_

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

### Fallback-Audit 2026-07-27 (Medium-Tier)

Ergebnis eines gezielten Durchgangs durch `hagrid/integrated/**` + `analysis/kpi/` nach
Fallbacks, die greifen statt zu scheitern und dabei still falsche Zahlen erzeugen. Die vier
scharfen Befunde sind erledigt (siehe `## Erledigt`); hier stehen die verbleibenden.
Positiv-Befund am Rande: im gesamten `integrated`-Baum wirft **jedes** `catch` weiter — es gibt
dort kein Exception-Swallowing.

- **`[M]` Locker-Kanal ist per Konstruktion tot + `IntegratedScenarioConfig` ist totes Config-Objekt** —
  `ParcelAgentGenerator.java:59` hartcodiert `new DeliveryChannelResolver(List.of(), 500.0)`,
  also ist `share_channel_locker` strukturell 0. Das Javadoc von `DeliveryChannelResolver:10-15`
  behauptet, der Locker-Zweig aktiviere sich „ohne Codeänderung hier" — das stimmt nicht.
  Zusätzlich: `IntegratedScenarioConfig` (mit `b2cLockerShare=0.7`, Autonomie-Dwell-Faktor,
  Labour-Kosten, Retooling-Zeit) wird **nur vom eigenen Test** referenziert und erreicht keinen
  Run — liest sich aber wie aktive Konfiguration. Locker selbst bleibt Phase-2 (siehe
  `[L]` Phase-2-Deferrals); hier geht es nur darum, dass Javadoc und totes Config-Objekt
  aktuell täuschen. Verwandt: `[M]` Autonomie-Switch-Plan (dort ist `operation_mode` in
  `RunMetadataWriter.java:31` hart auf `"conventional"`). _(added 2026-07-27)_

- **`[M]` Ungeschützter Kompositions-Zweig im DRT-Config-Aufbau** —
  `DrtConfigComposer.java:63` `if (multi.getModalElements().isEmpty())`: bringt die Base-Config
  je ein `drt`-Element mit, wird die **komplette** HAGRID-Komposition übersprungen (ServiceArea,
  Fleet-File, maxWaitTime, Rebalancing, ExtensiveInsertionSearch) und die Base-Werte laufen.
  Feuert heute nicht, loggt aber auch nichts. Vorschlag: gewählten Zweig loggen, oder werfen
  wenn nicht-leer aber die erwarteten Keys fehlen. (Der zweite Teil dieses Punkts — der stille
  `PtAndDrtFareModule`-Skip — ist erledigt, siehe `## Erledigt`.) _(added 2026-07-27)_

- **`[M]` Depot-Zonenzuordnung ohne Warnung** — `ReturnToDepotRebalancingModule.java:94-106`:
  ein Depot außerhalb aller Rebalancing-Zonen hängt sich still an die nächstgelegene
  Zentroid-Zone. Zusammen mit `ReturnToDepotTargetCalculator.java:38`
  (`getOrDefault(zone, 0.0)`) zieht das die Abend-Flotte in die falsche Gitterzelle.
  Vorschlag: Fallback pro Depot loggen, Containment für In-Area-Depots assertieren.
  _(added 2026-07-27)_

- **`[M]` Analyse-Provenance: stiller Kostenbasis-Tausch + nie aufgefrischte `shared/`-Inputs** —
  (a) `extract_freight.py:~40-53`: jede Exception im Provider-Parse tauscht die Kostenbasis
  still auf die TSV-Spalten — andere Zahlen unter gleichem KPI-Namen; Vorschlag: `cost_basis`-
  Provenance-Zeile (analog zu den neuen `meta`-Rows) und im Dashboard zeigen.
  (b) `HagridPaths.java:478-489` `copyIfMissing` aktualisiert nie und warnt bei fehlender
  Quelle nur — ein veraltetes `hagrid-output/shared/sim-config.xml` oder Zonen-Shapefile
  überlebt beliebig lange Input-Änderungen. Vorschlag: Hash/Mtime vergleichen, laut warnen.
  _(added 2026-07-27)_

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

- **`[L]` `ct_cep_size_s` Doku/Daten-Divergenz** — `lmd-vehicle-types.xml` enthält drei Van-Typen
  und alle drei werden eingesetzt (je 56 Fahrzeuge in `married250` verifiziert), obwohl
  `HagridPaths.java:337` „ct_cep_size_m / _l only" dokumentiert. Kein Fallback, aber ein realer
  Effekt auf den Flotten-Mix — entweder `_s` bewusst aufnehmen (dann Doku korrigieren) oder aus
  der Typdatei entfernen. Nebeneffekt: `LmdCarrierBuilder.jitterSigmaMinutes:160-163` gibt `_s`
  per Durchfall die 15-Min-Sigma des „m"-Zweigs. _(added 2026-07-27)_

---

## Erledigt

- **Fallback-Audit Folgearbeit: M2 (strikte Parcel-Attribute) + M6 (Fare-Modul-Skip)** — ✅ 2026-07-27,
  **lokal, ungepusht**.
  - **M2:** neue `ParcelAttributes` (shareduse) baut die Population-Snapshots STRIKT und meldet
    alle Verstöße auf einmal mit Person-IDs. Damit fallen vier stille Stand-ins weg:
    `SharedUseStopDurationProvider` „1 Paket" (Depot-Pickup 30 s statt bis 600 s, Türdwell 120 s
    statt bis 900 s), `SharedUseKpiHandler` `getOrDefault(personId, 0)` (Segment zählte 0 Pakete)
    und der DOOR-Default für einen unattribuierten Kanal, sowie `ParcelOnlyRetryQueue`
    `orElse(POSITIVE_INFINITY)` (= M5-Fenster still abgeschaltet). Validierung passiert **einmal
    beim Modul-Install / Handler-Bau**, also als billiger Startup-Abbruch statt als Exception
    Stunden in die Mobsim hinein. Der Kanal wird zusätzlich gegen die
    `DeliveryChannelResolver.Channel`-Enumwerte geprüft, damit ein Tippfehler nicht mehr im
    DOOR-Bucket landet. **Nebenbefund:** der Guard hat sofort zwei Fixtures erwischt
    (`SharedUseDispatchTest`, `SharedUseRebalTest`), die Paketpersonen ohne `parcelChannel`
    bauten — also eine Population beschrieben, die real gar nicht entstehen kann; beide an
    `ParcelAgentGenerator` angeglichen.
  - **M6:** `DrtConfigComposer.installModules` loggt jetzt beide Zweige — INFO wenn
    `PtAndDrtFareModule` installiert wird, WARN mit `faresComposed=…, ptScoringParams=…` wenn
    nicht (dann fahren drt und pt monetär gratis, während Auto pro km zahlt → Mode-Choice-Bias).
    **Verifiziert, dass der echte Lausitz-Pfad das Modul bekommt** (`SharedUseEndToEndTest` loggt
    „installed") — es war also reine Logging-Härtung, kein Live-Bug. Der WARN feuert nur in zwei
    Minimal-Fixtures, die keine Tarife komponieren.

  Verifikation: 45 Unit-Tests (davon 8 neue in `ParcelAttributesTest`, je 1 neuer in
  `SharedUseStopDurationProviderTest`/`ParcelOnlyRetryQueueTest`) + 11 Integrations-/E2E-Tests grün.

- **Fallback-Audit Lausitz: die vier scharfen Befunde** — ✅ 2026-07-27, **lokal, ungepusht**.
  Gezielter Durchgang durch `hagrid/integrated/**` (~3,2k LOC) + `analysis/kpi/` (~11k LOC) nach
  Fallbacks, die greifen statt zu scheitern. Behoben:
  - **Pax-KPIs auf Shared-Use-Runs waren paketkontaminiert, die Korrektur war toter Code.**
    `extract_shareduse` berechnete `*_pax_only`, aber `render.HEADLINE_KPIS`, `render_drt`-Kacheln
    und `economics.py` lasen alle das kontaminierte `drt_rides`/`wait_median`/`modal_share_drt`.
    Neu: `analysis/kpi/pax_only.py` tauscht einmalig zentral (`<name>_pax_only` → `<name>`, Stock →
    `<name>_incl_parcels`) vor `economics.extract`; `extract_shareduse` liefert jetzt zusätzlich
    p95/below-10/below-15/in-vehicle-time/Detour/Trip-Distanz (Legs-CSV), pax-only Rejections
    (Rejections-CSV) und **alle** Modal-Shares (output_trips — gemeinsam, sonst summieren sie
    sich nicht zu 1). Was vehicle-seitig unkorrigierbar bleibt (pooling_rate, sharing_factor,
    drt_passenger_km, drt_dp_over_dt, mean_pax_aboard, drt_empty_ratio) steht als
    `meta/parcel_contaminated_kpis` in der CSV statt sich als Passagierzahl auszugeben.
  - **Veraltete vorbereitete DRT-Inputs.** Der Run prüfte nur Existenz, und der Pfad kodiert nur
    `CONCEPT_date[_tag]` — nicht `fleetSize`/Sitzzahl/`noParcels`. Real scharf: alle 11
    Fleet-Dateien auf Platte tragen `capacity="8"`, geschrieben bevor `BASE_SEATS` am 2026-07-20
    auf 10 ging. Neu: `DrtInputsFingerprint` (Properties-Datei neben den Artefakten, Parameter +
    `size:mtime` der Rohinputs + die Sitz-/Slot-Konstanten), geschrieben vom Präprozessor,
    geprüft in `validateInputFiles()` → Abbruch mit konkreter Drift-Meldung. Die
    Kapazitätsregel lebt jetzt einmal in `DrtInputsFingerprint.expectedCapacity`, damit der
    Guard nicht von dem wegdriften kann, was er bewacht.
  - **Kapazitäts-/Schicht-Nenner wurden geraten.** `read_capacity(default=8)` und der
    Sim-Horizont-Ersatz für fehlende Schichtfenster sind weg: ohne Fleet-Datei liefert
    `drt_service_time.reconstruct` kein `capacity`/`util_*`/`ratio_shift`/`sum_shift_s` mehr,
    `extract_drt` lässt die betroffenen KPIs weg und setzt `meta/fleet_file_missing`.
    Zusätzlich emittiert `extract_drt` jetzt `system/drt_vehicle_capacity` — genau den KPI, den
    `maps._read_cap` suchte, dessen Suchmuster aber nie matchen konnte (immer `DEFAULT_CAP=8`).
  - **Fehlende `run_metadata.json` degradierte still.** `writeRunMetadataSafely` schluckt jeden
    Fehler, `load_run_meta` fiel dann wortlos auf Verzeichnisnamen-Parsing zurück
    (`fleet_size=None` → **gar keine** Kosten-KPIs). Jetzt laute Warnung + `meta_source` auf
    `RunMeta` + `meta/run_meta_degraded` in der CSV. (Betrifft real z.B.
    `DRT_BASELINE_13052025_fleet80_depot_railpt_iter150_jsprit100`.)
  - **`SharedUseModule` Retry-Params gehärtet** (`orElseGet(new …)` → `orElseThrow`): der
    MATSim-Default ist `maxRequestAge=0`, also *kein Retry* — das hätte die gesamte
    M5-Fensterlogik still abgeschaltet und δ von `segments_window_expired` nach
    `segments_rejected_final` verschoben.

  Verifikation: Python 210 Tests grün (vorher 193, +17 neue in `test_pax_only.py`,
  `test_fleet_provenance.py`, `test_extract_drt.py`); Java-Modulsuite grün; H1 zusätzlich
  end-to-end gegen den echten `SharedUseEndToEndTest`-Output geprüft. Verbleibende Befunde:
  siehe „Fallback-Audit 2026-07-27" unter Medium und Low.

- **MATSim-Core-Bump `2025.0-PR3552` → `2025.0`** — ✅ 2026-07-20. Branch `bump/matsim-2025.0`
  (von `hendrik`, **nicht** gemergt/gepusht). Approach A (in-place Property-Bump, fix-forward). Ergebnis:
  freight-Fork kompiliert **unverändert** gegen 2025.0 (kein PR3552-Patch-Drop nötig); matsim-lausitz-2.0
  binär **kompatibel** (keine Transitive-/`NoSuchMethod`-Konflikte, kein Lausitz-Neubau). API-Delta nur in
  HAGRID-eigenem Code: (1) `DrtConfigGroup`/`DvrpConfigGroup`/`RebalancingParams`/`MinCostFlow…Params`
  public-fields → getter/setter; (2) Rebalancing-Zone-System + Target-Link-Selection von entferntem
  `DrtZoneSystemParams` (DRT-Gruppe) → `RebalancingParams` (`getZoneSystemParams`/`setTargetLinkSelection`);
  (3) `DefaultDrtOptimizationConstraintsSet` → `DrtOptimizationConstraintsSetImpl`; (4) `DisallowedNextLinks`
  → `core.network.turnRestrictions`; (5) `FleetWriter` braucht `DvrpLoadType` → `IntegerLoadType("passengers")`
  (DVRP-Default); (6) `ReturnToDepotRebalancingModule` holt die Rebalancing-`ZoneSystem` jetzt aus dem
  modalen `MapBinder` (`REBALANCING_ZONE_SYSTEM`) statt via `getModal(ZoneSystem.class)`. Volle Suite grün:
  parcel-pipeline **282/0/0** inkl. aller vier e2e (Drt/Married/DrtRailIntermodal/Lmd), freight-Modul grün.
  jsprit bleibt **1.8**. Kein Sim-Run/keine Re-Baseline in diesem Scope. Commits `a3349fe`, `c292062`.
- **freight Fork + Submodule (Restructure Schritt 1)** — ✅ 2026-07-14. Copy-Vendoring durch
  Submodule `external/matsim-libs` (Fork `TUBS-IVS/matsim-libs`) ersetzt; sim-PC warm + kalt
  validiert. → [Spec](superpowers/specs/2026-07-13-freight-fork-submodule-design.md) / [Plan](superpowers/plans/2026-07-13-freight-fork-submodule.md)
- **matsim-lausitz aus `libs/` (Restructure Schritt 2)** — ✅ 2026-07-13. Jar+POM als projekt-lokales
  File-Repo committed.
- **1e KPI-CSV + Dashboard** — ✅ 2026-07-09. Kanonisches `analysis/kpi/`-Paket.
