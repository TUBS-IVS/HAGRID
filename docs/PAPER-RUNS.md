# Paper-ready Runs

Sammeldokument der kalibrierten Läufe, die für das Paper zitierbar sind. Ein Lauf steht hier
nur, wenn er auf dem aktuellen Codestand gefahren wurde und einem kalibrierten Betriebspunkt
entspricht. Alle Angaben sind aus der `run_metadata.json` des jeweiligen Laufs gelesen, nicht
aus Notizen rekonstruiert.

**Codestand.** Alle in den Tabellen gelisteten Läufe stammen vom Sim-PC, Branch `hendrik`,
HEAD `101e093`, JAR gebaut 2026-08-28 08:07. Ausgenommen sind die beiden im
Baseline-Abschnitt beschriebenen Determiniertheits-Duplikate (Dev `b120rg`, VM
`b120rgs_s1338`), die dort einzeln mit ihrem abweichenden Codestand ausgewiesen sind. Der Sim wird bewusst nicht gepullt, solange die
Kampagne läuft — dadurch ist der Codestand über alle Läufe hinweg identisch.

**Betriebspunkte.** Baseline: Flotte 120. 1c: Flotte 140 bei χ=900 (→ METHODS-LOG §2.57).
Beide mit `openDepots=all` und `maxJobsPerDistrict=300`, Depotkonfiguration dep7,
Even-Split-Population (SHA256 `DA17247C…` für 1c, `F76FE8A8…` für die Baseline).

---

## Baseline (`DRT_BASELINE`)

| Run-Tag | Seed | Flotte | PC | MATSim-Iter | jsprit-Iter | Pax-Fahrten |
|---|---|---|---|---|---|---|
| `b120rgs` | 1337 | 120 | Sim | 150 | 100 | 9.076 |
| `b120rgs` | 1337 | 120 | Sim | 250 | 100 | 9.143 |
| `b120rgs_s1338` | 1338 | 120 | Sim | 250 | 100 | 9.033 |
| `b120rgs_s1339` | 1339 | 120 | Sim | 250 | 100 | 8.990 |
| `b120rgs_s1340` | 1340 | 120 | Sim | 250 | 100 | 8.937 |
| `b120rgs_s1341` | 1341 | 120 | Sim | 250 | 100 | 9.178 |

Bei 250 Iterationen liegt damit n=5 vor: 9.056,2 ± 101,9 Fahrten.

⚠️ **Diese Läufe stehen unter Vorbehalt: `jspritIter=100` gegen den nominellen Goldstandard
1000.** Der Vorbehalt ist aber ein anderer, als die Zahl vermuten lässt — und schließt sich
nicht dadurch, dass man 1000 fährt:

- **Ein Hochlauf auf 1000 senkt die Streuung nicht** (§2.2, gemessen 2026-07-30). Die Sonde
  über drei jsprit-Seeds bei `jspritIter=1000` fand **7,61 % km-Spanne bei 0,00 %
  Tourenspanne** — also nicht weniger als der 6,5-%-Rauschboden bei 100. Die Fahrzeugzahl
  konvergiert, die Tourengeometrie nicht, weil die Zielfunktion fixkostendominiert ist,
  Distanz ein schwacher Term bleibt und Zeit **gar keiner** ist (`costsPerSecond = 0`). Der
  Solver hat schlicht kein Signal, eine Tour zu verkürzen. §3.8 zieht die alte
  Laufzeitbegründung zurück: verworfen wurde der Hochlauf, weil er **nicht wirkt**, nicht
  weil er teuer ist.
- **Der eigentliche Vorbehalt ist die ungezogene Stichprobe.** Die Frachtseite ist in allen
  sechs Läufen **bitgleich**: 41 Touren, 2.701,54 km, 41 Fahrzeuge. Das sieht nach
  Determiniertheit aus, ist aber **eine einzige jsprit-Ziehung, fünfmal wiederholt** — jsprit
  hängt an seinem eigenen festen Seed, nicht am Mobsim-Seed, den der Fächer variiert. Die
  ~7 % Geometriestreuung sind damit überhaupt nicht abgetastet. `freight_vehicle_km` und die
  daraus abgeleiteten 732 kg CO₂e sind **ein Punkt aus einer Verteilung mit rund 7 % Breite**,
  nicht ihr Mittel. Gemessen wurde die 7,61 % an **einem** Carrier; wie stark sich das über
  sieben Carrier herausmittelt, ist nicht gemessen — die Obergrenze steht, das Mittel nicht.
