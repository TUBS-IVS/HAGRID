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
einen gibt — den Reproduktionspfad. _Zuletzt aktualisiert: 2026-08-17._

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
  **Präzisierung 2026-08-13:** der Abzug lief bis dahin auf der *Summe* beider Insertion-Beine und
  war damit selbst fehlerhaft — die Entscheidung „Detour-only statt Dwell-inklusive" trägt, ihre
  Umsetzung war es nicht → §2.35.

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
  **Annotation 2026-07-31:** die hier prognostizierte Quoten-Verzerrung ist **nicht eingetreten** —
  gemessen ist der Fenster-Effekt auf die Baseline-Zustellquote ≈ 0, das alte Fenster hat jsprit
  nie gebunden (§3.10). Die Entscheidung trägt weiter, aber als Design-Hygiene (ein Versprechen,
  eine Konstante), nicht als Fehlerkorrektur.
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

- **1d Idle-Threshold: operativer Bereich [0,1–0,3], kein Tier-2-Kostengate** — gemessen · 2026-07-31
  θ-Sweep komplett (iter150, fleet120, ein Seed, gepaart auf einer jsprit-Realisierung — §2.17;
  Referenzwerte 6020 Pakete / 127 Touren in jedem Punkt identisch): θ=0,1 → 5894 Pakete (97,9 %),
  363 Fracht-Veh-h, Pax 7488 Rides (−16,6 %) · 0,15 → 4038 (67,1 %), 262 h, 8160 (−9,1 %) ·
  0,2 → 2261 (37,6 %), 153 h, 8545 (−4,8 %) · 0,3 → 551 (9,2 %), 42 h, 8964 (im Band) ·
  0,4 → 9 (0,15 %), 1,3 h · 0,5 → 1 Tour · 1,0 (Kontrolle) → 0. Wartezeit (~704–710 s) und
  Rejections (<0,5 %) sind über die gesamte Kurve **θ-invariant**: der Integrationspreis läuft
  vollständig über Mode-Choice-Nachfrageverlust (§2.25), ~proportional zu den
  Fracht-Fahrzeugstunden. Dispatch fällt quasi-exponentiell; im Intervall [0,1–0,2] ist der
  Trade-off lokal nahezu linear (0,15 liegt auf der Sehne: 67,1 % vs. 67,75 % interpoliert) —
  θ dosiert dort praktisch stufenlos zwischen Paketleistung und Pax-Nachfrage.
  **Konsequenzen:** θ≥0,4 ist de facto pax-only (zweiter Kontrollarm); ein prädiktives
  Tier-2-Kostengate ist für die Headline **nicht nötig** (θ_hist bleibt Backlog-Option);
  Verspätungen 0 und Erhaltungsidentitäten geschlossen in allen Punkten; einziger Sonderwert:
  2 `tours_rejected_at_splice` bei θ=0,15 (alle anderen Punkte 0).
  Läufe: `m1d010/015/020/030/040` + `ctrl1d`/`m1d050` (Sim-PC). Einzelseed-Vorbehalt für
  absolute km-/Stunden-Niveaus bleibt (§2.1/§2.17); Cross-Machine-Demand-Vorbehalt → §2.30.

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

