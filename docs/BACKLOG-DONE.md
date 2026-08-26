# HAGRID Backlog — Erledigt

Archiv der abgeschlossenen Backlog-Punkte, mit Nachweis (Verifikation, Commit, Branch).
Konsument: die Frage „haben wir das schon gemacht, und woran sieht man das?".

**Abgrenzung:** offene Arbeit → [BACKLOG.md](BACKLOG.md) · methodische Substanz (Entscheidungen,
Limitations, zurückgezogene Befunde) → [METHODS-LOG.md](METHODS-LOG.md). Erledigtes, das ändert
*wie eine Zahl zu lesen ist*, steht in beiden: Nachweis hier, Konsequenz dort.

Neueste zuerst. _Zuletzt aktualisiert: 2026-08-26._

---

## 2026-08-26

- **`[H]` Kaltstart-Zuschlag implementiert und gemessen.** Zuschlag
  `cold_km * ef_hot(v) * bc(ltrip) * (Q(v, ta) - 1)` in die bestehenden KPIs eingerechnet, je
  Schadstoff eine `*_coldstart_share`-Zeile. Zählung: konventioneller Freight 1 Start je Tour
  (Datenlücke `TimeDistance_perVehicle.tsv`, keine Task-Sequenz); DRT/modularer Arm 1 bei
  Schichtbeginn plus je STAY-Block ≥ 60 min mit folgender Fahrt, zugerechnet an das Regime des
  folgenden Fahrblocks. 60-min-Schwelle belegt: EPA (1994) via Reiter & Kockelman (2016),
  *Transportation Research Part D* 43, 123–132, doi:10.1016/j.trd.2015.12.012.
  Gemessen (`LMD_BASELINE_13052025_bandz_central_iter0_jsprit100`,
  `DRT_MODULAR_13052025_d1d_dep7_f130_iter150_jsprit100`): `freight_nox_coldstart_share` 5,339 %,
  `drt_nox_coldstart_share` 2,436 % (1,646 Starts/Fahrzeugtag im Mittel, min 0, max 4, n=130;
  Verteilung 0:8/1:57/2:41/3:21/4:3). Die alte Bound war „je Kaltstart" gerechnet (DRT 1,41 %) —
  richtig, aber untersetzt, kein Fehler. Regime-Invariante `drt_* + freight_modular_* ==
  total_*` hält exakt (Residuen 0,0012 g NOx, 0,026 kg CO₂e = CSV-Rundung). Nebenbefund: 8 von
  130 DRT-Fahrzeugtagen mit `n_cold=0` sind korrekt (erster Fahrblock ist FREIGHT_DRIVE, der
  Schichtbeginn-Kaltstart geht an `freight_modular`). `kpi_emissions_vehicles.csv` trägt seit
  commit `084ede3` `n_cold` und `cold_<KEY>`. Vier Limitations bleiben (L1 BEV-Arm ohne
  Kaltstart, einseitig zugunsten BEV; L2 Freight-Zählung ist selbst noch eine Untergrenze; L3
  PM-Auspuff unparametrisiert; L4 nur RANGE 1). Suite 432 → 438 Tests. Design-Spec, Herleitung,
  Zahlen: METHODS-LOG §2.29, `analysis/kpi/data/README.md` Abschnitt „Kaltstart",
  `docs/superpowers/specs/2026-08-26-coldstart-stay-analysis-design.md`. Commits bis `084ede3`,
  Branch `hendrik`.

- **`[M]` Ladefenster-Analyse für die DRT-Elektrifizierbarkeit — beantwortet, mit bindendem
  Befund.** Neue KPI `drive_block_max_km_<20|40|60>` (längster zusammenhängender Fahrblock
  zwischen zwei STAYs ≥ Fensterbreite `w`) gegen `ev_range_km_low/mid/high` gehalten; die
  Fensterbreiten sind ein Sweep, weil ein DRT-Fahrzeug jederzeit neu disponiert werden kann und
  nur die kurzen Fenster operativ verlässlich sind. Gemessen (1d-Lauf):
  `drive_block_max_km_20/40/60` = 445,5 / 548,2 / 548,2 km, monoton; alle neun
  `drive_block_exceed_<w>_<schwelle>` ungleich null, bis 96 %. Selbst im großzügigsten
  20-min-Fenster (jede STAY dieser Länge lädt voll — die optimistischste mögliche Annahme, ohne
  Ladeleistung/Batteriekapazität/Infrastruktur) liegt der längste Fahrblock bei 445,5 km gegen
  maximal angenommene 250 km Reichweite: **die geometrische Schranke ist bindend.** DRT ist in
  der jetzigen Disposition NICHT ohne Zwischenladen elektrifizierbar — das sagt nichts über eine
  anders disponierte Flotte und ist keine Aussage „DRT ist nicht elektrifizierbar" an sich.
  Ersetzt die Aussagekraft von `ev_range_exceed_drt_*` (dessen Nenner ein ganzer Fahrzeugtag inkl.
  aller Standzeit war). Per Design-Spec-Eskalationspfad (§5) öffnet ein bindender geometrischer
  Befund einen neuen Backlog-Punkt statt die Frage zu schließen: **`[H]` Energetisches
  Lademodell** (Ladeleistung, Batteriekapazität, SoC-Verlauf) in BACKLOG.md angelegt. Details:
  wie oben.

---

## 2026-08-17

- **1d-Kostenkampagne gefahren und ausgewertet — drei Hebel, sechs Läufe, alle Exit-Codes geprüft.**
  Dev-PC, `maxIter=150`, `jspritIter=100`. Neue REGRET-Baseline `b120rg` (87.046,09 €/Tag, 9.076
  Fahrten) plus `f150t010`/`f150t015` (θ), `f140t015`/`f130t015` (Flotte), `f150d25`/`f150d45`
  (Tourdauer); `f150d40` und `f150d70` liefen am 2026-08-17 nach. **Gewinner-θ = 0,15**, bester
  zulässiger Punkt `f150t015` mit +7,5 % gegen die Baseline. Zulässigkeit automatisiert in
  `devlog/decide_theta.py` (Pakete ≥ 99,9 % **und** Fahrten ≥ 99 % Baseline), damit die
  Wochenendkette nicht auf eine menschliche Entscheidung um 04:00 wartet. Ketten:
  `run_weekend_chain.bat`, `run_tourdur_chain.bat`, `run_dur40.bat`, `run_dur70_chain.bat` (alle
  mit `if errorlevel 1 goto :FAILED` statt `||`, Erfolgserkennung über `analysis/kpis_long.csv`
  statt Exit-Code — Begründung siehe BACKLOG `[M]` `writeDashboard`). Ergebnisse, Mechanismus,
  Bilanz und der Seed-Band-Vorbehalt → [METHODS-LOG](METHODS-LOG.md) §2.36–§2.38.
  ⚠️ **Betriebswissen aus dieser Kampagne, zweimal reingelaufen:** der `DrtInputsFingerprint`-Guard
  bricht jeden Lauf nach ~1 s ab, dessen Flottenparameter von den vorbereiteten Inputs abweichen
  (`prepared=8 but run wants=10`, genauso bei jeder neuen `fleetSize`) → **vor jedem
  Parameterwechsel zwingend `PrepareLausitzDrtInputs`** als eigener Schritt davor. Deshalb hat jede
  Kette dieser Session einen Prepare-Schritt je Lauf. Nebenbei damit erledigt: die
  10-Sitze-Re-Baseline ist live (`drt_vehicle_capacity=10` in `b120rg` **und** `f150t015`
  verifiziert); alte married-Runs (married120/250) fahren weiterhin cap=8.

- **Log-Selbstblockade umgangen (Workaround, nicht der Fix)** — `devlog/log4j2_dev.xml` pinnt das
  Logverzeichnis auf `hagrid-output/logs` außerhalb des Laufverzeichnisses, aktiviert über
  `-Dlog4j2.configurationFile=devlog/log4j2_dev.xml` in `vmargs_dev.txt` (separat von `vmargs.txt`,
  das die Hannover-Sweep-Konfiguration mit `-Xmx124g` trägt und unberührt bleibt). ✅ Nachweis:
  alle sechs Kampagnenläufe kamen damit durch, nachdem ein `DRT_MODULAR`-Lauf zuvor nach 19 min
  daran gestorben war. **Ursache lokalisiert:** `SimulationRunnerUtils.initLogging()` legt
  `hagrid.log.dir` korrekt außerhalb ab (`:64-72`) und `runSimulation` biegt es 220 Zeilen später
  wieder hinein (`:286-288`), wo es mit `LausitzDrtConfigurator`s `deleteDirectoryIfExists`
  (`:143`) kollidiert. Der echte Fix (zweite Zuweisung entfernen/gaten) bleibt offen → BACKLOG.
  ⚠️ Der Pfad im `@argfile` muss **relativ** bleiben: ein absoluter Pfad mit Leerzeichen wird von
  javas Argfile-Parser gesplittet und als Main-Klasse gelesen (Lauf 3 starb daran nach 0,5 s).

- **BACKLOG-Durchsicht: 30 `file:line`-Referenzen gegen den Code geprüft, 20 waren verschoben.**
  Zwei zeigten auf Zeilen, die inzwischen etwas anderes enthalten — `LausitzFreightPreprocessor:163`
  war als `DrtNetworkPreparer`-Kommentar zitiert und ist heute die `MAXROUTEDURATION`-Zeile,
  `SimulationRunnerUtils:238` lag 49 Zeilen neben dem gemeinten Exception-Swallow. Alle korrigiert;
  außerdem waren beide relativen Links im Dokument kaputt (`parcel-demand-…` statt
  `../parcel-demand-…` aus `docs/` heraus). Ein Strukturschaden repariert: der Kopftext des
  1d-Abschnitts samt Plan/Design/Spike-Links hing als Fließtext hinter einer Bullet-Liste, weil
  ein später eingefügter Punkt ihn abgeschnitten hatte.