- Was daraus folgt: die Frachtseite braucht einen **jsprit-Seed-Fächer**
  (`-Dhagrid.jsprit.seed`), keinen Iterations-Hochlauf. Solange der fehlt, ist jede Aussage,
  die auf `freight_vehicle_km` oder der Frachtemission ruht, mit ~7 % Unsicherheit behaftet,
  die in keiner Fehlerangabe dieses Dokuments steckt.
- Die CO₂e-Werte der Frachtseite schwanken trotz identischer km leicht (732,45–733,08 kg,
  0,09 %). Das kommt aus dem mobsim-abgeleiteten Kaltstartkanal, nicht aus der Routenplanung.
- Die **150er-Zeile** liegt zusätzlich unter dem MATSim-Goldstandard von 250 und ist nur als
  gepaarter Partner der 250er-Zeile zu lesen, nicht als eigenständiger Ergebnispunkt.

Die beiden `b120rgs`-Zeilen teilen sich den Tag, aber nicht das Ausgabeverzeichnis — die
Iterationszahl steht im Verzeichnisnamen (`_iter150_` / `_iter250_`), nicht in der runId.
Beide konsumieren dieselbe vorbereitete Population, per Hash geprüft; deshalb ist das Paar
eine saubere gepaarte Messung des Iterationseffekts (+67 Fahrten, → §3.14).

Ein config-identisches Duplikat des 150er-Laufs existiert auf dem Dev unter `b120rg`
(0 von 310 Config-Pfaden abweichend, identische 9.076 Fahrten). Es ist der Nachweis der
maschinenübergreifenden Determiniertheit und **kein zweiter Datenpunkt** — beim Mitteln nicht
doppelt zählen.

Ein zweites config-identisches Duplikat existiert seit 2026-09-09 auf der neuen **Lausitz-VM**
(`ssh lausitz`, Ubuntu 20.04 in der Proxmox-Instanz 134.169.42.108) unter demselben Tag
`b120rgs_s1338`: identische **9.033 Fahrten**, und `drt_vehicle_stats`, `drt_customer_stats`,
`drt_sharing_metrics` sowie `modestats` sind **zeilengleich zum Sim-Lauf — 0 Abweichungen bei
252 Zeilen**. Auch dieser Lauf ist **kein zweiter Datenpunkt**; n bleibt 5.

Beim Vergleich über Maschinen ist eine Falle zu beachten: zwei der vier CSVs hatten
**abweichende SHA256** und stimmten trotzdem zeilengleich überein. MATSim schreibt
`drt_customer_stats` und `drt_sharing_metrics` mit plattformabhängigem Zeilentrenner
(Sim CR=251, VM CR=0), `drt_vehicle_stats` und `modestats` fest mit `
`. Ein Hash-Vergleich
allein hätte hier fälschlich einen Determiniertheitsbruch gemeldet — Zeilenenden vorher
normalisieren.

Der VM-Lauf weicht in drei Punkten bewusst ab und ist deshalb als Determiniertheitsnachweis,
nicht als Kampagnenlauf zu lesen:

- **Codestand `7da217b`** statt `101e093`, also sechs Commits neuer (Emissions- und
  Dashboard-Arbeit). Dass die vier CSVs trotzdem zeilengleich sind, zeigt: diese Commits sind
  für die **Mobsim-Seite** der Baseline verhaltensneutral. **Für die Emissionskanäle ist das
  nicht geprüft** — das KPI-Dashboard lief auf der VM nicht (s.u.), und genau dort sitzt die
  geänderte Logik. Aus diesem Lauf folgt nichts über die Emissionswerte.
