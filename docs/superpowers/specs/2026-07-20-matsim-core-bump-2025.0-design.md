# Design: MATSim-Core-Bump `2025.0-PR3552` → `2025.0`

_Datum: 2026-07-20 · Status: Design (User-approved, Approach A) · Autor: Hendrik + Claude_

## Kontext & Ziel

HAGRID hängt aktuell an einem MATSim-Core-**Preview** (`2025.0-PR3552`). Das finale
Release `2025.0` ist als Artefakt verfügbar (liegt bereits im lokalen `.m2` neben `2025.0-PR3552`,
resolved über `https://repo.matsim.org/repository/matsim`).

**Ziel:** kleinster Upgrade-Schritt auf die finale Core-Version `2025.0`. Dies ist der
technische **Gate für Szenario 1c** (Shared-Use/Cargo-Hitching), weil `DvrpLoad` / 2D-Kapazität
erst im finalen `2025.0` stabil vorliegt.

**Nicht-Ziel dieses Bumps:** jsprit-Upgrade (bleibt `1.8`), Sim-Runs, Dashboard-Re-Baseline,
Fork-Rebase. Siehe [Out-of-Scope](#out-of-scope).

## Build-Topologie (verifiziert 2026-07-20)

Relevant, weil der Bump mehrere Artefakte trifft:

- **Single-Source der Version:** `pom.xml` (root) → `<matsim.version>2025.0-PR3552</matsim.version>`
  (`pom.xml:22`). Fließt via `${matsim.version}` in **beide** Reactor-Module (`freight`,
  `parcel-demand-2-matsim-pipeline`).
- **`freight`-Modul hat KEINEN eigenen Quelltext** (`freight/src` leer). Es kompiliert die
  **geforkte freight-contrib-Quelle aus dem Submodule** gegen core `${matsim.version}` +
  jsprit `${jsprit.version}`:
  - `freight/pom.xml:133-134`: `sourceDirectory` = `../external/matsim-libs/contribs/freight/src/main/java`,
    `testSourceDirectory` = `.../src/test/java`.
  - Enforcer prüft, dass das Submodule (sparse-checkout `contribs/freight` +
    `examples/scenarios/logistics-2regions`) vorhanden ist.
- **Submodule `external/matsim-libs`** (Fork `TUBS-IVS/matsim-libs`) trägt HEAD-Commits, die
  explizit „compat with matsim core 2025.0-PR3552" sind — u.a. `39ad2ccb`/`c44fe15a`
  („inline fuel-consumption attribute access", betrifft freight-contrib **Test**-Quellen:
  `CarrierVehicleType*Test`, `jsprit/DistanceConstraintTest`, `FixedCostsTest`,
  `MatsimTransformerTest`, `CarrierControllerUtils*`).
- **`parcel-demand-2-matsim-pipeline`** enthält HAGRIDs eigenen Java-Code, inkl. ~16 Dateien mit
  direkter, tiefer jsprit-API-Nutzung (eigene Constraints/Cost-Functions/StateUpdater). jsprit
  bleibt `1.8` → diese API ist von diesem Bump **nicht** betroffen.
- **matsim-lausitz** ist als projekt-lokale **`2.0`-Jar** gepinnt
  (`libs/com/github/matsim-scenarios/matsim-lausitz/2.0/matsim-lausitz-2.0.jar`), gebaut gegen eine
  bestimmte Core-Version → **Binärkompat-Risikopunkt**.

## Ansatz (Approach A: In-place Property-Bump, fix-forward)

Auf eigenem Branch `bump/matsim-2025.0` (von `hendrik`; kein hendrik→master):

1. `<matsim.version>` `2025.0-PR3552` → `2025.0` in root `pom.xml`.
2. `mvn clean install` — der **erste Compile ist der Spike**: er zeigt sofort das API-Delta
   PR3552 → 2025.0.
3. Reparieren, was bricht (fix-forward):
   - **Submodule-freight-Test-Patches:** Wenn `2025.0` die Fuel-Consumption-API restauriert →
     die PR3552-Patch-Commits droppen/reverten; sonst nachziehen. Änderung passiert **im
     Submodule** und wird dort committed (Submodule-Pointer in HAGRID aktualisiert).
   - **HAGRID-Quelle** (`parcel-demand-2-matsim-pipeline`): etwaige Core-API-Breaks anpassen.
   - **matsim-lausitz-2.0-Binärkompat:** falls Version-Clash/`NoSuchMethodError` o.Ä. auftaucht,
     hier isolieren und lösen (Exclusion/Transitive-Pin oder — Notausgang — Neubau der Lausitz-Jar).
4. **Eskalation zu Approach B (Fork-Rebase) nur falls** die Submodule-freight-Quelle nicht gegen
   `2025.0` kompiliert. Das ist ein Scope-Zuwachs → **vor** Durchführung an User melden, nicht
   still tun.

### Warum A statt B/C

- **B (Submodule-Fork auf sauberen 2025.0-Tag rebasen):** bessere Fork-Hygiene, aber genau der
  teure, upstream-divergente Fork-Rebase — unnötig, wenn A durchkompiliert. Bleibt Backlog-Option.
- **C (separater Compile-only-Spike vorab):** faltet sich in A, da A bereits fix-forward ist
  (Schritt 2 = der Spike). Kein Mehrwert.

## Risiken

| Risiko | Einschätzung | Umgang |
|---|---|---|
| Fuel-Consumption-API in `2025.0` ≠ PR3552 | mittel | Patch-Commits droppen oder nachziehen (Schritt 3) |
| matsim-lausitz-2.0 binär inkompatibel mit core 2025.0 | **echter Unbekannter** | isolieren via e2e-Suite; Exclusion/Pin; Notausgang Lausitz-Neubau |
| freight-Fork kompiliert gar nicht gegen 2025.0 | niedrig-mittel | Trigger für Approach-B-Eskalation (erst an User melden) |
| KPI-Zahlen verschieben sich | erwartbar, **nicht** in diesem Scope geprüft | Kontroll-Sim-Run (User startet auf Zuruf, danach) |

## Validierungs-Gate („Fertig" für diesen Bump)

**Option 1 — Compile + volle Suite grün, dann sauberer Stopp:**

- `mvn clean install` über den gesamten Reactor **grün** (freight-Modul-Tests aus der
  Submodule-Quelle + `parcel-demand-2-matsim-pipeline` JUnit/e2e).
- **freight-272-Regression** grün.
- Kein neuer Sim-Run in diesem Scope.

Nach grüner Suite: **sauberer Stopp + Übergabe.** Den **Kontroll-Sim-Run** (married250 auf
`2025.0`, **mit Kapazitätsänderung wie im 1c-Spec** festgehalten — Baseline 10 Sitze / Shared-Use 8)
startet der User selbst auf Zuruf; der KPI-Vergleich gegen die PR3552-Baseline erfolgt dann dort.

## Out-of-Scope

- jsprit `1.8 → 2.0` (eigener Backlog-Punkt, erst nach Spike; Core-Bump zieht jsprit nachweislich
  **nicht** mit — Upstream steht selbst auf 1.8).
- Sim-Runs / Dashboard-Re-Baseline.
- Fork-Rebase (Approach B), außer als gemeldete Eskalation.
- Autonomie-/Nachhaltigkeits-/Kostenfunktions-Themen.

## Offene Punkte vor Umsetzung

- Aktueller Arbeitsbaum trägt noch **uncommittete** Änderungen (Bahn-Zubringer-Entfernung +
  BACKLOG-Edits). Vor dem Branchen sauber committen/stashen (User-Zuruf — „commit only on ask").
