# HAGRID Backlog — Erledigt

Archiv der abgeschlossenen Backlog-Punkte, mit Nachweis (Verifikation, Commit, Branch).
Konsument: die Frage „haben wir das schon gemacht, und woran sieht man das?".

**Abgrenzung:** offene Arbeit → [BACKLOG.md](BACKLOG.md) · methodische Substanz (Entscheidungen,
Limitations, zurückgezogene Befunde) → [METHODS-LOG.md](METHODS-LOG.md). Erledigtes, das ändert
*wie eine Zahl zu lesen ist*, steht in beiden: Nachweis hier, Konsequenz dort.

Neueste zuerst. _Zuletzt aktualisiert: 2026-07-28._

---

## 2026-07-28

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