- **`-Xmx32g` statt `-Xmx48g`.** Gemessener Live-Heap nach GC (ZGC, 80 Messpunkte):
  Median 9,2 GB, p95 11,1 GB, **Max 11,6 GB**, bei praktisch null Allocation Stalls. Die 48 GB
  der Sim-Konfiguration sind damit Kopfraum, kein Bedarf. Windows-Prozesszähler taugen dafür
  nicht: ZGC mappt den Heap mehrfach, weshalb der Sim 122 GB Working Set bei `-Xmx48g`
  ausweist — nur das GC-Log ist belastbar.
- **JDK 21.0.8+9**, bitidentisch zum Sim. Java 25 baut das Projekt nicht:
  `maven-compiler-plugin 3.11.0` bricht mit
  `ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN` ab.

**Laufzeit VM: 17,37 h** gesamt (2,33 h jsprit-Vorlauf + 15,01 h für 250 Iterationen) gegen
9,14 h Iterationsphase auf dem Sim, also **Faktor 1,64**. Das ist Takt, kein Defekt:
`QEMU Virtual CPU @ 2,095 GHz` gegen i9-12900KS (3,4 GHz Basis plus Turbo) bei
single-threaded DVRP; die 64 vCPU der VM liegen dabei brach (Load 4,4). Damit liegt die VM auf
Dev-PC-Niveau (~16,5 h/Lauf) und taugt für **parallele** Seed-Fächer, nicht für Einzelläufe
auf dem kritischen Pfad. Output 3,3 GB gegen 3,32 GB auf dem Sim.

Zwei Betriebsdetails, die auf einer neuen Maschine sonst Zeit kosten: ein Lausitz-Lauf ist
**nicht** mit Repo plus Eingabedaten allein reproduzierbar — `PrepareLausitzDrtInputs` muss
vorher laufen (eigener CLI-Einstiegspunkt, gleiche Scenario-Spec, 91 s), sonst bricht der Lauf
sofort mit `Missing or stale required inputs` ab; dessen `DrtInputsFingerprint` hängt an
`Größe:mtime` der Quelldateien, ein erneuter Input-Transfer macht die Vorbereitung also stale.
Und `vmargs_lausitz.txt` liegt **nur lokal auf dem Sim-PC**, nicht im Repo.

Die KPI-Aufbereitung fehlt für diesen Lauf: `kpiDashboard=true` scheitert auf der VM mit
`Cannot run program "python"` (Ubuntu hat nur `python3`), und mit Python 3.8 gegen
`scipy==1.15.2` wäre sie ohnehin gescheitert. Der Trigger ist der letzte Schritt nach dem
MATSim-Shutdown; der Lauf selbst ist unberührt (`RUN_EXIT=0`), alle MATSim- und
Freight-Analysen sind geschrieben. Die Auswertung gehört auf den Dev-PC.

---

## 1c Shared Use (`DRT_SHAREDUSE`)

| Run-Tag | Seed | Flotte | PC | MATSim-Iter | χ [s] | Pax-Fahrten |
|---|---|---|---|---|---|---|
| `d1c_dep7_f140_chi900_evensplit` | 1337 | 140 | Sim | 150 | 900 | 9.164 |
| `d1c_f140_c900_es_s1338` | 1338 | 140 | Sim | 150 | 900 | 8.970 |
| `d1c_f140_c900_es_s1339` | 1339 | 140 | Sim | 150 | 900 | 8.954 |
| `d1c_f140_c900_es_s1340` | 1340 | 140 | Sim | 150 | 900 | 9.219 |
| `d1c_f140_c900_es_s1341` | 1341 | 140 | Sim | 150 | 900 | 8.999 |
| `d1c_f140_c900_i250_s1337` | 1337 | 140 | Sim | 250 | 900 | 9.183 |
| `d1c_f140_c900_i250_s1338` | 1338 | 140 | Sim | 250 | 900 | 9.317 |
| `d1c_f140_c900_i250_s1339` | 1339 | 140 | Sim | 250 | 900 | 9.153 |
| `d1c_f140_c900_i250_s1340` | 1340 | 140 | Sim | 250 | 900 | 9.232 |
| `d1c_f140_c900_i250_s1341` | 1341 | 140 | Sim | 250 | 900 | 9.228 |

n=5 auf beiden Iterationsstufen, dieselben fünf Seeds: 9.061,2 ± 121,6 (150) und
9.222,6 ± 62,1 (250).

