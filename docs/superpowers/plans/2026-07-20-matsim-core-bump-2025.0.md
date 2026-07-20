# MATSim-Core-Bump `2025.0-PR3552` → `2025.0` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, recommended for this plan — see Execution Handoff) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HAGRID von MATSim-Core-Preview `2025.0-PR3552` auf das finale Release `2025.0` heben, sodass der gesamte Maven-Reactor kompiliert und die volle Testsuite grün ist.

**Architecture:** Approach A (in-place Property-Bump, fix-forward) auf eigenem Branch `bump/matsim-2025.0`. Eine einzige Version-Property in der root `pom.xml` fließt in beide Reactor-Module. Der erste Build **ist** der Spike: er zeigt das API-Delta. Reparaturen erfolgen fix-forward in (a) den freight-contrib-Quellen des Submodule-Forks und (b) HAGRIDs eigenem Code. jsprit bleibt `1.8`.

**Tech Stack:** Java 21, Maven (Multi-Module-Reactor), MATSim `2025.0`, jsprit `1.8`, Git-Submodule `external/matsim-libs` (sparse-checkout).

## Global Constraints

- **Ziel-Version (verbatim):** `<matsim.version>2025.0</matsim.version>` (von `2025.0-PR3552`). Einzige Definition: root `pom.xml:22`.
- **jsprit unverändert:** `<jsprit.version>1.8</jsprit.version>` bleibt — kein jsprit-Bump in diesem Plan.
- **Java:** 21 (bereits gesetzt, `pom.xml:19-21`) — nicht anfassen.
- **Branch:** `bump/matsim-2025.0` von `hendrik`. **Kein** Merge nach `master`, **kein** `hendrik`→`master`.
- **Grüner Gate (verbatim-Zielzahlen):** freight-Modul **272/0** Tests, parcel-pipeline **275/0** Tests (Referenz: `.superpowers/sdd/progress.md` Task 4/6/7 der Fork-Arbeit). Zahlen dürfen sich leicht verschieben, müssen aber **0 Failures/Errors** bleiben.
- **Stopp-Punkt:** Nach grüner Suite **sauber stoppen**. **Kein** Sim-Run, **keine** Dashboard-Re-Baseline, **kein** Fork-Rebase (Approach B) in diesem Plan.
- **Eskalation:** Falls die Submodule-freight-Quelle gar nicht gegen `2025.0` kompiliert (≠ punktuelle Patch-Anpassung, sondern struktureller Bruch) → **anhalten und an User melden** (Approach-B-Trigger), nicht eigenmächtig rebasen.
- **Submodule-Änderungen** werden **im Submodule** committed; der Submodule-Pointer in HAGRID wird als eigener Commit aktualisiert.

---

### Task 0: Sauberer Arbeitsbaum + Branch

Der Arbeitsbaum trägt uncommittete Änderungen (Bahn-Zubringer-Entfernung inkl. Tests + BACKLOG/Spec/Plan-Docs). Vor dem Branchen sauber wegcommitten, damit der Bump-Branch nur den Bump enthält.

**Files:**
- Modify (bereits geändert, nur committen): `parcel-demand-2-matsim-pipeline/analysis/kpi/render_maps.py`, `.../maps.py`, `.../tests/test_maps.py`, `.../tests/test_render_maps.py`, `docs/BACKLOG.md`
- Create (bereits geschrieben, nur committen): `docs/superpowers/specs/2026-07-20-matsim-core-bump-2025.0-design.md`, `docs/superpowers/plans/2026-07-20-matsim-core-bump-2025.0.md`

- [ ] **Step 1: Status ansehen**

Run: `git status`
Erwartung: die o.g. modifizierten/neuen Dateien; sicherstellen, dass nichts Fremdes/Sensibles dabei ist (`git diff --stat`).

- [ ] **Step 2: Rail-Entfernung als eigenen Commit (Code + Tests) auf `hendrik`**

```bash
git add parcel-demand-2-matsim-pipeline/analysis/kpi/render_maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_maps.py \
        parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_render_maps.py
git commit -m "$(cat <<'EOF'
refactor(kpi): remove Bahn-Zubringer map layer (empty on Hoyerswerda-scale runs)

Layer was inert on married250 (rail_stops absent from map_data.json); at the
current Hoyerswerda-only extent rail feeders are not meaningful. Re-implementation
tracked in BACKLOG, coupled to the case-study-area expansion. Removes checkbox+JS
(render_maps.py), producer maps._rail_stops + FEED_RADIUS_M + now-unused gzip/ET
imports. Tests: 183/183 green.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Docs (BACKLOG + Spec + Plan) als eigenen Commit**

```bash
git add docs/BACKLOG.md docs/superpowers/specs/2026-07-20-matsim-core-bump-2025.0-design.md docs/superpowers/plans/2026-07-20-matsim-core-bump-2025.0.md
git commit -m "$(cat <<'EOF'
docs: matsim 2025.0 bump spec+plan; backlog jsprit re-assessment + rail re-impl

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

