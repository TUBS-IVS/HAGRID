# HAGRID Backlog — Erledigt

Archiv der abgeschlossenen Backlog-Punkte, mit Nachweis (Verifikation, Commit, Branch).
Konsument: die Frage „haben wir das schon gemacht, und woran sieht man das?".

**Abgrenzung:** offene Arbeit → [BACKLOG.md](BACKLOG.md) · methodische Substanz (Entscheidungen,
Limitations, zurückgezogene Befunde) → [METHODS-LOG.md](METHODS-LOG.md). Erledigtes, das ändert
*wie eine Zahl zu lesen ist*, steht in beiden: Nachweis hier, Konsequenz dort.

Neueste zuerst. _Zuletzt aktualisiert: 2026-07-29._

---

## 2026-07-29

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