Der 1337er-150er-Anker trägt aus historischen Gründen einen abweichenden Tag. Er gehört
trotzdem in denselben Fächer: gegen die `es`-Arme weicht er in genau einem Config-Pfad ab
(`randomSeed`), gegen seinen 250er-Zwilling in genau einem (`lastIteration`) — geprüft mit
`analysis/kpi/config_diff.py`.

**Die Tabelle hat bewusst keine jsprit-Spalte, weil jsprit in 1c nicht läuft.** Die Pakete
werden online per DVRP-Insertion eingefügt; `runsCarrierModules()` schließt `DRT_SHAREDUSE`
aus und `getJspritIterations()` wird auf diesem Pfad nirgends gelesen. Die Paketstopps kommen
aus `DeliveryDistrictBuilder.build(...)`, einer Bezirkseinteilung der geclippten Nachfrage,
nicht aus einer Tourenplanung. Geprüft am Konsolenlog: `algorithm starts` 0× in 1c gegen 7×
in der Baseline, `com.graphhopper.jsprit` 0× gegen 98×. Der Scenario-String enthält trotzdem
`jspritIter=100` — der Wert landet nur im Verzeichnisnamen (`..._jsprit100`), in der
`run_metadata.json` und in der Dashboard-runId. **Nicht als Einstellung zitieren.**

⚠️ **f140 ist bei 250 Iterationen nicht mehr iso-service** (+166 Fahrten gegen die Baseline,
t=3,04 → §2.65). Die 250er-Zeilen sind als Messung gültig, aber sie liegen nicht auf dem
Kalibrierungspunkt. Der Flottensweep klärt, wo der bei 250 Iterationen liegt.

---

## 1d Modular (`DRT_MODULAR`)

*Noch nicht gefüllt.* Es existieren 1d-Läufe auf dem Dev, aber ich habe nicht geprüft, welche
davon auf dem aktuellen Codestand und dem beschlossenen Depotpunkt liegen — die
Depotlogik-Umstellung vom 2026-08-17 hat einen Teil der älteren 1d-Kalibrierläufe entwertet.
Diese Tabelle bleibt bewusst leer, bis das je Lauf gegen die `run_metadata.json` und den
Codestand geprüft ist, statt sie mit Läufen zu füllen, deren Status ungeklärt ist.

---

## Bewusst nicht aufgenommen

- **χ=600-Fächer** (`d1c_f140_c600_es_s1338…s1341` plus Anker) — vollständig und sauber
  gefahren, aber χ=600 ist als Betriebspunkt verworfen: dort bleiben 331 ± 64 Pakete liegen,
  bei χ=900 keines (§2.57). Als Beleg für die χ-Wahl zitierbar, nicht als Ergebnispunkt.
- **χ-Raster 150/300/450/600/900 bei n=1** — Screening, keine Kalibrierung.
- **Alle 1c/1d-Läufe vor dem Even-Split** (Population `052FA2D9…`) — andere Nachfrage, mit
  nichts hier vergleichbar.
- **`basew21` (Dev)** — die 150er-Hälfte lief mit 14 statt 12 DRT-Threads und ist damit nicht
  paarbar (§2.59).

## Lesehinweise zu den Metadaten

- **Zwei Metadatenfelder sind je nach Szenario inert und sehen trotzdem wie Einstellungen aus:**
  `chi_threshold` in den Baseline-Metadaten (dort 600, aber es fahren keine Pakete in
  DRT-Fahrzeugen) und `jsprit_iterations` in den 1c-Metadaten (dort 100, aber jsprit läuft in
  1c nicht). Beide nicht als Setting zitieren. Bei 1d ist `jsprit_iterations` dagegen **echt** —
  dort routet `runModular(...)` die Touren, nur der `CarrierModule` entfällt.
- Die runId enthält weder Seed noch Iterationszahl. Wer einen Fächer fährt, muss den Seed in
  den Tag schreiben, sonst teilen sich alle Arme ein Ausgabeverzeichnis.
- Von einem Lauf bleiben dauerhaft nur Dashboard-HTML und die KPI-CSVs; die MATSim-Outputs
  werden nicht archiviert.

_Zuletzt aktualisiert: 2026-09-09._