- **`writeDashboard=true` gegen 1c/1d abgesichert — der Lügen-Exit-Code ist weg.** ✅ im
  Arbeitsbaum (`SimulationRunnerUtils.generateDashboard` + neuer `GenerateDashboardGuardTest`,
  hermetisch, noch nicht committet). Der Legacy-Dashboardpfad liest `output_carriers.xml.gz`, das
  1c/1d nie schreiben; er warf danach aus `HAGRIDSimulationRunner.main`, **nachdem** Simulation,
  KPIs und v2-Dashboard fertig waren — jeder 1c/1d-Lauf meldete Fehlschlag. ⚠️ **Der Guard ist
  bewusst zweiteilig** (`cfg.isDrtScenario() && !runsCarrierModules(...)`): allein auf
  `runsCarrierModules` zu gaten hätte auch `LMD_BASELINE` und den Hannover-`BASECASE`
  übersprungen, weil `isDrtWithFreight()` dort false ist — und genau aus deren `SUMMARY`-Blob
  liest der Hannover-Sweep. Die einzeilige Fassung, die im BACKLOG als Fix vorgeschlagen war,
  wäre also falsch gewesen; der Punkt ist damit erledigt und aus BACKLOG entfernt.

- **BACKLOG auf Roadmap-Höhe zurückgeschnitten (User-Anstoß 2026-08-17)** — 838 → 495 Zeilen. Das
  Dokument hatte seine eigene Abgrenzungsregel verletzt: 35 Bullets mit ≥8 Zeilen machten 59 % der
  Datei, ein großer Teil davon Findings-Narrative. Erledigte Punkte entfernt (sie haben ihren
  Nachweis hier), Befundtexte nach [METHODS-LOG](METHODS-LOG.md) verschoben bzw. gestrichen, wo sie
  dort schon standen (§2.2, §2.33 Punkt 5, §2.34). Neu hierher gewandert ist die beschlossene
  Hannover-Korrekturregel → METHODS-LOG §2.33.

---

## 2026-08-11

- **jsprit-Konstruktionsheuristik auf jsprits Default zurückgesetzt (`BEST_INSERTION` →
  `REGRET_INSERTION`, `HAGRIDRouterUtils:232`)** — ✅ lokal auf `hendrik`, **nicht gepusht, Sim-PC
  bewusst nicht gezogen** (Hannover läuft dort). Anlass war die Frage, warum jsprit übermäßig oft
  den 100-Paket-Van wählt, obwohl die Touren die Arbeitszeitgrenze nicht erreichen. Der Van war
  Symptom: greedy Insertion eröffnet zu viele Routen (Fahrzeug-Fixkosten sind während der Insertion
  unsichtbar), und bei ~100 Paketen je Tour ist der kleine Van dann die korrekte, billigste Wahl.
  Volle 7-Carrier-Lausitz-Route gegen `DRT_BASELINE_13052025_basew21`: **52 → 41 Touren (−21,2 %)**,
  Distanz 3.002,4 → 1.984,5 km (−33,9 %), Kosten 9.502 → 7.797 € (−17,9 %), Schichtnutzung
  79,6 → 91,7 %, Anteil `ct_cep_size_s` 60 % → 12 %; Pakete/Stops je Carrier identisch,
  `unassignedJobs=0`. **Laufzeit +9,2 %** (gepaart, beide Arme gleichzeitig auf leerer Maschine:
  11.240 s gegen 12.274 s) — die zuerst notierte Aussage „nicht langsamer" stammte aus einer
  Ein-Carrier-Messung und ist widerlegt. → Konsequenz für die Zahlen:
  [METHODS-LOG](METHODS-LOG.md) §2.34.
- **Harness gegen den echten Produktionsrun verifiziert** — ✅ der `BEST`-Arm der gepaarten Messung
  reproduziert `DRT_BASELINE_13052025_basew21` **exakt** (52 Touren, 3.002,4 km, 9.502,17 €,
  Typenmix je Carrier identisch); der `REGRET`-Arm reproduziert sich über zwei Läufe sechs Stunden
  auseinander byte-gleich. Die −21 % sind damit weder Harness-Artefakt noch Suchrauschen.
- **Ausschluss-Nachweis (Zehn-Arm-Probe auf Carrier `dpd`, 408 Services, je einvariabel)** — ✅ die
  drei naheliegenden Erklärungen sind widerlegt, nicht nur unwahrscheinlich: Kapazität 100/165/230
  ergibt **immer** 5 Touren; `FIXED_COST_PARAM=1.0` lässt 5 Touren bei −0,3 % Gesamt; alle Ruins
  über jsprits harte 50/70-Deckel hinaus vergrößert (radial 122, random 163, incl. der
  höchstgewichteten `WORST_*`/`CLUSTER_*`, die HAGRID nicht überschreibt) → weiterhin 5 Touren.
  Erst `REGRET_INSERTION` bringt 4. Der `ct_cep_size_s`-Preis ist damit als Ursache ausgeschlossen.
- **Regressionsschutz `JspritConstructionHeuristicTest`** — ✅ verhaltensbasiert, nicht
  konstanten-prüfend: 7×7-Gitter, 48 Stops, Kapazität absichtlich großzügig und Dauer-Cap 1 h
  (dauergebundenes Regime — ein kapazitätsgebundenes Fixture diskriminiert **nicht**, mitgemessen).
  4 Touren mit `REGRET`, 5 mit `BEST`. **Mutationsgeprüft**: Produktionszeile testweise
  zurückgeflippt → 1 Failure mit der Diagnosemeldung, danach wieder grün. Suite
  `hagrid.integrated.freight` 36/36 grün inkl. `LmdBaselineEndToEndTest` (84 min, echter
  End-to-End-Durchlauf mit dem Fix).

---

## 2026-07-29 _(nachgetragen 2026-08-10)_

- **χ-Gate instrumentiert (`ChiGateStats`, 3 Zähler in beide Channel-Stats-CSVs)** — ✅ Commit
  `a375df9` auf `hendrik`. Anlass: `segments_rejected_final = 0` wurde als „χ bindet nicht"
  gelesen, obwohl der Zähler ein χ-blockiertes Paket **nie** sieht (Rückkehr in die
  `ParcelOnlyRetryQueue`, Ausfall hinter dem Fenster ohne Event). Neu:
  `chi_blocked_insertion_attempts`, `chi_blocked_segments`, `segments_window_expired_chi_blocked`
  — Controller-Scope-Singleton, weil das Gate je Iteration im QSim-Child-Injector neu gebaut wird;
  Zähler teilen den Per-Iterations-Lebenszyklus des Handlers. Nachweis: Java 38/38 shareduse grün
  inkl. `SharedUseDispatchTest`-Boot, Python 251/251.
- **Verdrahtungs-Probelauf `chiwire` (χ=−1, 1 Iteration)** — ✅ beweist, dass Gate und
  KPI-Handler dasselbe Guice-Singleton sehen (ein stiller Null-Zähler wäre testgrün geblieben):
  `chi_blocked_segments` 2953 = `segments_submitted`, `segments_window_expired_chi_blocked` 2953,
  46 838 041 Blocks, 0 Zustellungen, δ = 1,0. Per-Iterations-Reset verifiziert (Iter 0
  46 680 497 ≠ Iter 1 46 838 041 statt Summe). Nebenbefund, jetzt belegt statt argumentiert:
  `segments_rejected_final` bleibt bei 100 % verhinderter Zustellung **auf 0**
  → [METHODS-LOG](METHODS-LOG.md) §2.31.
- **Instrumentierter Referenzlauf `chid600i` (χ=600, 150 Iter)** — ✅ alle Exit-Codes 0,
  `CHIGATE_DONE` 29.07. 20:48. In **allen** gemeinsamen Spalten und Iterationen **bit-identisch**
  zu `chid600` (δ = 0,1046375355767621) — die Zähler sind also nebenwirkungsfrei, und ein Rerun
  derselben Konfiguration taugt **nicht** als Reproduktionstest (§1.5). Ergebnis: das Gate ist
  aktiv (11 087 254 Blocks, alle 2953 Segmente), der Zuordnungszähler saturiert aber
  → [METHODS-LOG](METHODS-LOG.md) §2.31. Läufe selbst durch das 21:00-Fenster überholt
  (`chid600w21`), der Zählerbefund nicht.
- **Betriebsdetail (kostete ~3 h):** der erste Detached-Start über
  `Start-Process -WindowStyle Hidden` aus einer ssh-Sitzung **starb still** — Log blieb auf
  `===== COMPILE =====`, kein Prozess, leeres stderr, kein Reboot, `target/classes` unverändert.
  Kein Compile-Problem (synchron 13,7 s BUILD SUCCESS). Fix: Start über
  `Invoke-CimMethod Win32_Process Create` (hängt am WMI-Provider, nicht am ssh-Prozessbaum).
  Zweiter Fehler derselben Klasse: der Marker-Monitor schwieg 3 h, weil ein Prozess, der **vor**
  dem ersten Marker stirbt, in einem Marker-only-Filter aussieht wie „kompiliert noch" — Filter
  brauchen einen Liveness-Zweig (java-Prozess vorhanden?), nicht nur Erfolgs-/Fehlermarker.
  Nachfolgearbeit: [Remote-Crash-Alerting](superpowers/specs/2026-07-31-remote-crash-alerting-design.md).

---

## 2026-07-31

