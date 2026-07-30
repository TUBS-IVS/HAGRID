# HAGRID Methods-Log — Entscheidungen, Limitations, zurückgezogene Befunde

Sammlung der **methodischen Substanz** quer über die HAGRID-/Lausitz-Arbeit: was entschieden
wurde und warum, welche Kennzahlen wie belastbar sind, was sich als nicht haltbar erwiesen hat,
und was bewusst außerhalb des Scopes liegt.

**Konsument ist das Paper** (Methods · Limitations · Discussion), nicht die Tagesplanung.
Deshalb gliedert dieses Dokument nach Zweck, nicht nach Szenario oder Datum — der
jsprit-Rauschboden betrifft 1c, 1d *und* das Emissions-Paper.

**Abgrenzung zu den Nachbardokumenten:**

| Dokument | Inhalt | Lebenszyklus |
|---|---|---|
| [BACKLOG.md](BACKLOG.md) | was noch **zu tun** ist | stirbt beim Erledigen |
| **METHODS-LOG.md** (hier) | was wir **wissen/entschieden** haben und was davon nicht trägt | wird nie gelöscht, nur annotiert |
| [BACKLOG-DONE.md](BACKLOG-DONE.md) | was **erledigt** wurde, mit Nachweis/Commit | Archiv |

Erledigtes, das ändert *wie eine Zahl zu lesen ist*, steht in **beiden**: Nachweis in
BACKLOG-DONE, Konsequenz für die Zahl hier.

**Status-Marken:** `trägt` = gemessen/belegt und über dem Rauschen · `vorläufig` = gesetzt, aber
noch nicht belegt · `zurückgezogen` = war ein Befund, ist keiner mehr · `offen` = Entscheidung
steht aus.

**Pflege:** wird im Arbeits-Workflow mitgepflegt. Jeder Eintrag trägt Datum, Status und — wo es
einen gibt — den Reproduktionspfad. _Zuletzt aktualisiert: 2026-07-30._

---

## 1 · Methodische Entscheidungen

### 1.1 Szenario-Architektur

- **1c ist echtes Co-Riding, umgesetzt als Option A** — `trägt` · 2026-07-20
  Pax + Paket *gleichzeitig* an Bord (2D-Kapazität, Spec §4.2/§6.1), nicht Exklusiv-Phase.
  Umsetzung: Dummy-Parcel-Agenten + natives `DvrpLoad`; die Trennung Fracht/Pax passiert nur auf
  **Event-/KPI-Ebene**. Begründung: eine *native* Trennung separater Fracht- von
  Passagieragenten *bei gleichzeitigem* Co-Riding existiert in MATSim nicht (→ §4.3, Option C).
  Konsequenz für KPIs: siehe §2.4.

- **1c-Kapazitäts-Plumbing = native Core-API, kein Fork** — `trägt` · verifiziert 2026-07-24
  `org.matsim.contrib.dvrp.load` in `dvrp:2025.0` (schon auf dem Classpath): `DvrpLoad`/
  `DvrpLoadType`, `IntegersLoad`/`IntegersLoadType` (mehrdimensional → Sitze + Paket-Slots),
  `DvrpLoadFrom{Fleet,Vehicle}`, `DvrpLoadModule`, `DvrpLoadParams`; Request→Load nativ über
  `dvrp.passenger.DvrpLoadFromTrip`. **`PersonsLoadType`/`GoodsLoadType` existieren nicht** —
  die zwei Dimensionen definiert man selbst über `IntegersLoadType`. Verifiziert gegen lokales
  `.m2` `dvrp-2025.0.jar` + GitHub-Tag `2025.0`. (Ersetzt eine frühere Fehlannahme, → §4.3.)

- **1d Kapsel-Tausch = nativer `DefaultDrtCapacityChangeTask`** — `trägt` · 2026-07-27
  Keine neue Dependency. `drt-extensions/services` = Ein-Stopp-Vorlage zum Abschreiben,
  `reconfiguration` = nur `onPrepareSim` — beide ungeeignet für den laufenden Tausch.
  → [1d-Spike](superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md)

- **Konzeptparameter leben in `HAGRIDSimulationConfig` + Szenario-Konstanten, nicht in einem
  zentralen Config-Objekt** — `trägt` · 2026-07-30 (Design D8, zweimal angewandt)
  `IntegratedScenarioConfig` war als Parameter-Objekt der integrierten Szenarien entworfen
  (Foundations-Plan 2026-06-18, Task 4), hat aber **nie einen Run erreicht**: 1c führte
  `chiThreshold`, 1d `idleThreshold`/`maxTourDurationSeconds` bewusst über
  `HAGRIDSimulationConfig` (+ CLI) und legte die festen Werte nach `Modular` — explizit, um
  doppeltes Config-Plumbing zu vermeiden (D8). Damit standen sieben Parameter zweimal im Code, eine
  gepflegte und eine ungepflegte Quelle; `depotCount=3` war schon sachlich überholt (`DepotNetwork`
  nimmt die Depot-Liste, ein Zählparameter existiert nicht), `b2cLockerShare=0.7` parametrisierte
  ein Phase-2-Feature mit struktureller 0 (§2.10). **Konsequenz 2026-07-30:** Duplikate entfernt,
  Klasse auf den Autonomie-Kern (Design-Spec §4.4) eingedampft und im Javadoc als unverdrahtet
  deklariert. **Kein Zahleneffekt** — die Klasse war produktionstot, keine berichtete Zahl hat je
  aus ihr gelesen. Neue Konzeptparameter gehen künftig denselben Weg.

- **Der Autonomie-Switch ist kein Config-Task** — `trägt` · 2026-07-30 (Befund; Umsetzung vertagt)
  Die vier gekoppelten §4.4-Effekte der Design-Spec landen an **vier verschiedenen Stellen**:
  Speed-Cap = Fahrzeug-`maximumVelocity`; **Autobahn-Ausschluss = mode-restringiertes Netz +
  `MultimodalNetworkCleaner`**, nicht der Speed-Cap (die Spec ist explizit: ein gedeckeltes
  Fahrzeug würde auf dem Autobahn-Link nur kriechen, der Router könnte ihn weiter wählen — dazu die
  Konnektivitätsprüfung, dass Zone und alle Depots ohne Autobahn erreichbar bleiben);
  Roboter-Dwell wirkt in der jsprit-Tourplanung **und** der DRT-Stop-Dauer; die Labour-Abschaltung
  sitzt vollständig in `analysis/kpi/economics.py` und fällt damit mit dem Neubau der
  Kostenfunktion zusammen (§2.6). Für die Zahlen: bis der Switch gebaut ist, sind **alle** Läufe
  konventionell — `RunMetadataWriter` schreibt `operation_mode="conventional"` hart, und das ist
  korrekt, kein stiller Fallback. Ein Autonomie-Ergebnis ist bislang **nicht** berichtet worden.

- **χ-Gate ist Detour-only** — `trägt` · 2026-07-27 (Review-Fund F1, kritisch)
  `ChiGateInsertionCostCalculator` zieht die **stoppeigene Dwell-Zeit** vor dem χ-Vergleich ab;
  χ<0 bleibt hart geschlossen. Vorher maß das Gate `totalTimeLoss` *inklusive* der Dwell-Zeit des
  eingefügten Pakets — ein Umweg-Gate, das strukturell mit der **Paketgröße** statt dem Umweg
  korrelierte. Pilotbeweis `suhead600`: δ=0,40, zugestellte Segmente ⌀1,42 vs. verworfene ⌀8,0
  Pakete/Segment. Caveat zur Kennzahl selbst: §2.3.

### 1.2 Parametrisierung

- **Sitz-Basis: Baseline 10 Sitze, Shared-Use 8 Sitze + 20 Paket-Slots** — `trägt` · 2026-07-20 (M1)
  Erzwingt eine Re-Baseline der married-Runs (alte Läufe fahren cap=8). Der
  `DrtInputsFingerprint`-Guard blockiert seitdem stille Kapazitäts-Drift.

- **1d-Konzeptparameter** — `trägt` · 2026-07-27, C4 revidiert 2026-07-28
  Pax-Sperre ab Dispatch (strikt) · Pax-Kapsel 10 Sitze (= M1-Basis) · 7 Depot-Gruppen, nur
  Fahrzeugtyp getauscht · Tour-Cap **3,5 h** als Konzeptparameter + **7,0-h-Kontrollarm**
  (Reversibilitäts-Argument) · Idle-Threshold voll gesweept, Cap 2 Punkte · **Tagesfenster
  07:30–21:00, keine Dispatch-Waves** (Begründung: Pakete kommen nachts im Depot an,
  Tageszustellung zählt, Uhrzeit nicht) · Provider-Interleave mit akzeptiertem 07:16-Surge.
  → [1d-Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md)

- **Zeitfenster je Sendungstyp: B2B 07:30–17:00, B2C 07:30–20:00** — `trägt` · 2026-07-20 (M5)
  → revidiert 2026-07-29: 21:00-Vereinheitlichung, s. u.

- **Einheitliches Lieferfenster 07:30–21:00 für ALLE DREI Arme** — User-Entscheidung · 2026-07-29,
  auf die Baseline erweitert 2026-07-30 · umgesetzt 2026-07-30
  1d fuhr 07:30–21:00 schon (C4). 1c und die **Baseline** sind nachgezogen; eine einzige Konstante
  (`hagrid.integrated.DeliveryDay`) ist jetzt die Quelle für alle drei, weil pro-Szenario-Konstanten
  genau das Auseinanderdriften einladen, das hier aufgefallen ist.
  **Vorher standen drei verschiedene Fenster im Code:**

  | Arm | vorher | jetzt |
  |---|---|---|
  | Baseline (`LmdCarrierBuilder`) | 08:00–20:00 | 07:30–21:00 |
  | 1c Shared-Use (`SharedUse`) | B2B 07:30–**17:00** / B2C 07:30–**20:00** | 07:30–21:00, ohne Typ-Split |
  | 1d Modular (`Modular`) | 07:30–21:00 | unverändert |

  **Warum die Baseline mit muss (Befund 2026-07-30):** die integrierten Szenarien werden *gegen*
  die Baseline gemessen. Ein engeres Baseline-Fenster ist kein neutraler Unterschied — weniger
  Zustellzeit heißt systematisch niedrigere Zustellquote, also wäre ein Teil des
  „Integrationsvorteils" ein Fenster-Artefakt gewesen. Die Entscheidung vom 29.07. nannte nur 1c
  und 1d; die Baseline war eine Lücke, nicht eine bewusste Ausnahme.
  **Limitation, die ins Methods-Kapitel gehört:** 21:00 gilt auch für **B2B**, und ein
  Geschäftsempfänger ist um 21:00 nicht da. Bewusster Tausch — Vergleichbarkeit über die Arme
  schlägt Realismus je Sendungstyp, und 1d hatte diese Eigenschaft konstruktiv schon (sein
  Tagesfenster unterscheidet B2B/B2C nicht). Das Fenster begrenzt den *Servicebeginn*; Stopp und
  Rückfahrt liegen danach, die letzte Zustellung fällt also etwas vor 21:00.
  **Konsequenz für bestehende Läufe:** `chid600`/`chid600i` (1c, alte M5-Fenster), `base10c`
  (Baseline, 08:00–20:00) sowie **alle älteren Baseline-Läufe** sind für Headline-Vergleiche
  **neu zu fahren**. Die 1d-Läufe (`ctrl1d`, `m1d050`) sind nicht betroffen — sie fuhren 21:00
  bereits.
  **Nicht angetastet:** das Hannover-Fenster (`HagridConfig.Routing.deliveryWindowStartHour/EndHour`
  = 8/20). Es ist eine separate Studie; eine Angleichung hätte alle 51 Läufe der
  Kapazitäts-Sensitivität unvergleichbar gemacht.

- **Joint-Cost-Allokation: marginale Attribution** — `trägt` · 2026-07-20 (M11)
  Paketkosten = akzeptierter `totalTimeLoss` × Fahrzeug-Zeitrate + Zustell-km × km-Rate
  (nutzt die χ-Messung wieder); Pax tragen die Basis-Flottenkosten. Regel ist *vor* den Runs
  fixiert, um Ad-hoc-Wahl zu vermeiden. Gilt unter dem Vorbehalt aus §2.6.