Hinweis: `docs/BACKLOG.md` enthält auch die 1c-Ergänzungen des Users — die kommen hier legitim mit rein (dieselbe Datei).

- [ ] **Step 4: Bump-Branch anlegen**

```bash
git switch -c bump/matsim-2025.0
git status
```
Erwartung: `On branch bump/matsim-2025.0`, working tree clean.

---

### Task 1: Version-Bump + diagnostischer Build (der Spike)

**Files:**
- Modify: `pom.xml:22`

- [ ] **Step 1: Baseline sichern — Suite auf PR3552 ist grün**

Run (Warm-`.m2` vorausgesetzt): `mvn -q clean install -pl freight,parcel-demand-2-matsim-pipeline -am`
Erwartung: BUILD SUCCESS, freight 272/0, pipeline 275/0. Falls hier schon rot → **erst das** klären, nicht den Bump überlagern.

- [ ] **Step 2: Version-Property flippen**

`pom.xml:22`:
```xml
<matsim.version>2025.0</matsim.version>
```
(von `2025.0-PR3552`).

- [ ] **Step 3: Diagnostischer Build, Output vollständig capturen**

Run: `mvn clean install 2>&1 | tee /c/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/07bf8bd4-dafb-463e-9fa4-b63bd3715261/scratchpad/bump-build-1.log`
Erwartung: einer von drei Ausgängen —
  - **(A) BUILD SUCCESS + Suite grün** → springe direkt zu Task 5 (Verifikation), Tasks 2-4 sind No-Ops.
  - **(B) Compile-/Test-Failures** → kategorisieren (Step 4), dann Task 2/3/4 gezielt.
  - **(C) Dependency-Resolution-Fehler** (2025.0-Artefakt oder Lausitz-Transitive nicht auflösbar) → Task 4.

- [ ] **Step 4: Fehler kategorisieren (kein Commit, nur triagieren)**

Aus `bump-build-1.log` extrahieren:
```bash
grep -nE "BUILD FAILURE|ERROR|COMPILATION ERROR|Tests run:|Failed tests|Cannot resolve|NoSuchMethod|NoClassDefFound" \
  /c/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/07bf8bd4-dafb-463e-9fa4-b63bd3715261/scratchpad/bump-build-1.log
```
Zuordnung:
  - Fehler in `external/matsim-libs/contribs/freight/...` (freight-Modul) → **Task 2**.
  - Fehler in `parcel-demand-2-matsim-pipeline/src/...` (HAGRID-Code) → **Task 3**.
  - `Cannot resolve`/`NoSuchMethod`/`NoClassDefFound` mit `matsim-lausitz`- oder Core-Version-Bezug → **Task 4**.

**Noch nicht committen** — erst nach grünem Modul (jeweils am Ende von Task 2/3/4).

---

### Task 2: freight-Modul (Submodule-Quelle) grün gegen 2025.0