- **Klassenmapping: beide Flotten = N1-III Diesel Euro 7, DPF+SCR** — **überholt durch den
  nächsten Punkt** · 2026-07-28, ersetzt 2026-07-31
  Der LMD-Teil („alle drei Van-Typen ebenfalls N1-III") ist zurückgezogen, siehe unten. Der
  DRT-Teil **trägt weiter** und ist unten nur mit Zahlen unterlegt: „Urban Buses Midi ≤15 t"
  bleibt verworfen (repräsentiert 9–12-t-Midibusse, Kurven enden bei 80 km/h).

- **Klassenmapping Rev. B: alle Fahrzeuge LCV (N1) Euro 7 DPF+SCR, differenziert nach Segment**
  — `trägt` · 2026-07-31
  Kategorie, Kraftstoff, Norm und Nachbehandlung bleiben für alle Fahrzeuge identisch; variiert
  wird ausschließlich das **Segment**, also die methodeneigene Massenklasse:
  `ct_cep_size_s` → **N1-II** (angesetzte Bezugsmasse ~1700 kg), `_m` → **N1-III** (~2000 kg),
  `_l` → **N1-III** (~2400 kg), DRT-Fahrzeug → **N1-III**. Generierte Sweep-Typen
  `ct_cep_<cap>_<tpl>`: Kapa ≤ 120 → N1-II, sonst N1-III.
  **Warum die Änderung:** die Van-Flotte ist real gemischt und der Mix ist ein **jsprit-Ergebnis**,
  keine Konstante ([`LmdCarrierBuilder`](../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LmdCarrierBuilder.java) bietet alle Typen bei
  `FleetSize.INFINITE` an). Gemessen 2026-07-31: `base10c` fährt **92,6 %** der Freight-km auf
  `size_s` (56 von 63 Fahrzeugen), `localdepots_stagger` dagegen **100 %** `size_m`. Eine
  Einheitsklasse N1-III überschätzt die Baseline-Freight-Energie um **~39 %** (Flottenintensität
  2,25 statt 3,12 MJ/km) und kann Szenarienrangfolgen kippen.
  **Segmentwirkung**, verifiziert bei 30 km/h (Tourmittel der Läufe: 36 km/h): Energie N1-II
  2,183 vs. N1-III 3,123 MJ/km (**+43 %**); NOx N1-I 0,054 vs. N1-II/III 0,090 g/km (N1-II und
  N1-III **identisch**); PM exhaust über alle drei Segmente identisch (DPF). Der einzige
  Energiebruch liegt also zwischen II und III, der einzige NOx-Bruch zwischen I und II.
  **Annahmecharakter:** die Bezugsmasse je Typ ist eine ausgewiesene Setzung. Sie ist die Größe,
  über die die **EU-Typgenehmigung** die N1-Segmente definiert (≤1305 / ≤1760 / >1760 kg); das
  Segment folgt daraus mechanisch. **Zitatkette:** diese Grenzen stehen **nicht** im
  Guidebook-Kapitel (kein Treffer für „1305", „1760", „reference mass" im PDF) — dort ist nur N1
  als Ganzes definiert („carriage of goods […] not exceeding 3.5 tonnes", Tab. 2-1). Aus der
  Verordnung zitieren, nicht aus dem Guidebook.
  Caveats: §2.7.

- **Keine Zuladungsskalierung — methodenkonform, nicht Auslassung** — `trägt` · 2026-07-31
  Geprüft und **verworfen**. Das Guidebook beschränkt die Lastkorrektur explizit auf schwere
  Nutzfahrzeuge, Kap. 1.A.3.b.i–iv S. 62 f., Abschnitt „Emission corrections": *„road gradient
  and vehicle load. Corrections need to be made to **heavy-duty vehicle** emissions […] Also, by
  default, a factor of 50 % is considered for a load of heavy-duty vehicles."* Für LCV ist
  Zuladung **kein Methodenparameter** — 0 von 1087 LCV-Zeilen im Appendix-4-Sheet tragen einen
  Wert in `Load` oder `Road Slope` — und ein Referenz-Ladezustand ist für LCV nicht dokumentiert.
  Ein Lastmultiplikator hätte damit **keinen definierten Nullpunkt**. EMEP legt den Masseeffekt
  bei leichten Fahrzeugen ins **Segment**; die Differenzierung oben ist die methodenkonforme
  Abbildung genau dieses Effekts.
  **Bound des nicht modellierten Lasteffekts** (aus der HDV-Parametrisierung, Rigid ≤7,5 t,
  30 km/h, 0 % Steigung: 4,387 leer → 4,666 bei Default-Last 50 % → 4,954 MJ/km voll):
  **≤13 %** für einen 7,5-Tonner, **~5 %** für unsere Vans (~575 kg Zuladung auf ~2100 kg
  Leergewicht), im Tourmittel ~2,5 % weil die Ladung leerläuft. Das liegt **unter dem
  jsprit-Rauschboden von 6,5 %** (§2.1) und weit unter dem Segmenteffekt von 43 %.
  **Ausdrücklich unzulässig:** Mischung von EMEP-Niveaus mit den STREAM-Lastverhältnissen aus
  [`hagrid_output_analysis/config.py`](../parcel-demand-2-matsim-pipeline/src/hagrid_output_analysis/config.py) (dort van >2t urban 276→302 g/km = +9,4 %). Keine
  gemeinsame Referenzbasis, keine haltbare Invarianzannahme über zwei Methoden. Wer Zuladung im
  Ergebnis haben will, muss die **gesamte** Rechnung auf STREAM umstellen — dann fallen
  Geschwindigkeitskurve, BEV-Arm und der SPN23/CH4/N2O-Vektor weg.
  **Konsistenzcheck** (legitim, weil er nichts berechnet): die beiden unabhängigen Quellen sagen
  dasselbe — Lasteffekt +9,4 % (STREAM) vs. +12,9 % (EMEP-HDV), Klassensprung +40 % (STREAM van
  <2t→>2t) vs. +43 % (EMEP N1-II→N1-III).

- **Kategoriensubstitution M2 → N1-III für die DRT-Flotte** — `trägt` · 2026-07-31
  Das DRT-Fahrzeug hat `capacity="10"` und ist damit nach der Guidebook-eigenen Definition **M2**
  („more than eight seats in addition to the driver's seat […] not exceeding 5 tonnes", Tab. 2-1),
  nicht N1. Die Substitution ist eine benannte Annahme, gestützt auf die Einordnung bei 30 km/h:
  PC Large-SUV-Executive Euro 7 Diesel **2,545** / LCV N1-III **3,123** / Buses Urban Midi ≤15 t
  Euro VI bei Default-Last 50 % **~9,1** MJ/km. Der Bus liegt Faktor ~3 zu hoch, der Pkw zu
  niedrig; ein 10-sitziger Sprinter Tourer *ist* mechanisch ein N1-III-Sprinter mit Sitzen.
  Im Paper mit diesen drei Zahlen ausschreiben.

- **Emissionszurechnung Freight ↔ Pax bei gemeinsam genutzten Fahrzeugen** — `trägt` · 2026-07-31
  **1d (modular):** Regimesplit, vollständig durch die Tasksequenz bestimmt. km innerhalb der
  `MODULAR_FREIGHT_DRIVE`-Fenster → Fracht, übrige km → Pax; Anfahrt zum Depot → Fracht, Fahrt
  vom Depot zum nächsten Fahrgast → Pax. Erschöpfend und ohne Rest: jeder km landet auf genau
  einer Seite. Retooling selbst ist ein STOP (`drt_retooling_hours_total` = „events(STOP inside a
  freight window)"), erzeugt also keine km. **Voraussetzung:** `drt_vehicle_km` trägt bisher
  keinen Freight/Pax-Kanal (§2.14, „not corrected") — der Distanzsplit entsteht erst im
  Emissions-Extractor (Plan Task 5b).
  **1c (Co-Riding), User-Entscheidung 2026-07-31:** **zweistufig — allokationsfreie Systemsumme
  als Boden, plus massenbasierte Aufteilung je Link für spezifische Intensitäten.**
  (a) *Boden:* `total_*` wird immer berichtet, dazu die beiden Extremzurechnungen als Bandbreite.
  (b) *Aufteilung:* die Emissionen eines Links werden nach der **Masse an Bord** aufgeteilt —
  Paketmasse gegen Fahrgastmasse. Für CO₂e je Paket bzw. je Fahrgast durch die jeweils bediente
  Menge teilen. Konvention: Verteilung über **kg·km** (Masse × Linklänge), analog zur
  tkm-Allokation in EN 16258 / GLEC Framework, die im Projekt für die WTT-Kette ohnehin zitiert
  wird. Leerfahrten (nichts an Bord) haben keine kg·km-Basis und werden proportional zu den
  kg·km-Anteilen des Fahrzeugtages verteilt — ebenfalls GLEC-Konvention.
  *Warum nicht marginal:* eine marginale Zurechnung („Umwege durch Parcel-Insertion → Parcel")
  bräuchte Java-Eingriff und 1c-Rerun (§2.24, Option M) und würde sich am Ende nicht zum Ganzen
  summieren. Die Massenbasis ist direkt gemessen, summiert konstruktionsgemäß auf und ist
  rauschunempfindlich — im Gegensatz zu den verworfenen Differenzverfahren (§3.9).
  *Preis, ausdrücklich mitzuführen:* die Aufteilung hängt an zwei Konstanten, von denen eine nicht
  im Modell steht — siehe §2.26.
  **Pax-Zuladung ist irrelevant:** `mean_pax_aboard_pax` = 1,60 → ~128 kg auf ~2100 kg = +6 %
  Masse → **~1,2 %** Energie. Unter dem Rauschboden.

- **EV-Reichweite als Schwellen-Sweep, nicht als Pass/Fail-Gate** — `trägt` · 2026-07-31
  Gemessen über alle 12 Läufe mit Freight-Touren: längste Tour überhaupt **183,3 km**, `base10c`
  max 158,8 / p95 139,3 km. Bei **250 km ist die Überschreitung in jedem einzelnen Lauf 0 %** —
  ein Einzelgate bei 250 km hätte ein Nullresultat produziert, das nach Prüfung aussieht.
  Trennschärfe liegt allein bei ~150 km (dort 0–13,4 % je Lauf; Maximum `married120` /
  `localdepots_stagger_c100`). Berichtet wird die Kurve über 150/200/250 km. **Befund:**
  Freight-Elektrifizierung ist in der Lausitz nicht reichweitenbegrenzt — als falsifizierbare
  Aussage, nicht als leeres Häkchen.

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

**Annotation 2026-08-11 — der Mechanismus ist richtig, aber eine Ebene zu flach beschrieben
(→ §2.33).** Zwei Präzisierungen: (a) die **81 % sind basisabhängig**, kein Modellwert — gemessen
81,4 % auf `bandz_central`, 72,2 % auf `basew21`, 88,3 % auf dem Hannover-Lauf `230v2`; die Spanne
kommt fast vollständig aus dem fenster­abhängigen Overtime-Artefakt (§2.33 Punkt 4). (b) Distanz
ist nicht nur ein *schwacher* Term — **Zeit ist gar keiner** (`costsPerSecond = 0` in allen
Vantypen), und der Fixsatz hängt an der Tour, nicht an ihrer Dauer. Deshalb kann kein
Iterations-Hochlauf die Geometrie stabilisieren: der Solver hat schlicht kein Signal, eine Tour zu
verkürzen.

**Annotation 2026-07-30 — unabhängig reproduziert bei `jspritIter=1000`, und ein neuer Kanal.**
Die 1000-Iterationen-Sonde (§2.2) fand auf einem Carrier über 3 Seeds **7,61 % km-Spanne bei
0,00 % Tourenspanne** und, mit Mischsatz gerechnet, **~0,7 % Gesamtkosten-Spanne** — die
Signal-Rausch-Struktur dieses Abschnitts (−6,5 % km / −0,8 % Kosten) ist damit auf anderer
Konfiguration und zehnfacher Iterationszahl bestätigt.
**Zusätzlich instabil ist der FLOTTENMIX**, nicht nur die Geometrie. Touren je Van-Typ
(`_s`=100 / `_m`=165 / `_l`=230 Pakete):

| Seed | `_s` | `_m` | `_l` | Σ Touren |
|---|---|---|---|---|
| 4711 | 6 | 13 | 0 | 19 |
| 1234 | 8 | 10 | 1 | 19 |
| 9876 | 6 | 12 | 1 | 19 |

Die **Anzahl** steht bombenfest, die **Größenverteilung** verschiebt sich. Konsequenz, die noch
nirgends berücksichtigt ist: Emissionsfaktoren hängen in der EMEP/EEA-Zuordnung an der
Fahrzeuggrößenklasse (§1.4), also propagiert der instabile Mix als **zusätzlicher Rauschkanal in
die Emissions-KPIs — oben auf die km-Instabilität**. Für den `[H]`-Nachhaltigkeitspunkt heißt das:
der Multi-Seed-Fächer ist auch dort Pflicht, und die Faktor-Sensitivität je Größenklasse muss
gegen diese Streuung gestellt werden.

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

⚠️ **Die ×10-Spalte war eine obere Schranke und ist zu niedrig — gemessen 2026-07-30.** Grund für
die Unsicherheit: `configureAlgorithm` hängt ein `IterationWithoutImprovementTermination` an, dessen
Geduld selbst von der Iterationszahl abhängt (`calculateNoImprovementThreshold`:
`min(iters/4, round(14·ln iters))` → **25** bei 100, **97** bei 1000).

**Sonde (3 Seeds × größter Carrier, `jspritIter=1000`, Dev-PC, Java 21.0.10):** Setup 3,8 min,
jsprit-Phase **~5,0 h** für **einen** Carrier (875 Services). Meine ×10-Hochrechnung sagte 3,7 h —
also **~35 % zu niedrig**. Die Annahme, die Kostenmatrix-Aufwärmung sei ein großer, nicht
skalierender Einmalblock, war falsch: sie verteilt sich über die frühen Iterationen.
**Hochrechnung auf einen ganzen Arm wird hier bewusst NICHT mehr gemacht** — genau dieser Schritt
hat die zurückgezogene 20-h-Zahl erzeugt (§3.8). Festzuhalten ist nur: der größte Carrier allein
kostet auf dieser Maschine ~5 h, und der Dev-PC-Laptop ist nicht der Sim-PC.

Gegenprobe: `base10c` startete 23:29:12, `ITERATION 0 BEGINS` um 00:34:39 → 65,5 min bis zur
MATSim-Schleife. Der Rest des Laufs (150 MATSim-Iterationen) sind ~4 h 50.
**Fehlerursache der alten Zahl:** linear vom *größten* Carrier hochgerechnet. Die Carrier sind
extrem ungleich (Faktor 11 zwischen Nr. 1 und Nr. 7, deckt sich mit §2.1: dhl 24 Touren, ups 4);
die beiden größten ergeben @1000 zusammen 5,6 h — das *ist* die abgebrochene 5,8-h-Messung, und
„4 h pro Carrier" war Carrier 1 allein. → Zurückziehung §3.8.

**Folge für die gewählte Antwort — jetzt auf dem besseren Argument (gemessen 2026-07-30):** der
Iterations-Hochlauf ist verworfen, weil er **nicht wirkt**, nicht weil er teuer ist. Die Sonde
misst bei `jspritIter=1000` auf einem Carrier über 3 Seeds:

| Seed | Plan-km | Touren |
|---|---|---|
| 4711 | 586,2 | 19 |
| 1234 | 576,0 | 19 |
| 9876 | 542,9 | 19 |

**km-Spanne (max−min)/Mittel = 7,61 %** bei **0,00 % Tourenspanne**. Die Streuung bei 1000
Iterationen ist damit **nicht kleiner** als der 6,5-%-Rauschboden bei 100 (§2.1) — dieselbe
Größenordnung. Der Mechanismus aus §2.1 ist exakt reproduziert: **Fahrzeugzahl konvergiert,
Tourengeometrie nicht.** Mehr Iterationen können das nicht heilen, weil die Zielfunktion
fixkostendominiert ist und Distanz ein schwacher Term bleibt.
⇒ **Der Multi-Seed-Fächer (§1.5) ist keine Bequemlichkeit, sondern die einzige Antwort.**
Vorbehalte: n=3 (die 7,61 % sind eine Spannweite aus drei Ziehungen, keine Streuungsschätzung),
ein Carrier statt Arm (auf Armebene kann sich über 7 Carrier etwas wegkürzen), andere Maschine.
Die *qualitative* Aussage trägt trotzdem. Reproduktion: Sonde via
`-Dhagrid.jsprit.onlyCarrier=largest` + `-Dhagrid.jsprit.seed=…`, km aus den `<route>`-Linklisten
des routed Carrier-XML gegen die Netz-Linklängen (0 nicht auflösbare Links).

**Nebenbei bestätigt:** die Carrier werden in `routeWithDurationCap` strikt **sequenziell** auf
einem Thread gelöst (BACKLOG-Punkt Carrier-Parallelisierung) — für die Sonde irrelevant (ein
Carrier), für Armläufe der Hebel.

### 2.3 χ ist eine untere Schranke, nicht der Umweg

`trägt` · 2026-07-27 · **Paper-Caveat.** Bei Teil-Piggyback misst χ den *verworfenen* Umweg nur
nach unten. Zusätzlich: **n=1 Seed pro χ-Punkt** in den bisherigen 1c-Läufen.
Wie *aktiv* das Gate ist, ist gemessen; ob es **bindet**, ist es nicht → §2.31.
⚠️ Bis 2026-08-13 war die Untererfassung **kein Messcaveat, sondern ein Rechenfehler** im Gate
(Abzug auf der Summe statt je Bein): bis zur vollen Eigenstandzeit realer Umweg las sich als 0,
und das betraf die Zulassungsentscheidung → §2.35.

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

**Erweiterung 2026-08-11: die LMD-Seite ist ebenfalls nicht belastbar, und aus einem anderen
Grund.** Dieser Abschnitt las sich bislang so, als sei nur die DRT-Seite ein Platzhalter und die
LMD-Kosten aus Hannover gesetzt. Gesetzt sind sie — aber ohne dokumentierte Quelle, ohne
zeitproportionalen Term und mit zwei Scoring-Defekten. Die vollständige Zerlegung, die Messung und
die Folgen stehen in **§2.33**. Für die Formulierung im Paper heißt das: *alle* €-Zahlen stehen
unter Vorbehalt, nicht nur die DRT-seitigen.

### 2.7 Emissions-Caveats

- **Euro-7-Faktoren sind projiziert** — im Paper als solche kennzeichnen.
- **LCV-Kurven haben kein N2O/NH3** → Tier-2-Pauschalergänzung aus dem Hauptkapitel;
  CO₂e-Beitrag beim Diesel-LCV ~1–2 %, vertretbar.
- **`_l`-Van (230, 6 m, keine Masse in der XML) bleibt Grauzone** zum leichten Lkw → optional
  einmal als HDT „Rigid ≤7,5 t" gegenrechnen, damit die Bandbreite ausgewiesen statt versteckt ist.
  **Korrektur 2026-07-31:** falls das kommt, ist die Vergleichszeile `Load = 0.5` — das ist der
  Guidebook-**Default** für HDV —, also 4,666 MJ/km bei 30 km/h / 0 % Steigung, **nicht** 4,387
  (das wäre Load = 0). Und die Richtung ist nicht die erwartete: HDT bei Nulllast liegt mit 4,387
  *über* N1-III (3,123), die Variante verschiebt also das Niveau deutlich, statt nur eine
  Obergrenze zu setzen. Zusätzlich bringt die HDT-Kategorie `Load`- **und** `Road Slope`-Strata
  mit, die der Extractor nicht kennt.
- **Der Fahrzeugmix ist endogen und muss mitberichtet werden** — 2026-07-31.
  jsprit wählt bei `FleetSize.INFINITE` frei aus den angebotenen Van-Typen; der Mix schwankt
  zwischen Läufen von 0 % bis 93 % km-Anteil `size_s`. Mit der Segmentdifferenzierung (§1.4) hängt
  CO₂ damit an einer kostengetriebenen Optimierungsentscheidung. **Nebenwirkung, die offen stehen
  muss:** die Kosten von `ct_cep_size_s` sind selbst linear interpoliert
  (`lmd-vehicle-types.xml`, Kommentar im File) — sie beeinflussen die Fahrzeugwahl und damit
  indirekt das Emissionsergebnis. Deshalb emittiert der Extractor `segment_km_share_*`: ohne diese
  Zeilen ist ein CO₂-Delta nicht in Mixverschiebung und Fahrleistungsänderung zerlegbar.
- **~~Non-Exhaust-Abrieb ist nicht segmentdifferenziert~~ — RETRAHIERT am selben Tag (2026-07-31),
  Details in §2.27.** Die Behauptung stand hier, *bevor* Kap. 1.A.3.b.vi–vii überhaupt vorlag
  (`hagrid-input/emissions/SOURCES.md` wies es selbst als Lücke aus). Nach dem Nachladen: die Quelle
  löst LCV **sehr wohl** nach N1-Segment auf. Die „bewusste Asymmetrie" war keine
  Methodenentscheidung, sondern eine Annahme über eine ungelesene Quelle.
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

   **Annotation 2026-07-31 — die Begründung der Nicht-Korrektur ist empirisch entfallen.** Das
   Gegenargument war, eine Selbstrechnung träte an die Stelle der autoritativen MATSim-CSV. Der
   Emissions-Extractor rechnet die Fahrzeug-km nun mit `link.getLength()` (Netzwerkattribut) statt
   Euklid-Knotendistanz — und trifft `drt_vehicle_stats` in **allen fünf** fertigen 1d-Läufen auf
   **0,001 %**: Rekonstruierte Pax-km + Fracht-km aus dem Fensterschnitt = CSV-Gesamtdistanz
   (48.824 / 47.089 / 48.697 / 48.773 / 47.160 km). Selbstrechnung und autoritative CSV sind also
   **dieselbe Zahl**, sobald die Länge stimmt; es gibt keinen Autoritätsverlust mehr abzuwägen.

   Damit ist auch der oft zitierte **„~3 % zu niedrige Event-Rekonstruktion" ein Artefakt der
   Euklid-Näherung**, nicht eine Eigenschaft der Rekonstruktion (die Luftlinie zwischen den
   Linkknoten ist kürzer als der modellierte Straßenverlauf). Wer künftig Event-Distanzen
   rekonstruiert, muss das `length`-Attribut nehmen; `geometry.load_link_geometry.length_m` ist für
   Distanz-KPIs untauglich und nur für Kartenzeichnung gedacht.

   **Die Entscheidung selbst ist damit nicht getroffen** — ob die drei km-KPIs korrigiert werden,
   bleibt offen und ist eine User-Entscheidung. Nur ihre Begründung braucht eine neue, falls sie
   bestehen bleiben soll.
2. **Das „von Hand korrigierbar"-Rezept (Subtraktion) stimmt nur für `drt_tour_hours_total`.**
   `service_ratio_active`, `fleet_utilisation_by_time`, `mean_pax_aboard` brauchen eine
   **Reskalierung** × `tour_h / (tour_h − freight_h)` (funktioniert nur, weil Exkursionen
   garantiert occupancy-0 sind); `fleet_utilisation_by_trips` ist aus den veröffentlichten KPIs
   **gar nicht** rückrechenbar (Segment-Zählung; Fracht-only-Fahrzeuge erzeugen ein
   unveröffentlichtes Level-0-Segment).
   **Behoben 2026-07-29 (`48bff7c`):** das Rezept ist korrigiert, und die `*_pax`-Zusatzzeilen für
   die reskalierbaren KPIs werden jetzt mechanisch mit emittiert; `fleet_utilisation_by_trips`
   ist dabei explizit als **unkorrigierbar** umklassifiziert (nicht bloß „schwierig").
   **Annotation 2026-07-31 (`c47fc80`):** die Reskalierung ist jetzt gegen **ihre eigene
   Begründung** geprüft, nicht mehr gegen sich selbst. Die bisherigen Tests rechneten dieselbe
   Formel wie die Produktion, waren also gegen eine mutierte Formel blind. Der neue
   Äquivalenztest leitet `mean_pax_aboard_pax` sekundenweise her — `freight_s` Sekunden aus dem
   0-Pax-Eimer entfernen (das ist der D2-Lockout, auf dem der Faktor `tour_h/(tour_h − freight_h)`
   überhaupt ruht) und über den Rest neu mitteln — und trennt das Ergebnis explizit von
   unkorrigiert, invertiertem Rescale und Subtraktion. Zwei Mutationen verifiziert. Am Wert selbst
   ändert sich nichts; belastbar ist er erst jetzt.
3. **Der Marker selbst ist zerbrechlich:** `meta/modular_contaminated_kpis` wird nur auf dem
   Event-Pfad geschrieben (`extract_drt.py:149/194`). Ein `--no-events`-Build oder ein Run ohne
   aufgehobene `output_events.xml.gz` publiziert alle kontaminierten KPIs **ohne jeden Marker**
   (end-to-end reproduziert); die Comparison-Page verliert den Marker-*Payload* und chartet
   `drt_vehicle_km` zugleich als Headline-Balken; die Run-Kacheln tooltippen „autoritativ" ohne
   Banner, obwohl die `warnbanner`-Präzedenz (Shared-Use) in derselben Datei existiert.
   **Behoben 2026-07-29:** der Marker ist jetzt CSV-/szenario-gebunden statt Event-gebunden und
   übersteht `--no-events` (`48bff7c`); die Comparison-Page rendert den Marker-Payload wieder, und
   die Run-Kacheln tragen das Kontaminations-Banner (`5fee8f1`).
   **Annotation 2026-07-31 (`c47fc80`):** `meta/fleet_file_missing` saß im **selben**
   Event-Zweig und ist jetzt ebenfalls draußen — es war derselbe Defekt an einer zweiten Flag,
   und die Fixwelle hatte nur die erste erwischt. Konsequenz für einen Leser: ein
   `--no-events`-Build ohne auffindbare DVRP-Fleet-Datei sagt jetzt in `kpis_long.csv`, dass
   `drt_vehicle_capacity`, `fleet_utilisation_by_time`, `fleet_utilisation_by_trips`,
   `service_ratio_shift` und `fleet_shift_hours` **wegen fehlender Fleet-Datei** fehlen — vorher
   fehlten sie stumm und ununterscheidbar von „dieser Build hat keine Events rekonstruiert". Die
   Flag ist eine Eigenschaft jedes DRT-Laufs, nicht 1d-spezifisch; sie steht hier nur, weil sie
   im selben Zweig wohnte.
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

**Annotation 2026-07-31 (`d386e18`):** das Kommentar-Duplikat ist weg, die Rücknahme damit
vollständig — der Kommentar trägt nur noch das KPI-Zuordnungsargument und verweist für den
Mechanismus aufs Javadoc, statt ihn zweitzuschreiben. Dass ein Overclaim eine Korrektur
überlebt, weil er an zwei Stellen stand, ist der eigentliche Befund; eine Aussage über die
Zahlen ändert sich hier nicht.
**Der Perf-Rand ist ebenfalls behoben, und zwar so, dass er kein Verdikt verschiebt:**
`ModularTourScheduler` prüft vor der Routung eine **zeitunabhängige untere Schranke** der Kette
(2 × 420 s Rüstzeit + alle Servicezeiten + Free-Flow-Fahrzeit Depot→Stops→Depot, je Tour
gecacht). Reißt schon die Schranke das Envelope, ist die Ablehnung bewiesen, und die N+2
Router-Queries entfallen. Bewusst **keine** Wiederverwendung eines früheren Neins: das würde
Monotonie in der Abfahrtszeit unterstellen, die gebinnte DVRP-Fahrzeiten nicht garantieren
(Nicht-FIFO an Bin-Grenzen), und könnte einen machbaren Splice verschlucken — also genau die
Fehlattribution vergrößern, um die es in diesem Abschnitt geht. Die Schranke braucht nur
„eine Linkfahrzeit liegt nie unter `length/freespeed`". `tours_rejected_at_splice`,
`tours_expired_pending` und jede daraus abgeleitete Zahl bleiben unverändert; ein
differentieller Test über den Machbarkeits-Übergang und die beiden 1d-End-to-End-Tests halten
das fest.

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
   **Annotation 2026-07-31 (chid600w21/basew21 gemessen):** der 1c-Kanal hat **nirgends** ein
   Overlay — weder Java-seitig (`LmdCarrierBuilder` ist der einzige Implementierungsort,
   `shareduse/**` referenziert es nicht) noch in `extract_shareduse.py`. Das ist M10-konform
   („raw 100 % in beiden Armen", 1c-Plan M10), aber die **KPI-Schicht mischt die Konventionen**:
   `delivery_rate` der Baseline ist *netto* (Overlay als Miss verbucht, basew21 93,61 %),
   `delivery_rate_total` von 1c ist *brutto/operativ* (chid600w21 93,72 %). Der
   Beinahe-Gleichstand ist eine **Mechanismus-Koinzidenz** (Overlay ~6,4 % vs. χ-Gating ~6,3 % —
   alle 218 verfallenen chid600w21-Segmente waren χ-geblockt), keine Parität. M10-konform
   gelesen: Baseline operativ ≈ 100 % (`unassignedParcels=0`), 1c 93,7 % → die Integration
   kostet ~6,3 pp Zustellquote. Für jede Vergleichstabelle gilt: je Arm dieselbe Konvention
   (brutto/operativ überall, Overlay als separate Zeile) → BACKLOG `[M]`. §2.5-Quarantäne
   (nur Deltas berichten) unverändert.
   **✅ ENTSCHIEDEN + UMGESETZT 2026-08-10 (User): das Overlay ist kosmetisch, die Baseline ist
   operativ 100 %.** Begründung des Users: im POC wird weder der Rücktransport noch die
   Ersatzzustellung an der Packstation simuliert, also ist ein Not-at-home-Miss kein
   Zustellausfall des *Konzepts*, sondern eine nicht modellierte Folgeprozess-Annahme. Solange
   `parcels_unassigned = 0` ist — bei `FleetSize.INFINITE` legt jsprit immer ein Fahrzeug nach,
   ein Ausfall entstünde nur bei echter Einzel-Infeasibility — ist die Baseline definitionsgemäß
   bei 100 %. Umsetzung: **ein** KPI-Name `delivery_rate` in allen drei Armen, kpi_group
   `freight`, Basis „zugestellt / Nachfrage, Overlay NICHT abgezogen"
   (`extract_freight.py`, `extract_modular.py` — der Arm hatte vorher **gar keine** Quote, nur
   `delta_share_*` —, `extract_shareduse.py` als Alias auf `delivery_rate_total`). Der Netto-Wert
   bleibt als `delivery_rate_net_overlay` erhalten, `parcels_handled` und
   `parcels_per_vehicle_km` bleiben bewusst netto, weil `parcels_handled` der Nenner von
   `economics.freight_cost_per_parcel` ist und die €-Kennzahl sich nicht still mitverschieben
   soll (die Kostenfunktion wird separat überarbeitet → BACKLOG `[H]`; nach der Umstellung
   fällt sie von 2,06 auf ~1,92 €/Paket).
   **Mit-behobener Geschwisterdefekt:** `extract_freight_provider.py` rechnete `delivery_rate`
   ebenfalls netto — eine Provider-Tabelle auf anderer Basis als ihre eigene Headline. Lehre
   (vgl. [[feedback-test-discrimination]]): wer eine Quote auf falscher Basis findet, sucht im
   selben Zweig nach der zweiten.
   **Was sich dadurch an den Zahlen ändert — das Vorzeichen der Kernaussage.** Wie bisher
   exportiert las die Reihe *Baseline 93,6 % (netto) · 1c 93,7 % · 1d 97,9 %*, also
   „integriert ≥ Baseline". Auf einer Konvention lautet sie **Baseline ~100 % · 1d 97,9 % ·
   1c 93,7 %**, also 1d −2,1 pp und 1c −6,3 pp gegen die Baseline. Für 1d ist zusätzlich
   nachgerechnet, dass `parcels_served` wirklich brutto ist: auf `m1d010` beträgt die Fehlmenge
   126 Pakete, das ausgewiesene Overlay 393 — es kann darin also nicht enthalten sein.
   Der Limitations-Satz fürs Paper: *Rücktransport und Ersatzzustellung sind nicht modelliert;
   die Baseline-Quote ist eine Aussage über jsprit-Machbarkeit, nicht über Empfängerverhalten.*
   §2.5-Quarantäne (nur Deltas berichten) unverändert.
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

### 2.24 1c Co-Riding: marginale Emissionszurechnung ist nicht ohne Rerun erreichbar

`trägt` · 2026-07-31. Die naheliegende Zurechnung für 1c — „die von der Parcel-Insertion
verursachten Umwege gehören der Fracht, der Rest den Fahrgästen" — setzt Kenntnis der geplanten
Tour **vor** der Insertion voraus. Diese Größe **wird in 1c berechnet**, ist aber nicht
abrufbar. Drei Befunde:

1. **Die Größe existiert und ist genau die richtige.**
   [`ChiGateInsertionCostCalculator.calculate()`](../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/shareduse/ChiGateInsertionCostCalculator.java)
   bildet `detourOnly = max(0, detourTimeInfo.getTotalTimeLoss() − ownDwellSeconds(request))` —
   also den marginalen, um die Eigenbedienzeit bereinigten Umweg der Parcel-Insertion. Das ist
   das χ-Gate-Kriterium selbst.
   ⚠️ **Formel überholt 2026-08-13:** genau diese Summenform war fehlerhaft (ein mitgenommenes
   Bein bezahlte echten Umweg auf dem anderen); jetzt je Bein gegen dessen eigene, auf den
   Fahrplanboden gehobene Standzeit → §2.35. Die *Aussage* dieses Punktes bleibt: die Größe
   existiert im Gate und ist die richtige — sie ist es jetzt sogar genauer.
2. **Nur die *blockierten* Insertions werden protokolliert.** `ChiGateStats` führt
   `blockedAttempts` und `blockedSegments`; für eine **angenommene** Insertion wird `detourOnly`
   berechnet, gegen χ geprüft und dann verworfen. Genau die Fälle, die man für die Zurechnung
   bräuchte, hinterlassen keine Spur.
3. **Die Distanz existiert, ist aber nicht durchgereicht** (korrigiert 2026-07-31 gegen
   `drt/dvrp-2025.0-PR3552-sources.jar`). `OneToManyPathSearch.PathData` hält den vollständigen
   Link-Pfad (`ImmutableList.copyOf(path.links)`, ggf. lazy über `pathSupplier`) — die marginale
   **Distanz** wäre also durch Summieren von `link.getLength()` verfügbar, **ohne neues Routing**.
   Sie kommt nur nicht am Gate an: `InsertionCostCalculator.calculate(DrtRequest, Insertion,
   DetourTimeInfo)` bekommt lediglich die Waypoint-Links und die Zeitverluste; die `PathData`
   liegen eine Ebene höher in `InsertionWithDetourData.InsertionDetourData` und werden nicht
   weitergegeben. **Wichtig:** die Zeit lässt sich *nicht* per km ÷ v in Distanz zurückrechnen —
   die Reisezeiten kommen aus dem Dijkstra-Baum mit link-individuellen `TravelTime`-Werten, eine
   aggregierte Durchschnittsgeschwindigkeit würde das verschmieren. Der Weg ist die Link-Summe,
   nicht die Division.
4. **`calculate()` läuft für jede evaluierte Kandidaten-Insertion**, nicht nur für die gewählte;
   ein naives Logging erzeugt Dutzende Werte pro Request ohne Kennzeichnung des Gewinners. Man
   müsste am Commit-Punkt der Insertion ansetzen, nicht am Bewertungspunkt.

**Aufwand also: Java-Eingriff in den DRT-Insertion-Pfad (PathData bis zum Gate durchreichen bzw.
am Commit-Punkt hooken) + kompletter 1c-Rerun.** Und selbst dann
bleibt ein methodisches Problem, das kein Logging löst: **marginale Kosten sind
reihenfolgeabhängig und summieren sich nicht zum Ganzen.** Der Grenzumweg von Paket B hängt davon
ab, dass Paket A schon eingeplant ist; Σ marginal(Fracht) + Σ marginal(Pax) ≠ Gesamt-km. Der
Rest ist der Verbundvorteil des gemeinsamen Routings — also genau die Größe, die das Paper
messen will. Sie per Konstruktion einer Seite zuzuschlagen wäre zirkulär.

**Vorbemerkung zum Bedarf:** die Kernaussage des Papers braucht **gar keine Zurechnung**.
„Integration spart X % CO₂e" ist ein Systemvergleich — 1c-Gesamt gegen (Baseline-Pax-DRT +
Baseline-LMD-Fracht). Eine Aufteilung braucht man erst für *spezifische* Intensitäten (kg CO₂e je
Paket bzw. je Fahrgast). Die folgenden Optionen sind nach Aufwand geordnet:

**D — Allokationsfrei plus Intervall (Untergrenze, immer verfügbar, kein Rerun).**
`total_*` berichten, dazu die beiden Extremzurechnungen (Verbund-km vollständig Pax bzw.
vollständig Fracht) als Bandbreite. Null Annahmen, dafür weit. Zusätzlich als belegte Schranke:
χ ist der policy-seitige Deckel des Umwegs pro Insertion, also frachtverursachter Zeitumweg
≤ (angenommene Parcel-Segmente) × χ. **NEEDS-CHECK:** ob die Zahl angenommener Segmente in den
`SharedUseKpiHandler`-KPIs steht.

**A — Verbunddifferenz gegen einen Pax-only-Zwillingslauf — VERWORFEN, siehe §3.9.**
Nicht aus Kostengründen (das Kontrafaktum hängt nicht am Frachtparameter, `ctrl1d` bzw. der
χ<0-Pfad decken je einen ganzen Sweep ab, Zusatzkosten null), sondern weil die Differenz im
Rauschen verschwindet und das falsche Vorzeichen trägt.

**B — Shapley-Aufteilung mit zwei Spielern — VERWORFEN, siehe §3.9.**
Setzt feste Spieler voraus. Die Pax-Seite ist bei 150 Iterationen mit Modenwahl endogen und
schrumpft zwischen `v(pax)` und `v(beide)` um 15 % — dann ist „Pax" nicht derselbe Spieler und
die Formel rechnet, ohne etwas zu bedeuten.

**C — Physikalische Anteilszurechnung (kein Rerun, aber willkürliche Basis).**
Emissionen je Link nach Masse bzw. Belegungsslots an Bord aufteilen. Summiert konstruktionsgemäß
auf, braucht kein Kontrafaktum, und ist **direkt gemessen** — also rauschunempfindlich. Preis: das
Ergebnis hängt von der gewählten Basis ab (Masse? Slots? Sitze?). Als Aufteilung brauchbar, wenn
die Basis offen deklariert wird; als alleinstehende Kernzahl schwach.

**M — Marginale Insertion mit Java-Hook (teuer, aber methodisch solide).**
Wie oben beschrieben: `PathData` bis zum Gate durchreichen bzw. am Commit-Punkt hooken, Link-Längen
summieren, 1c-Rerun. Relativ zu A/B ist diese Variante **besser gestellt, nicht schlechter**: sie
misst pro Insertion *innerhalb eines Laufs*, bildet also keine Differenz zweier Läufe und erbt
keinen Replanning-Drift. Es bleibt der nicht-summierende Verbundrest.

**Entschieden (User, 2026-07-31): D als Boden + C mit Masse als Basis.** Spezifische Intensitäten
(CO₂e je Paket / je Fahrgast) sind als potenziell wertvoll eingestuft, also wird C implementiert,
nicht nur vorgehalten. M bleibt vorgehalten für den Fall, dass eine echte Marginalaussage verlangt
wird. A und B nicht verwenden (§3.9). Leitsatz: **direkt gemessene Größen schlagen Differenzen
zweier Läufe.** Caveats der Massenbasis: §2.26.

Verwandt: §2.3 („χ ist eine untere Schranke, nicht der Umweg"), §2.4 (was bei Pax-KPIs unter
Co-Riding unkorrigierbar bleibt), §2.17 (gepaarte vs. ungepaarte Vergleiche), §2.25
(Pax-Verdrängung), §3.9 (Verwerfung von A/B).

### 2.25 1d Modular: die Fracht verdrängt Pax-Bedienung, sie addiert nicht nur Fahrleistung

`trägt` · 2026-07-31. Gemessen an `drt_vehicle_stats_drt.csv`, Iteration 150, alle Läufe 120
Fahrzeuge:

| Run | Pakete zugestellt | DRT-Gesamtdistanz | bediente Pax-Distanz | Leerfahrtquote | d_p/d_t | l_det |
|---|---|---|---|---|---|---|
| `ctrl1d` | 0 | 48.824 km | 99.353 km | 0,12 | 2,03 | 0,71 |
| `m1d010` | 5.894 | 47.089 km | **84.065 km (−15,4 %)** | **0,22** | 1,79 | 0,80 |
| `m1d020` | 2.261 | 48.697 km | 93.496 km (−5,9 %) | 0,16 | 1,92 | 0,75 |
| `m1d030` | 551 | 48.773 km | 97.786 km (−1,6 %) | 0,14 | 2,00 | 0,72 |
| `m1d050` | 1 | 47.160 km | 97.949 km (−1,4 %) | 0,11 | 2,08 | 0,69 |

**Der Befund:** bei vollem Frachtbetrieb (`m1d010`, 98 % Zustellquote) bedient dieselbe Flotte
**15,4 % weniger Pax-Distanz**, die Leerfahrtquote steigt von 0,12 auf 0,22, und die
Pax-Distanz je Fahrzeug-km (`d_p/d_t`) fällt von 2,03 auf 1,79. Die Fracht hat Pax also teilweise
**verdrängt**, statt Fahrleistung zu addieren. Der Effekt ist monoton in der Frachtmenge
(−1,4 / −1,6 / −5,9 / −15,4 %) und liegt bei `m1d010` und `m1d020` klar außerhalb des Rauschbands,
das `m1d050` mit einem einzigen zugestellten Paket auf ~±1,5 % abgrenzt.

**Warum das entsteht:** über 150 Iterationen mit Modenwahl ist die Pax-Nachfrage endogen. Sind die
Fahrzeuge in Frachtfenstern gebunden, verschlechtert sich das Pax-Angebot, Agenten wandern ab — der
Endzustand ist ein anderes Gleichgewicht, nicht dieselbe Pax-Nachfrage mit Fracht obendrauf.

**Annotation 2026-07-31 — der Regimesplit macht die Verdrängung erst sichtbar, und sie ist viermal
so groß wie die Gesamtdistanz suggeriert.** Mit dem implementierten Fensterschnitt (Plan Task 5b/6)
lässt sich die DRT-Gesamtdistanz jetzt in **Fracht-km und Pax-bedienende km** zerlegen:

| Run | Gesamt (`drt_vehicle_stats`) | Fracht (Fensterschnitt) | **Pax-bedienende Fzg-km** | Gesamt vs. ctrl1d | **Pax-km vs. ctrl1d** |
|---|---|---|---|---|---|
| `ctrl1d` | 48.824 | 0 | 48.824 | — | — |
| `m1d010` | 47.089 | 5.616,5 | **41.473** | −3,6 % | **−15,1 %** |
| `m1d020` | 48.697 | 2.517,7 | 46.179 | −0,3 % | −5,4 % |
| `m1d030` | 48.773 | 704,9 | 48.068 | −0,1 % | −1,6 % |
| `m1d050` | 47.160 | 13,5 | 47.146 | −3,4 % | −3,4 % |

**Die naive Lesart der Fahrzeug-km unterschätzt die Verdrängung um mehr als das Vierfache**
(−3,6 % gegen −15,1 %), weil die Gesamtdistanz die Fracht-km *enthält*: der Pax-Rückgang wird vom
Fracht-Zuschlag kaschiert. Das ist genau der Kanal, den §2.14 als „not corrected" führt — er ist
mit dem Fensterschnitt geschlossen. Bemerkenswert: die Verdrängung auf der **Fahrzeug**-km-Seite
(−15,1 %) und auf der **bedienten Pax-Distanz** (−15,4 %, Tabelle oben) stimmen fast überein, die
Flotte fährt also nicht ineffizienter für weniger Leistung, sondern schlicht weniger Pax-Betrieb.

**Einschränkung, die die Zerlegung ebenfalls sichtbar macht:** die Monotonie gilt nur oberhalb des
Rauschbands. `m1d050` liegt mit **einem** zugestellten Paket bei −3,4 %, also *tiefer* als `m1d030`
mit 22 Touren (−1,6 %). Unterhalb von `m1d020` ist das Signal von der Replanning-Streuung
(~±1,5–3 %) nicht zu trennen; belastbar sind `m1d010` und `m1d020`.

**Konsequenzen:**
1. **Eigener Kostenposten der Integration**, der berichtet werden muss, unabhängig von jeder
   Emissionszurechnung. „Integration spart CO₂" wäre unvollständig, wenn dabei 15 % weniger
   Fahrgäste bedient werden — das ist keine Emissionsersparnis, sondern teils ein Leistungsabbau.
2. **Die DRT-Gesamtdistanz ist als Vergleichsgröße zwischen den Läufen unbrauchbar** (§3.9): sie
   sinkt mit steigender Frachtmenge, weil der Pax-Rückgang den Fracht-Zuschlag überkompensiert —
   und sie *verschleiert* zusätzlich die Verdrängung, s. Annotation oben. Zu berichten ist die
   zerlegte Größe, nicht die Summe.
3. **Der Vergleich 1d ↔ Baseline braucht eine Leistungsnormierung.** Ungleiche bediente
   Pax-Distanz *und* ungleiche Zustellquote gleichzeitig — verwandt mit dem Netto-vs-Brutto- und
   Sitzplatz-Confound in §2.21, aber ein zusätzlicher Kanal.
4. **NEEDS-CHECK:** wie viel des Pax-Rückgangs ist Abwanderung (Modenwahl) und wie viel
   Ablehnung (`rejections`)? `drt_customer_stats` je Lauf gegenrechnen — die Unterscheidung
   ändert die Interpretation erheblich.

### 2.26 Massenbasierte Emissionszurechnung: was daran Modelldaten sind und was Setzung

`trägt` · 2026-07-31, zur Entscheidung in §1.4 (Option C). Zwei Befunde, die beim Verifizieren der
Datenlage aufgefallen sind und die Aussagekraft der Aufteilung begrenzen:

**1. Die Paketmasse steht NICHT im Lausitz-Modell.**
Es gibt einen Pfad dafür — `enrich_vehicle_spatial()` in
[`hagrid_output_analysis/analysis.py:412-418`](../parcel-demand-2-matsim-pipeline/src/hagrid_output_analysis/analysis.py#L412-L418)
liest ein `weights`-Attribut je Carrier-Service und leitet daraus `total_weight`,
`avg_weight_per_parcel`, `emissions_per_kg` ab. Dieses Attribut wird von den Lausitz-Carriern aber
**nicht geschrieben**: in `..._lmd_carriers_routed.xml` gibt es **0 Treffer** für `name="weights"`.
Verfügbar ist nur `capacityDemand`, also die Paket-**Anzahl** (Verteilung im geprüften Lauf:
2070 × 1, 489 × 2, 209 × 3, 119 × 4, 71 × 5 …). Der `weights`-Pfad ist Hannover-Erbe
(Notebook-Port), nicht Lausitz-Funktionalität.

**Konsequenz:** die Paketmasse ist `Anzahl × Mittelmasse aus Literatur`. Damit hängt die Aufteilung
an **zwei Konstanten**:
  Paket-Anteil = (n_Pakete · kg_Paket) / (n_Pakete · kg_Paket + n_Pax · kg_Pax)

**Paketmasse = 1,65 kg, festgezurrte Annahme (User-Entscheidung 2026-07-31).** Ein einziger Wert,
absichtlich gerundet — die Rundung signalisiert Annahme, nicht Messung. Drei Quellen stützen
dieselbe Größenordnung:

| Quelle | Kontext | Aussage |
|---|---|---|
| **Amaral et al. (2026):** *Empirical analysis of e-commerce delivery operations: from parcels to tours.* Transportation Research Part E, Tab. 1 | Brasilien, E-Commerce | Mittel **1,6478 kg** (Haushalte 1,5922 / Gewerbe 1,8160); Median 0,6950; Std.abw. 3,0797 |
| **Rajendran & Harper (2021):** *Simulation-based algorithm for determining best package delivery alternatives under three criteria: Time, cost and sustainability.* Transportation Research Interdisciplinary Perspectives (TRIP) | USA, Paketzustellung | 1–350 lbs = 0,454–158,757 kg; >50 % unter 5 lbs (2,268 kg); **kein Mittelwert angegeben** |
| **Mohri, Nassir, Lavieri & Thompson (2024):** *Modeling package delivery acceptance in Crowdshipping systems by Public Transportation Passengers: A latent class approach.* Travel Behaviour and Society **35**, 100716 | Crowdshipping/ÖPNV | Paketmassen **0,5–5 kg** |

Amaral liefert den einzigen gemessenen Mittelwert und damit den Punktwert; die beiden anderen
bestätigen die Größenordnung. **Kein Kanal-Split** (B2C/B2B): der Kontrast beträgt 3,2 Pp am
Aufteilungsanteil und liegt damit innerhalb der Unsicherheit der Annahme selbst — differenzieren
wäre Scheingenauigkeit. Eine Konstante, `kg_per_parcel = 1.65`.

**Zurückgezogen: mein Oberbracket von ~5,4 kg.** Es entstand als Rajendrans Median-*Obergrenze*
(2,268) × Amarals Schiefeverhältnis (2,37), also als Schranke auf eine Schranke. Mohri et al.s Spanne
0,5–5 kg **schließt es aus** — ein Mittel von 5,4 kg läge über dem berichteten Maximum. Damit fällt
auch die daraus gezogene Warnung („29 Pp Spanne über Kontexte"): das plausible Band auf den
Mittelwert ist **1,3–2,5 kg**, am Aufteilungsanteil ~16 Pp bei 50 Paketen. Material, aber kein
Regimewechsel.

**Mittelwert, nicht Median — das bleibt die eigentliche Falle.** Gebraucht wird die Gesamtmasse an
Bord, und `Σ Gewichte = n × Mittelwert`; der Mittelwert ist dafür der unverzerrte Schätzer. Die
Verteilung ist stark rechtsschief (Amaral: Median 0,6950 = 42 % des Mittels), die Schiefe schlägt
aber nur auf die Varianz der Schätzung durch, nicht auf den Erwartungswert. Wer hier
„robustheitshalber" zum Median greift, unterschätzt die Paketmasse um 58 %. Ebenso: **Q1/Q3 aus
Amarals Tab. 1 sind kein Sensitivitätsband für den Mittelwert** — das ist die Streuung einzelner
Sendungen.

**Transfer-Vorbehalt, im Paper zu benennen:** keine der drei Quellen ist deutsch (Amaral
Brasilien/BRL, Rajendran USA). Als deklarierter Transfer führen, nicht als deutsche Kennzahl. Eine
deutsche KEP-Referenz würde den Punkt erledigen.

**Empfindlichkeit** (bei 1,6 Fahrgästen an Bord = 128 kg, gemessen als `mean_pax_aboard_pax`):

| Pakete an Bord | 1,30 kg | **1,65 kg** | 2,50 kg |
|---|---|---|---|
| 10 | 9,2 % | **11,4 %** | 16,3 % |
| 20 | 16,9 % | **20,5 %** | 28,1 % |
| 50 | 33,7 % | **39,2 %** | 49,4 % |
| 99 (`max_parcels_per_tour`) | 50,1 % | **56,1 %** | 65,9 % |

Der Frachtanteil wird primär von der **Beladung** getrieben (10 → 99 Pakete: ~45 Pp) und sekundär
von der Massenannahme (~16 Pp im Band). Bei der Annahme kippt die Mehrheit jenseits von ~78 Paketen
zur Fracht. **Berichtsregel:** `alloc_share_parcels_mass` immer neben den spezifischen Intensitäten
ausweisen — nie eine kg-CO₂e-je-Paket-Zahl ohne den Anteil, aus dem sie kommt.

**`kg_per_passenger` = 80 kg ist eine Setzung**, keine Quelle (gängige Straßenverkehrskonvention,
ohne Gepäck). Da nur das **Verhältnis** kg_Paket/kg_Pax die Aufteilung bestimmt, wiegt sie genauso
schwer wie die belegte Paketmasse — beide zusammen ausweisen.

**Alle Aufteilungskonstanten sind reine Post-Processing-Größen.** `kg_per_parcel`,
`kg_per_passenger` und `slots_per_seat_equiv` gehen weder in die Simulation noch in den
Emissionsfaktor (EMEP hat für LCV keine Lastdimension, §1.4) noch in die Fahrleistung ein. Eine
Änderung — etwa `kg_per_passenger` von 80 auf 85 — ist ein Edit in `emep_supplement.csv` plus
`build_kpis.py`-Neulauf auf vorhandenem Output, Minuten statt Stunden, **kein Sim-Rerun**. `total_*`
bleibt unverändert; es bewegen sich nur die Anteile und die spezifischen Intensitäten. Die
Sensitivitätsanalyse ist damit billig — es gibt keinen Grund, sie nicht als Band zu berichten.

**GEMESSEN 2026-07-31 — die Basiswahl dominiert alles andere um Größenordnungen.** Erste
Realdatenrechnung am 1c-Lauf `chid600w21` (Code-Validierung; die Zahlen selbst sind paperseitig
ungültig, da vor der Lieferfenster-Vereinheitlichung). Zurechnung exakt aufgehend, Residuum
+0,000 kg von 12.726,7 kg CO₂e-WTW:

| Basis | Frachtanteil | CO₂e je Paket | CO₂e je Pax-Fahrt |
|---|---|---|---|
| **Masse** (kg·km) | **0,90 %** | **0,0202 kg** | 1,722 kg |
| **Slots** (Kapazität) | **23,64 %** | **0,5305 kg** | 1,327 kg |

**Faktor 26 auf der Zahl, die wir berichten wollen.** Das ist keine Rundungsfrage, sondern der
dominierende Unsicherheitsbeitrag — verglichen mit den ~16 Pp Band auf `kg_per_parcel` (oben) ist er
um mehr als eine Größenordnung größer. Ursache ist strukturell und folgt direkt aus den Konstanten:
**ein Paket ist massenseitig 1/48,5 eines Fahrgasts (1,65 vs. 80 kg), kapazitätsseitig aber 1/2,5**
(1 Paketslot vs. 2,5 Slots/Sitz) — Faktor **19,4** in der Gewichtung je Einheit. Der *realisierte*
Anteil hängt zusätzlich vom Beladungsmix des Laufs ab, Richtung und Größenordnung nicht.

**Plausibilitätssignal, das gegen die Massenbasis spricht:** 0,0202 kg = **20 g CO₂e je Paket** liegt
weit unter allem, was Last-Mile-Literatur berichtet (Größenordnung 100–1000 g); die Slot-Basis mit
**531 g** liegt darin. Der Grund ist inhaltlich: bei einem *Personen*fahrzeug ist die Masse eines
Fahrgasts (80 kg) ein schlechter Proxy für seinen Anteil an der Fahrleistung — das Fahrzeug fährt
seiner **Kapazitätsbindung** wegen, nicht seines Gewichts wegen. Die kg·km-Konvention aus EN 16258 /
GLEC ist für **Güterfahrzeuge** entwickelt, wo alle Nutzlast Fracht ist; in einem Mischfahrzeug
verliert ihre Begründung an Kraft.

**Interner Benchmark — und eine externe Validierung der Faktorkette.** Mit denselben Faktoren
gerechnet liefert der *konventionelle* Baseline-Lauf (`bandz_central`, 5627 zugestellte Pakete,
1243,8 kg CO₂e-WTW) **221 g CO₂e/Paket** (Diesel) bzw. **77 g** in der BEV-Variante. Bienzeisler et
al. (2026) berichten für **ländliche** Kategorien **188 g** (ohne) und **239 g** (mit
Industrieeinfluss) bei einem Netzmittel von 136 g — die Lausitz ist ländlich, unsere 221 g liegen
**genau in diesem Band**. Das ist eine unabhängige externe Bestätigung der Kette
Tier-3-Kurve × N1-Segment × WTW und gehört als solche ins Paper.

Damit lässt sich die Basiswahl gegen einen *eigenen* Maßstab stellen:

| Basis | Frachtanteil | g CO₂e/Paket | vs. eigene konventionelle Baseline (221 g) |
|---|---|---|---|
| Masse | 0,90 % | 20 | **0,09×** — Integration senkt um 91 % |
| Slots | 23,64 % | 531 | **2,40×** — Integration *verdreifacht* |
| — | **9,85 %** | 221 | Parität (rechnerischer Break-even-Anteil) |

**Die beiden Basen drehen also das Vorzeichen der Kernaussage.** Damit ist die Intensität je Paket
in 1c **keine Messung, sondern eine Umformulierung der Konventionswahl** — sie darf nicht als
Ergebnis geführt werden. Der Paritätsanteil **9,85 %** ist die informativere Größe: er sagt, wo die
Break-even-Konvention liegt, ohne eine zu behaupten.

**Warum die Massenbasis instabil ist — und warum das die Szenarienvergleichbarkeit trifft**
(User-Einwand 2026-07-31, nachgerechnet): der Anteil hängt von der **Zahl der Mitfahrenden** ab, also
von einer für das Paket exogenen Größe.

| Beladung | Frachtanteil Masse | Frachtanteil Slots |
|---|---|---|
| 0 Pax / 20 Pakete | 1,000 | 1,000 |
| 2 Pax / 20 Pakete | 0,171 | 0,800 |
| 8 Pax / 20 Pakete | 0,049 | 0,500 |
| 8 Pax / 5 Pakete | 0,013 | 0,200 |

Über die Spanne 0 Pax/20 Pakete → 8 Pax/5 Pakete schwankt die Massenbasis um **Faktor 78,6**, die
Slot-Basis um **5,0** — die Masse verstärkt die Mitfahrer-Abhängigkeit also **15,7-fach**. Das ist
genau die Dimension, die zwischen unseren Szenarien **variiert** (§2.25: −15 % Pax-Bedienung bei
vollem Frachtbetrieb), womit die Massenbasis die Intensität je Paket über Szenarien hinweg
**unvergleichbar** macht. *Innerhalb* eines Laufs ist die Streuung dagegen unkritisch (je Fahrzeug
Q3/Q1: Masse 1,7 / Slots 1,6; VarKoeff 0,55 / 0,45) — alle 120 Fahrzeuge haben ähnliche Mischungen.
Das Problem ist der Szenarienvergleich, nicht die Fahrzeugheterogenität.

**Die eigentliche Auflösung: die zwei Basen beantworten zwei verschiedene Fragen.**
- **Massenbasis ≈ marginale Frage** („was kostet es, Pakete auf eine ohnehin fahrende DRT zu
  legen?"). In 1c ist die Antwort **konstruktiv nahe null**: das χ-Gate lässt eine Einfügung nur zu,
  wenn ihr Umweg die eigene Standzeit nicht übersteigt (`ChiGateInsertionCostCalculator`), Pakete
  verursachen also fast keine Zusatzstrecke. Die 20 g sind damit **keine Fehlrechnung**, sondern eine
  zufällig gute Näherung der marginalen Antwort — die Konstanten treffen sie, ohne sie herzuleiten.
- **Slot-Basis ≈ Fair-Share-Frage** („welcher Anteil der Systemlast gehört buchhalterisch zum
  Paket?"). Sie bindet den Anteil an die **Kapazitätsbindung**, also an das, was das Fahrzeug
  tatsächlich fahren lässt — und trägt keine externe Massenannahme und keinen Datentransfer.

**Keine der Basen verteilt Leerfahrten nach Verursachung.** Das ist der berechtigte Kern des
User-Einwands: in 1d ist das gelöst (Regimesplit nach Task-Fenstern, §1.4), in 1c nicht. Eine
verursachungsgerechte Zurechnung gäbe Paketen in 1c wegen des χ-Gates ≈0 — sie fiele also mit der
marginalen Antwort zusammen, nicht mit der Fair-Share-Antwort.

**Empfehlung fürs Paper (Entscheidung offen, User):**
1. `total_*` allokationsfrei führen — unverändert der Boden.
2. **Keine** einzelne Zahl „g CO₂e je Paket" für 1c. Stattdessen die **Bandbreite mit beiden
   benannten Basen** plus den Paritätsanteil 9,85 %.
3. Die marginale Aussage **qualitativ** und mit dem χ-Gate begründen statt sie zu bepreisen: „im
   Shared-Use-Regime fahren Pakete konstruktionsbedingt nahezu umwegfrei mit".
4. Falls innerhalb der Bandbreite geführt werden muss, **Slot-Basis zuerst** — szenariodefiniert,
   annahmenfrei, 15,7-fach stabiler gegen die Größe, die zwischen den Szenarien variiert; Masse als
   Sensitivität daneben.

Umgesetzt ist beides, beide Anteile werden immer als Paar emittiert
(`alloc_share_parcels_mass` / `_slots`).

**Kontamination quantifiziert (dieselbe Messung):** von 258.780 Link-Einträgen tragen **20,4 %
Pakete** an Bord, **9,9 % beides gleichzeitig** (Pax *und* Paket — genau das Pooling, um das es in
1c geht). Der gemischte `occ`-Zähler betrifft also ein Fünftel der Fahrleistung, nicht einen
Randfall. Siehe §2.28 zur Frage, ob die bestehenden 1c-KPIs das ausweisen.

**Alternative Basis als Pflicht-Begleitung:** **Kapazitätsanteile** statt Masse. In 1c hat das
Fahrzeug 8 Sitze und 20 Paketslots (§2.21), die Äquivalenz ist also **szenariodefiniert** statt
gesetzt und hängt an keiner externen Massenannahme. Neben der Massenvariante berichten; beide
summieren konstruktionsgemäß auf und unterscheiden sich nur in der Gewichtung.

**2. Die Belegungsrekonstruktion vermischt in 1c Pakete und Fahrgäste.**
[`geometry.reconstruct_drt_paths`](../parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py#L53-L87)
zählt `occ` als einfachen Zähler über `PersonEntersVehicle`/`PersonLeavesVehicle` für alle
`drt_`-Fahrzeuge außer dem Fahrer. In 1c sind Pakete als **Parcel-Personen** modelliert
(`SharedUse.PARCEL_PERSON_PREFIX = "parcel_"`), erzeugen also dieselben Events. Der `occ`-Wert je
Link ist in 1c damit **Fahrgäste + Pakete gemischt**.

Das ist zweierlei: (a) ein **Vorbefund für die bestehenden 1c-Belegungs-KPIs** — alles, was auf
`occ` aufbaut (Occupancy-Karte, `occ_km`, `occ_segments`, `occ_time`), ist im 1c-Arm entsprechend
kontaminiert, analog zu §2.4 auf der Zeitseite; (b) genau der Hebel, den C braucht: die Trennung
ist trivial, weil die Person-ID sie trägt. Zwei Zähler statt einem, Fallunterscheidung auf
`person_id.startswith("parcel_")`. **Vor der Implementierung prüfen**, ob die 1c-Occupancy-KPIs
diese Kontamination schon irgendwo ausweisen — falls nicht, ist es ein eigener Befund und gehört
in die Limitations des 1c-Arms, nicht nur in die Emissionsrechnung.

### 2.27 Non-Exhaust (Abrieb): segmentauflösend, dominierend — und von der Quelle selbst als überschätzt gekennzeichnet

`trägt` · 2026-07-31 · Quelle: EMEP/EEA Guidebook 2023 – Update 2025, **Kap. 1.A.3.b.vi–vii**
„Road tyre and brake wear / Road abrasion" (38 S., nachgeladen 2026-07-31; vorher lag das Kapitel
nicht vor, s. `hagrid-input/emissions/SOURCES.md`). Implementiert in
[`emissions_emep.non_exhaust_pm10`](../parcel-demand-2-matsim-pipeline/analysis/kpi/emissions_emep.py#L89).

**Was Non-Exhaust ist:** Partikelemissionen, die nicht aus dem Auspuff stammen, sondern aus
**Abrieb** — Reifen, Bremse, Straßendecke. Reiner Feinstaub (PM10), kein CO₂; betrifft also die
Luftschadstoffseite des Papers, nicht die CO₂e-Kernaussage.

**Befund 1 — es dominiert die PM-Bilanz um Größenordnungen.** Der Auspuff-PM eines Euro-7-Diesels
mit DPF ist praktisch verschwunden (0,142 mg/km, in allen drei Segmenten identisch, weil der DPF
die Segmentwirkung wegfiltert). Der Abrieb liegt bei N1-III/30 km/h bei **59,1 mg PM10/km**, also
**Faktor ~400 darüber**. Eine PM-Aussage, die nur den Auspuff rechnet, berichtet fast Null und
verfehlt die reale Belastung vollständig. Deshalb ist Non-Exhaust kein Nebenbaustein, sondern *die*
PM-Aussage des Papers.

**Befund 2 — Elektrifizierung halbiert den Feinstaub nicht.** BEV hat null Auspuffemission, reibt
aber Reifen und Straße **stärker** ab (höhere Fahrzeugmasse), nur die Bremse deutlich schwächer
(Rekuperation). Guidebook-interne ICE→BEV-Verhältnisse des Medium-Pkw: Reifen 1,0841, Bremse
0,2113, Straße 1,1267. Netto bleiben bei N1-III **34,4 von 59,1 mg PM10/km = 58 %** übrig — die
Elektrifizierung senkt den Feinstaub also um gut 40 %, nicht auf Null. Das ist eine
berichtenswerte Gegenaussage zur „Zero-Emission"-Rhetorik und gehört ins Ergebniskapitel, nicht in
die Limitations.

**Befund 3 — die Geschwindigkeitsabhängigkeit läuft der Auspuffseite entgegen.** Abrieb ist im
langsamen Verkehr **hoch** (häufiges Bremsen und Lenken) und auf der Autobahn niedrig, umgekehrt
zur Auspuffkurve. Guidebook-eigene Korrekturen auf die **mittlere Trip-Geschwindigkeit**: Gl. (5)
Reifen 1,39 unter 40 km/h, Gl. (8) Bremse 1,67 unter 40 km/h; Straßenabrieb ist
geschwindigkeitsunabhängig (Gl. 9). Unsere Touren fahren bei ~30–36 km/h, also **in beiden
Plateaus der Höchstwerte**. Das ist kein Zufallseffekt der Parametrisierung, sondern die
Kernaussage des Kapitels für Stadt-/Zustellverkehr.

**Befund 4 — Segmentauflösung, entgegen der ursprünglichen Annahme** (§2.7 retrahiert). TSP-Basen
[g/km]:

| Abriebquelle | N1-I | N1-II | N1-III | Tabelle | PM10-Anteil |
|---|---|---|---|---|---|
| Reifen | 0,0107 | 0,0169 | 0,0169 | 3-4 (II+III gruppiert) | 0,600 (Tab. 3-5) |
| Bremse | 0,0117 | **0,0155** | **0,0211** | 3-6 (**alle drei getrennt**) | 0,980 (Tab. 3-7) |
| Straße | 0,0150 | 0,0210 | 0,0210 | 3-8 (II+III gruppiert) | 0,50 (Tab. 3-9) |

Innerhalb unserer Flotte wirkt das real: N1-II→N1-III hebt den Bremsabrieb um **36 %**. Die
Gruppierung der Quelle ist als Daten ausgeschrieben (identische Werte für ii/iii in
`emep_supplement.csv`), nicht in Code-Verzweigungen versteckt — sonst wäre nicht mehr erkennbar,
was Quellenstruktur und was unsere Interpretation ist.

**Einschränkung, die methodentreu ist und ins Methods-/Limitations-Kapitel gehört:** die Quelle
kennzeichnet ihre eigenen Reifen-PM-Werte als **überschätzt**. Wortlaut (Kap. 1.A.3.b.vi, zu
Tab. 3-4): *„Recent measurements demonstrated that the existing PM10 and PM2.5 in this table are
overestimated since the actual ratio of PM10 to total tyre wear is more recently found to be well
below 3 % (Saladin et al., 2024; Huber et al., 2024; Giechaskiel et al. 2024a). Therefore, the
large majority of tyre wear is deposited on the ground and does not become airborne. Current
evidence does not allow a revision of the values in Table 3-4."*

Konsequenz und **Entscheidung: wir rechnen trotzdem mit 0,600.** Begründung — der angesetzte
PM10/TSP-Anteil von 0,600 ist gegenüber „deutlich unter 3 %" um mehr als eine Größenordnung zu
hoch, aber die Quelle stellt den revidierten Wert **nicht bereit**; ihn selbst zu setzen hieße, das
Guidebook zu verlassen und eine Zahl aus drei Primärstudien zu synthetisieren, die wir nicht
geprüft haben. Methodentreue schlägt hier Punktgenauigkeit: wir übernehmen den dokumentierten Wert
**samt seinem dokumentierten Vorbehalt**. Für das Paper heißt das:
- Der Reifen-PM10-Beitrag (14,1 mg/km bei N1-III/30 km/h, **24 % des Abriebs**) ist eine
  **Obergrenze**, nicht eine Schätzung. Bei 3 % statt 60 % wäre er ~0,7 mg/km, der Gesamtabrieb
  fiele von 59,1 auf ~45,7 mg/km (−23 %).
- Diese Spanne ist als Sensitivität zu berichten, nicht als Fehler zu verschweigen. Sie ist
  **einseitig**: der wahre Wert liegt unter dem berichteten, nie darüber.
- Die **Richtungsaussagen bleiben davon unberührt**, weil der Vorbehalt Diesel und BEV gleich
  trifft: der BEV-Vorteil beim Bremsabrieb und der BEV-Nachteil beim Reifenabrieb verschieben sich
  proportional. Befund 2 (58 % bleiben) würde bei revidiertem Reifenanteil sogar *günstiger* für
  den BEV, da sein Nachteil genau am überschätzten Term hängt.

**Zweiter Quellenvorbehalt:** der Straßenabrieb trägt Qualitätscode **C–D** und wird von der Quelle
selbst als *„based on limited information and highly uncertain"* bezeichnet — mit 10,5 mg/km immerhin
**18 % des Abriebs**. Reifen- und Bremsbasen tragen Code B („non-statistically significant based on a
small set of measured re-evaluated data"). Es gibt in diesem Kapitel also **keinen** Wert mit
Qualitätscode A; das ist der Rahmen, in dem alle PM-Aussagen des Papers stehen.

**Nebenertrag für die Lastentscheidung (§1.4):** auch hier ist die Lastkorrektur **HDV-only** —
`LCF_T = 1,41 + 1,38·LF` (Gl. 4) und `LCF_B = 1 + 0,79·LF` (Gl. 7) gelten explizit nur für Trucks,
Busse und Reisebusse; für LCV existiert kein Lastparameter. Damit steht die Begründung, warum die
Zuladung nicht in den Faktor eingeht, auf **zwei unabhängigen Kapiteln** statt auf einem.

### 2.28 1c: welche Occupancy-KPIs die Paket-Kontamination ausweisen — und welche nicht

`trägt` · 2026-07-31. Vorabprüfung zu Plan Task 5c. Der Befund ist zweigeteilt, und das ist die
eigentliche Aussage: **die Personenseite ist korrigiert, die Distanz-/Belegungsseite nicht.**

**Korrigiert und dokumentiert.** `extract_shareduse.py` existiert genau deswegen: es leitet die
Pax-KPIs (Rides, Wartezeiten) direkt aus der Leg-CSV neu ab, weil die MATSim-Aggregate
`parcel_`-Personen mitzählen (Modul-Docstring Z. 2–7). Auch `distributions.py:66` schließt
Paket-Legs aus der Wartezeitverteilung aus. Der Mechanismus ist also bekannt und an den Stellen
adressiert, wo er Personen betrifft.

**Nicht korrigiert und in der Ausgabe nicht gekennzeichnet:** `occ_km`, `occ_segments`, `occ_time`
(alle `distributions.py`), `mean_pax_aboard` (`extract_drt.py:244`) und die Occupancy-Karte. Sie
entstehen aus dem gemischten Zähler und tragen **keinen Hinweis in der eigenen KPI-Zeile**. Für den
1c-Arm heißt „Ø Pax an Bord" faktisch „Ø Pax **plus Pakete** an Bord". Gemessen (§2.26):
**20,4 % aller Link-Einträge tragen Pakete, 9,9 % beides** — der Fehler ist also kein Randfall.
Das gehört in die Limitations des 1c-Arms, unabhängig von der Emissionsrechnung, und ist das
Distanz-Pendant zum Zeit-Befund in §2.4.

**Der als „vertagt" geführte 1c-KPI ist jetzt baubar.** `extract_shareduse.py` Z. 29–40 begründet
das Weglassen von `system/freight_veh_km` damit, eine korrekte Zerlegung brauche
„per-link, per-identity occupancy reconstruction … `geometry.reconstruct_drt_paths` only tracks an
occupancy COUNT per link, not rider identity". Genau das leistet
`geometry.reconstruct_drt_paths_detailed` seit Plan Task 5b. Der Blocker ist damit weg;
**gebaut ist der KPI nicht** (außerhalb des Emissions-Plans), aber die Begründung für die Vertagung
ist verfallen und sollte nicht weiter zitiert werden.

**Nebenbei erledigt: NEEDS-CHECK zur χ-Obergrenze** (§2.24, Option D). Die Frage war, ob die Zahl
der *angenommenen* Paketsegmente in den KPIs steht. Ja: `shareduse_channel_stats.csv` führt
`segments_delivered` (im geprüften Lauf 2884, bei 3104 `segments_submitted` und 3127
`segments_injected`). Die Obergrenze ist also ohne Java-Eingriff berechenbar.

### 2.29 Kaltstart: gerechnete Untergrenze, nicht geschätzte Limitation — NOx-Zahlen sind zu niedrig

_(gerechnet 2026-07-31, Task 9 des EMEP/EEA-Plans)_

Kaltstart ist im Emissionskanal **nicht modelliert**. Das ist eine Limitation wie jede andere —
nur ist sie hier **quantifiziert** statt bloß erwähnt, und das Ergebnis kehrt die geplante
Konsequenz um: die Entscheidungsregel des Plans („< 5 % → dokumentierte Limitation, fertig")
greift nicht, weil NOx auf der Frachtseite darüber liegt.

**Methode** (EMEP/EEA GB 2023 – Update 2025, Kap. 1.A.3.b.i–iv Gl. (10) in der
Euro-6+-Fassung mit β-Reduktionsfaktor):

```
E_COLD = β · bc · km · e_HOT · (Q − 1)
β  = 0,6474 − 0,02545·ltrip − (0,00974 − 0,000385·ltrip)·ta      (Tab. 3-39)
bc = NOx 0,1719 − 0,0055·ltrip; CO 0,2022 − 0,0064·ltrip;
     VOC 0,2398 − 0,0076·ltrip; sonst 1,0                        (Tab. 3-46)
Q  = A·v + B·ta + C, Boden 1  (Appendix 4 COLD_EMISSIONS_PARAMETERS,
     Euro 7 Diesel LCV, RANGE 1 für ta > 0; N1-II == N1-III):
     NOx 0,04806·v + 14,6608 | CO 0,16114·v + 27,3472
     VOC −0,28614·v + 18,4451 | Energie 1,34 − 0,008·ta
```

**Ein ausgewiesener Transfer, und er ist der einzige Freiheitsgrad.** β ist ein Anteil an der
*Gesamt*fahrleistung, kalibriert für `ltrip` ∈ [8, 15] km (europäischer Default 12,4 km).
Unsere Touren sind ~99 km lang — dort ist die Formel nicht auswertbar, sie wird negativ.
Übertragbar ist die **Kaltdistanz je Start**, β(ltrip)·ltrip, und die ist über das gültige Band
stabil: **3,02 / 3,50 / 3,39 km** bei ltrip 8 / 12,4 / 15 km (ta = 10 °C). Angesetzt wird
**ein** Kaltstart je Tour bzw. Fahrzeugtag, also β_eigen = Kaltdistanz / Entity-km. Die
Endzahl hängt daran nur schwach (NOx-Fracht 5,99 / 5,63 / 4,71 % über dasselbe Band) — die
Aussage ist also nicht ein Artefakt der ltrip-Wahl.

**Ergebnis auf `base10c`** (63 Touren / 6252 km Fracht; 120 Fahrzeugtage / 47 953 km DRT):

| Arm | ta = 10 °C | ta = 0 °C |
|---|---|---|
| **Fracht NOx** | **+5,63 %** | +6,62 % |
| Fracht CO | +13,94 % | +16,39 % |
| Fracht VOC | +3,61 % | +4,25 % |
| Fracht Energie/CO₂ | +0,93 % | +1,43 % |
| DRT NOx | +1,41 % | +1,66 % |
| DRT Energie/CO₂ | +0,23 % | +0,35 % |

**Was daraus für das Paper folgt:**

1. **Alle berichteten NOx-Zahlen sind eine UNTERGRENZE**, auf der Frachtseite um ~5–6 %. Die
   Abweichung ist **einseitig** (der wahre Wert liegt darüber, nie darunter) und trifft Diesel
   und BEV **nicht** gleich — der BEV-Arm hat keinen Kaltstart, sein Vorteil wäre also größer.
   Die Richtungsaussagen bleiben damit gültig; die Niveauaussage für NOx nicht.
2. **CO₂ und Energie sind unberührt** (< 1,5 %), also unter dem jsprit-Rauschboden von ~6,5 %.
   Die Kernaussagen des Papers hängen nicht am Kaltstart.
3. **PM-Auspuff hat für Euro 7 keine Kaltstart-Parametrisierung** im Appendix-4-Sheet (Euro 5+
   nutzt laut Kapitel eine eigene Gleichung mit absolutem Kaltfaktor). Für unsere Bilanz
   irrelevant: Auspuff-PM ist 0,89 g gegen 316,6 g Abrieb im selben Lauf (§2.27).
4. **Die DRT-Zahl ist „je Kaltstart" zu lesen und skaliert linear.** Ein Fahrzeugtag enthält
   lange STAY-Phasen; ob der Motor darin thermisch auskühlt, ist ohne Thermomodell nicht
   entscheidbar. Bei 5 echten Kaltstarts je Fahrzeugtag läge die DRT-Seite bei ~7 % NOx, also
   in derselben Größenordnung wie die Frachtseite. Das ist **keine** Aussage über die
   Realität, sondern die Angabe, wie empfindlich die Zahl auf eine Größe reagiert, die wir
   nicht messen.

**Konsequenz:** Backlog-Punkt „Kaltstart-Zuschlag implementieren" (`[H]`, ~0,5 d) angelegt;
Formeln und Sensitivitäten liegen rechenfertig in `analysis/kpi/data/README.md`.

### 2.30 Demand-Input-Drift Dev-PC ↔ Sim-PC: 0,53 % verschiedene Nachfrage

`trägt` · 2026-07-31. `hagrid-input/**` ist git-ignoriert und synchronisiert **nicht** über
Maschinen: Dev-PC-Demand (MD5 `eb52b4fb`, Stand 2026-07-28 15:19) ≠ Sim-PC-Demand (`8c39f76c`,
2026-07-28 10:23) → 6052 vs. 6020 Pakete (+0,53 %). **Intern konsistent** sind damit:
Baseline↔1c (basew21/chid600w21, beide Dev-PC, 6052/6051 Pakete) und die komplette
1d-θ-Kurve (alle Punkte Sim-PC, 6020). **Nicht exakt konsistent** ist jeder
Baseline↔1d-Vergleich: die 0,53 % sind ein *Muster*-, nicht nur ein Niveau-Unterschied
(anders platzierte Pakete). Optionen (Entscheidung offen, BACKLOG `[H]`):
(a) Inputs synchronisieren + Gewinner-θ-Punkt (und ggf. Baseline) auf synchronisiertem Stand
neu rechnen — sauber für die Paper-Headline; oder (b) 0,53 % als Caveat im Methods-Kapitel
ausweisen — klein gegen das ±10-%-Nachfrageband und den 6,5-%-km-Rauschboden (§2.1).
Der m1d015-Nachschuss lief bewusst **vor** jedem Sync, um die Kurve nicht intern zu brechen.

**Annotation 2026-08-10 — welcher Stand der richtige ist, und eine Falle beim Syncen.** Per
SHA256 nachgezählt: die aktive Datei auf dem **Dev-PC** ist `fdac2435ebc56d41` = der archivierte
Stand **`level_central`**, also der aktuelle PANDA-Stand (Zensus + Zentroid-Snap-Fix, 1131
Segmente, §2.8). Der **Sim-PC** fährt `bc86ecc580b0a81f` = `level_ctrsnap_central`, einen Stand
zurück. Damit ist die Richtung des Syncs festgelegt (Dev → Sim) und die Bewertung der
bestehenden Läufe eindeutig: **`basew21` und `chid600w21` fahren den aktuellen Stand und bleiben
gültig; die komplette θ-Kurve fährt den überholten.**
⚠️ **Falle:** auf dem Sim-PC existiert ein Verzeichnis `hagrid-input/lausitz/demand/level_central`,
das ebenfalls `bc86ecc5` enthält — den **alten** Stand unter dem Namen des neuen. Wer dort
„level_central einspielt", holt die falsche Datei und hält sie für die richtige. Die aktuelle
Datei liegt physisch nur auf dem Dev-PC. Beim Sync also nach SHA256 prüfen (wie
`run_lmd_band.ps1` es tut, nicht nach Dateigröße — `.dbf` ist fixed-width) und das fehlbenannte
Verzeichnis umbenennen. Nicht syncen, solange dort ein Lauf aktiv ist: eine getauschte
Eingabedatei würde von einem Folgelauf derselben Kette still aufgegriffen.
**Konsequenz für die θ-Entscheidung:** die Kandidaten müssen auf dem aktuellen Stand ohnehin neu
laufen → **keine Seed-Replikate auf der überholten Datei kaufen.** Ein Lauf je Kandidat
(θ=0,10 und 0,15) auf dem aktuellen Stand erledigt „Gewinner-θ-Rerun" und „Demand-Sync"
zusammen und ist dann erstmals gepaart gegen `basew21`.

### 2.31 1c: der χ-Zuordnungszähler saturiert — „alle verfallenen Segmente χ-geblockt" ist informationslos

`trägt` · Messung 2026-07-29, nachgetragen 2026-08-10. **Instrument-Limitation.** Die drei
`ChiGateStats`-Zähler (`chi_blocked_insertion_attempts`, `chi_blocked_segments`,
`segments_window_expired_chi_blocked`, eingebaut `a375df9`) beantworten **nicht**, ob χ die
verfallenen Segmente verursacht. Grund: `chi_blocked_segments` ist in **jeder** Iteration gleich
`segments_submitted` — das Prädikat „wurde je einmal χ-geblockt" ist über einen Simulationstag mit
tausenden Dispatch-Runden praktisch garantiert und gilt für erfolgreich zugestellte Segmente
genauso wie für verfallene.

Belege (zwei Läufe, unabhängig vom Lieferfenster):

| Lauf | `segments_submitted` | `chi_blocked_segments` | zugestellt | `window_expired` | `..._chi_blocked` |
|---|---|---|---|---|---|
| `chid600i` (χ=600, 20:00-Fenster) | 2953 | **2953** | 2676 | 260 | 260 (100 %) |
| `chid600w21` (χ=600, 21:00-Fenster) | 3104 | **3104** | 2884 | 218 | 218 (100 %) |

`segments_window_expired_chi_blocked == segments_window_expired` ist also **keine Messung, sondern
eine Identität**, solange der Zähler saturiert. ⚠️ Die daraus in BACKLOG/BACKLOG-DONE (2026-07-31)
gezogene Folgerung „das Gate ist der bindende Mechanismus, nicht die Fahrzeugkapazität" ist durch
diesen Zähler **nicht gedeckt** und dort entsprechend annotiert. Was gilt: das Gate ist
**nachweislich aktiv** (11,7 Mio. blockierte Evaluationen bei χ=600) — aktiv ≠ bindend.

**Positiv-Befund aus derselben Messreihe:** `segments_rejected_final` ist als Gate-Signal
empirisch wertlos. Der Hard-Closed-Lauf `chiwire` (χ=−1) blockt 46 838 041 Evaluationen und
verhindert **100 %** der Zustellungen — `segments_rejected_final` steht dabei **auf 0**. Ein
χ-blockiertes Paket wird nie terminal abgelehnt (es kehrt in die `ParcelOnlyRetryQueue` zurück und
fällt hinter seinem Fenster ohne Event heraus). Wer aus `rejected_final = 0` auf ein inaktives Gate
schließt, irrt maximal — genau das war der Anlass der Instrumentierung.

**Was die Frage tatsächlich beantworten würde** (nicht gebaut, Aufwand ~1–2 h + 1 Rerun): statt
eines Zählers eine **Verteilung** — pro Segment und Dispatch-Runde der *kleinste erreichbare*
`detourOnly`-Wert über alle Kandidaten. Liegt das Minimum für die verfallenen Segmente bei
700–900 s, hätte χ=900 sie zugestellt; liegt es bei mehreren 1000 s, ist χ nicht der Engpass.
Das liefert eine erste Näherung der **ganzen δ(χ)-Kurve aus einem Lauf** und damit eine begründete
Platzierung der Sweep-Punkte statt eines geratenen Rasters. Keine exakte Kurve: höheres χ verändert
die Trajektorie (zusätzlich angenommene Pakete verschieben die Fahrzeugzustände), der Wert ist eine
Ober-/Unterschranken-Aussage pro Runde, kein Kontrafaktum. Nebenbedingung aus §2.24 Punkt 4:
`calculate()` läuft je Kandidat, nicht je gewählter Insertion — die Aggregation muss das Minimum je
Runde bilden, nicht jede Evaluation loggen.

Verwandt: §2.3 (χ als untere Schranke des Umwegs), §2.24 Punkt 2 (dieselbe Zählerlücke aus
Emissions-Perspektive: angenommene Insertions hinterlassen keine Spur), §1.5 (Determinismus —
ein Rerun derselben Konfiguration ist bit-identisch und taugt nicht als Reproduktionstest).

**✅ GEBAUT 2026-08-10 — das Instrument existiert, die Messung fehlt noch.** Umgesetzt genau wie
oben skizziert, statt eines Zählers eine Verteilung:
`ChiGateInsertionCostCalculator` meldet jetzt **jede** Paket-Evaluation an
`ChiGateStats.recordEvaluation(person, parcels, detourOnly)` — auch die geblockten, sonst hätten
die verfallenen Segmente überhaupt keinen Wert —, und `ChiGateStats` hält je Segment das
**Minimum** über alle Kandidaten (CAS-Schleife, Schreibvorgang nur bei neuem Minimum; ein
Map-Zugriff und ein Double-Vergleich je Evaluation). `SharedUseKpiHandler.writeDetourCsv`
schreibt zum Shutdown `<runId>.shareduse_detour_min.csv` mit
`segment;parcels;evaluations;min_detour_s;outcome`; das Outcome-Label entsteht in **derselben**
Schleife wie die Bucket-Zähler (`Totals.outcomeByPerson`), damit Label und Zählung nicht
auseinanderlaufen können. Zeilen sind nach Segment-ID **sortiert** — die Map darunter ist
concurrent, eine unsortierte CSV würde zwei bit-identische Läufe unterscheidbar machen und die
Determinismus-Kontrollen (§1.5) brechen. Python-Seite: `analysis/kpi/chi_detour.py` liefert
Quantile je Bucket (`delivered` inkl. `delivered_late` gegen `window_expired`), das billigste
verfallene Segment, die mittlere Segmentgröße je Bucket (F1-Gegenprobe) und zwei
100-s-Histogramme in `kpi_distributions.csv`; leere Buckets werden **weggelassen**, nicht
0-gefüllt (M4). Tests: 8 Java (`ChiGateDetourStatsTest`) + 3 (`SharedUseKpiHandlerTest`) +
13 Python, die drei tragenden Java-Verhalten mutationsgeprüft (Aufzeichnung entfernt / rohen
`totalTimeLoss` statt `detourOnly` / Minimum-Vergleich entfernt → alle gefangen).
Zwei Punkte für die Auswertung: `pending_open` gehört **nicht** in den χ-Bucket (das Fenster ist
nie zugegangen, es ist kein Beleg über die Schwelle), und der `χ<0`-Arm zeichnet ebenfalls auf —
dort boardet kein Paket, die Trajektorie ist die reine Pax-Trajektorie, also ist seine Verteilung
die **unperturbierte Referenz**. **Offen: der Rerun**, der die Datei erzeugt (bestehende
χ=600-Konfiguration, ~7 h) — Sim-PC und Dev-PC waren am 2026-08-10 belegt.

### 2.32 1d gegen 1c an den gemessenen Punkten: eine Achse trägt, die andere nicht

`trägt mit Einschränkung` · 2026-08-10. Gegenüberstellung der beiden bislang einzigen gültigen
Betriebspunkte (`m1d010`, θ=0,10, Sim-PC · `chid600w21`, χ=600, Dev-PC), beide gegen die
10-Sitzer-Baseline `basew21`:

| | Pakete zugestellt (brutto) | Pax-Fahrten | Δ Pax gegen basew21 |
|---|---|---|---|
| Baseline `basew21` | ~100 % (6052, unassigned 0) | 8973 | — |
| 1d `m1d010` | **97,9 %** (5894/6020) | 7488 | **−16,6 %** |
| 1c `chid600w21` | **93,7 %** (5671/6051) | 7326 | **−18,4 %** |

**Paketachse: belastbar.** 4,2 pp Unterschied liegen außerhalb jedes hier gemessenen
Rauschbands. **Pax-Achse: nicht auflösbar.** Die 1,8 pp entsprechen 162 Fahrten, und der
Rauschboden auf diesem Kanal ist ~113 Fahrten — die Spanne der drei Läufe mit praktisch keiner
Fracht (θ=0,40/0,50/1,00: 8949 / 8914 / 9027). Die ehrliche Fassung ist deshalb: *1d stellt klar
mehr Pakete zu; dass es dabei auch weniger Pax-Fahrten kostet, ist nicht belegt.*

Drei Vorbehalte, die vor jeder Übernahme ins Paper stehen müssen: **je ein einzelner Punkt** pro
Arm (der χ-Sweep fehlt noch, δ(χ) ist nicht bekannt — 1c könnte einen Punkt haben, der der
Dominanz entgeht); der **Sitz-Confound** aus §2.21 Punkt 3 (1c fährt 8 Sitze, die −18,4 %
enthalten den Sitzverlust *und* das Mitnehmen, untrennbar ohne einen 8-Sitz-Kontrolllauf über
iter150 — `suref8` existiert nur mit `maxIter=1`); und der **Cross-Machine-Drift** aus §2.30
(1d 6020 Pakete, 1c 6051).

**Normalisierungs-Falle, beim Zeichnen aufgefallen.** Die θ-Prozentwerte in §1.2 sind gegen
**`basew21` (8973)** gerechnet, nicht gegen den Kontrollarm **`ctrl1d` (9027)** desselben Sweeps.
Beide Referenzen sind vertretbar und beantworten verschiedene Fragen — `ctrl1d` ist der
*gepaarte* Bezug (gleiche Maschine, gleiche Nachfrage, identischer Modulstack, nur θ variiert)
und damit der richtige für Aussagen *innerhalb* der θ-Kurve; `basew21` ist der *armübergreifende*
Bezug und der richtige für 1c↔1d↔Baseline. Sie unterscheiden sich um 0,6 %, was θ=0,10 zwischen
−16,6 % (basew21) und −17,0 % (ctrl1d) verschiebt. **In einer Tabelle darf nur eine von beiden
vorkommen**, und welche, muss dabeistehen.

### 2.33 Die LMD-Kostenfunktion bepreist keine Zeit — der Tagesfixsatz je Tour ist der ganze Personalkanal

`trägt` (gemessen, nicht geschätzt) · 2026-08-11 · betrifft **alle** LMD-€-Zahlen, Hannover wie
Lausitz-Baseline, weil beide Pfade dieselbe `ScoringFunctions` binden
(`HAGRIDSimulationModule:64` bzw. `FreightRunComposer:53-54`).

**Was in die berichtete Summe eingeht** (`DashboardGenerator:1352-1357,1788-1789`, Python-Zwilling
`extract_freight_provider.py:184-204`) — gemessen auf `BASECASE_13052025_230v2` (663 Zustelltouren,
97.528 Pakete, 30.282 km, 4.449 Tour-h):

| Term | Formel | Wert | Anteil |
|---|---|---|---|
| Fixkosten | 663 Touren × 189,15 €/Tag | **125.406,3 €** | **88,3 %** |
| Distanz | Linklängen × 3,864e-4 €/m | 11.180,0 € | 7,9 % |
| Overtime | 5 € pauschal je Aktivität > 7,5 h | 5.425,0 € | 3,8 % |
| Zeit | Fahrzeit × `costsPerSecond` = **0** | **0,0 €** | 0 % |
| **Σ** | | **142.011,4 €** | |

**Was getrackt, in Euro ausgewiesen und dann aus der Summe geworfen wird:** `costActivity`
93.904,9 € (Standzeit × **0,008 €/s**, hardcoded `ScoringFunctions:139`) und
`costTimeWindowPenalty` 31.005,0 € (Verspätung × 5 €/s ≙ 18.000 €/h — ein Scoring-Gerät, kein
Preis). Beide werden im Dashboard als Balken *gezeichnet*.

**1. Es gibt keinen zeitproportionalen Kostenterm.** `costsPerSecond="0.0"` steht explizit in allen
`ct_cep_*`-Vantypen (`lmd-vehicle-types.xml`, `HAGRID_vehicleTypes2.0.xml`), während `ct_car`
(0,00628) und `ct_bus` (8e-4) Werte tragen — die Null ist eine Typentscheidung, kein Default.
Der einzige echte Zeitterm (`costActivity`) ist aus der Summe genommen. **Kein Bug, eine Setzung
— aber eine, die das Modell blind macht für alles, was Zeit kostet.**

**2. Der Fixsatz ist eine Tagespauschale je _Tour_, unabhängig von der Dauer.** Auf 230v2 (Median
7,35 h, Mittel 6,71 h) liegen **32 % der Touren unter 7 h**, 7 % unter 3 h; die kürzeste (0,72 h)
zahlt 189,15 € ≙ **263 €/h**, die längste (12,64 h) ≙ 15 €/h. Für jsprit heißt das: Touren
zusammenlegen lohnt, Touren *verkürzen* nie. Das ist der eigentliche Mechanismus hinter §2.1/§2.2
(„Fahrzeugzahl konvergiert, Tourengeometrie nicht") — Distanz ist nicht nur ein schwacher Term,
Zeit ist gar keiner.

**3. Dass im Fixsatz ein Fahrer steckt, ist Inferenz, kein Beleg.** Eine Quelle für 171,78/189,15 €
existiert im gesamten Repo nicht, `ct_cep_size_s` (154,41 €) ist aus den beiden anderen linear
interpoliert (§2.7). Zwei Indizien für den Fahrer: 189 €/Tag ist für Kapital+Wartung+Versicherung
eines Transporters zu hoch (real 30–60 €), und 125.406 € / 4.449 Tour-h = **28,2 €/h implizit** —
praktisch identisch mit den 28,8 €/h, die das Activity-Scoring separat ansetzt. Gegen-Indiz:
189,15 € lassen sich **nicht** in ein plausibles Fahrzeug *plus* einen vollkostenrechnenden Fahrer
zerlegen (Arbeitgeber-Vollkosten 25–30 €/h wären über 7 h schon 175–210 €). Der Satz ist also
zusätzlich zu niedrig, vermutlich Netto-Lohnbasis.

**4. Zwei echte Defekte im Scoring** (im Unterschied zu 1.–3.):
- `costFix` im Carrier-Attribut ist **immer 0** — `CostAttributeWriter.finish()` schreibt, bevor
  `VehicleEmploymentScoring.getScore()` akkumuliert (`SumScoringFunction` ruft erst alle `finish()`).
  Folge: das Java-Attribut `costTotal` (230v2: 148.635,6 €) ist `dist+activity+overtime+twPen`
  **ohne** Fix, die Board-Summe ist `dist+fix+overtime` **ohne** activity/twPen. **Zwei Totale mit
  disjunkten Auslassungen, keins davon sind die Kosten.** Die Python-Schicht umgeht es (rechnet fix
  selbst aus den Fahrzeugtypen), der Java-Pfad nicht.
- Overtime: `isExceedingWorkTime` wird deklariert und **nie auf `true` gesetzt**
  (`ScoringFunctions:146,184`) → 5 € feuern je Aktivität statt einmal je Tour; Bezugspunkt ist
  zudem die erste Aktivität des **Carriers**, nicht der Tour. Der Term ist damit
  fenster­abhängig statt arbeitszeit­abhängig: **35 €** auf `bandz_central` (08:00–20:00) gegen
  **1.920 €** auf `basew21` (07:30–21:00, 16,5 % der dortigen Summe). Der Fixkostenanteil
  verschiebt sich dadurch mit: 81,4 % (`bandz_central`) vs. 72,2 % (`basew21`) vs. 88,3 % (230v2)
  — **der „81 %" aus §2.1 ist basisabhängig, nicht eine Modellkonstante.**

**5. Zwei Distanzmaße im selben Blob, systematisch 4,6 % auseinander.** `distKm` (aus dem
geparsten Carrier-Plan) × `costPerKm` ergibt konsistent mehr als `costDist` (aus dem
MATSim-Scoring der ausgeführten Legs): Verhältnis **0,950–0,958 über alle 14 lokal vorliegenden
Hannover-Boards**, 0,983 auf `bandz_central` (Lausitz, iter0). **Nicht** die
Low-Util-Re-Allokation — auf 230v2 liegt keine Tour unter der 5-%-Schwelle (Minimum-Loadfactor
5,20), ratio = 1,0. Die Richtung (gescorte Strecke kürzer) und der Kontrast iter150 ↔ iter0 passen
zu „Plan vs. nach Re-Routing ausgeführt", erklären die 1,7 % Restlücke bei iter0 aber nicht.
**Mechanismus ungeklärt.** Für €-Summen irrelevant (4,6 % auf einen 7,9-%-Posten = 0,4 % der
Gesamtkosten), für jede €/km- oder km-Kennzahl nicht.

**Konsequenz für die Kapazitäts-Sensitivität (Hannover).** Die Kostenkurve folgt heute der
**Tourenzahl** (−81 % von cap 30→280), nicht den **Tourstunden** (−37 %). Probeweise nachgerechnet
mit einem Zwei-Term-Modell (F_Fzg 40 €/Tag + w 21,31 €/h, niveauverankert auf 189,15 € bei 7 h,
Distanz und Overtime unverändert):

| cap | Touren | Tour-h | alt € | neu € | Δ |
|---|---|---|---|---|---|
| 30 | 3.357 | 7.095 | 672.391 | 322.864 | **−52,0 %** |
| 100 | 1.077 | 4.723 | 227.670 | 167.671 | −26,4 % |
| 190 | 671 | 4.468 | 141.558 | 136.672 | −3,5 % |
| 280 | 652 | 4.451 | 142.329 | 139.923 | −1,7 % |

Fast reiner Low-Cap-Effekt. Vorzeichen und Monotonie bleiben, die **Stärke** nicht: die Ersparnis
cap 30 → 280 fällt von **−78,8 % auf −56,7 %**. Das Kostenminimum wandert von cap 240 auf 190,
liegt aber in einem Plateau von 2,4 % Spanne — daraus ist keine Optimum-Aussage zu bauen.
**Ausdrücklich unberührt:** der Arbeitszeit-/Kapazitäts-Crossover bei ~170. `classify()`
(`extract_sweep.py:109-119`) prüft nur `durH > 7.0` und `parcels > 0.9·cap`; da geht kein Euro ein.

**Konsequenz für 1c/1d (die paper-relevante).** Die drei Größen, die die Integrationsszenarien
überhaupt ausmachen — χ-Umwegzeit, 2 × 420 s Rüstzeit, Deadhead zur Idle-Position — kosten
allesamt **Zeit**, und Zeit hat in dieser Kostenfunktion den Preis null. §2.20 hält fest, dass die
*Zielfunktion* Rüstzeit und Deadhead nicht kennt; hier kommt dazu, dass auch die *nachträgliche
Abrechnung* sie nicht sichtbar machen könnte. Eine Kostenaussage über 1c/1d ist damit vor dem
Neubau der Kostenfunktion strukturell nicht möglich — unabhängig davon, dass beide Arme heute
ohnehin **gar keine** Fracht-€-KPI exportieren (nur die Baseline hat ein `analysis/freight/`).

**Beschlossene Korrekturregel für den Hannover-Sweep** (Entscheidungen 2026-08-11, hierher
verschoben aus dem BACKLOG 2026-08-17, weil es Festlegungen sind und keine offene Arbeit):

- **Regel = „Zwei-Term-Rekonstruktion"** — `F_Fzg + w × durH` statt der Tagespauschale,
  niveauverankert über `F_Fzg + w × 7 h = 189,15 €`. Vorzug vor Pro-rata-Umlage, Schichtstaffel und
  Fahrzeug-statt-Tour-Zählung (letztere ist auf diesen Daten wirkungslos: Fahrzeuge == Touren), weil
  sie **strukturell identisch mit dem späteren echten Fix** ist — der Ad-hoc ist ein Prototyp, kein
  Wegwerfcode. Zwei Defekte fallen gratis mit: `max(0, durH − 7) × w × Zuschlag` ersetzt die kaputte
  5-€-Overtime-Pauschale, und der Lohnterm über `durH` **subsumiert** `costActivity` (nicht
  zusätzlich draufrechnen — Doppelzählung). `costTimeWindowPenalty` bleibt draußen (5 €/s ist ein
  Scoring-Gerät, kein Preis).
- **Kalibrierung `F_Fzg ≈ 40 €/Tag` → `w = 21,31 €/h`** (User-Vorentscheidung). Bewusst akzeptiert:
  das liegt unter Arbeitgeber-Vollkosten (25–30 €/h, vgl. die 28,99/33,45 €/h in
  `cost_parameters.csv`) und unterzeichnet die Personalkosten weiter um ~⅓ — jetzt aber **sichtbar
  an einer Zahl** statt versteckt in einer Tagespauschale. Die Niveaukorrektur gehört in den echten
  Fix, nicht in den Ad-hoc.
- **Darstellung: beide Kurven zeigen**, Konventionswechsel explizit, kein stiller Tausch.
- **Distanzkosten nicht neu rechnen** — `costDist` aus `CARRIER_DETAIL` unverändert übernehmen, dann
  fasst die Korrektur die 4,6-%-Lücke aus Punkt 5 gar nicht an und behält Anschluss an alle
  berichteten Zahlen.
- **Terminierung: erst wenn v2–v4 vollständig sind**, dann gesammelt — alle Läufe sollen auf
  derselben Kostenversion stehen. v4 ist ein Reseed-Replikat auf identischem Codestand, gibt dem
  Board also **drei** Ziehungen statt zwei (Struktur wie der Multi-Seed-Fächer, §1.5/§2.1).
  Erwartung: die Korrektur **verkleinert** die Reseed-Spanne, weil die Tourenzahl zwischen Seeds
  stärker schwankt als die Tourstunden.

**Reproduktion:** Zerlegung aus den `COSTS` / `SUMMARY` / `CARRIER_DETAIL`-JSON-Blobs der
Java-Dashboards (`hagrid-matsim-output/*/analysis/HAGRID_Dashboard_*.html`); Carrier-Attribute
gegengelesen aus `*.output_carriers.xml.gz`. Sweep-Nachrechnung auf
`analysis/hannover-sweep/sweep_kpis.csv`. Umsetzung → BACKLOG `[H]` Hannover-Sweep: Kostenkorrektur
im Postprocessing.

---

### 2.34 Die Tourenzahl war systematisch ~21 % zu hoch — greedy Konstruktionsheuristik, nicht Rauschen

`trägt` (gemessen, nicht geschätzt; Ursache behoben 2026-08-11) · betrifft **alle** jsprit-geplanten Touren,
Hannover wie Lausitz, weil beide Pfade dieselbe Methode binden
(`HAGRIDRouterUtils#configureAlgorithm`, aufgerufen aus `Router:741` bzw.
`LausitzFreightPreprocessor:374`).

`HAGRIDRouterUtils:232` setzte `Jsprit.Parameter.CONSTRUCTION` auf `BEST_INSERTION` und überschrieb
damit jsprits eigenen Default `REGRET_INSERTION`. Eine Quelle oder Begründung für die Abweichung
existiert im Repo nicht.

**Mechanismus.** `BEST_INSERTION` ist greedy je Job: jeder Job landet dort, wo er *im Moment seiner
Einfügung* am billigsten ist. Eine frische Route zu eröffnen kostet dabei nur die Depot-Stichfahrt,
weil die **Fixkosten des Fahrzeugs während der Insertion unsichtbar** sind — jsprits
`FIXED_COST_PARAM` ist per Default 0, und der Fixkosten-Zweig in
`JobInsertionCostsCalculatorBuilder.build()` ist in jsprit 1.8 auskommentiert. Die *Zielfunktion*
berechnet `vehicleCostParams.fix` je Route korrekt, bewertet aber nur fertige Lösungen.
Ruin-and-Recreate kann den Anfangsfehler nicht heilen: eine halb geleerte Tour zahlt ihre volle
Tagespauschale weiter, jeder Zwischenzustand ist also schlechter und wird verworfen. Das ist die
Kehrseite von §2.33 Punkt 2 — dort steht „Touren zusammenlegen lohnt", hier steht, dass der
Optimierer es nicht getan hat.

**Messung, volle Lausitz-LMD-Route, 7 Carrier, 3.123 Deliveries, `jspritIter=100`, identische
Inputs incl. Service-Area-Clip, gegen `DRT_BASELINE_13052025_basew21_iter150_jsprit100`:**

| | vorher (`BEST`) | nachher (`REGRET`) | Δ |
|---|---|---|---|
| Touren | 52 | **41** | **−11 (−21,2 %)** |
| Distanz (plan-basiert) | 3.002,4 km | 1.984,5 km | **−33,9 %** |
| Kosten (Fix + Distanz) | 9.502,17 € | 7.797,24 € | −17,9 % |
| Arbeitsstunden | 289,7 h | 263,2 h | −9,1 % |
| Schichtnutzung | 79,6 % | **91,7 %** | |
| ungenutzte Fahrzeugtage | 10,61 | 3,40 | |
| Touren an der 7-h-Grenze | 11 | 26 | |
| Anteil `ct_cep_size_s` | 31/52 (60 %) | **5/41 (12 %)** | |

Pakete und Stops sind je Carrier auf beiden Seiten identisch, `unassignedJobs=0` überall — die
Ersparnis ist keine ausgelassene Arbeit. Kleine Carrier ändern sich kaum (fedex 2→2, ups 3→3),
die großen stark (amazon 11→8, hermes 7→5, dhl 19→15).

**Das ist Bias, nicht Rauschen, und widerspricht §2.1/§2.2 nicht.** Die Fahrzeugzahl konvergierte
stabil — nur auf einen systematisch zu hohen Wert. Die Distanzänderung (−33,9 %) liegt beim
Fünffachen des dort dokumentierten jsprit-Rauschbodens von 6,5 % auf der Fahrleistung.

**Was ausgeschlossen wurde** (Zehn-Arm-Probe auf Carrier `dpd`, 408 Services, je einvariabel):
- **Fahrzeugkosten sind nicht die Ursache.** Bei Kapazität 100 / 165 / 230 ergeben sich *immer* 5
  Touren; `ct_cep_size_s` teurer zu machen ändert nichts an der Tourenzahl. Der kleine Van ist
  innerhalb einer 5-Touren-Struktur die korrekte, billigste Wahl — er war Symptom, nicht Ursache.
- **`FIXED_COST_PARAM` ist nicht der Hebel.** Auf 1.0 gesetzt: 5 Touren bleiben 5, −0,3 % Gesamt.
  `IncreasingAbsoluteFixedCosts` bepreist die Fixkosten des *eingesetzten* Fahrzeugs, bestraft also
  das Upgrade auf den größeren Van, während der Gewinn erst nach dem Zusammenlegen anfällt.
- **Die Ruin-Größe ist nicht die Ursache.** HAGRIDs Anteile (radial 48, random 81 Jobs bei 408) sind
  nicht kleiner, sondern **größer** als jsprits eigene, hart gedeckelte Defaults (20–50 / 70). Alle
  Ruins über die Deckel hinaus vergrößert (radial 122, random 163, incl. `WORST_*`/`CLUSTER_*`, die
  HAGRID nicht überschreibt und die die höchstgewichteten Strategien sind): **Tourenzahl bleibt 5.**

**Laufzeit: +9,2 %, nicht 0.** Gepaarte Messung 2026-08-12 (beide Arme **gleichzeitig** gestartet,
leere Maschine, identische Inputs/`-Xmx`/Seed, einzige Code-Differenz die eine `CONSTRUCTION`-Zeile
gegen `git HEAD`, im Bytecode gegengeprüft):

| | Wall | jsprit | s/Tour | s/km |
|---|---|---|---|---|
| `BEST` | 11.240 s (187,3 min) | 11.071 s | 212,9 | 3,69 |
| `REGRET` | 12.274 s (204,6 min) | 12.100 s | 295,1 | 6,10 |
| Δ | **+9,2 %** | +9,3 % | +38,6 % | +65,4 % |

**Die zuerst hier notierte Aussage „Laufzeit ist kein Argument" war eine Übertragung von einem
Carrier auf sieben und ist falsch.** Auf `dpd` mit reinem M-Van-Menü war `REGRET` 22 % schneller
(722 s gegen 931 s) — auf der vollen Route ist es 9 % langsamer. Je Ergebnis-Einheit ist der
Abstand größer, weil `REGRET` länger rechnet *und* weniger Touren/km erzeugt. +17 min je
Baseline-Arm gegen −21 % Fahrzeuge und −34 % Fahrleistung bleibt ein guter Tausch, ist aber ein
Tausch und keine Gratisverbesserung. Kein Arm bricht früh ab (beide 100/100 Iterationen), der
Unterschied ist also Kosten pro Iteration, nicht Konvergenzgeschwindigkeit.

**Zwei Validierungen, die die Tabelle oben tragen:** (a) der `BEST`-Arm reproduziert die
Produktions-Baseline `basew21` **exakt** — 52 Touren, 3.002,4 km, 9.502,17 €, gleicher Typenmix je
Carrier —, die Gegenüberstellung ist also kein Harness-Artefakt; (b) der `REGRET`-Arm liefert in
zwei Läufen sechs Stunden auseinander und unter verschiedener Maschinenlast identische Zahlen
(41 / 1.984,5 km / 7.797,24 €), die Richtung ist also nicht Suchrauschen.

**Konsequenz für bestehende Läufe.** Alle vor dem 2026-08-11 geplanten Touren tragen die zu hohe
Tourenzahl. Betroffen: die **Lausitz-Baseline** und **1d Modular** (`runModular` bindet dieselbe
Methode); **1c** rechnet selbst kein jsprit (Pakete fahren auf der DRT-Flotte), seine
Vergleichsbasis aber schon. Ebenso der komplette **Hannover-Sweep cap 30→400** — und dort mit
zusätzlicher Wucht, weil dessen Kostenkurve laut §2.33 der *Tourenzahl* folgt, also genau der
Größe, die hier um ein Fünftel falsch war. Der Arbeitszeit-/Kapazitäts-Crossover bei ~170 (§2.33)
ist neu zu vermessen, nicht fortzuschreiben.

**Offen:** Seed-Fächer auf dem Fix (Einzellauf, jsprit-Default-Seed) — die Richtung ist weit
außerhalb des Rauschbodens, die exakte Höhe nicht abgesichert. `FAST_REGRET` steht weiter auf
`false` (= jsprit-Default) und ist der nächste Kandidat.

**Reproduktion:** `LausitzFreightPreprocessor.run` mit `hagrid-input/lausitz`, Vergleich
plan-gegen-plan aus beiden `*carriers.xml` (Kosten aus der Fahrzeugtypen-Tabelle neu gerechnet, weil
die Java-Kostenattribute laut §2.33 Punkt 4 unvollständig sind). Gepinnt durch
`JspritConstructionHeuristicTest` (48 dauergebundene Stops: 4 Touren mit `REGRET`, 5 mit `BEST`;
mutationsgeprüft). Teilergebnis zum BACKLOG-Item „jsprit-Upgrade 1.8 → 2.x": Spike-Frage (i) ist
positiv beantwortet — 1.8 genügt, der `[L]`-Fork-Port ist dafür nicht nötig.

**Nebenbefund, nicht untersucht:** plan-basierte Distanz 3.002,4 km gegen 3.724,3 km in
`analysis/freight/TimeDistance_perVehicleType.tsv` (event-basiert, nach `LmdTourRetimer`-Reroute)
— 19 %, deutlich mehr als die 4,6 % aus §2.33 Punkt 5, aber ein anderes Messpaar.

### 2.35 Das χ-Gate verrechnete den Umweg auf der Summe — echter Umweg bis zur eigenen Standzeit las sich als Null

`behoben 2026-08-13` · **Defekt in der Entscheidungsgröße des 1c-Arms, nicht bloß im Instrument.**

Das Gate zog die Eigenstandzeit des Pakets von der **Summe** beider Beine ab und klemmte einmal:

```
alt:  detourOnly = max(0, totalTimeLoss − [depotPickup(n) + segmentDwell(n)])
neu:  detourOnly = max(0, pickupTimeLoss  − max(depotPickup(n),  60 s))
                 + max(0, dropoffTimeLoss − max(segmentDwell(n), 60 s))
```

**Warum die Summenform falsch ist.** `InsertionDetourTimeCalculator` bildet jedes Bein separat, und
jedes Bein enthält **die Standzeit seines eigenen Stops**: das Pickup-Bein die Depot-Ladezeit, das
Dropoff-Bein die Türstandzeit. Für einen **neuen** Stop ist `stopDuration` exakt die Dauer dieses
Requests — die Subtraktion hebt sie punktgenau weg und lässt `toTT + fromTT − replacedDriveTT`
stehen, also den reinen Fahrumweg. Für einen **gleich-verlinkten** Stop trägt das Bein nur die
*zusätzliche* Standzeit (`ParallelStopTimeCalculator`-max-Semantik) und **überhaupt keine
Fahrterme** — dort ist der wahre Fahrumweg 0. Zieht man aber die Nominalstandzeit **beider** Stops
von der Summe ab, wird die Überzahlung des mitgenommenen Beins gegen echtes Fahren auf dem
**anderen** Bein verrechnet: bis zu `ownDwell(n)` Sekunden realer Umweg lasen sich als 0.

**Zweiter, kleinerer Bias in derselben Größenklasse — mit behoben.** Der Abzug nahm die
*nominale* Eigenstandzeit, der Fahrplan kennt aber einen Boden: `MinimumStopDurationAdapter`
(`drtCfg.getStopDuration() = 60 s`) macht jeden Stop mindestens 60 s lang. Ein Ein-Paket-Segment
lädt nominal 30 s, im Fahrplan aber 60 s — der Abzug von 30 s ließ also 30 s **eigene Standzeit**
im Rest stehen und zählte sie als Umweg. Richtung: Gate zu **streng**, also entgegengesetzt zum
Hauptfehler. Bei χ=600 unter 5 %, bei χ=60 die halbe Schwelle — im neuen Sweep-Gebiet also nicht
vernachlässigbar. Jetzt zieht das Gate je Bein `max(Eigenstandzeit, Boden)` ab und liest den Boden
aus **derselben** `drtCfg`-Quelle, die den Adapter parametrisiert (zwei Literale wären genau die
Drift, die hier zu vermeiden ist). Der Dropoff-Boden bindet in dieser Parametrisierung nie
(`segmentDwellSeconds(1) = 120 s`).

**Kein Doppel-Zufall nötig, deshalb der Normalfall.** Jedes Paketsegment startet am `parcelDepot`;
ein mitgenommener Depot-Stop ist die Regel, nicht die Ausnahme. Ein Bein genügt. Messbild in
`chid600det` (χ=600, 3104 Segmente, 6009 Pakete, 11 849 786 Evaluationen): **1096 Segmente (35 %)
melden exakt 0** — jedes davon mit ≥ 123 Evaluationen, es ist also gemessen und nicht defaultet.
Kanten der Fehlgröße: `ownDwell(1)=150 s`, `ownDwell(13)=1290 s`.

**Was das entwertet.** Die beiden bislang einzigen 1c-Punkte `chid600w21` und `chid600det` haben
Pakete unter der Summenform **zugelassen** — die Entscheidung, nicht nur die Anzeige, war betroffen.
Der Fix ist strikt **strenger** (kein unverdientes Guthaben mehr), die Zustellquote bei χ=600 kann
also nur fallen oder gleich bleiben. **Wie weit, ist aus der vorhandenen Datei nicht invertierbar**:
für ein Segment mit gemeldeter 0 liegt der wahre Fahrumweg irgendwo in `[0, ownDwell(n)]`, das Clamp
hat die Information vernichtet. Genau dafür ist der χ=600-Anker-Rerun die Messung, nicht die
Schätzung. Vom θ-Sweep und dem gesamten 1d-Arm ist **nichts** betroffen: `chiThreshold` existiert nur
in `hagrid.integrated.shareduse` + der Simulations-Verdrahtung, `theta` nur in
`hagrid/integrated/modular/ModularTourDispatcher.java`.

**Was aus der `chid600det`-Messung trotzdem trägt.** Die Kernaussage lebt in der
Entscheidungsvariablen des Gates selbst und ist gegen den Defekt robust, weil er nur nach unten
verzerrt: **215 der 218 verfallenen Segmente (98,6 %) hatten ein Minimum unter χ=600** — sie wurden
nicht von der Schwelle gehindert. Worst-Case-Korrektur (jedem verfallenen Segment das volle
`ownDwell` als unterschlagenen Umweg gutgeschrieben) hebt nur **16 von 218 (7,3 %)** über 600 s;
Grund: **178 der 218 sind Ein-Paket-Segmente** mit `ownDwell = 150 s`, es ist kaum Guthaben da, in
dem sich etwas verstecken könnte. Nicht tragend und **nicht zitierbar** sind dagegen alle
Niveauzahlen derselben Datei — Median 127 s (verfallen) / 25 s (zugestellt), `p25 = 0` (das ist die
Klemmgrenze, kein Quantil: 35 % Punktmasse), „19 Segmente fuhren umsonst mit", `expired_cheapest_s`.

**Nebenbefund, der die Deutung dreht.** Verfallene Segmente sind *kleiner* (1,41 vs. 1,98 Pakete) —
die F1-Hypothese „große Segmente kippen heraus" ist damit invertiert — und wurden im Median
**17 477** mal evaluiert gegen **1 140** bei den zugestellten (Verteilungen berühren sich kaum:
Minimum verfallen 15 907 > Median zugestellt 1 140). Der zirkuläre Teil davon ist die
`ParcelOnlyRetryQueue` (sie bietet jede Runde neu an, bis das Fenster zugeht); der nicht-zirkuläre
Teil ist, dass über ~17 k Angebote hinweg der billigste Kandidat billig **blieb** und Kandidaten den
Kostenrechner erst *nach* Kapazitäts- und Zeitfensterfilter erreichen. Übrig bleibt Konkurrenz:
das Fahrzeug, das es günstig mitgenommen hätte, war jede Runde anderweitig verplant.

**Konsequenz für den Sweep** (BACKLOG): das informative Gebiet liegt **unter 200 s** (bei 60 s
trennen sich zugestellt/verfallen 28 % vs. 64 %, zwischen 200 und 900 s bewegt sich die zulässige
Menge kaum). Dort ist das unverdiente Guthaben **so groß wie die Schwelle selbst oder größer**
(150 s = 75 % von χ=200, > χ=120, 2,5 × χ=60) — ein Sweep auf der Summenform hätte dort den Defekt
gemessen, nicht die Politik. Deshalb ist der Fix Voraussetzung für Block 1 und nicht dessen
Fußnote. χ=0 (nur Piggyback) wird damit ein eigenes Politikszenario statt eines Randwerts.

**Tests.** `ChiGateInsertionCostCalculatorTest` 17 → 23, `ChiGateDetourStatsTest` 8 → 9. Die alten
Fixturen waren **konstruktionsblind**: der Helfer legte den ganzen `totalTimeLoss` auf das
Pickup-Bein und 0 auf das Dropoff-Bein, in dieser Lage sind Summen- und Bein-Form identisch — die
Suite war während des gesamten Defekts grün. Jetzt buchstabiert jede Fixtur beide Beine aus, und drei
Regressionen sind so gelegt, dass die beiden Formen sich im **Urteil** unterscheiden, nicht nur in
einer Zahl (n=1: 100 s statt 70 s, blockiert bei χ=90 · n=13: 300 s statt 0 s, blockiert bei χ=200 ·
Spiegelfall mit mitgenommener Zustellung: 400 s statt 0 s). Eine Fixtur mit vertauschten
Bein-Konstanten fällt auf (60 s statt 0 s), eine weitere pinnt den 60-s-Boden: ein solo
eingefügtes Ein-Paket-Segment ohne Fahrumweg muss bei χ=0 **durchgehen** — ohne Boden läse es
30 s Umweg und würde abgelehnt.

**Offen, aus derselben Messung:** `chid600w21` brauchte 7,0 h, `chid600det` mit identischer 1c-Config
**11,9 h** (+70 %) — Kandidaten: das `recordEvaluation` im DRT-Hot-Path (11,85 Mio.
`ConcurrentHashMap`-Zugriffe aus den *parallelen* Insertion-Providern; der Kommentar dort adressiert
CAS-Schreibvorgänge, nicht Map-Contention), REGRET_INSERTION (§2.34, +9,2 %), Codedrift seit
2026-07-31. Über 24 Pflichtläufe sind das ~120 h, der Diskriminator kostet ~1 h.

Verwandt: §2.3 (χ als untere Schranke — richtige Richtung, aber die dort genannte Ursache
„Teil-Piggyback" war nur die halbe: nicht der gemessene Umweg war unvollständig, sondern die
Verrechnung), §2.31 (das Instrument, das diesen Defekt überhaupt sichtbar gemacht hat),
§2.24 Punkt 4 (`calculate()` je Kandidat, nicht je Entscheidung).

---

### 2.36 Tourdauer hat ein Innenoptimum bei 3,5 h — die Fracht wird effizienter, das System nicht

> ⚠️ **Vorbehalt, nachgetragen 2026-08-18: §2.36–§2.38 stehen auf der ALTEN, providergebundenen
> Depotlogik.** Am 2026-08-17 wurde die Umstellung auf bezirksscharfe Depotzuordnung für 1c/1d
> beschlossen (Spec/Plan `docs/superpowers/{specs,plans}/2026-08-17-integrated-district-depot-assignment*`,
> beide noch ungetrackt) — mit der ausdrücklichen Konsequenz, dass **alle bisherigen
> 1c/1d-Kalibrierläufe hinfällig sind**. Alle Läufe dieser drei Abschnitte (`b120rg`, `f150t015`,
> `f150d40`, `f150d45`, `f150d70`) sind davor entstanden.
>
> **Was das trifft:** jede Niveauzahl — die +7,5 % des besten Punktes, €/Paket, €/Fahrt, und
> besonders die 1.187,93 km Leerfahrt, denn genau dort greift die Umstellung an (Ø Depotdistanz je
> Paket 6,63 km → 1,92 km). Der mit 21 % der Lücke bezifferte Leerfahrt-Kanal wird sich deutlich
> verkleinern; die Richtung ist **zugunsten von 1d**, die Aussage „1d verliert in jeder
> Cap-Einstellung" ist damit **nicht** auf die neue Depotlogik übertragbar.
>
> **Was voraussichtlich trägt**, weil es Mechanismus und Messgüte betrifft, nicht Niveau: die
> Identität Cap ↔ Tourenzahl (7 h ⇒ 41 Touren = Baseline), die Beobachtung, dass der Frachtkanal
> glatt und die Pax-Seite rauh ist, und der Befund, dass die Rauheit der Kostenfläche so groß ist
> wie die verglichenen Differenzen. Das ist vor einer Wiederverwendung zu prüfen, nicht zu
> unterstellen. Die Baseline bleibt laut Beschluss bewusst providergebunden und wird **nicht** neu
> gerechnet — der Headline-Vergleich enthält danach Konsolidierung und Integration gemeinsam.


`trägt` · 2026-08-17 · **Der dritte und letzte der drei 1d-Hebel ist vermessen; keiner schließt die Lücke.**

Alle Läufe `fleetSize=150, idleThreshold=0.15, maxIter=150, jspritIter=100`, gegen die
nachgezogene REGRET-Baseline `b120rg` (87.046,09 €/Tag, 9.076 Fahrten). Kosten post-hoc nach
`cost_parameters.csv` v0.7-draft (Overhead = 0), **nicht** nach jsprits interner Zielfunktion:

| Cap | Touren | Swaps | Leerfahrt km | Service km | Fracht-h | Fahrten | vs Ziel | €/Tag | vs Baseline |
|---|---|---|---|---|---|---|---|---|---|
| 2,5 h | 134 | 268 | 1.809 | 4.064 | 377,9 | 8.797 | −3,07 % ✗ | 94.973,65 | +9,1 % |
| **3,5 h** | 89 | 178 | 1.188 | 3.259 | 331,4 | 9.070 | −0,07 % ✓ | **93.570,57** | **+7,5 %** |
| 4,5 h | 65 | 130 | 846 | 2.893 | 307,8 | 9.005 | −0,78 % ✓ | 94.182,63 | +8,2 % |

**Der Mechanismus ist der Befund, nicht die Zahl.** Von 3,5 h auf 4,5 h verbessert sich *jeder*
Frachtkanal deutlich: Touren, Swaps und Retooling −27,0 %, Leerfahrt −28,8 %, Servicekilometer
−11,2 %, Fracht-Fahrzeugstunden −7,1 % (−23,6 h). Trotzdem steigen die Systemkosten um 612 €/Tag,
weil die **Pax-Seite** verliert: `drt_tour_hours_total_pax` +2,2 % (1.991 → 2.036 h),
`drt_wait_hours_total` +9,9 % (481 → 529 h), Fahrten −0,7 %, Ablehnungen +31,0 %. Die 23,6
gesparten Frachtstunden werden von 44,7 zusätzlichen Fahrgaststunden mehr als aufgefressen.
Deutung: 65 *lange* Fahrzeugsperren zerreißen den Fahrgastbetrieb stärker als 89 *kurze* — ein
Fahrzeug, das 4,5 h weg ist, fehlt über einen ganzen Nachfragepeak. **Tourlänge ist im modularen
Konzept kein Fracht-Optimierungsproblem, sondern ein Zielkonflikt zwischen Bündelungsvorteil und
Flottenverfügbarkeit.** Das ist die verallgemeinerbare Aussage; die 3,5 h selbst sind
netz- und nachfragespezifisch.

**⚠️ Der 4,0-h-Lauf hat die Glattheitsannahme widerlegt — und damit die innere Rangfolge
(2026-08-17 abends).** Die oben notierte Vorhersage war **93.624,71 €/Tag**; gemessen wurden
**94.256,91 €/Tag** — daneben um **+632 €/Tag (+0,68 %)**. Der Punkt liegt damit nicht zwischen
3,5 h und 4,5 h, sondern **über beiden**:

| Cap | Touren | Swaps | tour_h | veh_km | Fahrten | vs Ziel | €/Tag | vs Baseline |
|---|---|---|---|---|---|---|---|---|
| 2,5 h | 134 | 268 | 2.362,2 | 53.509 | 8.797 | −3,07 % ✗ | 94.999,72 | +9,10 % |
| **3,5 h** | 89 | 178 | 2.322,6 | 53.178 | 9.070 | −0,07 % ✓ | **93.596,42** | **+7,49 %** |
| 4,0 h | 75 | 150 | 2.351,6 | 51.857 | 8.957 | −1,31 % ✗ | 94.256,91 | +8,25 % |
| 4,5 h | 65 | 130 | 2.343,6 | 52.786 | 9.005 | −0,78 % ✓ | 94.208,38 | +8,19 % |

**Der Fehlschlag ist das Ergebnis.** Die Abweichung der Vorhersage (632 €/Tag) ist praktisch
**gleich groß wie der Unterschied, den ich rangiere** (3,5 h ↔ 4,5 h: 612 €/Tag). Die Rauheit der
Fläche entspricht also der Effektgröße — der Vorbehalt von heute Mittag ist damit nicht nur
bestätigt, sondern **verschärft**: es geht nicht bloß um die genaue Scheitellage, die Fläche ist auf
der Skala der berichteten Differenzen messbar unglatt und nicht konvex (4,0 h liegt über 4,5 h).
Bestätigend: `tour_h` ist **nicht monoton** (2.362 / 2.323 / 2.352 / 2.344) und `veh_km` auch nicht,
während die Tourenzahl streng monoton fällt (134 / 89 / 75 / 65). Die Fracht-Seite reagiert glatt
auf den Cap, die **Systemantwort** nicht. Der 4,0-h-Punkt fällt zudem mit −1,31 % Fahrten durch die
1-%-Zulässigkeitsregel, 4,5 h mit −0,78 % nicht — auch das nicht monoton.

**Was danach noch trägt:** (a) 2,5 h ist eindeutig schlechter (1,6 pp ≈ 4 × Rauheit); (b) 3,5 h ist
der billigste **und** zulässige der vier gemessenen Punkte; (c) der Mechanismus der
Vorzeichenumkehr, weil die Kanalbewegungen zwischen 3,5 h und 4,5 h (Fracht −23,6 h, Pax +44,7 h)
größer sind als die Rauheit von ~19 Lohnstunden. **Was nicht trägt:** jede Aussage über die
Scheitellage innerhalb [3,5 h; 4,5 h] und das Vorzeichen des Netto-Effekts zwischen benachbarten
Punkten. Für eine belastbare Kurve braucht es ≥3 Seeds je Cap → BACKLOG `[H]`
Multi-Run-Aggregation. **Konsequenz für die Formulierung:** „3,5 h ist das Innenoptimum" ist als
Punktaussage zurückzuziehen; tragfähig ist „der operative Bereich liegt bei 3,5–4,5 h, unterhalb
davon wird es klar teurer".

_Hinweis zur Rekonziliation:_ die absoluten €-Werte liegen ~29 €/Tag (0,03 %) über den vormittags
notierten, weil der Van-Mix der Baseline jetzt aus deren **eigener** `kpis_provider.csv` gelesen
wird (5 × size_s / 30 × size_m / 6 × size_l, Präfix `vtype:`) statt aus einem Vorlauf. Alle
Relativaussagen ändern sich um ≤0,01 pp.

**Bilanz aller drei Hebel** — bester zulässiger 1d-Punkt bleibt **+7,5 %**:

| Hebel | Bestwert | Spielraum |
|---|---|---|
| θ (Reserve-Gate) | 0,15 | 1,7 pp, Optimum erreicht |
| Flottengröße | 150 (zulässig ab ~149) | ~0,7 pp |
| Tourdauer | 3,5 h (Bereich 3,5–4,5 h) | 0 — 3,5 h war bereits der beste Punkt |

**Interpolation als Vorhersage, nicht als Fit — und sie ist gefallen.** Die exakte Parabel durch
die drei Punkte (Krümmung +1.007,57 €/Tag·h⁻², Steigung bei 3,5 h −395,51 €/Tag·h⁻¹) legte den
Scheitel auf **3,696 h** und sagte für 4,0 h **93.624,71 €/Tag** voraus. Die Zahl war notiert,
**bevor** der Lauf um 08:40 startete — er war damit ein Test der Glattheitsannahme, keine
Bestätigung. **Er ist durchgefallen** (Messung 94.256,91 €/Tag, +632 €/Tag daneben) → nächster
Block. Die Parabel-Deutung ist damit zurückgezogen; sie bleibt hier nur als Protokoll dessen
stehen, was vorhergesagt und dann widerlegt wurde.

Verwandt: §2.34 (REGRET_INSERTION, auf dem diese Baseline steht), §2.38 (die Cap-Asymmetrie, die
diesen Sweep in ein anderes Licht rückt), §2.33 (jsprit optimiert *nicht* gegen diese Kostenfunktion).

---

### 2.37 Das Schichtfenster ist kein Integrationshebel — es skaliert beide Arme fast gleich

`trägt` · 2026-08-17 · **Ohne neuen Lauf entschieden. Enthält eine Korrektur an meiner eigenen früheren Rahmung.**

Der als „fünfter Hebel" vorgemerkte Kandidat war das DVRP-Servicefenster der DRT-Fahrzeuge
(`t_0=0, t_1=86400` in der Flottendatei). Die Frage ließ sich vollständig aus vorhandenen
Läufen beantworten, weil die **Definition der bepreisten Größe** sie entscheidet.

**Was die Kostenfunktion tatsächlich bepreist.** `drt_tour_hours_total` = `tour_s = sum_active`
mit `active = last_prod[v] − first_prod[v]`
([drt_service_time.py:396](../parcel-demand-2-matsim-pipeline/analysis/drt-headline/drt_service_time.py#L396)) —
die **aktive Spanne** vom ersten produktiven Task bis zum letzten, *nicht* das Schichtfenster
`t_1 − t_0`. Ein engeres Fenster kann die Rechnung also nur dort senken, wo es diese Spanne an
den **Rändern** abschneidet.

**Korrektur.** Meine frühere Formulierung, der fünfte Hebel liege dort, „wo die 481 Leerlaufstunden
mit 16.100 €/Tag sitzen", war im Mechanismus falsch. Diese 481 h sind
`waiting_s = tour_s − drive − stop − freight`, also Leerlauf **innerhalb** der Spanne, mitten am
Tag. Kein Schichtfenster erreicht sie. Erreichbar sind nur die Randstunden, und die sind klein.

**Gemessen** (Event-Rekonstruktion, reproduziert die publizierten KPIs exakt: 2.322,55 h und
481,288 h für `f150t015`; 1.908,70 h / 390,68 h für `b120rg`):

| Fenster | Baseline €/Tag | 1d €/Tag | neue Lücke | Fahrten in den Rändern |
|---|---|---|---|---|
| 00:00–24:00 (Status quo) | 87.046 | 93.571 | **+7,50 %** | — |
| 05:00–23:00 | 84.426 | 90.651 | +7,37 % | 342 (3,8 %) |
| 06:00–22:00 | 80.671 | 86.574 | +7,32 % | 870 (9,6 %) |
| 07:00–21:00 | 75.173 | 80.324 | +6,85 % | 1.795 (19,8 %) |

**Warum der Hebel tot ist.** Das Fenster ist ein **Szenario-Designparameter beider Arme**, kein
1d-Merkmal: die Baseline-DRT-Flotte hat dasselbe 0–24-Fenster und verliert beim Kürzen
dasselbe. Pro Fahrzeug trägt die Baseline sogar *mehr* Randzeit (0,653 h) als 1d (0,602 h) — ein
engeres Fenster hilft ihr also relativ geringfügig **mehr**. Selbst ein brutales 14-h-Fenster
bewegt die Lücke nur um 0,65 pp und vernichtet dafür ein Fünftel aller Fahrten; es fiele durch
die 1-%-Zulässigkeitsregel um das Zwanzigfache. Die Randfahrten sind zudem **nicht
umverteilbar**: außerhalb des Fensters darf kein Fahrzeug fahren, sie entfallen ersatzlos.

**Was bleibt.** Der Mehr-Leerlauf von 1d (481 h vs 391 h, +90 h ≙ 3.022 €/Tag) ist pro Fahrzeug
praktisch identisch (3,32 vs 3,26 h) — er ist ein reiner **Flottengrößeneffekt** der 150 statt
120 Fahrzeuge, also bereits der Preis der Fracht-Verdrängung und kein eigener Hebel. Die
Zahlen der Tabelle sind Obergrenzen (reine Spannenkürzung, ohne Rückwirkung des Umplanens).

Verwandt: §2.36 (die drei vermessenen Hebel), §3.10 (das *Liefer*-Fenster — anderer Parameter,
gleiche Lehre: ein Fenster, das nie bindet, erklärt nichts).

---

### 2.38 Der Vergleich ist in der Tourdauer asymmetrisch: Baseline 7 h, 1d 3,5 h

`trägt` · 2026-08-17 · **Modellannahmen-Audit. Eine Asymmetrie gefunden, drei Verdächtige entlastet.**

Anlass war die Frage, ob die schwache 1d-Performance am Modell statt am Konzept liegt.
Geprüft: Höchstgeschwindigkeit, Kapsel-Wechselzeit, Hub-Positionierung, Servicezeiten — plus
das, was dabei auffiel.

**Entlastet (alle drei symmetrisch zwischen den Armen):**
- **Geschwindigkeit** — `maximumVelocity` der Vans steht bei 140/160 km/h, die DRT-Fahrzeuge
  fahren Netz-Freespeed; der Autonomie-Cap (`autonomousMaxSpeedKmh = 30`) ist inaktiv, weil die
  Läufe `conventional` sind. Kein Cap bindet unterhalb des Freespeeds → Wirkung 0 €/Tag.
  *(Nebenbefund ohne Wirkung: size_m/size_s haben mit 160 km/h einen **höheren** Cap als size_l
  mit 140 — inhaltlich verdreht, aber folgenlos, da beide nie binden.)*
- **Hub-Positionierung** — beide Arme laden dieselben Depots aus derselben Quelle
  (`LmdDepotLoader.load(depotCsv, network)`, je LSP ein synthetisches Depot). Kein Bias.
- **Servicezeiten** — `DURATION_PER_PARCEL_MIN = 2`, `MAX_DURATION_PER_STOP_MIN = 15`, in
  beiden Armen dieselben Konstanten.
- **Kapsel-Wechselzeit** — `Modular.RETOOLING_S = 420` s (Spec §6.1), 178 Swaps = 20,77 h =
  **695 €/Tag = 10,6 % der Lücke**. Real, aber selbst ein *kostenloser* Wechsel ließe +6,7 %
  stehen. Kein Erklärer.

**Die Asymmetrie.** Der LMD-Baseline-Arm routet mit `HAGRIDRouterUtils.MAXROUTEDURATION = 25200`
(**7 h**), der modulare Arm mit dem CLI-`maxTourDuration` (**3,5 h** Default) —
[LausitzFreightPreprocessor.java:163](../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java#L163)
vs. [:225](../parcel-demand-2-matsim-pipeline/src/main/java/hagrid/integrated/freight/LausitzFreightPreprocessor.java#L225),
wo der Kommentar es selbst benennt: *„capped at the Modular tour duration (not the 7h shift)"*.
Bei identisch 6.052 Paketen:

| | Baseline LMD | 1d modular | Verhältnis |
|---|---|---|---|
| Touren | 41 | 89 | 2,17 |
| Fracht-km | 2.702 | 4.447 | 1,65 |
| Fracht-h | 266,8 | 331,4 | 1,24 |
| Pakete je Tour | 147,6 | 68,0 | 0,46 |
| **Tourdauer-Cap** | **7 h** | **3,5 h** | **0,50** |

Die Tourenzahl skaliert fast exakt invers mit dem Cap (Tour×Cap: 335 / 312 / 293 bei 2,5 / 3,5 /
4,5 h), was bei 7 h auf **~45 Touren** extrapoliert — die 41 der Baseline. **Der Tourenzahl-Unterschied
*ist* der Cap-Unterschied**, nicht das Konzept. Bestätigend: die Kapsel ist mit 68 von 216 Paketen
nur zu **31 %** gefüllt, während die Vans 148 Pakete gegen 100/165/230 tragen — die Kapazität bindet
nicht, die Dauer bindet (mittlere Tour 3,490 h gegen 3,500 h Cap). Und jsprit bestraft im 1d-Arm
jede Tour mit 189,15 €/Tag (Kostenspender = größter Van, `ModularVehicleTypes`) und kommt
*trotzdem* auf 89 Touren: der Cap bindet hart, es ist kein Heuristik-Artefakt.

**Ist die Asymmetrie ein Defekt?** Nein — sie ist verteidigbar: ein Kapselfahrzeug ist ein
DRT-Fahrzeug auf Exkursion und kann nicht 7 h fehlen, das ist der Kern des Konzepts. Aber die
3,5 h sind ein **gesetzter Parameter**, kein physikalischer Zwang, und der Konkurrent bekommt das
Doppelte. Solange das so steht, misst der Vergleich Konzept **und** Cap gemeinsam.

**Konsequenz — Revision einer eigenen Aussage.** Nach §2.36 hatte ich den 7-h-Kontrollarm für
erledigt erklärt („das Optimum ist eingeklammert"). Als *Optimierungsfrage* stimmt das weiterhin.
Als **Symmetriefrage** ist er jetzt der wichtigste offene Lauf: er ist die einzige Konfiguration,
in der 1d und Baseline dieselbe Freiheit haben. Die Erwartung aus §2.36 ist, dass er *schlechter*
ausfällt als 3,5 h (die Pax-Seite bestraft lange Sperren) — dann wäre die Aussage sauber und
stärker als heute: *auch bei voller Cap-Parität verliert das modulare Konzept.* Fällt er besser
aus, ist der bisherige 1d-Nachteil teilweise ein Parameter-Artefakt und die drei Sweeps sind auf
der falschen Achse gefahren worden.

**Gewichtung der übrigen Kanäle** gegen die Lücke von 6.524 €/Tag (Obergrenzen, Leerfahrt-Stunden
mit der mittleren Frachtgeschwindigkeit 36,5 km/h approximiert):

| Kanal | €/Tag | Anteil an der Lücke |
|---|---|---|
| Fracht-Stunden zum DRT-Lohnaufschlag (33,45 statt 28,99 €/h) | 1.478 | 22,7 % |
| davon allein Türstandzeit 188,68 h | 842 | 12,9 % |
| Leerfahrt 1.187,93 km (32,6 h + km-Kosten) | 1.369 | 21,0 % |
| Retooling 20,77 h | 695 | 10,6 % |

Die Leerfahrt ist strukturell: der Baseline-Van *startet* im Depot, das 1d-Fahrzeug muss erst
hinfahren. Sie ist über die Depotlage reduzierbar, aber nicht abschaffbar — und eine Depot-Verlegung
wäre eine Änderung der **Eingangsdaten**, kein Parameter-Sweep.

**✅ AUFGELÖST 2026-08-18 — der 7-h-Kontrollarm ist gefahren (`f150d70`).** Ergebnis in einem Satz:
**die Cap-Asymmetrie erklärt die Tourenzahl vollständig und den Frachtpreis zu zwei Dritteln, aber
sie ist nicht der Grund, warum 1d verliert.**

Bei 7 h produziert 1d **exakt 41 Touren** — die Zahl der LMD-Baseline. Die Extrapolation in §2.36
sagte ~45; gemessen sind es 41, die Vorhersage war also konservativ und die These „der
Tourenzahl-Unterschied *ist* der Cap-Unterschied" ist bestätigt, nicht nur plausibel.

M11-Marginalzurechnung (Fracht zahlt eigene Fahrzeugstunden + eigene km, Pax den Rest samt
Fixblock), alle 1d-Punkte gegen `b120rg`:

| Arm | Cap | Touren | System €/Tag | €/Paket | vs Basis | €/Fahrt | vs Basis | Fahrten vs Ziel |
|---|---|---|---|---|---|---|---|---|
| Baseline | 7,0 h | 41 | 87.073 | 1,497 | — | 8,596 | — | — |
| 1d | 3,5 h | 89 | 93.595 | 2,004 | +33,9 % | 8,982 | +4,5 % | −0,07 % |
| 1d | 4,0 h | 75 | 94.256 | 1,916 | +28,0 % | 9,228 | +7,4 % | −1,31 % |
| 1d | 4,5 h | 65 | 94.207 | 1,847 | +23,4 % | 9,221 | +7,3 % | −0,78 % |
| 1d | 7,0 h | 41 | 95.327 | 1,685 | **+12,6 %** | 9,031 | +5,1 % | **+3,87 %** |

**Drei Befunde, und der dritte ist der wichtige.**

1. **Der Frachtkanal ist sauber und monoton**: der Aufschlag je Paket fällt über alle vier Caps
   gleichmäßig (+33,9 → +28,0 → +23,4 → +12,6 %). Anders als die Systemsumme (§2.36) zeigt er
   **keine** Rauheit — die Unglattheit der Gesamtkosten sitzt also nachweislich auf der Pax-Seite,
   nicht in jsprits Tourenplanung. Das ist ein eigenständiges methodisches Ergebnis: es sagt, wo
   Seed-Bänder gebraucht werden und wo nicht.
2. **Auch bei voller Cap-Parität bleibt Fracht +12,6 % teurer je Paket.** Der Cap erklärt also gut
   zwei Drittel des Frachtaufschlags, aber nicht alles; der Rest ist die Konzeptlast (Anfahrt zur
   Kapsel, Retooling, DRT-Lohnsatz statt LMD-Lohnsatz).
3. **Die Pax-Seite verbessert sich nie.** Über alle Caps kostet eine Fahrt 4,5–7,4 % mehr als in
   der Baseline, ohne Trend, der sich schlösse. **Das ist der irreduzible Teil** — die Verdrängung
   von Fahrgastbetrieb durch Frachtexkursionen lässt sich über die Tourdauer nicht wegstellen.

**Korrektur an meiner Erwartung.** In §2.36 stand, der 7-h-Lauf werde *schlechter* ausfallen und
damit die saubere Aussage „auch bei Cap-Parität verliert 1d" liefern. Die Systemsumme ist
tatsächlich am höchsten (+9,48 %) — **aber aus dem falschen Grund**: `f150d70` bedient **3,87 %
mehr Fahrten** als die Baseline (9.427 gegen 9.076), die Summe mischt also „kostet mehr" mit
„leistet mehr". Genau deshalb steht hier die M11-Zerlegung und nicht die Systemsumme. Die
Schlussfolgerung hält, ihre Begründung ist eine andere als erwartet.

**Direkt anschließendes Experiment (offen).** Der 7-h-Arm überliefert Fahrleistung, weil dieselben
150 Fahrzeuge seltener und kürzer für Fracht wegfallen. Eine faire Cap-Parität bräuchte bei 7 h
eine **kleinere Flotte**, die 9.076 Fahrten gerade trifft. Die Ersparnis ist **nicht** aus diesen
Daten hochzurechnen: der Flottensweep (§2.36) hat gezeigt, dass die Fahrzeugstunden *nicht* mit der
Flottengröße skalieren (150→140 ergab +0,7 %, 140→130 dann −13,1 %). Das ist zu messen, nicht zu
schätzen → BACKLOG.

Verwandt: §2.36 (die Sweeps, die unter dieser Asymmetrie gefahren wurden), §2.33 (jsprits
Zielfunktion bepreist keine Zeit — die Touren sind also *nicht* für die Größe optimiert, mit der
sie hier bewertet werden), §2.5 (Zustellquote/Overlay).

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
gemessen und davon unberührt). Zurückgezogen ist allein die **Kostenzahl**.

**Annotation 2026-07-30 (Abwägung erledigt, anderes Ergebnis als erwartet):** die Abwägung
Hochlauf ↔ Multi-Run musste gar nicht über Kosten entschieden werden. Die Sonde zeigt, dass 1000
Iterationen die km-Streuung **nicht kollabieren** (7,61 % gegen 6,5 % bei 100, §2.2) — der
Iterations-Hochlauf ist also nicht „zu teuer", sondern **wirkungslos**. Das ersetzt die
zurückgezogene Begründung durch eine stärkere und billiger zu verteidigende. Nebenbei war auch mein
Ersatzwert (≤10,7 h je Arm) zu optimistisch: gemessen ~5,0 h für den größten Carrier **allein**.
**Lehre, zweifach bestätigt:** Extrapolation über ungleichgroße Einheiten nie linear; eine
abgebrochene Messung nie als Kostenzahl zitieren ohne den Abbruch mitzuführen; und wenn eine
Entscheidung an einer Zahl hängt, die nie gemessen wurde, ist die Messung billiger als die
Debatte.

### 3.9 „Die Emissionszurechnung Fracht ↔ Pax lässt sich über die Verbunddifferenz gegen einen Pax-only-Zwillingslauf lösen"

Zurückgezogen 2026-07-31, **am selben Tag wie vorgeschlagen** (vorgeschlagen in `a685f7f` als
Option A samt Shapley-Aufbau B, verworfen nach Messung).

**Was behauptet war:** einen Lauf ohne Fracht bei identischem Seed fahren; die Differenz der
DRT-Fahrzeug-km *ist* der frachtverursachte Mehraufwand — exakt, ohne Aufteilungsannahme, ohne
Java-Eingriff. Darauf aufbauend eine Zwei-Spieler-Shapley-Aufteilung aus `v(beide)`, `v(pax)`,
`v(fracht)`, die sich exakt auf die Systemsumme summiert.

**Was die Messung zeigt** (`drt_vehicle_stats_drt.csv`, Iteration 150, Tabelle in §2.25):

1. **Falsches Vorzeichen.** Die DRT-Gesamtdistanz *sinkt* mit steigender Frachtmenge — `m1d010`
   (5.894 Pakete) liegt 3,6 % **unter** `ctrl1d` (0 Pakete). Fracht fahren kostet km; eine
   Differenz mit negativem Vorzeichen kann kein Mehraufwand sein.
2. **Die Differenz liegt im Rauschband.** `m1d050` stellt **ein** Paket zu und liegt trotzdem
   3,4 % unter `ctrl1d`. Das Rauschband auf der Gesamtdistanz ist damit mindestens ±3,5 % — die
   −3,6 % von `m1d010` sind vollständig darin. Die Differenz zweier Läufe misst hier den
   Replanning-Drift, nicht den Effekt.
3. **Die Spieler sind nicht fest.** Ursache von (1) und (2) ist die Pax-Verdrängung (§2.25):
   `m1d010` bedient 15,4 % weniger Pax-Distanz als `ctrl1d`. Shapley setzt feste Spieler voraus;
   wenn „Pax" zwischen `v(pax)` und `v(beide)` um 15 % schrumpft, ist es nicht derselbe Spieler.
   B rechnet dann, ohne etwas zu bedeuten.

**Was bleibt:** die Kostenkritik an A war *nicht* der Grund — sie war sogar unbegründet. Das
Kontrafaktum „gar keine Fracht" hängt nicht am Frachtparameter, `ctrl1d` deckt den ganzen 1d-Sweep
ab und der χ<0-Pfad (`ChiGateInsertionCostCalculator`, „hard-closed mode") den ganzen 1c-Sweep;
Zusatzkosten wären null gewesen. A scheitert an der Auflösung, nicht am Preis.

**Lehre:** eine Differenz zweier Simulationsläufe braucht vor der Empfehlung einen Abgleich gegen
das Rauschband — und in einem Lauf mit Replanning ist „alles außer dem Behandlungsparameter bleibt
gleich" keine Annahme, die man kostenlos haben kann. Verallgemeinert für diesen Stack: **direkt
gemessene Größen (Regimesplit, Anteile je Link, marginale Insertion) sind Differenzen zweier Läufe
vorzuziehen**, weil letztere den kompletten Replanning-Drift erben. Der 1d-Regimesplit (§1.4) war
von Anfang an die bessere Wahl; meine inkrementelle Alternative war ein Rückschritt.

### 3.10 „Das engere Baseline-Fenster (08–20 Uhr) drückte die Zustellquote — ein Teil des Integrationsvorteils wäre ein Fenster-Artefakt"

**Geglaubt** (2026-07-30, Begründung der Fenster-Vereinheitlichung, §1.2): weniger Zustellzeit ⇒
systematisch niedrigere Baseline-Zustellquote ⇒ die Vereinheitlichung korrigiere einen realen
Bias zu Lasten der Baseline.
**Gemessen** (2026-07-31): base10c (08–20 Uhr) 93,47 % vs. basew21 (07:30–21 Uhr) 93,61 % —
+0,14 pp, innerhalb der 0,53-%-Demand-Drift (§2.30); `unassignedParcels=0` in **beiden**
Läufen. Das alte Fenster hat jsprit **nie gebunden** — die Baseline-Zustellquote besteht
vollständig aus dem kosmetischen Not-at-home-Overlay (M10), das vom Fenster unabhängig ist.
**Was bleibt:** die 21:00-Vereinheitlichung selbst (§1.2) — als Design-Hygiene und
Versprechens-Vergleichbarkeit (drei Lieferversprechen → eines, §2.21), nicht als
Bias-Korrektur. Formulierungen wie „verzerrte den Zustellquoten-Vergleich zu Lasten der
Baseline" sind zurückgezogen; alte Baseline-Läufe sind wegen des Fensters *nicht* falsch,
wohl aber wegen Versprechens-Vergleichbarkeit und Demand-Stand neu zu fahren gewesen.

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