- **not-at-home beidseitig 100 % konsistent** — `trägt` · 2026-07-20 (M10)

### 1.3 Nachfragemodell (PANDA → HAGRID)

- **Wohnfläche kommt aus dem Zensus-2022-Gebäudebestand × Leerstandskorrektur, nicht aus OSM**
  — `trägt` · 2026-07-27 (B8)
  Entschieden per **blindem Bake-off** von fünf Kandidaten gegen zwei **vorab** festgelegte Gates
  (blinder Hannover-CV darf nicht schlechter werden UND Anker muss näher an BIEK). Der Gewinner
  (`PANDA/zensus_wohnflaeche.py`) nimmt **beide** Achsen: blinder wMAPE **9,8 %** (OSM: 10,1 %)
  *und* Ankerabstand **+0,9 %** (OSM: +22,2 %). Robust über neun Kombinationen der
  Klassenkonstanten (Anker +3,6…+4,8 %).
  Neue Parameter: `w_efh` 0,20 → **1,28** · `w_mfh` 0,12 → **0,99** · `rate_einwohner`
  0,051 → **0,018** (Zensus zählt Netto-Wohnfläche, OSM zählte Brutto-GF mit Nebengebäuden).
  Diagnostisch wichtig: nur den *Split* zu ersetzen (Kandidat C2) brachte fast nichts (+20,7 %) —
  der Fehler saß in der **Fläche selbst**. Details: `PANDA/docs/transferability.md` → B8.

- **Nachfrage-Band = ±10 % erklärte Sensitivität, kein Konfidenzintervall** — `trägt` · 2026-07-28
  Drei Arme um ein extern verankertes Zentralniveau. Die ±10 % decken BIEKs eigene Ungenauigkeit +
  den Bootstrap-KI (7,5–13 % wMAPE) grob ab. Räumliche Verteilung über die Arme unverändert.
  Treiber `run_lmd_band.ps1`, Tags `bandz_{central,low,high}`.
  Niveaus **wie gemessen** (Eingaben jetzt archiviert als `level_ctrsnap_*`): low 5.426 · central
  6.020 · high 6.618 Pakete auf 1.053 Segmenten. Niveaus **aktuell gestaged** (nach dem
  Zentroid-Snap-Fix, → §2.8): **5.461 / 6.058 / 6.642** auf 1.131 Segmenten — gleiche Erwartungs-
  werte (5.412 / 6.013 / 6.615), abweichende Realisierung nur wegen der stochastischen Rundung
  auf mehr Segmenten. Überholte Stände: `level_osm_{central,low}` (OSM-Proxy),
  `level_ctrsnap_{central,low,high}` (Zensus + Zentroid-Snap).
  (Ersetzt das asymmetrische Zwei-Run-Band, → §3.2.)

- **Residual-PLZ statt `00000`-Sentinel** — `trägt` · 2026-07-28
  `_segment_plz` stimmte nur über OSM-Gebäude ab, also bekam jedes Segment, das ausschließlich
  Residualnachfrage trägt, den Sentinel `00000`: **58 Punkte mit 570 Paketen (9,8 %)**. Jetzt
  stimmen auch die Residualzellen mit ihrer eigenen PLZ ab → **0 Sentinel-Punkte**. Im
  integrierten LMD-Pfad war das harmlos (`LmdDemandReader.group()` gruppiert nach Provider,
  `postal_cod` fließt nur in `ParcelStatisticsLogger`), es verfälschte aber die PLZ-Statistik und
  hätte im Hannover-Legacy-Pfad (`DemandProcessor`, Schlüssel `provider_PLZ`) eine Scheingruppe
  erzeugt.
  Dabei gefunden und mitbehoben: `export_demand.run()` übergab `_segment_plz` die **unprojizierten**
  Zellen, während die Segmente in EPSG:25832 lagen. Vorher folgenlos (die Funktion nutzte keine
  Geometrie), mit dem Residual-Join aber ein **stiller** Fehler — `shapely.STRtree` kennt kein CRS
  und liefert bei Mismatch einfach keine Treffer. `distribution.py` prüft die CRS-Gleichheit
  jetzt explizit und hat einen Regressionstest dafür.

- **Gemessenes Ergebnis des Bandes** — `trägt` · 2026-07-28
  (`bandz_{low,central,high}`, je `maxIter=0 jspritIter=100`, 1 h 46 / 1 h 34 / 1 h 40, Stand je
  Arm per SHA256 verifiziert.) Pakete 5.426 / 6.020 / 6.618 · Fahrzeuge 59 / 63 / 67 ·
  Gesamtkosten 11.524 / 12.097 / 13.438 € · € je Paket 2,124 / 2,009 / 2,031 ·
  `parcels_unassigned = 0` überall (7,5-h-Cap bindet nie).
  **Belastbare Bogen-Elastizitäten** über die volle Spanne: Fahrzeuge **0,62** · Tour-Stunden
  0,93 · **Gesamtkosten 0,76**. Die 0,76 reproduzieren die erste Bandmessung exakt — auf einem
  *anderen* Nachfragemodell und mit drittem Arm.
  **Konsistenzbefund:** der neue Central-Run landet operativ fast exakt auf dem alten Low-Run
  (6.020 vs. 5.946 Pakete, 2,009 vs. 2,012 €/Paket) → die Umverteilung OSM→Zensus *innerhalb* der
  Region ändert die LMD-Effizienz bei gleichem Niveau praktisch nicht. **Niveau-Unsicherheit ist
  erster Ordnung, Muster-Unsicherheit zweiter.**
  Nicht belastbar sind die Fahrleistungs-Kanäle desselben Experiments → §2.1/§3.1.

### 1.4 Emissionen

- **EMEP/EEA direkt, OHNE MATSim-Emissions-Contrib** — `trägt` · 2026-07-28
  Kontext: das Emissions-Paper ist sekundär (~6 Seiten) und braucht **keine Emissionskarten** →
  die beiden Contrib-Vorteile (link-aufgelöste Karten, HBEFA-Kaltstart-Maschinerie) entfallen;
  beides kann die Python-Pipeline ohnehin selbst. Aufwand ~1–3 Tage statt 1–2 Wochen
  Contrib-Verdrahtung.
  **Methodischer Bonus:** der direkte Weg wendet die COPERT-Kurven auf **Trip-Mittelwerte** an
  (deren Intention), statt sie in HBEFA-Verkehrssituationen zu pressen — die Mapping-Naht
  verschwindet komplett aus den Limitations.
  → [Plan](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md)

- **Tier 3, voller Schadstoffsatz** — `trägt` · 2026-07-28
  e(v)-Kurven auf Trip-Ø-Geschwindigkeit aus Events (Guidebook-Empfehlung bei vorhandenen vkm +
  Ø-Speed). Kern-KPI **CO₂e**, dazu NOx/PM/CO/VOC/SPN23 (nur CO₂ wirkte methodisch dünn).
  **Faktorquelle:** Appendix 4 zum Guidebook-Kapitel 1.A.3.b.i–iv, Version Okt 2025.
  **Zitat-Caveat (2026-07-28):** der offizielle Werkstitel ist „EMEP/EEA air pollutant emission
  inventory guidebook **2023**"; 2025 ist ein *Update* davon. Die interne Kurzform „Guidebook 2025"
  ist als Provenance eindeutig, in der **Paper-Referenz** muss „Guidebook 2023, Update 2025" stehen.
  Rohdateien + Prüfsummen liegen lokal in `hagrid-input/emissions/` (untracked, `SOURCES.md`).
  Eigenschaften der Quelle: **CO₂ ist keine Tabellenzeile** — kraftstoffbasiert aus der EC-Kurve
  (MJ/km → FC → CO₂, Diesel ~3,17 kg/kg; der Energie-KPI fällt gratis ab). BEV-Zeilen (nur EC)
  existieren für beide Kategorien.

- **Klassenmapping: beide Flotten = N1-III Diesel Euro 7, DPF+SCR** — `trägt` · 2026-07-28
  **DRT-Flotte** in allen drei Szenarien N1-III (masse-nächste Klasse für Sprinter-Klasse-Minibus
  ~3,5–5,5 t). „Urban Buses Midi ≤15 t" **verworfen**: repräsentiert 9–12-t-Midibusse →
  überzeichnet NOx/PM grob 2–3×, Kurven enden bei 80 km/h, Steigungs-/Beladungsstrata sind in
  MATSim ohnehin nicht belegbar (optional als *obere* Sensitivitätsgrenze).
  **LMD**: alle drei Van-Typen (`_s`/`_m`/`_l`) ebenfalls N1-III → beide Flotten einheitlich.
  Caveats: §2.7.

- **Systemgrenze: Well-to-Wheel quantitativ** — `trägt` · 2026-07-28
  Appendix 4 ist strikt TTW + Abrieb, **keine Vorkette/LCA**. WTT über EC-Kurve × externe
  Faktoren (JEC WTW v5 bzw. DIN EN 16258/GLEC für Diesel, UBA-Strommix für BEV; Pipeline-Hook
  `WTT_CO2E_G_PER_MJ` existiert). Fahrzeug-/Batterie-Lebenszyklus (Cradle-to-Grave) nur
  **qualitativ** im PB-Kontext (Novel Entities) — volle LCA sprengt 6 Seiten (§4.4).

- **EV = Faktorsatz-Switch im Postprocessing, kein Sim-Rerun** — `trägt` · 2026-07-28
  Auspuff→0, BEV-EC-Kurve × Netzintensität statt Diesel-Pfad, Non-Exhaust-Korrektur,
  Idle/Kaltstart→0. EV wird mitimplementiert, aber **sekundär** priorisiert. Vorbehalt: das
  EV-Reichweiten-Gate kann diese Entscheidung kippen (BACKLOG, `[H]`).

- **`hagrid_output_analysis/emissions.py` wird NICHT angefasst** — `trägt` · 2026-07-28
  Constraint: das Modul wurde vom Kollegen in einem Paper verwendet, Abstimmung frühestens
  ~2026-08-11. Stattdessen **Kopie** der tragenden Logik (Drive/Idle/Engine-on) als neues Modul
  unter `analysis/kpi/`, nur im Lausitz-Szenario getriggert — strukturelle Trennung statt
  Namenskonvention; Faktorschicht beim Kopieren direkt durch EMEP/EEA ersetzt.

### 1.5 Reproduzierbarkeit

- **jsprit-Seed ist steuerbar: `-Dhagrid.jsprit.seed`** — `trägt` · 2026-07-28, Commit `06c2707`
  **Gotcha für jeden, der das nachbaut:** jsprits globales `RandomNumberGeneration.setSeed()`
  wirkt **nicht** — `Jsprit.Builder` holt seinen RNG über `newInstance()`, das immer den festen
  `DEFAULT_SEED` nimmt. Tragender Hook ist `Builder.setRandom()` vor `buildAlgorithm()`.

- **MATSim-Seed ist CLI-Key, nicht globaler Default** — `trägt` · 2026-07-27
  Vorher globaler Default → Runs waren nicht per Punkt reproduzierbar. Enabler für Fehlerbänder.

- **Berichtsregel für km-basierte KPIs: ≥10 Sim-Runs, Mittelwert + Min/Max** — `trägt` · 2026-07-28
  Ersetzt die frühere 3–5-Seed-Empfehlung. Begründung: §2.1 (Rauschboden) + §2.2 (Iterationskosten).

---

## 2 · Limitations & Rauschböden

### 2.1 jsprit-Rauschboden auf der Fahrleistung: 6,5 %

`trägt` (gemessen, nicht geschätzt) · 2026-07-28 · Reproduktion: identischer Central-Stand,
identische 100 Iterationen, nur anderer `-Dhagrid.jsprit.seed`.