- **1d Idle-Threshold-Sweep komplett (θ = 0,1 / 0,15 / 0,2 / 0,3 / 0,4 + Kontrollen 0,5 / 1,0)** —
  ✅ alle Läufe iter150/fleet120/jsprit100 auf dem Sim-PC, Demand-Stand einheitlich 6020 Pakete /
  127 geplante Touren (gepaarter Vergleich, METHODS-LOG §2.17). Nachweis: Output-Verzeichnisse
  `DRT_MODULAR_13052025_m1d{010,015,020,030,040}_iter150_jsprit100` auf dem Sim-PC, Marker
  `SWEEP1D_DONE` (31.07. 08:01) / `SWEEP1DB_DONE` (13:22) / `SWEEP1DC_DONE` (18:36), alle
  STEP-Exit-Codes 0. In **jedem** Punkt: Erhaltungsidentitäten geschlossen, 0 Verspätungen,
  Overlay konstant 393, `unassigned_jsprit=0`. Kurve (Pakete served / Fracht-Veh-h / Pax-Rides):
  0,1 → 5894 (97,9 %)/363 h/7488 · 0,15 → 4038 (67,1 %)/262 h/8160 · 0,2 → 2261 (37,6 %)/153 h/8545 ·
  0,3 → 551 (9,2 %)/42 h/8964 · 0,4 → 9 (0,15 %)/1,3 h/8949 · 0,5 → 1 Tour · 1,0 → 0.
  Interpretation und Konsequenzen → METHODS-LOG §1.2 (neuer Sweep-Block) / §2.30.
  Betriebsdetails: θ=0,4 wurde per Sim-PC-seitigem Watcher automatisch an die Hauptkette
  angehängt (`watch_sweep1d.ps1`, LAUNCH_RC=0), θ=0,15 als Lückenschluss nach User-Go
  **bewusst vor jedem Demand-Sync** gestartet. Keine Code-Änderung, kein Commit (reine Run-Arbeit;
  Batches `run_sweep1d{,_b,_c}.bat` liegen untracked auf dem Sim-PC).

- **Baseline + 1c auf dem einheitlichen 07:30–21:00-Fenster neu gefahren (`basew21` +
  `chid600w21`)** — ✅ schließt den `[H]`-Punkt „Baseline + 1c neu fahren" (erster gültiger
  1c-Punkt; weitere 1c-Punkte = χ-Sweep). Dev-PC-Nachtkette `run_nightbc.bat`
  (Build-Gate `mvn install` grün → basew21 → chid600w21), `NIGHTBC_DONE` 31.07. 12:27, alle
  Exit-Codes 0; Outputs `DRT_BASELINE_13052025_basew21_iter150_jsprit100` +
  `DRT_SHAREDUSE_13052025_chid600w21_iter150_jsprit100` (Dev-PC) inkl. KPI-Build (85 KPIs).
  **basew21:** 7 Carrier, 6052/5665/387/0 (Demand/zugestellt/missed/unassigned) = 93,61 % netto;
  Pax 8973 Rides / 700 s / 26 Rejections — im alten Rauschband, Fensterwechsel + Demand-Drift
  pax-neutral. **chid600w21:** 6051 injiziert / 5671 zugestellt (93,72 % brutto, 31 spät) /
  338 verfallen / 42 nie submitted; alle 218 verfallenen Segmente χ-geblockt (11,68 M geblockte
  Insertion-Versuche) — ⚠️ **Annotation 2026-08-10:** daraus folgt *nicht*, dass das Gate der
  bindende Mechanismus ist; `chi_blocked_segments` = 3104 = *alle* eingereichten Segmente, also auch
  die 2884 zugestellten. Der Zähler saturiert → [METHODS-LOG](METHODS-LOG.md) §2.31.
  Pax 7326 Rides (−18,4 % vs. basew21) / 704 s / 18 Rejections.
  **Zwei Befunde daraus** (Konsequenzen im METHODS-LOG): Fenster-Bias-Verdacht widerlegt
  (§3.10 — base10c 93,47 % ↔ basew21 93,61 %, `unassigned=0` beidseits, jsprit nie gebunden)
  und Netto/Brutto-Konventionsmix Baseline↔1c aufgedeckt (§2.21, Annotation 2026-07-31 —
  93,61 ↔ 93,72 ist Mechanismus-Koinzidenz, M10-konform kostet die Integration ~6,3 pp).
  Nebenbefund mit eigenem Log-Eintrag: 0,53-%-Demand-Drift Dev↔Sim (§2.30 + neuer `[H]`-Punkt).