Nur ausführen, wenn Task 1 Fehler im freight-Modul zeigte. Bekannter Kandidat: die PR3552-Kompat-Patches (Commit `39ad2ccb` „inline fuel-consumption attribute access") betreffen die freight-contrib **Test**-Quellen. Wenn `2025.0` die frühere Fuel-Consumption-API restauriert hat, brechen genau diese Zeilen und der Patch muss revidiert werden.

**Files (im Submodule `external/matsim-libs`):**
- Modify: `contribs/freight/src/test/java/org/matsim/freight/carriers/CarrierVehicleTypeLoaderTest.java`, `CarrierVehicleTypeReaderTest.java`, `CarrierVehicleTypeTest.java`, `jsprit/DistanceConstraintTest.java`, `jsprit/FixedCostsTest.java`, `jsprit/MatsimTransformerTest.java`, `utils/CarrierControllerUtilsIT.java`, `utils/CarrierControllerUtilsTest.java` (= die von `39ad2ccb` berührten Dateien)

- [ ] **Step 1: Prüfen, was 2025.0 an der betroffenen API erwartet**

Run: `git -C external/matsim-libs show 39ad2ccb --stat` und den betroffenen API-Aufruf im Fehler-Log gegenprüfen. Entscheidung:
  - **2025.0 erwartet die alte (nicht-inlined) API** → Patch `39ad2ccb` reverten.
  - **2025.0 erwartet weiterhin die inlined API** → Patch bleibt, Fehler liegt woanders (zurück zu Task 1 Step 4).

- [ ] **Step 2: Patch reverten (nur falls Step 1 = alte API)**

```bash
git -C external/matsim-libs revert --no-edit 39ad2ccb
# ggf. auch c44fe15a, falls dessen (main-src) Änderung ebenfalls bricht
```
Falls der Revert nicht sauber greift (Kontext divergiert), die betroffenen Zeilen manuell auf die von 2025.0 erwartete API-Form bringen und regulär committen.

- [ ] **Step 3: freight-Modul isoliert bauen**

Run: `mvn clean install -pl freight -am`
Erwartung: BUILD SUCCESS, `Tests run: 272, Failures: 0, Errors: 0`.

- [ ] **Step 4: Submodule-Commit + Pointer-Update in HAGRID**

```bash
git -C external/matsim-libs log --oneline -2
# Submodule-Änderung ist dort bereits committed (revert/manueller commit).
git add external/matsim-libs
git commit -m "$(cat <<'EOF'
build(freight): compat with matsim core 2025.0 (drop/adjust PR3552 patch)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: parcel-pipeline (HAGRID-Quelle) grün gegen 2025.0

Nur ausführen, wenn Task 1 Fehler in `parcel-demand-2-matsim-pipeline/src` zeigte. Bekannter Kandidat: dieselbe Fuel-Consumption-Attribut-Zugriffsänderung wie im Fork, falls HAGRID-Code sie direkt nutzt; ansonsten sonstige Core-API-Signaturänderungen PR3552→2025.0.

**Files:**
- Modify: die vom Compiler im Log konkret benannten `.java`-Dateien unter `parcel-demand-2-matsim-pipeline/src/main/java/hagrid/...` bzw. `.../src/test/java/...`

- [ ] **Step 1: Konkrete Fehlerstellen aus dem Log ziehen**

```bash
grep -nE "parcel-demand.*\.java:\[[0-9]+" \
  /c/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/07bf8bd4-dafb-463e-9fa4-b63bd3715261/scratchpad/bump-build-1.log
```

- [ ] **Step 2: Fix pro Fehlerstelle (fix-forward)**

Für jede gemeldete Zeile die minimal nötige API-Anpassung vornehmen (Signatur/Attribut-Zugriff auf die 2025.0-Form). Keine Verhaltensänderung — nur API-Anpassung. Falls eine Änderung mehr als mechanisch ist (Semantik unklar), **anhalten und melden**.

- [ ] **Step 3: pipeline bauen (ohne lange e2e)**

Run: `mvn clean install -pl parcel-demand-2-matsim-pipeline -am -Dtest='!*EndToEndTest'`
Erwartung: BUILD SUCCESS; Unit-/Guard-Tests grün. (Lange e2e folgen in Task 5.)

- [ ] **Step 4: Commit**

```bash
git add parcel-demand-2-matsim-pipeline/src
git commit -m "$(cat <<'EOF'
fix(pipeline): adapt HAGRID sources to matsim core 2025.0 API

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: matsim-lausitz-2.0 Binärkompat (nur falls in Task 1 aufgetaucht)

Nur ausführen bei `Cannot resolve` / `NoSuchMethodError` / `NoClassDefFound` mit Lausitz- oder Core-Version-Bezug. Die Lausitz-Jar (`libs/.../matsim-lausitz/2.0/matsim-lausitz-2.0.jar`) wurde gegen eine bestimmte Core-Version gebaut.

**Files:**
- Modify (wahrscheinlich): `parcel-demand-2-matsim-pipeline/pom.xml` (Exclusions/Dependency-Management rund um `matsim-lausitz`, Zeilen ~45-90)

- [ ] **Step 1: Konflikt diagnostizieren**

Run: `mvn -pl parcel-demand-2-matsim-pipeline dependency:tree -Dincludes=org.matsim`
Erwartung: zeigt, welche Core-Version `matsim-lausitz` transitiv zieht und ob sie mit `2025.0` kollidiert.

- [ ] **Step 2: Auflösen (kleinste wirksame Maßnahme)**

  - Wenn Lausitz eine ältere Core-Transitive einschleppt → in `parcel-demand-2-matsim-pipeline/pom.xml` per `<exclusions>` ausschließen bzw. `2025.0` explizit vorziehen (`dependencyManagement`).
  - Nur wenn ein echter Binär-Bruch bleibt (`NoSuchMethod` zur Laufzeit trotz aufgelöstem Baum): **Notausgang** — Lausitz-Jar gegen `2025.0` neu bauen (separater Aufwand → **erst an User melden**, nicht still tun).

- [ ] **Step 3: Bauen + Commit**

Run: `mvn clean install -pl parcel-demand-2-matsim-pipeline -am -Dtest='!*EndToEndTest'`
Erwartung: BUILD SUCCESS.
```bash
git add parcel-demand-2-matsim-pipeline/pom.xml
git commit -m "$(cat <<'EOF'
build(pipeline): resolve matsim-lausitz transitive vs core 2025.0

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Volle Suite grün (inkl. e2e-Regression) — der Gate

**Files:** keine (reiner Verifikationslauf).

- [ ] **Step 1: Voller Reactor-Build inkl. e2e**

Run: `mvn clean install 2>&1 | tee /c/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/07bf8bd4-dafb-463e-9fa4-b63bd3715261/scratchpad/bump-build-final.log`
Erwartung: **BUILD SUCCESS**; freight `Tests run: 272, Failures: 0, Errors: 0`; parcel-pipeline `Tests run: 275, Failures: 0, Errors: 0` (inkl. `MarriedBaselineEndToEndTest`, `DrtBaselineEndToEndTest`, `DrtRailIntermodalEndToEndTest`, `LmdBaselineEndToEndTest`).

- [ ] **Step 2: Zielzahlen verifizieren**

```bash
grep -E "Tests run:|BUILD" /c/Users/HENDRI~1/AppData/Local/Temp/claude/c--Users-Hendrik-Bimmermann-Documents-GitHub-HAGRID/07bf8bd4-dafb-463e-9fa4-b63bd3715261/scratchpad/bump-build-final.log | tail -20
```
Erwartung: 0 Failures/Errors über alle Module. Falls eine e2e rot → zurück zu Task 3 (HAGRID-Fix) bzw. Task 2 (freight), NICHT die Zielzahl aufweichen.

- [ ] **Step 3: Kein weiterer Commit nötig** (Verifikation). Bei Bedarf Log-Artefakt aus dem Scratchpad NICHT committen.

---

### Task 6: Sauberer Stopp + Übergabe

**Files:**
- Modify: `docs/BACKLOG.md` (Core-Bump-Punkt nach `## Erledigt` bzw. Status aktualisieren)

- [ ] **Step 1: BACKLOG aktualisieren**

Den Medium-Punkt „MATSim-Core-Bump `2025.0-PR3552` → `2025.0`" auf erledigt setzen (kurzer Nachweis: Branch `bump/matsim-2025.0`, Suite grün freight 272/pipeline 275, welche Patches gedroppt/angepasst, ob Lausitz-Kompat nötig war). 1c-Gate-Vermerk entsprechend entschärfen.

- [ ] **Step 2: Commit**

```bash
git add docs/BACKLOG.md
git commit -m "$(cat <<'EOF'
docs(backlog): matsim core bump 2025.0 done (suite green), 1c gate cleared

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 3: Stopp + Übergabe an User**

Zusammenfassen: Branch-Name, was gebrochen war und wie gefixt, ob Lausitz-Kompat nötig war, ob Approach-B-Eskalation gebraucht wurde. **Kein** Merge, **kein** Push (außer auf User-Zuruf). Hinweis: der **Kontroll-Sim-Run** (married250 auf 2025.0, Kapazität 10/8 per 1c-Spec) läuft jetzt auf Zuruf des Users.

---

## Self-Review

**Spec coverage:** Ziel (2025.0-Bump) → Task 1-5; Build-Topologie/Reparaturstellen (freight-Fork, HAGRID-Quelle, Lausitz) → Task 2/3/4; Validierungs-Gate Option 1 → Task 5; Out-of-Scope (jsprit/Sim-Run/Fork-Rebase) → Global Constraints; offener Punkt „uncommittete Rail-Änderungen" → Task 0; Stopp+Übergabe → Task 6. Alle Spec-Abschnitte abgedeckt.

**Placeholder scan:** Tasks 2-4 sind bewusst **konditional** (fix-forward gegen live-diagnostizierte Fehler) — das ist keine Platzhalter-Schwäche, sondern die korrekte Struktur einer Dependency-Migration; die *Kandidaten* (PR3552-Patch-Dateien, Lausitz-Transitive) und die exakten Kommandos/Zielzahlen sind konkret benannt. Keine „TBD/TODO".

**Type consistency:** Konsistente Zielzahlen (freight 272/0, pipeline 275/0) über Task 1/5; konsistenter Branch-Name `bump/matsim-2025.0` über Task 0/6; konsistente Version `2025.0` über Global Constraints/Task 1.