Pakete und verpasste Pakete bitgleich, aber: **Fahrzeug-km −6,5 %** · km/Paket −6,5 % ·
Tour-Stunden −3,2 % · Touren −1,6 % · **Gesamtkosten nur −0,8 %**. Je Carrier bis **±30,6 %** km
(ups 494→343), Tourenzahlen kippen mit (dhl 24→22, ups 4→3) — **dieselbe Größenordnung wie die
„Effekte" zwischen den Bandarmen.**

**Signal-Rausch je Kennzahl:**

| Kennzahl | S/R | Verdikt |
|---|---|---|
| Gesamtkosten | 6× / 14× | **trägt** |
| Touren | 4× | **trägt** |
| Tour-Stunden | 2,4× / 3,4× | knapp |
| km/Paket | **0,5×** | **im Rauschen** |

**Nebenbefund mit Folgen:** der Alternativ-Seed findet die *bessere* Lösung (−6,5 % km bei
−0,8 % Kosten) → der Default-4711-Lauf ist **beliebig, nicht gut**; Distanzen sind bei 100
Iterationen unkonvergiert und **pessimistisch verzerrt**. Kosten dagegen praktisch ausoptimiert.

Mechanismus: die Zielfunktion ist **fixkostendominiert (81 %)** → jsprit optimiert die
Fahrzeugzahl (saubere +4-Schritte) und behandelt Distanz als schwachen Term. **Fahrzeugzahl
konvergiert, Tourengeometrie nicht.**

### 2.2 `jspritIter=100` genügt nicht für km-basierte KPIs

`trägt` · 2026-07-28 · **Betrifft direkt die distanzbasierten Emissionen.**
Ausreichend für Flotten- und Kostenaussagen; nicht für Fahrleistung, km/Paket oder
distanzbasierte Emissionen.

**⚠️ Kostenannahme korrigiert 2026-07-30 (die Zahl trug nicht, der Befund trägt).** Die
ursprüngliche Fassung nannte „~4 h pro Carrier, also ~20 h je Arm" auf Basis einer nach 2 von 7
Carriern abgebrochenen Messung. Aus dem `base10c`-Lauf (night1d-Batch, 29./30.07.) ist die
jsprit-Phase jetzt **vollständig** ausgelesen — jsprit loggt seinen Algorithmus-Start je Carrier
als JVM-Laufzeit, damit ist die Phase exakt zerlegbar:

| Carrier | Jobs | @`jspritIter=100` | ×10 (obere Schranke, s. u.) |
|---|---|---|---|
| 1 (größter) | 824 | 22,3 min | ≤ 3,7 h |
| 2 | 599 | 11,2 min | ≤ 1,9 h |
| 3 | 457 | 10,1 min | ≤ 1,7 h |
| 4 | 398 | 8,2 min | ≤ 1,4 h |
| 5 | 356 | 7,9 min | ≤ 1,3 h |
| 6 | 198 | 2,4 min | ≤ 0,4 h |
| 7 | 143 | 2,0 min | ≤ 0,3 h |
| **Σ (7 Lausitz-Carrier)** | **2.975** | **64 min** | **≤ 10,7 h** |

**Laufzeit skaliert mit `jobs^1,4`** (gemessen, nicht geschätzt: die Exponentenschätzung ist über
drei unabhängige Carrier-Paare stabil — 824/143, 824/398, 599/198 ergeben 1,38 / 1,38 / 1,40).
Superlinear, aber **nicht** quadratisch.

⚠️ **Die ×10-Spalte ist eine obere Schranke, keine Prognose.** `configureAlgorithm` hängt ein
`IterationWithoutImprovementTermination` an, dessen Geduld selbst von der Iterationszahl abhängt
(`calculateNoImprovementThreshold`: `min(iters/4, round(14·ln iters))` → **25** bei 100, **97** bei
1000). Bei `maxIter=100` haben alle 7 Carrier die Marke 64 überschritten (jsprit loggt in
Zweierpotenzen, also ≥64 und ≤100) — das Budget wurde praktisch ausgeschöpft. Bei `maxIter=1000`
ist offen, ob die Suche 1000 Iterationen läuft oder nach einigen Hundert abbricht; damit liegt der
echte Wert **irgendwo zwischen ~4 h und ~10,7 h**. Sauber messbar nur direkt — genau das tut die
3-Seed-Sonde auf Carrier 1 (BACKLOG).

Gegenprobe: `base10c` startete 23:29:12, `ITERATION 0 BEGINS` um 00:34:39 → 65,5 min bis zur
MATSim-Schleife. Der Rest des Laufs (150 MATSim-Iterationen) sind ~4 h 50.
**Fehlerursache der alten Zahl:** linear vom *größten* Carrier hochgerechnet. Die Carrier sind
extrem ungleich (Faktor 11 zwischen Nr. 1 und Nr. 7, deckt sich mit §2.1: dhl 24 Touren, ups 4);
die beiden größten ergeben @1000 zusammen 5,6 h — das *ist* die abgebrochene 5,8-h-Messung, und
„4 h pro Carrier" war Carrier 1 allein. → Zurückziehung §3.8.

**Folge für die gewählte Antwort:** Multi-Run-Mittelung (§1.5) bleibt richtig, aber die
Verwerfung des Iterations-Hochlaufs stand auf einer 2× zu hohen Zahl und ist **neu zu bewerten** —
zumal die Carrier in `LausitzFreightPreprocessor.routeWithDurationCap` strikt **sequenziell** auf
einem Thread gelöst werden (BACKLOG-Punkt Carrier-Parallelisierung); parallel fällt die Wall-Clock
auf den größten Carrier, also ~3,7 h statt 10,7 h.

### 2.3 χ ist eine untere Schranke, nicht der Umweg

`trägt` · 2026-07-27 · **Paper-Caveat.** Bei Teil-Piggyback misst χ den *verworfenen* Umweg nur
nach unten. Zusätzlich: **n=1 Seed pro χ-Punkt** in den bisherigen 1c-Läufen.

### 2.4 Pax-KPIs unter Co-Riding: was unkorrigierbar bleibt

`trägt` · 2026-07-27 · Konsequenz aus §1.1 (Option A).
Nach dem Fix laufen die Headline-Pax-KPIs auf pax-only-Größen. **Vehicle-seitig unkorrigierbar**
bleiben: `pooling_rate`, `sharing_factor`, `drt_passenger_km`, `drt_dp_over_dt`,
`mean_pax_aboard`, `drt_empty_ratio` — diese stehen als `meta/parcel_contaminated_kpis` in der
CSV, statt sich als Passagierzahl auszugeben.
⚠️ **Für die Zahleninterpretation:** alle Pax-KPIs aus Shared-Use-Runs **vor** dem 2026-07-27-Fix
sind paketkontaminiert (die Korrektur existierte, war aber toter Code). Nachweis:
[BACKLOG-DONE.md](BACKLOG-DONE.md) → Fallback-Audit.
Positiv-Nachweis der Sauberkeit der Mischung: der `noParcels`-Probelauf ist bei Iteration 0
**bit-identisch** zum χ→0-Shared-Use-Lauf, Abweichung ab Iteration 1 < 0,7 %.

### 2.5 Berichtete Zustellquote ist ~14 % zu optimistisch

`offen` (Mechanismus nicht identifiziert) · 2026-07-27
Die berichtete Quote weicht in **beiden** Bandarmen reproduzierbar um −14 % von den
konfigurierten Provider-Quoten ab (475 statt 552 bzw. 388 statt 452 verpasste Pakete;
providerweise z. B. Hermes −8 pp, DHL +3 pp). Weil es **systematisch** ist, hebt es sich in
Vergleichen weg und das Band ist unberührt — eine *absolut* berichtete Zustellquote wäre aber zu
optimistisch. Einstieg für die Ursachensuche:
`CarrierGenerator.adjustDeliveryRatesConsideringB2B:252`, `determineMissedParcels:1049`,
Sollwerte `HagridConfig.java:190-196`.

### 2.6 Kostenfunktion ist ein Platzhalter

`vorläufig` · Alle €-KPIs stehen unter diesem Vorbehalt. `analysis/kpi/economics.py` rechnet mit
25 €/Fahrzeug-Schicht-h (20 Arbeit + 5 Fahrzeug); das DRT-Dashboard trägt zwei
Platzhalter-Karten (Bottom-up 25 € / Literatur-Benchmark 68 €). Widersprüchliche Literaturwerte
noch unaufgelöst (150.000 € pauschal mit Verweis Currie/Fournier vs. 408.000 € Benchmark /
35,25 € pro Fahrt). Betrifft auch die Elastizitäts-Aussage aus §1.3, die über Fixkosten läuft —
die *Richtung* ist robust, das Niveau nicht.

### 2.7 Emissions-Caveats

- **Euro-7-Faktoren sind projiziert** — im Paper als solche kennzeichnen.
- **LCV-Kurven haben kein N2O/NH3** → Tier-2-Pauschalergänzung aus dem Hauptkapitel;
  CO₂e-Beitrag beim Diesel-LCV ~1–2 %, vertretbar.
- **`_l`-Van (230, 6 m, keine Masse in der XML) bleibt Grauzone** zum leichten Lkw → optional
  einmal als HDT „Rigid ≤7,5 t" gegenrechnen, damit die Bandbreite ausgewiesen statt versteckt ist.
- **Stop&Go wird in EMEP/EEA systematisch niedriger bewertet als in HBEFA** (Literaturbefund) —
  für Szenarien*vergleiche* unkritisch (konsistent), für absolute NOx/PM-Aussagen Faktorquelle
  ausweisen.
- **Idle-/Kaltstart-Parameter sind bislang unbelegte Setzungen** (±15 %-Spannen synthetisch via
  `_build_minmax`) → belegen, sonst Scope auf CO₂e + Energie begrenzen.

### 2.8 Räumliche Platzierung unterhalb ~300 m

`trägt` · 2026-07-27, entschärft 2026-07-28 · Nachfrage kommt seit B8 aus dem Zensus, die
Verteilung *innerhalb* der Zelle weiter aus OSM. **281 Zellen haben kein gewichtetes
OSM-Gebäude** — OSM kartiert die ländliche Lausitz unvollständig, was genau der Grund für B8
war. Diese Nachfrage ist echt, hat aber kein Gebäude zum Anhängen.

**Korrektur der Größenangabe:** die zuerst notierten „630 von 6.024 Paketen (10,5 %)" verglichen
DHL-Skala gegen All-Carrier-Skala. Korrekt sind **630 von 2.652 (23,8 %)** auf DHL-Skala bzw.
**1.666 von 6.013 (27,7 %)** auf der exportierten All-Carrier-Skala — also gut ein Viertel der
Nachfrage, nicht ein Zehntel.

**Behoben (2026-07-28, `PANDA/distribution.py`):** statt die ganze Zelle auf das eine dem
Zentroid nächste Segment zu werfen, wird die Residualnachfrage **längengewichtet über die Straßen
*innerhalb* der Zelle** verteilt (eine längere Straße in einer 100-m-Zelle erschließt mehr
Grundstücke). Gestuft, lokal zuerst: Straßen in der Zelle → Straßen innerhalb einer halben
Zellbreite → einzelner Nearest-Snap. Auf der Lausitz greift Stufe 1 bei **217 Zellen / 558
Paketen**, Stufe 2 bei **34 / 42**, Stufe 3 bei **30 / 31** (echte Off-Network-Zellen; fünf
liegen >500 m von jedem Auto-Link). Wirkung bei unverändertem Niveau: Zustellpunkte 1.053 →
**1.131**, Maximum je Punkt 83 → **81**, **12,6 % der Pakete anders platziert**. Der Faktor
zwischen 27,7 % betroffener und 12,6 % verschobener Nachfrage ist erklärbar: das
Zentroid-nächste Segment lag oft schon in der Zelle.

**Was bleibt:** die Zuordnung ist ein Straßennetz-Proxy, keine Adresse. Unterhalb der
dokumentierten ≳300-m-Verlässlichkeit bleibt die Platzierung unbelegt, und besser wird sie nur
mit Zustellpunkt-/Adressdaten — die nicht offen und damit nicht übertragbar sind (§4). Für den
Szenarienvergleich ist das der einzige Nachfragefehler-Kanal, der sich *nicht* herauskürzt, weil
die Konzepte unterschiedlich auf Raum reagieren (χ-Gate bei Shared-Use).