- **Single-Carrier-Diagnose-Artefakt-Check: entwarnt** — ✅ Frage: kann der in einer anderen
  Session benutzte Ein-Carrier-Testschalter (`-Dhagrid.jsprit.onlyCarrier`, Commit `086932a`)
  laufende/kommende Läufe kontaminieren? Befund: **nein** — der Schalter ist eine reine
  JVM-System-Property (default aus, `HAGRIDRouterUtils:98`, unbekannter Wert schlägt laut fehl,
  Nutzung loggt `DIAGNOSTIC`-WARN), persistiert nirgends: `.mvn/jvm.config` existiert nicht,
  `.mvn/maven.config` 0 Byte, keine pom-Referenz, `MAVEN_OPTS`/Env auf **beiden** Maschinen
  sauber (Batches setzen MAVEN_OPTS ohnehin selbst). Empirischer Gegenbeweis: m1d010-Referenz
  6020/127 exakt, 7 Carrier in `basew21.output_carriers.xml.gz` (amazon…ups), einzige
  DIAGNOSTIC-Treffer im nightbc-Log stammen aus der STEP0-Testregression
  (`LausitzCarrierSelectionTest`-Fixtures), vor STEP1A. Inputs seit 2026-07-28 unangetastet. (Plan `2026-07-28-emissions-emep-eea-tier3.md`,
  Tasks 1–9) — ✅ umgesetzt, **356 KPI-Tests** grün, KPI-Gruppe `environment` in `build_kpis`
  verdrahtet. Commits `87523e1` (Verdrahtung), `cb8205d` (Kaltstart-Bound + Limitations +
  Backlog), `cbc64fe` (Plan-Doc-Reparatur), Branch `hendrik`, **nicht gepusht**. Die früheren
  Tasks 1–7 sind in den Commits davor (Faktor-Extraktion bis Detail-CSV).
  Damit ist der Backlog-Punkt „Nachhaltigkeitsparameter einbauen" bis auf die dort explizit
  offen gelassenen Reste erledigt (SOS-/PB-Layer, Multi-Seed-Aggregation, optionale
  Substitutions-Sensitivitäten).

  - **Was der Kanal liefert.** Drei Arme (`freight` konventionelle Vans aus der
    CarriersAnalysis-TSV · `freight_modular` 1d-Fracht innerhalb der
    `MODULAR_FREIGHT_DRIVE`-Fenster · `drt`), jeweils Diesel **und** BEV aus demselben Lauf
    (Elektrifizierung ist ein Faktortausch, kein Rerun). Tier-3-Kurven auf der
    Entity-Mittelgeschwindigkeit, Non-Exhaust-Abrieb segmentdifferenziert nach Kap.
    1.A.3.b.vi–vii, EV-Reichweiten-**Sweep** (150/200/250 km) statt Einzel-Gate,
    massenbasierte 1c-Zurechnung mit Pflicht-Begleitung durch die Slot-Variante, plus
    `kpi_emissions_vehicles.csv` (eine Zeile je Entity × Antrieb).

  - **Beweis auf Realdaten, vier Läufe** (jede Armkombination trifft einen eigenen Datenpfad):
    `bandz_central` (freight) 1243,76 kg CO₂e WTW / 435,44 BEV, `segment_km_share_n1_ii`
    0,925937 · `base10c` (freight + drt) 14163,9 kg, 6252,1 Fracht-km / 47953 DRT-km ·
    `m1d050` (drt + freight_modular) 12706,1 + 3,52 = 12709,6 kg, **restfrei** über alle
    Schadstoffe · `chid600w21` (1c) 20,20 g CO₂e/Paket bei Massenanteil 0,90 % gegen
    Slotanteil 23,64 %. Die Task-7-Zahlen werden durch die KPI-Schicht **exakt** reproduziert.

  - **Zwei Defekte, die nur die Realdaten gefunden haben.** (1) Die
    `MODULAR_FREIGHT_DRIVE`-Fenster liegen im **DRT**-Event-Cache; der Freight-Cache ist auf
    jedem 1d-Lauf 0 Byte. Der vom Plan vorgegebene Snippet verdrahtete den Freight-Cache, und
    das wirft **nichts**: die `freight_modular_*`-Zeilen verschwinden und ihre km werden als
    Pax-km verbucht (12708,7 statt 12706,1 + 2,6). Derselbe Datei-Verwechsler war schon in
    Task 5b passiert → Parameter heißt jetzt `drt_task_events`, und der e2e-Test prüft den
    **km-Split** statt der Zeilenpräsenz (mutationsgeprüft: `KeyError: 'freight_modular'`).
    (2) `segment_km_share_*` war über **alle** Flotten gerechnet, also wog der DRT-Arm die Vans
    47953 : 6252 km aus → `n1_ii` = 0,107 für denselben LMD-Plan, der auf `bandz_central`
    0,926 liest. Ursache: DRT und modularer Arm tragen beide die feste Ersetzung N1-III, ihr
    Anteil ist konstruktionsbedingt 1,0. Jetzt nur über die konventionelle Van-Flotte, und auf
    Pax-only-/1d-Läufen **fehlt** die Zeile statt eine Scheinaussage zu liefern.

  - **Kaltstart gerechnet, nicht geschätzt — und die Plan-Regel kippt.** Fracht-NOx **+5,63 %**
    bei ta = 10 °C (Band 4,71–5,99 % über ltrip 8–15 km, +6,62 % bei 0 °C), CO₂/Energie
    +0,93 %, DRT-NOx +1,41 % je Kaltstart. ≥ 5 % ⇒ statt „dokumentierte Limitation, fertig"
    ein neuer `[H]`-Backlog-Punkt; bis dahin sind **alle NOx-Zahlen eine Untergrenze**,
    einseitig, und der BEV-Arm hat keinen Kaltstart, sein Vorteil wäre also größer.
    Herleitung: METHODS-LOG §2.29, Rechenrezept `analysis/kpi/data/README.md`.

  - **Zwei irreführende Ausgaben abgestellt.** Jede EV-Reichweiten-Flotte trägt ihre eigene
    Provenance — 3,2 % je **Tour** darf nicht neben 96,7 % je **Fahrzeugtag** wie eine
    vergleichbare Zahl stehen (daraus folgte der neue `[M]`-Punkt Ladefenster-Analyse). Und die
    Zurechnungszeilen entstehen nur bei vorhandener Paket-kg·km-Basis: im 1d-Arm fahren Pakete
    als Kapsel, ein Anteil von 0 % wäre die Behauptung, die Fracht sei emissionsfrei.

  - **Ein gescheiterter Emissionslauf ist jetzt sichtbar:** `meta/emissions_skipped` mit
    Exception-Typ und -Text in `kpis_long.csv` (und damit im „Hinweise"-Block des Dashboards),
    statt nur eines `print`. Ein unbekannter Fahrzeugtyp wäre sonst als stille Abwesenheit der
    Umwelt-KPIs durchgelaufen — dieselbe Konvention wie `run_meta_degraded`.

  - **Constraint eingehalten:** `src/hagrid_output_analysis/**` unberührt (Kollegen-Paper-Freeze
    bis ~2026-08-11); der Kanal liegt vollständig in
    `analysis/kpi/{emissions_emep,extract_emissions}.py` + `data/`.

  - **Fehler in der eigenen Plan-Pflege, behoben:** meine Task-8-Annotation begrenzte den
    Ersetzungs-Slice mit `s.index("Step 6: Commit")`, was Task **1**s Step 6 traf. Der Slice
    war rückwärts und damit leer, also hat `replace()` den neuen Step-5-Block **an den
    Dateianfang** gesetzt statt den alten zu ersetzen — Titel und Constraints standen darunter,
    Task 8s Step 5/6 blieben offen. Repariert in `cbc64fe`, zusammen mit den 44 Checkboxen aus
    Tasks 1–7, die nie gesetzt worden waren. Jetzt 0 offene Checkboxen im Plan.

  - **Literaturstelle geschlossen:** die dritte Paketmassen-Quelle ist **Mohri, Nassir, Lavieri
    & Thompson (2024)**, *Modeling package delivery acceptance in Crowdshipping systems by
    Public Transportation Passengers: A latent class approach*, Travel Behaviour and Society
    **35**, 100716. Jahr/Journal waren als `NEEDS-CHECK` offen und sind in allen fünf
    Fundstellen nachgezogen (METHODS-LOG §2.26, Plan Task 5c, `emep_supplement.csv`,
    `data/README.md`, `ALLOC_SRC` in `extract_emissions.py` — die `source`-Spalte jeder
    Zurechnungszeile trägt sie jetzt mit).


- **Die vier offenen 1d-Paper-Nacharbeiten abgeschlossen** (Rest der Fixwelle vom 2026-07-29:
  Parkungen P1/P2, F2-Kommentar-Duplikat, Re-Routing) — ✅ umgesetzt, **301 KPI-Tests** grün und
  die **volle Java-Modul-Suite** grün, inklusive `ModularEndToEndTest` (55 s) und
  `ModularControlArmTest` (182 s), die den echten Dispatcher/Splicer-Pfad fahren.
  Commits `c47fc80` (Python) und `d386e18` (Java), Branch `hendrik`, **nicht gepusht**.

  - **P1 — `meta/fleet_file_missing` recon-frei.** Die Flag saß im
    `recon is not None`-Zweig von `extract_drt.extract` und war damit genau auf den Builds
    unerreichbar, die sie brauchen: ein `--no-events`-Build lässt die Kapazitäts-/Schicht-KPIs
    ohnehin weg, ohne Flag stand in `kpis_long.csv` also nichts, was „dieser Lauf hat keine
    Fleet-Datei" von „dieser Build hat keine Events rekonstruiert" trennt. Jetzt in
    `_fleet_file_rows(fleet_file)`, gated auf die Fleet-Datei selbst. Prädikat ist
    `drt_service_time.read_shift_windows` — bewusst dessen eigene `fleet_file_known`-Definition
    statt einer Kopie, und bewusst **nicht** `os.path.exists`, das den Fall „Datei da, aber
    kein parsbares `t_0`/`t_1`" verfehlt. Der `source`-Text nennt jetzt die fünf KPIs, die die
    Flag kostet (ein Leser kann eine **Abwesenheit** nicht bemerken).
    **Korrektur der alten Backlog-Formulierung:** „modular-gebunden" war falsch — die Flag ist
    eine Eigenschaft *jedes* DRT-Laufs, nichts daran ist 1d-spezifisch. Gemeint war „dieselbe
    Behandlung wie der Modular-Marker aus Review C1", und genau die ist es geworden.
    **Nebenwirkung, bewusst:** `test_render`s Builds laufen jetzt über einen `_build_clean()`-
    Helper mit lesbarer Fleet-Datei, sonst wäre „eine Baseline-Seite trägt keinen
    Hinweise-Block" nicht mehr formulierbar. Realer Lauf ist davon nicht betroffen — der
    schreibt seine Fleet-Datei immer.
    **Nachweis:** Mutation (Flag zurück in den recon-Zweig) → 3 neue Tests rot; zusätzlich zwei
    Integrations-Pins durch `build()` auf `kpis_long.csv`.

  - **P2 — `mean_pax_aboard`/`_pax`-Äquivalenz mit unabhängiger Herleitung.** Alle bisherigen
    Zusicherungen rechneten **dieselbe Formel** wie die Produktion; eine mutierte Formel mutierte
    die Erwartung mit und der Test blieb grün. Die neuen Tests erreichen beide Zahlen über einen
    zweiten Weg: Belegungs-Timeline sekundenweise materialisieren, dann arithmetisches Mittel.
    Für `_pax` wird die I2-Kurzformel gegen **ihre eigene Begründung** geprüft — eine Exkursion
    ist durchgehend belegungsfrei (D2-Lockout), also `freight_s` Sekunden aus dem 0-Pax-Eimer
    entfernen und über die verbleibenden 5400 s neu mitteln. Zwei Fixture-Formen, plus explizite
    Diskriminierung gegen unkorrigiert / invertierten Rescale / Subtraktion statt Rescale.
    **Nachweis:** Mutation 1 (ungewichtetes Mittel über die Belegungsstufen) → 6 rot;
    Mutation 2 (Rescale invertiert) → 4 rot, darunter jeweils die neuen Äquivalenztests.

  - **F2-Kommentar-Duplikat entschärft.** Der Splice-Rejection-Zweig in
    `ModularTourDispatcher.dispatch()` behauptete weiter, die Splicer-Zahl sei „always larger and
    systematically so" — genau der Overclaim, den Task 6 im Javadoc zurückgezogen hatte. Task 6
    war auf das Javadoc gescoped, also überlebte das Duplikat die Korrektur; **das ist die
    Lehre**. Der Kommentar trägt jetzt nur noch das KPI-Zuordnungsargument (warum der Zweig
    existiert) und verweist für den Mechanismus aufs Javadoc statt ihn zu wiederholen.

  - **Re-Routing: als beweisbarer Kurzschluss gebaut, nicht als Cache.** Eine vom Splicer
    abgelehnte Tour bleibt pendend und wird in jedem Simstep mit offenem Gate erneut angeboten —
    bisher jedes Mal mit einer kompletten Kettenroutung (Anfahrt + Swap + N Stop-Legs + Rückweg)
    für dasselbe Nein. Neu ist Schritt 0 in `ModularTourScheduler`: eine **zeitunabhängige
    untere Schranke** (zwei Kapsel-Swaps + alle Servicezeiten + Free-Flow-Fahrzeit des Rings
    Depot→Stops→Depot), einmal je Tour berechnet und gecacht. Reißt schon die Schranke das
    Envelope, ist die Ablehnung **bewiesen** — gleiches Verdikt, unberührter Schedule.
    **Bewusst nicht der im Backlog benannte Cache:** ein wiederverwendetes früheres Nein
    unterstellt Monotonie in der Abfahrtszeit, die gebinnte DVRP-Fahrzeiten nicht garantieren
    (Nicht-FIFO an Bin-Grenzen) — das könnte einen Splice verschlucken, der gepasst hätte. Die
    Free-Flow-Schranke braucht diese Annahme nicht, nur „eine Linkfahrzeit liegt nie unter
    `length/freespeed`"; deshalb minimiert ihr eigener Router **Fahrzeit**, nicht die injizierte
    Disutility, und die Anfahrt bleibt außen vor (ein nichtnegativer Term weniger hält die
    Schranke gültig und macht sie pro **Tour** cachebar statt pro (Tour, Fahrzeug, Position)).
    **Nachweis:** 6 neue Scheduler-Tests, darunter 20 Wiedervorlagen einer hoffnungslosen Tour
    mit **null** Routing-Lookups (zählender `TravelTime`-Decorator), kein Veto am exakten
    Envelope-Rand, und ein differentieller Sweep über den Machbarkeits-Übergang, in dem
    „Schranke lehnt ab" nie von „Routing lehnt ab" abweichen darf.

---

## 2026-07-30

- **1000-Iterationen-Konvergenzsonde: Ergebnis NEIN** (war Vorbedingung des `[H]`
  Multi-Run-Aggregation) — ✅ gemessen, 3 Seeds × größter Carrier (`dhl`, 875 Services),
  `jspritIter=1000`, Dev-PC, Java 21.0.10, ~5 h Wall-Clock parallel.
  **Ergebnis:** km 586,2 / 576,0 / 542,9 → Spanne **7,61 %**; Touren 19/19/19 → **0,00 %**. Bei 1000
  Iterationen ist die km-Streuung also **nicht kleiner** als der 6,5-%-Rauschboden bei 100 (§2.1).
  Der Iterations-Hochlauf ist damit **nicht zu teuer, sondern wirkungslos** — ein stärkeres Argument
  als die zurückgezogene Kostenzahl (§3.8), und der Multi-Seed-Fächer ist die einzige Antwort.
  **Drei Nebenbefunde:** (a) der **Flottenmix** ist seed-instabil bei konstanter Tourenzahl
  (`_s` 6–8 / `_m` 10–13 / `_l` 0–1 von 19) → zusätzlicher Rauschkanal für größenklassenabhängige
  Emissionsfaktoren; (b) `_s`=100 trägt ~⅓ der Touren, `HagridPaths.java:337` („m/l only") ist
  falsch → `[M]`-Entscheid faktisch gefallen; (c) 19/19 Touren in der Morgenwelle **auch mit dem
  21:00-Fenster** → bestätigt „14:00-Welle tot" unabhängig vom Fensterende.
  **Laufzeit gemessen:** Setup 3,8 min + jsprit ~5,0 h für **einen** Carrier — meine ×10-Hochrechnung
  (3,7 h) war ~35 % zu niedrig; auf Armebene wird bewusst nicht mehr extrapoliert.
  **Nachweis/Reproduktion:** `-Dhagrid.jsprit.onlyCarrier=largest` + `-Dhagrid.jsprit.seed=…`,
  `concept=LMD_BASELINE,maxIter=0,jspritIter=1000`; km aus den `<route>`-Linklisten des routed
  Carrier-XML gegen 474.453 Netz-Linklängen, **0 nicht auflösbare Links**; `jspritIterations=1000`
  und `unassignedJobs=0` in allen drei XMLs verifiziert. Zahlen und Vorbehalte (n=3, ein Carrier,
  andere Maschine) → [METHODS-LOG](METHODS-LOG.md) §2.1/§2.2/§3.8.
  **Nebenbei aufgedeckt:** alle drei Läufe starben **nach** jsprit am Windows-Filelock auf dem
  eigenen `hagrid.log` → neuer `[M]`-Punkt in [BACKLOG](BACKLOG.md).

- **Lieferfenster über alle drei Arme auf 07:30–21:00 vereinheitlicht** (war `[H]` „1c-Lieferfenster
  anheben", User-Entscheidung 2026-07-29, auf die Baseline erweitert 2026-07-30) — ✅ umgesetzt,
  volle Modul-Suite **466 Tests grün**.
  **Befund beim Umsetzen (der eigentliche Wert des Tasks):** es standen **drei** verschiedene Fenster
  im Code — Baseline `LmdCarrierBuilder` 08:00–20:00, 1c `SharedUse` B2B 17:00 / B2C 20:00, 1d
  `Modular` 07:30–21:00. Nur 1c anzuheben hätte die **Baseline** zum Ausreißer gemacht: 30 min später
  Start, 1 h früher Schluss als beide integrierten Szenarien — und die Baseline ist der Arm, *gegen*
  den sie gemessen werden. Weniger Zustellzeit ⇒ systematisch niedrigere Zustellquote ⇒ ein Teil des
  „Integrationsvorteils" wäre ein Fenster-Artefakt gewesen. Die Entscheidung vom 29.07. nannte nur
  1c und 1d; die Baseline war eine Lücke.
  **Umsetzung:** neue Klasse `hagrid.integrated.DeliveryDay` (`START_S`/`END_S`) als einzige Quelle;
  `Modular.DELIVERY_DAY_*`, `SharedUse.WINDOW_END_S`/`SUBMIT_FROM_S`, `LmdCarrierBuilder`
  `DAY_START`/`DAY_END`/`LATEST_VEHICLE_END` und `LmdTourRetimer.LATEST_VEHICLE_END` delegieren
  dorthin statt die Zahlen zu wiederholen — die Duplizierung war die Driftursache. Die
  B2B/B2C-Verzweigung in `ParcelAgentGenerator:106` ist **entfernt** (nicht totgelegt);
  `B2B_WINDOW_END_S`/`B2C_WINDOW_END_S` sind durch ein `WINDOW_END_S` ersetzt, fünf Testdateien
  mitgezogen.
  **Nachweis:** neuer `DeliveryDayTest` (4 Fälle) — prüft die Konstanten, dass 1d und 1c delegieren,
  per Reflection dass die beiden Typ-Konstanten nicht zurückkehren, und baut einen **echten**
  Baseline-Carrier um das Fenster **an den Services** zu assertieren (fällt also auch, wenn es aus
  anderem Grund nicht mehr durchreicht). Volle Suite 466/0/0.
  **Guardrail:** Hannover bleibt bei 8/20 (`HagridConfig.Routing.deliveryWindowStartHour/EndHour`) —
  eine Angleichung hätte die 51 Läufe der Kapazitäts-Sensitivität unvergleichbar gemacht.
  **Konsequenz → [BACKLOG](BACKLOG.md):** `base10c` und `chid600`/`chid600i` neu fahren, Baseline
  zuerst. Limitation (21:00 gilt auch für B2B) und Vorher/Nachher-Tabelle →
  [METHODS-LOG](METHODS-LOG.md) §1.2.

- **Ein-Carrier-Routing-Schalter `hagrid.jsprit.onlyCarrier`** — ✅ Vorarbeit für die
  1000-Iterationen-Sonde (§2.2/§3.8). `-Dhagrid.jsprit.onlyCarrier=largest` (oder exakte Carrier-ID)
  routet nur einen Carrier; `largest` = meiste Services, Gleichstand über die ID gebrochen
  (deterministisch, keine ID-Recherche nötig). Auf Lausitz kostet der größte Carrier allein ~35 %
  der jsprit-Phase (824 von 2.975 Jobs) statt 100 %.
  **Zwei Fail-safes:** (a) eine ID ohne Treffer **wirft** samt Liste der verfügbaren Carrier — ein
  stiller Nicht-Treffer hätte eine leere Carrier-Datei erzeugt, die sich wie ein fertiger Lauf liest;
  (b) die übrigen Carrier werden aus dem Container **entfernt**, nicht bloß ungeroutet gelassen —
  `CarriersUtils.writeCarriers` persistiert den ganzen Container, sechs planlose Carrier neben einem
  gerouteten hätten sich downstream wie ein vollständiger Lauf gelesen und dem `CarrierModule`
  Carrier ohne Plan übergeben. Dazu eine WARN-Zeile, die die Unvollständigkeit explizit benennt.
  **Nachweis:** `LausitzCarrierSelectionTest` 5/5 — darunter der tragende Fall
  `filteringDoesNotChangeTheResult`: die Lösung des gefilterten Carriers ist identisch (Tourenzahl
  **und** jsprit-Score) zu der, die er im vollen Set bekommt. Bricht der, überträgt das
  Sondenergebnis nicht mehr. Volle Suite 466/0/0.

- **`IntegratedScenarioConfig` auf den Autonomie-Kern eingedampft** (war `[M]` „`IntegratedScenarioConfig`
  entscheiden", Fallback-Audit 2026-07-27) — ✅ als Vorarbeit für den vertagten Autonomie-Switch
  (User 2026-07-30: Autonomie nicht von unmittelbarer Relevanz, bleibt im Backlog).
  **Befund:** die Klasse war nicht bloß unverdrahtet, sondern eine **zweite, ungepflegte Quelle**
  für sieben Werte, die längst produktiv anderswo leben — `retoolingTimeSeconds`/
  `freightLookAheadSeconds`/`idleThreshold` (= `Modular.RETOOLING_S`/`FREIGHT_LOOKAHEAD_S`/
  `DEFAULT_IDLE_THRESHOLD`), `idleThreshold`+`fleetSize` (`HAGRIDSimulationConfig` + CLI),
  `vehicleTimeCostPerHour` (`analysis/kpi/economics.py`), `depotCount=3` (sachlich überholt:
  `DepotNetwork` nimmt die Depot-Liste), `b2cLockerShare=0.7` (Phase-2-Feature mit struktureller 0,
  METHODS-LOG §2.10).
  **Umsetzung:** die sieben Felder samt Buildern, Validierungen und Testfällen entfernt; übrig
  bleiben `OperationMode` + Labour-Rate + Dwell-Faktor + Speed-Cap + Road-Exclusion und die vier
  `effective*`-Helper, also genau die Design-Spec-§4.4-Effekte. Neues Klassen-Javadoc deklariert
  **NOT WIRED** (inkl. `RunMetadataWriter`s hartem `operation_mode="conventional"`) und listet für
  jeden entfernten Parameter sein lebendes Zuhause, damit ihn niemand wieder hinzufügt.
  Die verbleibende Doppelung `cargoLabourCostPerHour` ↔ `economics.py:LABOUR_EUR_PER_H` ist bewusst
  und benannt: `effectiveLabourCostPerHour()` braucht die Rate, und die Kostenfunktion wird ohnehin
  neu gebaut (METHODS-LOG §2.6).
  **Nachweis:** `IntegratedScenarioConfigTest` 7/7 grün (Defaults 1, Mode-Helper 3 — inkl. neuem
  50-km/h-Sensitivitätsfall —, Validierung 3); der Test-Compile des gesamten Moduls lief mit durch
  und bestätigt, dass **kein** anderer Aufrufer an den entfernten Gettern hing (der Grep vorab
  ergab: nur der eigene Test referenzierte die Klasse überhaupt). Kein Zahleneffekt — die Klasse
  war produktionstot. Konsequenz und künftige Regel → [METHODS-LOG](METHODS-LOG.md) §1.1;
  Restentscheid (verdrahten vs. löschen) hängt am Autonomie-Switch → [BACKLOG](BACKLOG.md).

- **LMD Dispatch-Stunden besser streuen — Lausitz-Abfahrts-Gruppierung** (war `[M]`, offen seit
  2026-07-21) — ✅ Root Cause identifiziert und per `LmdTourRetimer` gefixt (Commit `00c3b3a`
  auf `hendrik`).
  **Root Cause (Run-Evidenz, 2026-07-30):** nicht die Jitter-Logik (Hannover und Lausitz jittern
  identisch pro Vehicle-Template), sondern **Carrier-Granularität × jsprit-INFINITE-Cloning**:
  jsprit klont pro Provider×Van-Typ EIN Template, jeder Klon erbt dessen exakte Jitter-Sekunde.
  Legacy Hannover versteckt das hinter 187 Carriern (501 Templates, 488 distinkte Startzeiten in
  `BASECASE_13052025_delivery_carriers.xml`, 1–3 Touren je Carrier); Lausitz hat 7 regionsweite
  Carrier → `bandz_central_seed1234`: 62 Touren auf 16 Templates, bis zu 14 Touren zur
  identischen Sekunde (14× dhl_s_h8_v2 @ 07:41:32). Mehr Kopien (`VEHICLES_PER_TYPE_PER_WAVE`)
  helfen NICHT — jsprit bevorzugt weiter die billigste Kopie.
  **Fix (Option 1, User-Entscheidung 2026-07-30):** `LmdTourRetimer` — post-jsprit bekommt jede
  Wellen-Template-Tour (`..._h<h>_v<n>`) einen frischen Gauss-Draw um ihre Wellen-Stunde (gleiche
  Sigmas wie legacy `CarrierVehicleFactory.getTimeShift`) auf einer eigenen Vehicle-Kopie mit
  wellenrelativem Fenster; Abfahrt geclampt, sodass die geplante Tour bis 21:00 endet; danach
  Plan-Re-Routing (`NetworkRouter`), Stoppfolgen/jsprit-Lösung byte-unangetastet. Deterministisch
  (eigener `TOUR_RETIME_SEED` + Provider-Hash, content-sortierte Draw-Reihenfolge — Missed-
  Delivery-Overlay unverschoben). Verdrahtet NUR im LMD_BASELINE-`run()`-Pfad über eine neue
  `routeWithDurationCap`-Overload (Delegate-Muster gewahrt; `runModular`/1d und Nicht-Wellen-
  Vehicles wie `_day_v0` unverändert — per Test gepinnt).
  **Beweis:** `LmdTourRetimerTest` 4/4 (Spread auf distinkte Sekunden, Determinismus,
  21:00-Clamp, Modular-Passthrough), **mutation-verifiziert** (No-op-Mutation im Retimer →
  2 Failures: „Expected size: 3 but was: 1", Clamp-Erwartung 52200.0); `LmdCarrierBuilderTest`
  6/6, `LausitzFreightPreprocessorTest` 6/6 (das `run()`-e2e liest das retimte Carrier-XML
  wieder ein → neue Vehicles korrekt in den Capabilities registriert), `FreightRunComposerTest`
  3/3. Restpunkt (14:00-Welle quasi tot; echte Zwei-Wellen-Struktur bräuchte FINITE-Fleet)
  → [BACKLOG](BACKLOG.md). Achtung Lesehinweis: alle LMD_BASELINE-Läufe VOR diesem Fix zeigen
  die gruppierten Abfahrten.

## 2026-07-29

- **1d Paper-Readiness-Fixwave (2026-07-29)** — ✅ acht Tasks auf `hendrik`, abgearbeitet gegen die
  drei Paper-Readiness-Reviewer-Läufe (Java/Methodik/Python-KPI, → [BACKLOG](BACKLOG.md) 1d).
  Plan: `superpowers/plans/2026-07-29-1d-paper-readiness-fixwave.md` (Commit `bbc8591`). Commits
  und Test-Endstand je Task:
  - **T1** `9e4d9da`/`9ba086d`/`a97d7b0` — δ-Zähler `parcels_demand`/`parcels_unassigned_jsprit`/
    `parcels_missed_overlay`, `max_parcels_per_tour`, `peak_concurrent_swaps`, Identity 0,
    DISPATCHED-Event-Attribute `plannedDurationS`/`routedDurationS`. **64/64.**
  - **T2** `20bdd4e`/`0b1d068` — `extract_modular`: Rohzähler, alle Identities in Python,
    Omit- statt 0,0-Konvention, CSV-Policy. **260/260.**
  - **T3** `48bff7c` — Kontaminationsmarker szenario-/CSV-gebunden (übersteht `--no-events`),
    Korrekturrezept berichtigt, `*_pax`-Zeilen, „unkorrigierbar" → „nicht korrigiert",
    `fleet_utilisation_by_trips` als unkorrigierbar umklassifiziert. **271/271.**
  - **T4** `5fee8f1`/`f8561ca` — Kachel-Kontaminationsbanner, pax-bereinigte Sublines,
    Comparison-Page rendert den Marker-Payload, `_meta_notes` HTML-escaped, Sekundär-Badges auf
    occ-Chart/Tourdauer-Chart/`_vehicle_chart`, Karten-Legendenzeile. **281/281.**
  - **T5** `5a8d88f`/`e200053` — depot-lokales `<=`-Fenster-Fixture (mutation-verifiziert),
    `open_freight_windows`-Diagnostik + Meta-Row, `RE_TIME`-Wortgrenze, echtes 1d-e2e-Fixture
    (CSV **und** Events). **289/289.**
  - **T6** `2c00e8d` — Belt-2-CME-Pinning-Test (mutation-verifiziert), `OptimizerRebindGuard`-
    Feuertest, +INF-Donor-Guard, Depot-CSV-Precheck, Javadoc-Korrekturen (inkl. F2-Softening +
    C8-late-Konvention). **19/19** (betroffene Java-Klassen inkl. beider e2e-Tests).
  - **T7** `b639ff3`/`e12776d` — drei Legacy-`drt-headline`-Dashboards gelöscht
    (`build_drt_dashboard.py`, `build_dashboard.py`, `build_vehicle_tours.py`); Doku-Referenzen
    darauf annotiert statt gelöscht. **289/289** (Python-Vollsuite, Rerun nach Löschung).
  **Hannover-Legacy-Gate beim Dashboard-Löschen unabhängig re-verifiziert sauber** — Gate-Beweis
  (kein Live-Import/-Aufruf außerhalb von `analysis/drt-headline/` selbst, `hagrid_output_analysis/**`
  und alle Hannover-`.bat`-Skripte referenzfrei) wurde vom Reviewer eigenständig nachvollzogen, nicht
  nur vom Implementierer behauptet.
  **Zwei mutation-verifizierte Test-Pins als Beweisdetail:** (1) das depot-lokale `<=`-Fenster in
  `test_modular_service_time.py` — vor der Mutation (`a <= t0 < b`) grün, nach der Mutation zu
  `a < t0 < b` schlägt `retooling_s == 840.0` mit `420.0` fehl (T5); (2) der Belt-2-CME-Pinning-Test
  in `ModularOptimizerTest` — mit dem produktiven `List.copyOf(...)`-Belt grün, nach dessen
  Entfernung schlägt der Test mit `Tests run: 1, Failures: 1` fehl (T6).
  Konsequenzen für die Zahlen → [METHODS-LOG](METHODS-LOG.md) §2.13/§2.14/§2.16/§2.18/§2.21–§2.23;
  offene Nacharbeit (Parkungen P1/P2, F2-Kommentar-Duplikat, Re-Routing-Cache, 1c-21:00-Umbau,
  KPI-Landschaft-Konsolidierung) → [BACKLOG](BACKLOG.md).

- **CV-Batterie auf dem Zensus-Prädiktor neu gerechnet** (war `[M]`, offen seit 2026-07-27) —
  ✅ alle acht `studies/run_cv*.py` in PANDA neu gelaufen, Exit 0, Artefakte neu geschrieben;
  die überholten OSM-Ära-Artefakte liegen unangetastet in `PANDA/archive/cv_pre_zensus/`
  (sie sind gitignored, existierten also nur auf Platte — daher kopiert, nicht überschrieben).
  **Vorprüfung, ohne die keine Zahl gilt:** ein Refit auf `.spatial_cache/df_plz.parquet`
  reproduziert `fitted_params.json` bitgenau (max. relative Abweichung 0) — mehrere Skripte
  lesen das Parquet direkt statt über `init_data()`, ein stiller Cache-Vintage-Fehler wäre
  sonst nicht unterscheidbar von einem echten Ergebnis.
  Ergebnis und Konsequenzen: [METHODS-LOG](METHODS-LOG.md) **§2.9**, zwei Zurückziehungen
  **§3.7** (Cross-Carrier) und **§3.2** (Nachtrag: Strukturrichtung), Details und Reproduktion
  `PANDA/docs/transferability.md` → **B10**. PANDA-README auf Zensus-Zahlen umgestellt.
  Zwei Skript-Nebenfunde mitbehoben: `run_cv_robustness.py:119` druckte den Skill als
  **hartcodierte** „+36 %"-Überschrift (jetzt interpoliert), und `run_cv_transfer.py` gab die
  in B4b längst zurückgezogene Aussage aus, der Ankerabstand sei eine *untere Schranke*
  (jetzt: offene Fehlerrichtung). Beides Zahlen, die als gemessen aussahen und es nicht waren.
  Neu daraus im BACKLOG: die K6-Entscheidung und die Bake-off-Doku.

- **1d Whole-Branch-Review: Fix-Wave abgeschlossen** — ✅ Commit
  `fix(modular): correct DRT system-KPI contamination, publish modular group, record limitations
  (final review)` auf `hendrik`. Die vierzehn Task-Reviews konnten querschnittliche Fehler nicht
  sehen; dieser Durchgang behebt sie:
  1. **KRITISCH — Fracht-Tasks korrumpierten die vorbestehenden DRT-System-KPIs.**
     `MODULAR_FREIGHT_DRIVE`/`_STOP` fielen in nie gelesene Töpfe und kamen als **Flotten-
     Leerlauf** wieder heraus; der Kapsel-Tausch (Task-Typ heißt wörtlich `"STOP"`) zählte als
     **Passagier-Standzeit**. Getrennt wird jetzt über das `modularTour`-Fenster
     DISPATCHED…COMPLETED je Fahrzeug (kein Eingriff in die Java-Task-Hierarchie — C6 bleibt
     unangetastet). Konsequenz für die Zahlen: [METHODS-LOG](METHODS-LOG.md) **§2.14**.
  2. **Fünf `modular`-KPIs erreichten das Dashboard nie** — die Tabellenschleife zählte eine
     hartcodierte Gruppenliste auf. Leitet sich jetzt aus `common.KPI_GROUPS` ab.
  3. **Splicer-Ablehnungen waren unsichtbar** und wurden später als „pending expired"
     veröffentlicht, obwohl es eine *andere* Fehlerart ist (jsprit-Auto-Netz vs. reale
     DRT-Routung inkl. Anfahrt). Neu: `tours_rejected_at_splice`, angehängt an die bestehenden
     20 Metriknamen ohne Umsortierung.
  4. **Deadhead/Service-Split am Übergabepunkt ungeprüft** — zwei benachbarte `double`-Argumente,
     vertauschbar bei komplett grüner Suite. Jetzt gepinnt.
  5. **C4-Vergleichbarkeitsfolgen** standen im Plan, nicht in den Limitations →
     [METHODS-LOG](METHODS-LOG.md) **§2.15**.
  Dazu die Minors M6–M8, ein Test-Javadoc, das beschrieb, was der Test *nicht* unterscheidet, und
  ein `[L]`-BACKLOG-Punkt für die verschobene Testhygiene.
  Regression: Java **436/436** (drei Chunks gegen jeweils geleerte `target/surefire-reports/`),
  Python-KPI-Suite **249/249**. Bericht:
  `.superpowers/sdd/2026-07-27-1d-modular-capsule-swap/final-review-fix-report.md`.

---

## 2026-07-28

- **1d Implementierung (U-Shift Kapsel-Tausch): alle 14 Tasks abgeschlossen** — ✅ Commits
  `48a41eb`..`0516bcb` auf `hendrik`, vierzehn Tasks, jede mit eigenem Review-Pass und
  vollständig behobenen Findings. Volle Regression grün: Java **433/433** (Chunk 1, alles außer
  `LmdBaselineEndToEndTest`: **432/432**; Chunk 2, `LmdBaselineEndToEndTest` isoliert wegen seiner
  Laufzeit: **1/1** — jeweils gegen eine geleerte `target/surefire-reports/`, damit die beiden
  Zählungen sich nicht überschneiden können; Summe 433 deckt sich mit der statischen `@Test`-Zahl
  über den ganzen `src/test/java`-Baum) + Python-KPI-Suite **240/240**; das Modular-Paket (52
  Tests, Untermenge von Chunk 1) lief zusätzlich ohne `-q` — saubere Ausgabe, keine
  Warnungen/Stacktraces außer den Tests' eigenen, gezielt ausgelösten Negativpfaden
  (Conservation-Violation-Logging, Ghost-Tour, Expired-Pending).
  → [Plan](superpowers/plans/2026-07-27-1d-modular-capsule-swap.md) ·
  [Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md) (Status: implementiert).
  Konzeptparameter/Status: [BACKLOG](BACKLOG.md) `[H]` Modular; drei neue Paper-Limitations dazu:
  [METHODS-LOG](METHODS-LOG.md) §2.11–§2.13 (zwei weitere aus der Whole-Branch-Review:
  §2.14–§2.15, siehe Eintrag 2026-07-29). Voller Regressionsnachweis:
  `.superpowers/sdd/2026-07-27-1d-modular-capsule-swap/task-14-report.md`.
  Offen bleibt ausschließlich die Run-Arbeit (10-Sitze-Re-Baseline, Idle-Threshold-Sweep,
  7,0-h-Kontrollarm, Entscheidung prädiktives Gate) → BACKLOG.

- **Sofort-Block: fünf billige Fixes am Stück** — ✅ alle verifiziert, lokal auf `hendrik`.
  1. **Locker-Javadoc korrigiert** — `integrated/shareduse/DeliveryChannelResolver.java:10-20`
     behauptete, der Locker-Zweig aktiviere sich „without code changes here"; benennt jetzt die
     strukturelle 0 + die nötige Änderung am Aufrufer (`ParcelAgentGenerator:67`).
     → [METHODS-LOG](METHODS-LOG.md) §2.10 (annotiert).
  2. **`drt_service_time`-Sort-Key gehärtet** — neuer `_veh_sort_key` (letztes Token wenn Zahl,
     sonst 0; ID-String bricht Gleichstände → deterministisch trotz `sorted(set(...))`). Damit fiel
     der Workaround in `test_build_kpis._make_mini_events_run`, der die Fixture-ID `drt_veh_1` in
     der tmp-Kopie umschreiben musste; die Fixture ist jetzt selbst der Regressionswächter.
     **Nachweis:** 237 KPI-Tests grün.
  3. **JDK-Pfad entkoppelt** — `SimulationBatGenerator:118-133` generiert statt
     `jdk-21.0.3.9-hotspot` eine Auflösungskette `HAGRID_JAVA_EXE > JAVA_HOME > PATH` mit hartem
     Fail + Versionswarnung; der eingecheckte `run_hagrid_sim.bat` ist mitgezogen (CRLF/UTF-8
     erhalten). **Nachweis:** `mvn compile` exit 0; Bat in einer Sandbox-Kopie gefahren — Happy
     Path löste `jdk-21.0.10` über `JAVA_HOME` auf, Fail-Path (JAVA_HOME kaputt + java.exe von der
     PATH genommen) brach mit Hinweistext und exit 1 ab. Die Versionsprüfung liest die schon
     geschriebene `vm_settings_before.txt`, weil `for /f` an `C:\Program Files` scheitert
     (verifiziert).
  4. **`.sha1` für die `libs/`-Artefakte** — Maven-Format (nackter Hex-String) für Jar und Pom.
     **Nachweis A/B:** `~/.m2`-Eintrag gelöscht und neu aufgelöst — ohne `.sha1`
     `ChecksumFailureException: no checksums available`, mit `.sha1` sauberer BUILD SUCCESS.
  5. **EMEP/EEA-Rohquellen aus `~/Downloads` geholt** — Appendix-4-xlsx (10,9 MB) + Kapitel-PDF
     liegen in `hagrid-input/emissions/` mit Quellentabelle `SOURCES.md` (URL, SHA-256,
     Download-Datum, Blattnamen, RF-Bruchteil-Gotcha, Zitat-Caveat „Guidebook 2023, Update 2025").
     **User-Entscheidung 2026-07-28:** die Ablage bleibt komplett **untracked** — `.gitignore:59`
     erfasst `hagrid-input/**`, und der User will es dort lassen (auch `SOURCES.md`, kein
     `git add -f`). Reproduzierbarkeit läuft über URL + SHA-256 *in* der Datei, nicht über git.
     Git-seitig sichtbar ist davon nur die Plan-Constraint, die nicht mehr auf `~/Downloads` zeigt,
     plus der Zitat-Caveat in [METHODS-LOG](METHODS-LOG.md) §1.4.
  Nebenbefund: der `[L]`-Punkt „LMD-Karte leer bei DRT-losem Run" ist **kein Defekt** →
  [METHODS-LOG](METHODS-LOG.md) §3.6, festgenagelt durch
  `test_drt_less_run_still_gets_lmd_link_geometry`.

- **Zentroid-Snapping behoben: Residualnachfrage wird über die Straßen der Zelle verteilt** —
  ✅ `PANDA/distribution.py`, 8 Tests (`tests/test_distribution.py`), 61 Tests grün, alle drei
  Level neu exportiert und per SHA256 gestaged.
  Statt die ganze Zelle auf das dem Zentroid nächste Segment zu werfen, wird längengewichtet über
  die Straßen *innerhalb* der Zelle verteilt (gestuft: Zelle → halbe Zellbreite → Nearest-Snap).
  Lausitz: Stufe 1 217 Zellen/558 Pakete, Stufe 2 34/42, Stufe 3 30/31. Zustellpunkte 1.053 →
  1.131, Maximum je Punkt 83 → 81, **12,6 % der Pakete anders platziert, Niveau unverändert**.
  Mitbehoben: PLZ-Sentinel `00000` (58 Punkte / 570 Pakete → **0**) und ein **stiller
  CRS-Mismatch** in `export_demand.run()`, den erst der Residual-Join scharf machte — `STRtree`
  kennt kein CRS und liefert bei Mismatch schweigend keine Treffer; `distribution.py` prüft das
  jetzt und hat einen Regressionstest.
  Eingaben der schon gemessenen Bandläufe **archiviert statt überschrieben**:
  `level_ctrsnap_{central,low,high}`.
  → Konsequenz und korrigierte Größenangabe (23,8 % DHL / 27,7 % all-carrier, nicht 10,5 %):
  [METHODS-LOG](METHODS-LOG.md) §2.8; neue Niveaus §1.3.

- **Rauschboden der jsprit-Heuristik gemessen** — ✅ Seed-Test, Commit `06c2707`.
  Neue System-Property `-Dhagrid.jsprit.seed`; identischer Central-Stand, identische 100
  Iterationen, nur anderer Seed. Ergebnis: Pakete/verpasste bitgleich, Fahrzeug-km −6,5 %,
  Gesamtkosten −0,8 %; je Carrier bis ±30,6 % km. Damit ist der Rauschboden **gemessen statt
  vermutet** — und er kippt einen früheren Kernbefund.
  → Zahlen, Signal-Rausch-Tabelle und Gotcha: [METHODS-LOG](METHODS-LOG.md) §2.1/§1.5;
  Zurückziehung: §3.1.

- **Nachfrage-Band gemessen, drei Arme** — ✅ `bandz_{low,central,high}`, je
  `maxIter=0 jspritIter=100`, Laufzeiten 1 h 46 / 1 h 34 / 1 h 40, Stand je Arm per SHA256
  verifiziert. Belastbare Elastizitäten (Fahrzeuge 0,62 · Gesamtkosten 0,76) reproduzieren die
  erste Bandmessung auf anderem Nachfragemodell und mit drittem Arm; die Fahrleistungs-Kanäle
  desselben Experiments sind es nicht. Treiber `run_lmd_band.ps1`.
  → [METHODS-LOG](METHODS-LOG.md) §1.3 (Ergebnis) / §3.1 (was daran fiel).

- **1d Detail-Plan geschrieben + gegrillt/revidiert** — ✅
  [Plan](superpowers/plans/2026-07-27-1d-modular-capsule-swap.md). C4 revidiert (Tagesfenster
  07:30–21:00, keine Dispatch-Waves), C7 Provider-Interleave mit akzeptiertem 07:16-Surge,
  C8 Late-Metriken. POC auf aktuellem PANDA-Stand; gematchte Baseline erst für Paper-Runs.
  Ausführung offen (BACKLOG).

- **Emissions-Plan geschrieben** — ✅
  [Plan](superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md), 9 Tasks (Faktor-Extraktion,
  Tier-3-Kern, Freight-/DRT-Arm, BEV, EV-Range-Gate, `build_kpis`-Integration, Kaltstart-Bound).
  SOS-Layer + Multi-Seed-Aggregation bewusst ausgeklammert. Ausführung offen (BACKLOG).

## 2026-07-27

- **1c Task 9 (Smoke) + Task 10 (χ→0-Validierung)** — ✅ beide grün. Task 10 beweist
  **konstruktiv**, dass die Agenten-Mischung die Pax-Buchführung nicht verzerrt: der Probelauf
  ohne Pakete (`noParcels`-Schalter) ist bei Iteration 0 **bit-identisch** zum
  χ→0-Shared-Use-Lauf, Abweichung ab Iteration 1 < 0,7 %. D10 des 1c-Plans damit erledigt.

- **Kritischer 3-Reviewer-Review + Fix-Paket (7 Commits)** — ✅ umgesetzt, reviewed, **gepusht**.
  Größter Fund **F1 (kritisch)**: das χ-Gate maß den Umweg inklusive der Dwell-Zeit des
  eingefügten Pakets → korrelierte mit der Paketgröße statt dem Umweg. Behoben durch
  Detour-only-Semantik (→ [METHODS-LOG](METHODS-LOG.md) §1.1).
  Weitere behoben: Delivered-after-window-Leck · ehrliche Legacy-Fallback-Kennzeichnung ·
  `rejected_final`/`pending_eod`-Zähler ergänzt (der Zähler-Export war nicht wirklich
  vollständig) · Provider-Depot-Zuordnung (M4b) · MATSim-Seed als CLI-Key statt globalem Default.
  Bewusst offen geblieben: n=1 Seed pro Punkt, χ als untere Schranke, PPC/Fare tot
  (→ METHODS-LOG §2.3/§4.1).

- **Ursache des +22-%-Ankerabstands gefunden (B7) und behoben (B8)** — ✅ Der EFH-Term war gegen
  Zensus-2022-Gebäudedaten **falsch**; ein blinder Bake-off gegen zwei vorab festgelegte Gates
  hat die Wohnfläche auf den Zensus-2022-Gebäudebestand umgestellt
  (`PANDA/zensus_wohnflaeche.py`). Anker fällt +22,2 % → **+0,9 %**, blinder Hannover-wMAPE
  10,1 % → **9,8 %**. Reproduktion: `python -u studies/run_efh_validation.py`; Details
  `PANDA/docs/transferability.md` → B7/B8.
  → Entscheidung und neue Parameter: [METHODS-LOG](METHODS-LOG.md) §1.3; Zurückziehung: §3.2.

- **Baseline-Hälfte des ersten Nachfrage-Bandes** — ✅ `LMD_BASELINE` × {central, low}
  (`run_lmd_band.ps1`, Tags `band_central`/`band_low`, je `maxIter=0 jspritIter=100`).
  Ergebnis in `PANDA/docs/transferability.md` → „Ergebnis Baseline-Band". Der Kernbefund dieses
  Experiments ist später zurückgezogen worden (→ [METHODS-LOG](METHODS-LOG.md) §3.1); die
  Kosten-Elastizität 0,76 hat Bestand und wurde 2026-07-28 unabhängig reproduziert.

- **1d Spike + Design** — ✅ user-approved.
  [Spike](superpowers/notes/2026-07-27-modular-capsule-swap-dvrp-spike.md) /
  [Design](superpowers/specs/2026-07-27-1d-modular-capsule-swap-design.md). Kernbefund: Kapsel-
  Tausch ist nativer drt-Core (→ [METHODS-LOG](METHODS-LOG.md) §1.1/§3.4).

- **Fallback-Audit Folgearbeit: M2 (strikte Parcel-Attribute) + M6 (Fare-Modul-Skip)** — ✅
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

- **Fallback-Audit Lausitz: die vier scharfen Befunde** — ✅ Gezielter Durchgang durch
  `hagrid/integrated/**` (~3,2k LOC) + `analysis/kpi/` (~11k LOC) nach Fallbacks, die greifen
  statt zu scheitern. Behoben:
  - **Pax-KPIs auf Shared-Use-Runs waren paketkontaminiert, die Korrektur war toter Code.**
    `extract_shareduse` berechnete `*_pax_only`, aber `render.HEADLINE_KPIS`, `render_drt`-Kacheln
    und `economics.py` lasen alle das kontaminierte `drt_rides`/`wait_median`/`modal_share_drt`.
    Neu: `analysis/kpi/pax_only.py` tauscht einmalig zentral (`<name>_pax_only` → `<name>`, Stock →
    `<name>_incl_parcels`) vor `economics.extract`; `extract_shareduse` liefert jetzt zusätzlich
    p95/below-10/below-15/in-vehicle-time/Detour/Trip-Distanz (Legs-CSV), pax-only Rejections
    (Rejections-CSV) und **alle** Modal-Shares (output_trips — gemeinsam, sonst summieren sie
    sich nicht zu 1). Was vehicle-seitig unkorrigierbar bleibt, steht als
    `meta/parcel_contaminated_kpis` in der CSV statt sich als Passagierzahl auszugeben.
    ⚠️ **Konsequenz für alte Zahlen:** → [METHODS-LOG](METHODS-LOG.md) §2.4.
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
  siehe „Fallback-Audit" in [BACKLOG.md](BACKLOG.md).

## 2026-07-20

- **MATSim-Core-Bump `2025.0-PR3552` → `2025.0`** — ✅ Branch `bump/matsim-2025.0`
  (von `hendrik`, **nicht** gemergt/gepusht). Approach A (in-place Property-Bump, fix-forward).
  Ergebnis: freight-Fork kompiliert **unverändert** gegen 2025.0 (kein PR3552-Patch-Drop nötig);
  matsim-lausitz-2.0 binär **kompatibel** (keine Transitive-/`NoSuchMethod`-Konflikte, kein
  Lausitz-Neubau). API-Delta nur in HAGRID-eigenem Code:
  (1) `DrtConfigGroup`/`DvrpConfigGroup`/`RebalancingParams`/`MinCostFlow…Params` public-fields
  → getter/setter; (2) Rebalancing-Zone-System + Target-Link-Selection von entferntem
  `DrtZoneSystemParams` (DRT-Gruppe) → `RebalancingParams`
  (`getZoneSystemParams`/`setTargetLinkSelection`); (3) `DefaultDrtOptimizationConstraintsSet`
  → `DrtOptimizationConstraintsSetImpl`; (4) `DisallowedNextLinks`
  → `core.network.turnRestrictions`; (5) `FleetWriter` braucht `DvrpLoadType`
  → `IntegerLoadType("passengers")` (DVRP-Default); (6) `ReturnToDepotRebalancingModule` holt die
  Rebalancing-`ZoneSystem` jetzt aus dem modalen `MapBinder` (`REBALANCING_ZONE_SYSTEM`) statt via
  `getModal(ZoneSystem.class)`.
  Volle Suite grün: parcel-pipeline **282/0/0** inkl. aller vier e2e
  (Drt/Married/DrtRailIntermodal/Lmd), freight-Modul grün. jsprit bleibt **1.8**. Kein
  Sim-Run/keine Re-Baseline in diesem Scope. Commits `a3349fe`, `c292062`.
  → [Spec](superpowers/specs/2026-07-20-matsim-core-bump-2025.0-design.md) /
  [Plan](superpowers/plans/2026-07-20-matsim-core-bump-2025.0.md)

- **Grilling-Review → 12 Methodik-Verfeinerungen (M1–M12) in den 1c-Plan eingearbeitet** — ✅
  Sektion „Methodology refinements" im
  [1c-Plan](superpowers/plans/2026-07-06-1c-shareduse-cargo-hitching.md): Sitz-Basis 10/8+20 (M1),
  Segment-Split über Kapazität (M2), δ-Dekomposition (M3), skalierte Depot-Pickup-Dwell +
  Provider-Depot-Zuordnung (M4), per-Typ-Zeitfenster (M5), χ-Sweep statt Einzelpunkt (M6),
  Pax-only-Rebalancing (M7), präzises „passenger-primary"-Wording (M8), δ-Konvergenzcheck (M9),
  not-at-home beidseitig konsistent (M10), marginale Joint-Cost-Allokation (M11), DOF-Kontrollarm
  (M12 → [METHODS-LOG](METHODS-LOG.md) §4.2).

## Früher

- **freight Fork + Submodule (Restructure Schritt 1)** — ✅ 2026-07-14. Copy-Vendoring durch
  Submodule `external/matsim-libs` (Fork `TUBS-IVS/matsim-libs`) ersetzt; sim-PC warm + kalt
  validiert. → [Spec](superpowers/specs/2026-07-13-freight-fork-submodule-design.md) /
  [Plan](superpowers/plans/2026-07-13-freight-fork-submodule.md)
- **matsim-lausitz aus `libs/` (Restructure Schritt 2)** — ✅ 2026-07-13. Jar+POM als
  projekt-lokales File-Repo committed.
- **1e KPI-CSV + Dashboard** — ✅ 2026-07-09. Kanonisches `analysis/kpi/`-Paket.
