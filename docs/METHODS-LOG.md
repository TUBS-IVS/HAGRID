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
einen gibt — den Reproduktionspfad. _Zuletzt aktualisiert: 2026-07-28._

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
distanzbasierte Emissionen. Ein Iterations-Hochlauf ist teuer: `jspritIter=1000` braucht
**~4 h pro Carrier**, also ~20 h je Arm (Messung nach 2 von 7 Carriern in 5,8 h abgebrochen).
Gewählte Antwort: Multi-Run-Mittelung statt Iterations-Hochlauf (§1.5).

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

### 2.9 CV-Batterie ist noch auf OSM-Zahlen

`offen` · 2026-07-27 · Bootstrap-KI, Segment-, Cross-Carrier- und Transfer-Check im PANDA-README
sind nach dem B8-Fix **nicht** nachgezogen. Der blinde Hannover-wMAPE (9,8 %) ist neu gemessen,
der Rest der Batterie nicht.

### 2.10 `share_channel_locker` ist keine Variable

`trägt` · 2026-07-27 · `ParcelAgentGenerator.java:67` (bei Aufnahme `:59` notiert) hartcodiert
`new DeliveryChannelResolver(List.of(), 500.0)` → der Locker-Kanal ist strukturell 0. Locker
selbst ist bewusst Phase 2 (§4.5); relevant hier, weil das Javadoc etwas anderes behauptet
(BACKLOG-Punkt) und weil kein Ergebnis als „mit Locker-Anteil" gelesen werden darf.
**Annotation 2026-07-28:** das irreführende Javadoc ist korrigiert
(`integrated/shareduse/DeliveryChannelResolver.java:10-20` — nicht `integrated/freight/`, wie im
BACKLOG stand); es benennt jetzt die strukturelle 0 und dass die Aktivierung eine Codeänderung am
Aufrufer braucht, nicht bloß eine Standortdatei. Der Befund selbst trägt unverändert.

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