### 2.9 CV-Batterie auf dem Zensus-Prädiktor: was das Nachfragemodell jetzt trägt

`trägt` · 2026-07-29 (war `offen` seit 2026-07-27) · Die komplette Batterie ist auf dem
Prädiktor neu gerechnet, mit dem auch exportiert wird (PANDA B10; Vorprüfung: der
`.spatial_cache`-Refit reproduziert `fitted_params.json` exakt). Für die Studie relevant:

- **Besser geworden, aber nicht in anderer Größenordnung:** blind 9,8 % wMAPE (KI 7,2–12,9),
  Median-|Δ| 6,1 % statt 8,2 %. Die ±10 % des Bandes decken das KI weiter ab (§1.3).
- **Neu belastbar:** das Skill-KI gegen eine Pro-Kopf-Baseline **schließt die Null aus**
  (+39 %, KI [+10 ; +52], P(>0) 99,5 %; vorher +36 % mit KI [−9 ; +51]). Der Satz „das Modell
  schlägt *Pakete ∝ Einwohner* nachweisbar" ist jetzt tragfähig — vorher war er es nicht.
- **Sub-PLZ-Grenze unverändert:** ρ 0,56 (100 m) / 0,87 (300 m) / 0,92 (500 m). Die
  ≳300-m-Lesart aus §2.8 bleibt genau so stehen; die Bevölkerungsgewichtung ist bei 100 m
  weiter die bessere (ρ 0,65) — das Gebäudegewicht rechtfertigt sich nur über B2B in
  einwohnerlosen Gewerbezellen.
- **Zurückgezogen:** der Cross-Carrier-Transferbefund → §3.7.
- **Zurückgezogen:** „die Lausitz ist pro Kopf EFH-lastiger" → §3.2 (Nachtrag).
- **Kriterien-Bewegung:** das ländliche Skill-Kriterium (K3) ist nicht mehr rot — Punkt-
  schätzungen begünstigen das Modell in fünf von sechs Holdouts, ein KI schließt die Null
  aus (vorher keins). Nicht durchgängig grün: fünf KIs kreuzen weiter die Null. Der
  Ankerabstand ist mit **+0,8 %** erledigt; **die Begründung für das Band ist damit nicht
  mehr der Ankerabstand, sondern das ±29-pp-Prognoseintervall der Extrapolationskurve an
  Hoyerswerdas Betriebspunkt.** Am Band selbst ändert das nichts.
- **Altersterm: K6 erfüllt, Übernahme bewusst vertagt** (User-Entscheidung 2026-07-29). Das
  präregistrierte Kriterium ist jetzt in beiden Bedingungen erfüllt — die gefittete Variante
  stimmt im Vorzeichen zu (`a65plus` = 0,59 × `a18_49` statt 1,5 ×), die exogene kostet
  in-region nichts (−0,01 pp). Nach der eigenen Regel wäre der exogene Term zu übernehmen;
  **entschieden ist, ihn erstmal nicht zu nehmen**, weil (a) die Zustimmung aus einer
  Randlösung (`a50_64` genau 0) auf kollinearen Altersspalten kommt und die gefittete
  Variante blind schlechter ist, und (b) der Effekt mit **−1,3 %** klein gegen das ±10-%-Band
  ist, aber Neuexport + neues Band kosten würde. **Für das Paper heißt das: die
  Einwohnerzahl geht ungewichtet ins Modell, und die Demografie wird als Sensitivität
  berichtet (−1,3 bis −1,8 % des Lausitz-Niveaus), nicht als Korrektur.** Wiederaufnahme
  nur zusammen mit einem ohnehin fälligen Neuexport → BACKLOG.
- **Verschobene Abhängigkeit, als Limitation zu führen:** das Modell ist nach B8 überwiegend
  ein **Flächenmodell** — Wohn-GF trägt zwei Drittel des prognostizierten Totals, der
  Einwohner-Term ein Viertel (OSM-Ära: 71 % Einwohner). Der Transfer hängt damit primär an
  der Zensus-2022-Gebäudeaufnahme samt Leerstandskorrektur.
- **Nicht nachgezogen:** der Kandidaten-Bake-off (`PANDA/docs/bakeoff_model_selection.md`).
  Wer dessen Tabelle zitiert, zitiert OSM-Ära-Zahlen.

Reproduktion: die acht `studies/run_cv*.py` in PANDA, Reihenfolge und Artefakte in
`PANDA/docs/transferability.md` → B10. Überholte Artefakte: `PANDA/archive/cv_pre_zensus/`.

### 2.10 `share_channel_locker` ist keine Variable

`trägt` · 2026-07-27 · `ParcelAgentGenerator.java:67` (bei Aufnahme `:59` notiert) hartcodiert
`new DeliveryChannelResolver(List.of(), 500.0)` → der Locker-Kanal ist strukturell 0. Locker
selbst ist bewusst Phase 2 (§4.5); relevant hier, weil das Javadoc etwas anderes behauptet
(BACKLOG-Punkt) und weil kein Ergebnis als „mit Locker-Anteil" gelesen werden darf.
**Annotation 2026-07-28:** das irreführende Javadoc ist korrigiert
(`integrated/shareduse/DeliveryChannelResolver.java:10-20` — nicht `integrated/freight/`, wie im
BACKLOG stand); es benennt jetzt die strukturelle 0 und dass die Aktivierung eine Codeänderung am
Aufrufer braucht, nicht bloß eine Standortdatei.
**Annotation 2026-07-30:** die zweite Stelle, die einen Locker-Anteil suggerierte, ist ebenfalls
weg — `IntegratedScenarioConfig.b2cLockerShare = 0.7` (unverdrahtet, s. §1.1) ist entfernt. Der
Befund selbst trägt unverändert.

### 2.11 1d Modular: Rebalancing-Konfiguration in den Validierungstests ist fixture-getuned

`trägt` · 2026-07-28 · **Paper-Caveat.**
Sowohl der Splicer-/Return-to-Depot-Koexistenzbeweis (`ModularEndToEndTest`) als auch der
θ=1,0-Kontrollarm (`ModularControlArmTest`, zweites Testpaar) laufen mit einer
**fixture-getunten** Rebalancing-Konfiguration — 300-m-Zonenzellen und Rückkehr-Start 10:00 —,
nicht der Produktionskonfiguration (2000 m Zellgröße, Rückkehr-Start **22:30**,
`serviceEnd − returnWindow = 86400 − 5400 = 81000 s`, `SimulationRunnerUtils.java:340-347`).
Grund: das kleine
Test-Gitter deckt nur ~900 × 900 m ab; bei 2000 m Zellgröße ist das eine einzige Zone, und der
Min-Cost-Flow-Rebalancer kann dann grundsätzlich nichts mehr verlagern — die Koexistenz-Assertion
wäre vakuos. **Konsequenz für die Zahleninterpretation:** Koexistenz von Splicer und
Return-to-Depot-Rebalancing sowie die Trägheit des Kontrollarms sind für *eine funktionierende*
Rebalancing-Konfiguration gezeigt, nicht für die Produktionskonfiguration.

### 2.12 1d Modular: km-Konvention weicht von der üblichen Fracht-Konvention ab

`trägt` · 2026-07-28 · **Paper-Caveat.**
`deadhead_km_planned` zählt **nur** die An- und die Rückfahrt zum/vom Depot; **jede**
Zwischen-Stopp-Etappe, **einschließlich Depot→erster Stopp**, zählt als `service_km_planned`. Das
weicht von der üblichen Fracht-Konvention ab, in der Depot→erster-Stopp oft als Deadhead gilt.
Beides sind **geplante** Kilometer aus der Dispatch-Zeit-Routung, keine aus Events
rekonstruierten gefahrenen Kilometer.

### 2.13 1d Modular: `freight_vehicle_hours` schließt unvollständige Exkursionen aus

`trägt` · 2026-07-28 · **Paper-Caveat.**
Eine Exkursion, die nie abgeschlossen wurde, hat keinen Abschluss-Zeitstempel und geht daher nicht
in die Kennzahl ein. Das verzerrt die Kennzahl systematisch nach unten — genau dann, wenn die
Flotte am stärksten gesättigt ist, d. h. in genau den Armen, in denen Fracht den Passagierbetrieb
am stärksten stört.

**Anschluss-Caveat (2026-07-29):** `deadhead_km_planned`/`service_km_planned` summieren über
**alle DISPATCHTEN** Touren, `freight_vehicle_hours` nur über die **dispatchten UND
abgeschlossenen**. Die drei Kennzahlen laufen also über *verschiedene Tourenmengen*: eine
dispatchte, aber unvollständige Exkursion liefert ihre Kilometer und keine ihrer Stunden. Jedes
km-je-Fracht-Stunde-Verhältnis aus diesen Zahlen ist damit **überhöht**, und zwar umso stärker,
je gesättigter der Arm.

**Anschluss-Caveat 2 (Paper-Readiness-Review 2026-07-29):** drei Verschärfungen.
(a) `retooling_hours` (= `swaps_completed` × 420 s) ist eine **dritte** Tourenmenge: eine
gestrandete Exkursion liefert ihren einzelnen Hin-Swap. Verhältnisse wie
`retooling_hours / freight_vehicle_hours` („Rüstanteil der entzogenen Fahrzeugzeit") sind damit
nach demselben Mechanismus überhöht wie die km-Verhältnisse oben.
(b) Der Event-Kreuzcheck ist **im relevanten Fall nicht unabhängig**: das eventbasierte
`drt_freight_hours_total` zählt zwar die offene Fracht-Spanne einer gestrandeten Exkursion mit
(Fenster bleibt bis Sim-Ende offen), aber der **letzte, bei Mobsim-Ende noch laufende Task
emittiert nie `dvrpTaskEnded`** und seine Dauer fällt komplett heraus
(`drt_service_time.py:272-281`, Mechanik in `VrpAgentLogic` verifiziert). Beide „Fracht-Stunden"
sind bei gestrandeten Exkursionen also nach unten verzerrt — nur unterschiedlich stark; ihre
stille Divergenz in gesättigten Armen ist zu erwarten und sollte publiziert statt als
Unabhängigkeitsbeweis gelesen werden (betrifft auch R5).
(c) Der Ausschluss ist **nicht alternativlos**: `dispatchedAt` ist bekannt und ein gestrandetes
Fahrzeug kehrt nie zurück, also ist `freight_vehicle_hours_clamped` =
Σ min(completedAt, Tagesende) − dispatchedAt aus demselben Ereignisstrom konstruierbar. „Kein
Abschluss-Zeitstempel" begründet den Bias nicht, sie dokumentiert nur die gewählte Definition.
**Annotation 2026-07-29:** der geklammerte Schätzer (`freight_vehicle_hours_clamped`) ist weiterhin
**nicht gebaut** — bewusst nicht Teil dieser Welle.

### 2.14 1d Modular: fahrzeugseitige System-KPIs sind frachtkontaminiert (D7 war zu weit gefasst)

`trägt` · 2026-07-29 · **Paper-Caveat, wichtigster 1d-Eintrag für die Zahleninterpretation.**

**Korrektur einer bisherigen Zusage.** Design-Entscheidung D7 hielt fest, für die Fahrzeug-KPIs
von Modular sei *keine* `pax_only.py`-Korrektur nötig. Das gilt nur für die **Anfragen-Seite**:
Pakete werden in 1d nie DVRP-Passagiere, `drt_customer_stats` ist also unverfälschte Pax-Wahrheit.
Für die **Fahrzeug-Seite** ist die Zusage **falsch**, und zwar strukturell: 1c wurde von Paket-
*Agenten* kontaminiert, die am `parcel_`-Präfix erkennbar und damit filterbar sind; 1d wird von
Fracht-*Tasks* auf denselben Passagierfahrzeugen kontaminiert — dafür gibt es keinen Filter.

**Was vor dem Fix falsch war.** `drt_service_time.py` verbuchte `dvrpTaskStarted`-Dauern über den
Task-Typ-**Namen** in genau drei Töpfe (STAY/DRIVE/STOP). `MODULAR_FREIGHT_DRIVE` und
`MODULAR_FREIGHT_STOP` landeten in Schlüsseln, die nie gelesen wurden, und tauchten über
`waiting_s = tour_s − drive_s − stop_s` als **Flotten-Leerlauf** wieder auf; der Kapsel-Tausch
heißt als Task-Typ wörtlich `"STOP"` (er erbt `DefaultDrtCapacityChangeTask`, Design C6) und
zählte damit als **Passagier-Standzeit**. Größenordnung bei 120 Fahrzeugen und ~60 Exkursionen à
3,5 h: rund **195 Fahrzeugstunden Frachtarbeit als Leerlauf** plus rund **14 h Rüstzeit als
Passagierdienst**.

**Was jetzt korrigiert ist** (Trennung über das `modularTour`-Ereignisfenster
DISPATCHED…COMPLETED je Fahrzeug — innerhalb einer Exkursion gibt es wegen der strikten
Pax-Sperre D2 keine Passagierstopps): `drt_drive_hours_total`, `drt_service_hours_total` und
`drt_wait_hours_total` sind wieder reine Pax- bzw. Leerlaufgrößen. Neu ausgewiesen:
`drt_freight_drive_hours_total`, `drt_freight_dwell_hours_total`, `drt_retooling_hours_total`,
`drt_freight_hours_total`.

**Was unkorrigierbar bleibt:** `drt_vehicle_km`, `drt_empty_ratio`, `drt_dp_over_dt`. Ihre Quelle
`drt_vehicle_stats` enthält die Exkursions-Kilometer bereits aggregiert, und es gibt keine
Task-weise Distanzaufteilung, gegen die man sie herausrechnen könnte.

**Was von Hand korrigierbar bleibt:** `drt_tour_hours_total`, `service_ratio_active`,
`fleet_utilisation_by_time`, `fleet_utilisation_by_trips`, `mean_pax_aboard`. Ihr Fenster bzw.
Nenner ist die *aktive Spanne* des Fahrzeugs, die in 1d die Exkursion einschließt; sie sind
dadurch gedrückt. `drt_freight_hours_total` ist genau der Überschuss und erlaubt die Umrechnung.

⚠️ **Für die Zahleninterpretation:** fahrzeugseitige System-KPIs aus 1d-Runs sind **nicht direkt
mit denen der Baseline vergleichbar**, solange sie nicht wie oben korrigiert werden. Beide Gruppen
stehen als `meta/modular_contaminated_kpis` in `kpis_long.csv`. Runs, die **vor** dem
2026-07-29-Fix erzeugt wurden, tragen die alten, falschen Werte.

**Ergänzung (Paper-Readiness-Review 2026-07-29): die Quarantäne endet an der Datenschicht, und
zwei Aussagen oben sind zu stark.** Der Kern-Fix trägt (Fenstergrenzen `[a,b)` gegen beide
Java-Emissionspunkte verifiziert, Baseline bitgleich), aber:

1. **„Unkorrigierbar" ist faktisch falsch — richtig ist „nicht korrigiert".** Fracht-*Drives*
   sind echte DVRP-Fahrten mit `LinkEnterEvent`s (`ModularTourScheduler.java:151,161,171`) —
   exakt die Events, aus denen MATSims `DrtVehicleDistanceStats` die kontaminierten Zahlen
   überhaupt erzeugt; das Repo rekonstruiert Link-Distanzen selbst schon zweimal
   (`geometry.reconstruct_drt_paths`, `drt-headline/build_vehicle_tours.py` — Skript geloescht
   2026-07-29, Stand in Git-Historie: Parent von b639ff30198eafa692b151705be4d073d6f98a5d). Eine
   Fenster-Korrektur der drei km-KPIs wäre baubar; entschieden ist, sie (bislang) nicht zu bauen,
   weil sie eine Selbstrechnung an die Stelle der autoritativen MATSim-CSV setzte. Vorher zu
   fixen wäre ohnehin: `geometry` misst Euklid-Knotendistanz, nicht `link.getLength()`.
   **Behoben 2026-07-29 (`48bff7c`):** der Wortlaut im Code ist korrigiert (`unkorrigierbar` →
   `nicht korrigiert`); die tatsächliche km-Korrektur bleibt weiterhin **bewusst** nicht gebaut —
   an dieser Entscheidung ändert sich nichts.
2. **Das „von Hand korrigierbar"-Rezept (Subtraktion) stimmt nur für `drt_tour_hours_total`.**
   `service_ratio_active`, `fleet_utilisation_by_time`, `mean_pax_aboard` brauchen eine
   **Reskalierung** × `tour_h / (tour_h − freight_h)` (funktioniert nur, weil Exkursionen
   garantiert occupancy-0 sind); `fleet_utilisation_by_trips` ist aus den veröffentlichten KPIs
   **gar nicht** rückrechenbar (Segment-Zählung; Fracht-only-Fahrzeuge erzeugen ein
   unveröffentlichtes Level-0-Segment).
   **Behoben 2026-07-29 (`48bff7c`):** das Rezept ist korrigiert, und die `*_pax`-Zusatzzeilen für
   die reskalierbaren KPIs werden jetzt mechanisch mit emittiert; `fleet_utilisation_by_trips`
   ist dabei explizit als **unkorrigierbar** umklassifiziert (nicht bloß „schwierig").
3. **Der Marker selbst ist zerbrechlich:** `meta/modular_contaminated_kpis` wird nur auf dem
   Event-Pfad geschrieben (`extract_drt.py:149/194`). Ein `--no-events`-Build oder ein Run ohne
   aufgehobene `output_events.xml.gz` publiziert alle kontaminierten KPIs **ohne jeden Marker**
   (end-to-end reproduziert); die Comparison-Page verliert den Marker-*Payload* und chartet
   `drt_vehicle_km` zugleich als Headline-Balken; die Run-Kacheln tooltippen „autoritativ" ohne
   Banner, obwohl die `warnbanner`-Präzedenz (Shared-Use) in derselben Datei existiert.
   **Behoben 2026-07-29:** der Marker ist jetzt CSV-/szenario-gebunden statt Event-gebunden und
   übersteht `--no-events` (`48bff7c`); die Comparison-Page rendert den Marker-Payload wieder, und
   die Run-Kacheln tragen das Kontaminations-Banner (`5fee8f1`).
4. **Vier weitere Konsumenten sind kontaminiert und unmarkiert** — ihre CSVs haben keinen
   Provenance-Kanal: `drt_tour_duration`-Verteilung, `occ_time`/`occ_segments`,
   `occ_km`/`drt_tour_distance` (+ `veh_km` in `build_kpis`), `kpi_vehicles.csv`
   `active_h`/`ratio_active`. Visuell dazu: die Karte zeichnet Exkursionen als *leere
   Pax-Touren*.
   **Behoben 2026-07-29 (`5fee8f1`):** neue Meta-Row `modular_secondary_contaminated` plus
   Render-Badges auf dem occ-Chart, dem Tourdauer-Chart und `_vehicle_chart`, dazu eine
   Legendenzeile auf der Karte. **Nicht gebadged, bewusste Scope-Grenze (Task-4-Adjudikation):**
   der `drt_tour_distance`-Verteilungschart — steht als Plan-Level-Scope-Entscheidung offen,
   nicht als übersehener Fall.

Behebung als Paket → BACKLOG „Kontaminations-Fixwelle 2". Bis dahin gilt verschärft: **keine
fahrzeugseitige 1d-Zahl aus Dashboard oder Sekundär-CSVs ins Paper übernehmen, ohne diesen
Eintrag daneben zu legen.**

### 2.15 1d Modular: schwächeres Zeitversprechen — Touren-Geometrie ist nicht vergleichbar

`trägt` · 2026-07-29 · **Paper-Caveat.** Konsequenz aus C4 (§1.2), hier als Limitation geführt,
weil sie im Plan stand und in den Limitations fehlte.
1d macht ein **schwächeres Zeitversprechen** als die LMD-Baseline — Zustellung am selben Tag
zwischen 07:30 und 21:00 statt innerhalb einer Welle —, und das ist eine bewusst gesetzte
**Konzepteigenschaft** (Nutzerentscheidung 2026-07-28), kein Nebeneffekt der Umsetzung. Weil
jsprit für 1d dadurch ein anderes Fahrzeugfenster bekommt (dazu die 216er-Kapsel und der
3,5-h-Cap), schneidet es die 1d-Touren **strukturell anders** als die Baseline-Touren. Der
Vergleich 1d ↔ LMD-Baseline läuft deshalb über **δ/Pakete und operative KPIs**, **nicht** über
Tourenzahl, Tourlänge oder km je Paket.

> **§2.16–§2.23: Befunde des Paper-Readiness-Reviews vom 2026-07-29** (drei unabhängige
> Reviewer-Läufe: Java-Tiefenreview, Methodik-Review, Python-KPI-Review; Volltexte unter
> `.superpowers/sdd/2026-07-27-1d-modular-capsule-swap/paper-review/` — **untracked**, bei
> `git clean -fdx` weg). Alle tragenden Behauptungen wurden vor Eintrag hier gegen den Code
> nachgeprüft. Zugehörige Arbeitspunkte → BACKLOG `[H]` 1d.

### 2.16 1d Modular: δ ist links-zensiert — jsprit-Unassigned sind unsichtbar

`trägt` (Struktur verifiziert; Größe NEEDS-RUN) · 2026-07-29 · **Wichtigster offener Punkt vor
dem θ-Sweep; von zwei Reviewern unabhängig gefunden.**
`parcels_planned` = Summe über jsprits **zugewiesene** Touren. Jobs, die jsprits beste Lösung
unter 3,5-h-Cap und Tagesfenster nicht einplanen konnte, landen als Carrier-Attribut in der
Preprocessing-Datei (`LausitzFreightPreprocessor.recordUnassignedJobs:296/311-315`) und
verschwinden dann aus jeder Rechnung: keine der fünf Conservation-Identities beginnt vor der
Zuweisung, `extract_modular.py` liest das Attribut nicht, und der einzige Publisher von
`parcels_unassigned` (`extract_freight.py`) ist auf das CarrierModule-TSV gegattert, das 1d
designgemäß nie erzeugt (`build_kpis.py:47/78-79`). **δ = parcels_planned − parcels_served ist
damit eine Untergrenze der Nichtzustellung auf zensierter Nachfragebasis.** Verschärfung: der
**Tour-Cap ist Sweep-Parameter** und verändert selbst, was jsprit verwirft
(`MaxRouteDurationConstraint`, Priority.CRITICAL) — die Cap-Arme 12600 vs. 25200 s teilen also
nicht garantiert denselben Nenner, und nichts in irgendeiner CSV würde das anzeigen. Der einzige
gemessene Wert „Unassigned = 0" (§1.3) stammt vom **7,5-h**-Cap der Van-Typen, nicht vom
3,5-h-Cap. Gegenmittel (BACKLOG): `parcels_unassigned_jsprit` publizieren + Identity 0
(`parcels_demand == parcels_planned + parcels_unassigned_jsprit`), oder bei der Konversion laut
scheitern, wenn die gerouteten Carrier Unassigned tragen.

**Behoben 2026-07-29** (Commits `9e4d9da`/`20bdd4e`): `parcels_demand` + `parcels_unassigned_jsprit`
+ `parcels_missed_overlay` stehen jetzt in `modular_tour_stats.csv`, Identity 0 ist geprüft — in
Java als WARN (nie Abbruch, User-Entscheidung) und identisch in Python. Java-Identity-0
(Toursummen-Basis, Konversionszeit) und Python-Identity-0 (PLANNED-Event-Basis, Extraktionszeit)
können in der Randkonfiguration QSim-Ende vor ~07:16 (Tour nie aktiviert) auseinanderfallen — dann
warnt Java, Python sieht eine kleinere parcels_planned-Basis; im Produktionssetup (QSim bis 36:00)
unerreichbar.

### 2.17 1d Modular: was ein Einzellauf trägt — gepaarte vs. ungepaarte Vergleiche

`trägt` · 2026-07-29. Jede 1d-Frachtgeometrie-Kennzahl (`deadhead_km_planned`,
`service_km_planned`, Tourenzahl → `swaps_completed`, `retooling_hours`,
`freight_vehicle_hours`) erbt den §2.1-Rauschboden aus **einem** jsprit-Solve (Seed 4711 — laut
§2.1 „beliebig, nicht gut", distanzpessimistisch); der 1d-Validierungsplan enthält **null
Replikation**, MATSim-seitig ebenso. Konsequenz, präzise:
- **Übersteht einen Einzelseed-Einwand:** der θ-Sweep *innerhalb eines Caps* — identischer
  jsprit-Plan, identischer MATSim-Seed, nur θ variiert → monotone θ-Antworten (δ vs. θ,
  Pax-Wartezeit vs. θ) sind als **gepaarte** Vergleiche einer Realisierung berichtbar.
- **Übersteht nicht:** absolute km-/Stunden-Niveaus; der **Cap-Vergleich** 12600 ↔ 25200 (zwei
  unabhängige jsprit-Solves; §2.1 zeigt ±30 % km je Carrier allein zwischen Seeds — auch die
  D5-„cap-invariante Stunden"-Überschlagsrechnung ist keine Messung); jede Behauptung, ein θ-
  oder Cap-Effekt auf km-KPIs übersteige das Rauschen.
Die Berichtsregel §1.5 (≥10 Runs, Mittelwert + Min/Max) gilt explizit auch für 1d; im Methods-
Kapitel ist zu deklarieren, welche Vergleiche gepaart und welche ungepaart sind → BACKLOG
Multi-Run-Aggregation.

### 2.18 1d Modular: der Expiry-Pre-Check ist eine Heuristik, keine Schranke

`trägt` (Mechanismus; Richtung/Häufigkeit NEEDS-RUN) · 2026-07-29.
Der Javadoc-Anspruch in `ModularTourDispatcher.java:48-53`, die Splicer-Vervollständigung sei
gegenüber dem Expiry-Pre-Check „always larger", ist ein **Overclaim**: garantiert fehlt dem
Pre-Check nur die Anfahrt; ansonsten sind jsprits Car-Netz-Zeit (Van-Speed, Freifluss) und die
DRT-geroutete Zeit (ab Iteration > 0 beobachtete Reisezeiten, anderes Link-Set) **nicht
geordnet** — Link-Snapping kann Etappen auch verkürzen. Nahe `latestEnd` kann darum eine noch
machbare Tour als EXPIRED verbucht werden: die Fehlattribution („Gate zu eng" vs. „Tour passte
nie"), die die SPLICE_REJECTED/EXPIRED-Trennung verhindern soll, **in Gegenrichtung** — auf dem
Sweep, der das Hauptinstrument ist. Wirkung vermutlich klein (nur das schrumpfende Fenster nahe
`latestEnd`), aber unquantifiziert. Fürs Paper: Pre-Check als Heuristik beschreiben; zur
Quantifizierung `plannedDuration` je Tour ins CSV exportieren (Scatter geplant vs. geroutet).
Rand dazu (Perf, nicht Korrektheit): eine festhängende pendende Tour wird jeden offenen Simstep
komplett neu geroutet (N+2 Router-Queries pro Angebot).

**Annotation 2026-07-29 (`2c00e8d`):** der Javadoc-Overclaim in `ModularTourDispatcher.java` ist
entschärft. **Aber** derselbe Wortlaut existiert weiterhin als **Code-Kommentar** (nicht Javadoc)
in `ModularTourDispatcher.dispatch()` (~:164-165) — das war explizit außerhalb des Datei-Scopes
dieser Task (nur `:44-59`, s. Task-6-Bericht). Als `[L]`-Backlog-Einzeiler erfasst.

### 2.19 1d Modular: Kapsel- und Swap-Infrastruktur ist unbeschränkt modelliert

`trägt` · 2026-07-29 · **Paper-Caveat, Limitation-Absatz nötig.**
Das Modell nimmt unausgesprochen an: (a) unbegrenzter Kapselvorrat an allen 7 Depots, (b) jede
Kapsel ab 07:30 fertig kommissioniert, (c) **unbegrenzt parallele Swap-Plätze** — beim bewusst
akzeptierten 07:16-Surge können ~(1−θ)·Flotte Fahrzeuge nahezu gleichzeitig an 7 Depots
eintreffen und alle in exakt 420 s wechseln, ohne Warteschlange —, (d) abgestellte Pax-Kapseln
binden nichts. Für ein Kapselwechsel-Paper ist die Swap-Infrastruktur der Konzeptkern; die
DLR-/U-Shift-Community fragt als Erstes nach Kapselzahl und Swap-Bays, und das Modell kann es
nicht beantworten, weil die Ressource nicht existiert. Billiges Gegenmittel aus vorhandenen
Events: **Peak gleichzeitiger Swaps je Depot** (SWAP_DONE-Zeitstempel, 15-min-Bins) ausweisen —
macht aus der unsichtbaren Annahme eine bezifferte Infrastrukturanforderung.

### 2.20 1d Modular: die Tourplanung optimiert Baseline-Van-Ökonomie, nicht die 1d-Kosten

`trägt` · 2026-07-29. jsprit partitioniert die 1d-Touren unter **geklonter Van-Ökonomie**
(inkl. Fixkosten, `FleetSize.INFINITE` → Fixkosten ≈ Pro-Tour-Kosten), während 1d pro Exkursion
real 2 × 420 s Rüstzeit plus An-/Rückfahrt-Deadhead von der Position des Idle-Fahrzeugs zahlt —
beides kennt die Zielfunktion nicht. Tourenzahl und -schnitt (→ Swaps, Rüstzeit, Deadhead) sind
also unter dem *falschen* Kostenmodell optimal; die berichteten Modularitätskosten sind eine
**Obergrenze unter dieser Tour-Partition**. Die Richtung ist konservativ gegen 1d — als Argument
nutzbar, aber nur, wenn es im Methods-Kapitel steht. (Die Klonung selbst bleibt als
Vergleichbarkeitsentscheidung gegenüber der LMD-Baseline richtig; Ausblick = Ein-Pool-
Sensitivität, BACKLOG.)

### 2.21 Szenarienvergleich 1c/1d/Baseline: drei Lieferversprechen, Netto vs. Brutto, Sitzplatz-Confound

`trägt` · 2026-07-29 · erweitert §2.15 um die Punkte, die dort fehlen.
1. **Drei Versprechen:** die LMD-Baseline plant 08:00–20:00 (`LmdCarrierBuilder:43-45`),
   Shared-Use nach M5 B2B 07:30–17:00 / B2C 07:30–20:00 (§1.2), 1d einheitlich **07:30–21:00
   auch für B2B** (`Modular.java:21-22`). δ ist über Szenarien also **nicht dieselbe Größe**:
   1d darf ein B2B-Paket um 20:30 als „served" zählen, das unter M5 gar nicht zulässig wäre.
   Eine versprechens-robuste Zerlegung (z. B. „B2B bis 17:00 zugestellt") ist derzeit
   **strukturell unmöglich**, weil `ModularFreightTour.Stop` keinen Sendungstyp trägt und die
   CSV ihn folglich nicht ausweist. Entweder Fenster an M5 angleichen (eine Zeile am
   `runModular`-Aufruf, C4 neu argumentieren) oder Versprechens-Tabelle ins Methods-Kapitel und
   Querschnittsclaims auf versprechens-robuste KPIs beschränken.
   **Annotation 2026-07-29:** durch die 21:00-Entscheidung (§1.2) ersetzt sich die
   Versprechens-Asymmetrie 1c↔1d — sobald 1c auf 21:00 umgebaut ist (BACKLOG `[H]`), fahren
   beide dasselbe Fenster. Die Baseline (08:00–20:00) bleibt davon unberührt verschieden → der
   Baseline-Vergleichs-Caveat oben **bleibt** in voller Stärke stehen. Der B2B/B2C-je-Stop-Export
   als versprechens-robuste Zerlegung ist damit **obsolet** (bewusst nicht gebaut).
2. **Netto vs. Brutto:** die Baseline-`delivery_rate` rechnet Not-at-home-Misses heraus
   (`extract_freight.py:126`); 1d würfelt **dasselbe Overlay** (`runModular:202-205`,
   deterministischer Seed), aber `parcels_served` zählt Zustell*versuche* brutto — kein 1d-KPI
   verrechnet die Misses (`extract_modular.py` kennt sie nicht). Jede Tabelle
   Baseline-Zustellquote neben 1d-served/planned vergleicht netto gegen brutto. Solange zudem
   §2.5 (−14 %-Quotenrätsel) offen ist: **keine absoluten Zustellquoten berichten, nur Deltas.**
   **Annotation 2026-07-29 (`9e4d9da`):** `parcels_missed_overlay` wird jetzt exportiert — eine
   Netto-Rechnung für 1d ist damit möglich. Die §2.5-Quarantäne für *absolute* Zustellquoten
   bleibt unverändert bestehen.
3. **1c↔1d-Confound:** 1c fährt 8 Sitze + 20 Paketslots (M1), 1d 10 Sitze (D3) — beides einzeln
   richtig entschieden, zusammen heißt es: jeder **direkte** 1c↔1d-Pax-Vergleich vermengt
   Mechanismus- und Kapazitätseffekt (20 % Sitzdifferenz). Vergleichsdesign sternförmig über die
   gemeinsame 10-Sitzer-Baseline; direkte 1c↔1d-Zahlen nur baseline-normalisiert.
   Klein dazu: `occ_*`/`fleet_utilisation_*` beschreiben auf 1d eine **andere
   Fahrzeugpopulation** (Fracht-only-Fahrzeuge erhalten ein Level-0-Segment), und die
   „Freight-Stopps je Stunde"-Zeitreihe ist auf 1d **strukturell leer** (D7: Modular-Stopps
   emittieren keine `actstart`-Events) — ein 1d-Arm liest sich dort fälschlich als „keine
   Frachtaktivität".

### 2.22 1d Modular: Konstrukt-Label und die D8-Begründung

`trägt` · 2026-07-29.
1. **Getestet wird eine Kapselwechsel-Dispatchpolitik, nicht das U-Shift-Fahrzeug.** Alle
   U-Shift-spezifischen Eigenschaften sind (jeweils dokumentiert und einzeln vertretbar)
   neutralisiert: 10 Sitze statt 8 (D3), Speed/Kosten vom Spender-Van, Fahrerökonomie
   unverändert, Autonomie ausgeklammert (§4.4), 420 s Rüstzeit **eigene Annahme ohne
   unabhängige Quelle**, 216 aus Paper 1 übernommen. In Summe: Framing „U-Shift-inspiriertes
   modulares Kapselkonzept", nicht „U-Shift-Bewertung"; Rüstzeit-Sensitivität 2–15 min (Spec
   §11) einplanen oder prominent als Future Work; 216er-Quelle sauber zitieren.
2. **Die D8-„nie bindend"-Arithmetik ist defekt.** „216 × 2 min = 7,2 h > jeder Cap"
   (`Modular.java:12-14`) ignoriert den **15-min-Deckel pro Stopp**
   (`LmdCarrierBuilder:176-178,212`): ein Stopp mit 81 Paketen (§2.8-Maximum) kostet 15 min,
   nicht 162 min — drei solcher Stopps wären 243 > 216 Pakete in 45 min Dwell. Ob Kapazität je
   bindet, hängt an der Nachfragekonzentration (NEEDS-RUN; `max_parcels_per_tour` ausweisen).
   Falls sie bindet, wirkt es konservativ gegen 1d (mehr, kürzere Touren → mehr Swaps/Deadhead).
   D8 umformulieren: „bindet selten; wo doch, konservativ" — nicht „nie", solange die eigene
   Dwell-Regel die Rechnung widerlegt.
   **Annotation 2026-07-29 (`9e4d9da`):** `max_parcels_per_tour` wird jetzt je Run exportiert —
   der D8-Beleg liegt damit pro Lauf vor, statt NEEDS-RUN zu bleiben. Design-Spec-D8 ist
   entsprechend umformuliert (→ [Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md)
   §10 D8).

### 2.23 1d Modular: Definitionskonventionen fürs Methods-Kapitel

`trägt` · 2026-07-29. Nichts hiervon ist falsch — aber nichts davon ist selbstverständlich, und
jede dieser Konventionen ändert, wie eine Zahl zu lesen ist:
- **θ (Idle-Gate):** momentaner, flottenweiter Idle-Anteil, jeden Simstep **vor** dem
  Delegate-Optimizer geprüft; strikt `>` (darum ist θ=1,0 der Nie-Arm). **Nenner = alle
  registrierten Fahrzeuge** (`ModularTourDispatcher.java:139`) — auch solche, die nie idle sein
  können; heute vakuos (Fenster 0–86400), bei realen Schichtfenstern schlösse das Gate
  systematisch zu früh.
- **Fahrzeugwahl:** euklidisch nächstes Idle-Fahrzeug zum Depot, **ein** Kandidat pro Tick, kein
  Fallback auf den zweitnächsten; ein euklidnah-aber-netzfernes Fahrzeug kann eine Tour
  theoretisch bis zum Expiry aushungern (fürs Hoyerswerda-Netz unbelegt).
- **„late":** STOP_SERVED wird am **Dwell-Ende** bewertet (`ModularKpiHandler:242`) — Dwell
  20:59–21:03 zählt alle Pakete des Stopps als late. `tours_completed_late` misst die
  **Fahrzeugrückkehr** (Swap-Back-Ende), nicht die Zustellung → `parcels_served_late` ist die
  Versprechens-KPI, `tours_completed_late` Betriebsdiagnostik. Der Dispatch-Envelope verlangt
  Rückkehr + Swap-Back ≤ 21:00 — strenger als das Lieferversprechen, Richtung konservativ.
- **Look-ahead:** flach 420 + 420 s statt des im Parent-Spec angedachten gerouteten
  Anfahrt-Schätzers — faktisch Konkretisierung **C10** (unter C4 folgenlos, weil alle Touren ab
  ~07:16 pending sind); im Design-Spec nachtragen.

**Annotation 2026-07-29:** C10 ist im Design-Spec nachgetragen
(→ [Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md) §10); die
„late"-Konvention (STOP_SERVED am Dwell-Ende, `tours_completed_late` vs. `parcels_served_late`)
steht jetzt auch im `ModularKpiHandler`-Javadoc (`2c00e8d`); die `open_freight_windows`-Diagnostik
für offen gebliebene Fracht-Fenster existiert (`5a8d88f`, Meta-Row in `extract_drt._modular_rows`).

---

## 3 · Zurückgezogene Befunde

Chronologisch nach Zurückziehung. Format: **was geglaubt wurde → was gemessen wurde → was bleibt.**

### 3.1 „Nichtlinearität = Flächendegression, gemessen in Fahrzeug-km"

`zurückgezogen` · 2026-07-28

**Geglaubt** (erste Bandmessung, 2026-07-27): −18,1 % Pakete ⇒ −11,2 % Fahrzeug-km,
Elastizität 0,62, km/Paket +8,3 % → sublineare Reaktion, Ursache Flächendegression, gemessen in
Kilometern.
**Gemessen** (2026-07-28): der Rauschboden auf km ist **6,5 %** (§2.1). Der Befund liegt bei
1,3–1,7× darüber — nicht belastbar. Zusätzlich ist `km je Paket` über die drei Arme **nicht
monoton** (Central ist lokales Minimum, 1,039; beide Nachbarn höher), was als Dichteeffekt
unmöglich ist. Zerlegung je Carrier zeigt reines Heuristikrauschen: amazon fährt bei 10 % *mehr*
Paketen 74 % *mehr* km/Paket; gls und ups schwanken ±50 % in beide Richtungen; ups-Touren wobbeln
6→4→6. Nur dpd und fedex sind monoton. Gleiches Muster wie die frühere c=100-Diagnose.
**Es bleibt:** der Effekt selbst — **€ je Paket steigt bei weniger Nachfrage** (+5,2 % damals,
+5,7 % jetzt, gegen 0,8 % Rauschen = 6–7×). Aber der Mechanismus läuft über den
**Fahrzeugzahl-Kanal**: 81 % der Kosten sind Fixkosten je Fahrzeug, die Fahrzeugzahl sinkt mit
Elastizität 0,62, weil jedes Fahrzeug zeit-/dwell-gebunden ist und die Fläche trotzdem abgedeckt
werden muss. **Inhaltlich weiter Flächendegression — aber in Fahrzeugen gemessen, nicht in
Kilometern.**

### 3.2 „Der +22-%-Ankerabstand ist ein Transferfehler, Ursache = EFH-Term"

`zurückgezogen` · 2026-07-27 (B7 → B8)

**Geglaubt:** die räumliche Verteilung der Lausitz-Nachfrage ist belegt, nur das *Niveau* liegt
~22 % über dem einzigen externen Anker (BIEK-Kompendium 2021: Sachsen 43 vs. Niedersachsen 47
Sdg./Einw./Jahr). Ursache isoliert auf den EFH-Term (28,3 % statt 14,8 % Anteil am Total) —
**nicht** Demografie (−2…−3 %), **nicht** Einkommen (VGR 2023: verfügbares Einkommen je
Einwohner Bautzen/Region Hannover = 0,990), **nicht** das Dichte-Regime. Antwort darauf war ein
extern erzwungenes Zwei-Run-Band auf ×0,819.
**Gemessen (B7):** der EFH-Term ist gegen Zensus-2022-Gebäudedaten **falsch**.
`PANDA/data_loader.py:615` schickt jedes `building=yes` ohne `building:levels` per
`fillna(2) <= 2` in die EFH-Klasse (und es kann nie MFH werden). Dieser Fallback trägt in
Hannover 73,6 %, in der Lausitz **92,5 %** der EFH-Fläche, weil dort 91,8 % statt 57,8 % der
Wohngebäude untypisiert sind. Folge: **44,8 %** der Lausitzer OSM-„Wohn"-Geschossfläche liegen in
Zellen mit **null** gemeldeten Wohnungen (Hannover 28,3 %) — Scheunen und Nebengebäude,
abgerechnet mit dem größten B2C-Koeffizienten.
**Gemessen (B8):** es war **auch ein Modellfehler**, nicht nur ein Transferfehler — und der
Fehler saß in der **Fläche selbst**, nicht im Split (§1.3).
**Es bleibt:** der Ankerabstand fällt von +22,2 % auf **+0,9 %**. Das Band ist damit keine
Korrektur einer bekannten Verzerrung mehr, sondern Restunsicherheit um ein verankertes
Zentralniveau → symmetrisch, drei Arme (§1.3). Schöner Konsistenzbefund: das neue Central (6.024)
liegt fast auf dem alten, extern erzwungenen Low-Arm (5.946 gemessen) — was vorher eine
Setzung war, kommt jetzt aus den Daten.
Reproduktion: `python -u studies/run_efh_validation.py`, Details `PANDA/docs/transferability.md`
→ B7/B8.

**Nachtrag 2026-07-29 (B10): auch die *Richtung* der Strukturaussage war falsch, nicht nur ihre
Größe.** Die Termzerlegung oben stammt aus dem OSM-Prädiktor. Auf Zensus-Flächen hat die Lausitz
pro Kopf **weniger** EFH- (0,79× statt 2,16×) und **mehr** MFH-Fläche (1,12× statt 0,70×) als die
Region Hannover, und der EFH-Term trägt dort **31,4 %** gegen **37,1 %** in Hannover — er
über-trägt also nicht mehr, sondern unter-trägt. Für Hoyerswerda (Plattenbaustadt; EFH-Anteil der
Wohnfläche 0,41 statt OSM-seitig 0,82) ist das die plausible Beschreibung. Damit entfällt auch das
Erklärbild „in Hannover heißt EFH-lastig *wohlhabender Vorort*, in Hoyerswerda *Dorf*" — es
beschrieb einen Datenfehler, keine Siedlungsstruktur.

### 3.3 „1c Task 4 braucht `drt-extensions` / den MOIA-Fork"

`zurückgezogen` · 2026-07-24 · Verwechslung von Core und Extension. Das 2D-Kapazitäts-Plumbing
ist nativ im `dvrp`-Core `2025.0` (§1.1). Was `drt-extensions/reconfiguration` wirklich ist:
dynamischer Laufzeit-Umbau der Fahrzeugkapazität (`CapacityReconfigurationEngine` +
`logic/{,Default,Noop}CapacityReconfigurationLogic` + `run/CapacityReconfiguration{,QSim}Module`)
— ein **1d**-Thema, keine 1c-Abkürzung. Die Extension ist im `2025.0`-Release enthalten
(zwischen PR3552 und 2025.0 hinzugefügt); HAGRIDs `.m2` hat lokal nur das veraltete PR3552 ohne sie.

### 3.4 „1d Kapsel-Tausch braucht `drt-extensions:2025.0` als Dependency"

`zurückgezogen` · 2026-07-27 · Der Tausch ist nativer drt-Core (§1.1). `services` ist nur eine
Abschreib-Vorlage, `reconfiguration` läuft nur in `onPrepareSim` — für den *laufenden* Tausch
beide ungeeignet.

### 3.5 „Der MATSim-Emissions-Contrib ist der richtige Weg"

`zurückgezogen` · 2026-07-28 · Die Recherche 2026-07-27 war korrekt in der Sache: `matsim-lausitz`
bringt die Verkabelung mit (`LausitzScenario.setEmissionsConfigs()`,
`prepareVehicleTypesForEmissionAnalysis()`, `AirPollutionAnalysis`-Dashboard), Berechnung läuft
offline auf den Events, keine Re-Runs. Sie fiel trotzdem, weil die **Anforderung** wegfiel: ohne
Emissionskarten bleiben von den Contrib-Vorteilen nur Dinge, die die Python-Pipeline selbst kann
(§1.4). Mit ihr fiel die **HBEFA-Lizenzfrage** (Standard 350 € / Studentenversion 70 €,
VSP-Passwort `MATSIM_DECRYPTION_PASSWORD` gegen Lizenznachweis) — **wird nicht angestoßen**.
Reaktivieren nur, falls doch link-aufgelöste Karten gebraucht werden; dann ist auch der
Zwischenweg wieder aktuell (EMEP/EEA-Faktoren an den repräsentativen Geschwindigkeiten der
HBEFA-Verkehrssituationen auswerten → Pseudo-HBEFA-Tabellen, die Contrib prüft nur das CSV-Schema).
Erhalten geblieben ist aus dieser Linie die Identifikation der **Alt**-Faktorquelle des
Python-Modells: **CE Delft STREAM** (Kommentar `config.py:158`; Klassen/Segmente/Min-Max passen
exakt) — öffentlich und zitierfähig, wird aber durch EMEP/EEA ersetzt.

### 3.6 „Die LMD-Karte bleibt leer bei einem DRT-losen Run"

`zurückgezogen` · 2026-07-28

**Geglaubt** (Plan-D-maps Whole-Branch-Review, 2026-07-17): `build_kpis.py` gated den
Netzwerk-Geometrie-Block auf `drt_cache is not None`, also fiele bei einem reinen Freight-/LMD-Run
`link_geo=None` → alle LMD-Touren `runs:[]`, Stopps gedroppt, Heat leer, Karte nur Controls. Vor
dem ersten LMD-only-Szenario zu fixen.

**Gemessen** (2026-07-28): `events_cache.ensure_caches` gibt **beide** Cache-Pfade
bedingungslos zurück — bei einem DRT-losen Run ist der DRT-Cache eine **leere Datei**, nicht
`None` (nachgemessen: `drt is None = False`, 0 Bytes, Freight-Cache 922 Bytes). Der Block läuft
also, `reconstruct_drt_paths` liefert `({}, set())`, und `link_geo` wird aus `freight_used`
geladen — genau das, was die LMD-Layer brauchen. Ein Lauf ohne jede DRT-Zeile in den Events
produziert eine nicht-leere Heat-Ebene. `drt_cache is None` tritt nur bei `--no-events` oder
fehlender Events-Datei ein, und beide überspringen die Karten ohnehin komplett (eine Zeile
weiter unten).

**Es bleibt:** die *Prämisse*, dass `drt_cache` ein DRT-Indikator ist, war falsch — es ist ein
Events-vorhanden-Indikator. Das ist jetzt als Kommentar an der Bedingung und als Regressionstest
(`test_drt_less_run_still_gets_lmd_link_geometry`) festgenagelt, damit der Befund nicht ein
zweites Mal aufgeschrieben wird. Der eigentliche Grund, warum `tours`/`stops` bei so einem Lauf
leer aussehen können, ist ein **anderer**: ohne `output_carriers.xml.gz` gibt es kein
Carrier-Match, und ungematchte Fahrzeuge werden bewusst weggelassen.

### 3.7 „Das Wohnstruktur-Signal ist über Carrier-Grenzen übertragbar (+21 % Skill auf Hermes)"

`zurückgezogen` · 2026-07-29 (B10)

**Geglaubt** (2026-07-24, OSM-Prädiktor): der auf DHL gefittete B2C-Teil (Einwohner + EFH/MFH)
sagt die räumliche Verteilung eines **nie gefitteten** zweiten Carriers (Hermes, ~rein B2C)
**+21 % besser** vorher als eine reine Bevölkerungsbasis (P(Skill > 0) ≈ 0,83). Gelesen als Beleg,
dass das Siedlungsstruktur-Signal echt und übertragbar ist und kein DHL-Artefakt.

**Gemessen** (2026-07-29, Zensus-Prädiktor): Skill **−3,5**, 95-%-KI **[−62 ; +33]**,
P(Skill > 0) = **41 %**. Kein messbarer Vorteil mehr — und zwar in *keine* Richtung, das KI
umschließt die Null breit. Plausibler Mechanismus: die Zensus-Netto-Wohnfläche korreliert
deutlich stärker mit der Einwohnerzahl als die alte OSM-Bruttofläche, deren
`building=yes`-Fallback gerade *nicht* bevölkerungsproportional war — es bleibt weniger Signal,
das **zusätzlich** zur Bevölkerung etwas erklärt.

**Es bleibt:** (a) die Decke-Kennzahl ist prädiktorunabhängig und unverändert — die
Pro-Kopf-Muster der beiden Carrier stimmen nur moderat überein (r ≈ 0,31, KI 0,16–0,60), ein
großer Teil des DHL-Residuums ist also echt carrier-spezifisch. (b) Der B2B-Teil überträgt
korrekt **nicht** auf einen reinen B2C-Carrier (−36 % statt vorher −6 %) — der B2C/B2B-Split
trifft eine echte strukturelle Unterscheidung. (c) Die Übertragbarkeit **über den Raum** ist
unberührt und sogar besser belegt (§2.9: Skill-KI ohne Null). Was fehlt, ist der Beleg über
**Carrier**-Grenzen. Für diese Studie ist das kein tragender Baustein — sie überträgt räumlich,
nicht carrierweise —, aber der Satz darf so nicht mehr im Text stehen.

### 3.8 „`jspritIter=1000` kostet ~4 h pro Carrier, also ~20 h je Arm"

`zurückgezogen` · 2026-07-30 (User-Einwand aus der Hannover-Empirie)

**Geglaubt** (§2.2, 2026-07-28): ein Iterations-Hochlauf auf 1000 jsprit-Iterationen kostet ~4 h
pro Carrier und damit ~20 h je Szenario-Arm — deshalb verworfen zugunsten der Multi-Run-Mittelung.
Grundlage: eine nach **2 von 7 Carriern in 5,8 h abgebrochene** Messung, linear hochgerechnet.

**Gemessen** (2026-07-30, vollständige Phasenzerlegung aus dem `base10c`-Log, Tabelle in §2.2):
die jsprit-Phase über **alle 7** Lausitz-Carrier dauert bei `jspritIter=100` **64 min**, @1000
also **höchstens 10,7 h** (obere Schranke — die Iterationsgeduld skaliert mitsamt der
Iterationszahl, s. §2.2) — jedenfalls nicht 20 h. Die alte Zahl entstand durch lineare
Extrapolation vom **größten** Carrier auf alle sieben, bei einem Größenfaktor von 11 zwischen
Nr. 1 (824 Jobs) und Nr. 7 (143 Jobs). Rekonstruktion: Carrier 1 ×10 = 3,7 h (= die „4 h pro
Carrier"), Carrier 1+2 ×10 = 5,6 h (= die abgebrochene 5,8-h-Messung).

**Auslöser der Prüfung:** die Hannover-Sensitivitätsläufe fahren `jsprit=1000` bei **201**
Carriern in ~7 h pro Lauf — mit der alten Zahl unvereinbar. **Zwei Faktoren lösen das auf:**
1. **Hannover parallelisiert, Lausitz nicht.** Der Legacy-`Router` löst Carrier auf Worker-Threads
   (`ThreadingType`, `forkJoinPool.submit(...parallelStream...)`, `Executors.newFixedThreadPool` —
   `Router.java:420,517`); `LausitzFreightPreprocessor.routeWithDurationCap:313` ist eine
   sequenzielle `for`-Schleife auf dem Main-Thread. Das ist vermutlich der größere der beiden
   Faktoren — und es heißt zugleich, dass der parallele Carrier-Pfad **produktiv erprobt** ist
   (201 Carrier, gleiche `NetworkBasedTransportCosts`-Klasse) → BACKLOG-Punkt.
2. **Jobs pro Carrier statt Carrier-Anzahl.** Laufzeit ∝ `jobs^1,4` (§2.2) ⇒ Aufteilen ist billig,
   Poolen teuer. Hannover verteilt ~97.500 Pakete auf 201 kleine Probleme, Lausitz poolt weniger
   Pakete auf 7 regionsweite (2.975 Jobs, davon 824 in einem einzigen). **Dieselbe strukturelle
   Ursache** wie bei der LMD-Abfahrts-Gruppierung (BACKLOG-DONE 2026-07-30) — dort 7 regionsweite
   vs. 187 kleine Carrier.

**Es bleibt:** §2.2 selbst — `jspritIter=100` genügt für km-KPIs nicht (der Rauschboden §2.1 ist
gemessen und davon unberührt). **Nicht** mehr belastbar ist die *Begründung*, mit der der
Iterations-Hochlauf verworfen wurde; die Abwägung Hochlauf ↔ Multi-Run ist bei 10,7 h (bzw. ~3,7 h
mit parallelisierten Carriern) neu zu führen. Lehre fürs Nächste: Extrapolation über
ungleichgroße Einheiten nie linear, und eine abgebrochene Messung nie als Kostenzahl zitieren
ohne den Abbruch mitzuführen.

---

## 4 · Bewusst ausgeklammert

- **4.1 Passenger-Parcel Compensation (PPC)** — Profit-Redistribution nach Calabro et al. 2026:
  Fahrgäste, die paketbedingte Umwege tolerieren, werden aus dem Netto-Gewinn der
  Paketintegration per Fahrpreis-Rabatt entschädigt
  (PPC = (Netto-Gewinn − aggregierter Fahrgast-Diskomfort) / Fahrgastzahl). Adressiert
  **Nutzerakzeptanz** — eine der zwei Standing-Barrieren für Passenger-Freight-Integration (die
  andere: Regulierung). Synthetisches Fallbeispiel: ≈ 0,11–0,24 €/Fahrgast bei 100 Paketen/Tag
  (500 Fahrgastanfragen/Tag); bei 200 Paketen/Tag ≈ 0,21–0,28 €, aber Zustellerfolgsquote fällt
  auf 70–76 %. Autoren empfehlen öffentliche statt Operator-Finanzierung.
  HAGRID misst bislang nur operative Machbarkeit, keine Fahrgast-Kompensation. Fare-Kanal ist
  aktuell ohnehin tot. **Prüfen vor der Evaluation** (BACKLOG). _(2026-07-15)_

- **4.2 DOF-Kontrollarm (M12)** — „Dedicated Online Freight" = Shared-Use-Dispatch + Fahrzeug,
  nur Pakete, keine Pax. Würde den **reinen** Integrationseffekt (Shared-Use vs. DOF) vom
  Tooling-/Fahrzeug-Effekt (DOF vs. offline-Baseline) isolieren und eine faktorielle Zerlegung
  vervollständigen (Pax-allein = χ=0-Lauf / Fracht-allein = DOF / beide = Shared-Use).
  Priorität liegt auf Systemebene → aufgreifen, wenn fürs Paper eine mechanistische
  Zusatzaussage gebraucht wird. _(2026-07-20)_

- **4.3 Vollsimultaner nativer `CargoRequest`-Fork (Option C)** — die architektonisch saubere
  Alternative zu Option A (§1.1), **zurückgestellt wegen Aufwand**, nicht wegen Fehlern.
  Der DRT-Request-Lebenszyklus ist durchgängig personengebunden (`DrtRequest implements
  PassengerRequest`, `DefaultPassengerEngine`, `DrtStopActivity`/Passenger-Handler; eine leere
  Passenger-ID-Liste wird per `Preconditions` abgelehnt, eine künstliche ID ohne Agent crasht beim
  Pickup). Ein eigener `CargoRequest`-Typ, der *gleichzeitig* mit Pax auf demselben Fahrzeug
  fährt, erfordert die Generalisierung von `InsertionGenerator` / `VehicleEntry` / `StopWaypoint`
  / Occupancy — ein **tiefer Fork des drt/dvrp-Contribs** (verliert Upstream-Wartung,
  Forschungs-Softwareaufwand von Monaten). Die native `drt-extensions/services`-Vorlage deckt nur
  die **exklusive** Phase ab → gehört zu 1d, nicht zu Co-Riding.
  Quelle des Vorschlags: externes Dokument „3.2 Fracht-Requests nicht als Dummy-Passagiere
  modellieren" (`Research/Paper/City Logistics/Dummy_Chat.txt`) — technisch korrekt, adressiert
  aber den exklusiven Fall, nicht Co-Riding (§3.2.7 dort räumt das selbst ein).
  **Reaktivieren, falls** die Event-/KPI-Kontamination in der Evaluation trotz gehärtetem D10
  untragbar wird (§2.4). _(2026-07-20)_

- **4.4 Volle Multi-Boundary-AESA / volle LCA** — Scope für 6 Seiten: Climate-Boundary
  quantitativ (CO₂e-Budget-Downscaling), Aerosole (PM) + Novel Entities (Reifen-Mikroplastik)
  qualitativ. Der SOS-Layer ist Methodik-, nicht Code-Aufwand: Downscaling globaler Grenzen auf
  Region/Sektor (AESA-Literatur: Bjørn & Hauschild, Ryberg et al., Richardson et al. 2023) — die
  **Allokationswahl ändert Ergebnisse um Größenordnungen**, muss also begründet und als
  Bandbreite gezeigt werden. Cradle-to-Grave nur qualitativ (§1.4). _(2026-07-28)_

- **4.5 Phase-2-Deferrals** — Packstationen/Locker (braucht Standortdaten, → §2.10),
  Ride-and-Collect, mobile Packstation, verschiedene Van-Größen als eigene Dimension.
  Alle bewusst aufgeschoben. _(2026-07-14)_

- **4.6 Case-Study-Area bleibt Hoyerswerda** — die Erweiterung zum zusammenhängenden
  Hoyerswerda↔Ruhland-Korridor (DRT als Bahn-Zubringer = natives Lausitz-Konzept) ist erst
  **~2027** ein Thema, konkret wenn es um die Simulation konkreter Policies geht. Muss **vor**
  einem finalen Headline-Run entschieden sein (Vergleichbarkeit). Folge: der
  „Bahn-Zubringer"-Kartenlayer ist 2026-07-20 entfernt worden (BACKLOG trägt die
  Wieder-Einbau-Notiz). _(User-Entscheidung 2026-07-20)_
