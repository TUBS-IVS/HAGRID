# Freight-Modul: Fork + Submodule statt Copy-Vendoring — Design

**Datum:** 2026-07-13
**Status:** Approved (Brainstorming-Session mit Hendrik)
**Kontext:** Schritt 1 des HAGRID-Restructure-Plans (Fresh-Clone-Buildability). Ersetzt das
Copy-Vendoring von `matsim-libs/contribs/freight` (`sync-freight-upstream.ps1`) durch einen
git-nativen Mechanismus: GitHub-Fork mit Patch-Branch, eingebunden als Git-Submodule.

## Problem

- `freight/src` ist eine per Skript einkopierte Momentaufnahme von upstream
  `contribs/freight` (Stand ~2025.0-2025w13). Sie kompiliert **nicht** gegen die gepinnte
  `matsim.version` `2025.0-PR3552` (Core hat u.a. `VehicleUtils.set/getFuelConsumptionLitersPerMeter`
  entfernt) — alle Rechner laufen auf einem veralteten, handkopierten `.m2`-Jar.
- HAGRID-lokale Fixes in 3 Dateien werden vom Sync-Skript **überschrieben** und müssen laut
  Skript-Ausgabe von Hand per `git restore` zurückgeholt werden — fehleranfällig.
- Ziel (User-Entscheidung): git-nativ, **bewusst gebumpt** — kein unbeaufsichtigtes Ziehen von
  upstream `main` (das würde den Build fremdgesteuert brechen, weil upstream gegen neuere
  Core-APIs entwickelt als unsere gepinnte Version).

## Verifizierte Fakten (2026-07-13)

- `refs/pull/3552/head` existiert upstream: Commit `3eecf93313a997dd4d749d5f306d42fa1e22276a`.
  Damit kann der Patch-Branch auf **exakt den Source-Stand des Artefakts `2025.0-PR3552`**
  gesetzt werden — Source und Binary erstmals deckungsgleich.
- Echte HAGRID-Patches sind nur **zwei** Commits:
  - `b91b948` — Null-Path-Guards in `jsprit/NetworkBasedTransportCosts.java` reinstated
    (disconnected networks).
  - `eef72dd` — `informEndCalc` immer aufrufen + Null-Path-Guard in
    `controller/CarrierTimeAndSpaceTourRouter.java`; enthält zusätzlich Reformatierungs-Rauschen
    in `NetworkBasedTransportCosts` (159 Zeilen), semantischer Kern ist klein.
  - Der dritte "Patch" (`2625dc6`, `calcLeastCostPath`-Node-Args in 3 Dateien inkl.
    `PassengerScenarioCreator`) war nur ein Kompilier-Fix gegen den API-Drift des alten
    Snapshots — auf PR3552-Stand kompiliert upstream-freight per Konstruktion gegen den
    PR3552-Core; entfällt voraussichtlich ersatzlos (bei Ausführung verifizieren).

## Entscheidungen (User)

| # | Entscheidung | Gewählt |
|---|---|---|
| D1 | Update-Semantik | Git-nativ, bewusst gebumpt; Stand gepinnt auf Ref passend zur `matsim.version` |
| D2 | Mechanismus | **A: Fork + Submodule** (statt gefiltertem Mirror-Repo oder Patch-Datei-Vendoring) |
| D3 | Erst-Resync-Ziel | **PR3552-Stand jetzt** (nicht mit dem 2025.0-Bump aus 1c zusammenlegen) |
| D4 | Fork-Ort | `HBimmermann/matsim-libs` (User-Account; Org-Transfer später trivial, nur Submodule-URL ändert sich) |
| D5 | Wer bumpt | Claude in einer Session; zusätzlich reproduzierbares Skript `resync-freight.ps1`. Fork-`main` aktualisiert sich NICHT automatisch (bewusst — Build hängt nur am Patch-Branch) |

## Design

### 1. Fork & Branch-Layout

- Fork `HBimmermann/matsim-libs` (via `gh repo fork matsim-org/matsim-libs`).
- Branch **`hagrid/2025.0-PR3552`** = `3eecf93` (PR-Head) + 2 Patch-Commits.
- Die Patches werden **semantisch neu aufgesetzt** (Guards + `informEndCalc` als minimale
  Diffs gegen den PR3552-Stand), NICHT blind cherry-gepickt — `eef72dd` enthält
  Reformatierungs-Rauschen, das Konflikte provozieren würde.
- Beim späteren MATSim-Bump (z.B. 1c Task 1 → `2025.0`) entsteht analog `hagrid/2025.0`
  per Rebase des Patch-Branches auf den Release-Tag `matsim-2025.0`.

### 2. Einbindung in HAGRID

- Submodule unter **`external/matsim-libs`**, URL = Fork, Branch = Patch-Branch,
  `shallow = true` in `.gitmodules`.
- Bootstrap konfiguriert zusätzlich **sparse-checkout auf `contribs/freight`** im Submodule
  (sonst liegen mehrere hundert MB fremder Contribs/Test-Fixtures im Arbeitsverzeichnis).
  Sparse-Checkout ist nicht in `.gitmodules` deklarierbar → Einzeiler im Setup-Skript/README.
- Das **eigene [freight/pom.xml](../../../freight/pom.xml) bleibt** (upstream-Parent
  `matsim-all` passt nicht in unseren Reactor):
  - `sourceDirectory`/`testSourceDirectory`/Resources zeigen nach
    `${project.basedir}/../external/matsim-libs/contribs/freight/src/...`.
  - Surefire bekommt `workingDirectory` = `.../contribs/freight`, damit `MatsimTestUtils`
    die `test/input`-Fixtures relativ zum CWD findet.
  - Dependencies bleiben auf `${matsim.version}` gepinnt (unverändert).

### 3. Resync-Ablauf (ersetzt `sync-freight-upstream.ps1`)

Neues **`resync-freight.ps1 -Tag <upstream-ref>`**:
1. Im Fork-Clone: Upstream-Ref fetchen, Patch-Branch `hagrid/<neue-version>` per Rebase der
   2 Patch-Commits auf den neuen Stand erzeugen, pushen. Konflikte erscheinen nur, wenn
   upstream die ~10 gepatchten Zeilen anfasst — Git meldet sie explizit, statt dass Fixes
   stillschweigend überschrieben werden.
2. In HAGRID: Submodule-Pointer auf den neuen Branch-Stand setzen; `matsim.version` wird im
   selben Commit gebumpt (manuell, bewusst).

### 4. Aufräumen & Doku

- Löschen: `freight/src`, `freight/test`, `sync-freight-upstream.ps1` (ein Lösch-Commit).
- Clone-Anleitung (README + Sim-PC-Doku): `git clone --recurse-submodules` +
  Sparse-Checkout-Bootstrap-Einzeiler. Bestehende Clones: `git submodule update --init`.

### 5. Validierung & Akzeptanzkriterien

- `mvn install` aus **frischem Clone** grün (ohne handkopierte `.m2`-Jars fürs freight-Modul).
- Volle HAGRID-Suite (~271 Tests) grün.
- Nachweis, dass die 2 Guard-Patches wirksam sind (existierende Unit-Tests identifizieren;
  falls keiner die Guards abdeckt, minimalen Test ergänzen).
- Validierung zusätzlich auf dem Sim-PC (ssh sim).

## Risiken

- **Verhaltensdrift** upstream-freight 2025w13 → PR3552: wird von den LMD-e2e-Tests gefangen;
  falls KPI-relevante Änderungen auftauchen, Stopp und Rücksprache.
- **Submodule auf Windows/Sim-PC**: Reibung möglich (Pfade, Bootstrap vergessen). Gegenmittel:
  Bootstrap-Skript + klare Fehlermeldung im Build, wenn das Submodule leer ist
  (z.B. maven-enforcer `requireFilesExist` auf eine Submodule-Datei).
- **PR-Head vs. PR-Merge-Stand**: Das Artefakt wurde ggf. vom Merge-Commit (PR + damaliger
  main) gebaut, nur `refs/pull/3552/head` ist verfügbar. Für `contribs/freight` ist die
  Differenz mit hoher Wahrscheinlichkeit leer; falls der Build dagegen doch nicht kompiliert,
  Fallback = nächstliegender Weekly-Tag, der gegen PR3552-Core kompiliert.

## Out of Scope

- MATSim-Bump auf `2025.0` (gehört zu 1c Task 1; danach ist der Resync ein Rebase).
- `hagrid-input`-Bootstrap und Multi-Module-Split `hagrid-core/hannover/lausitz`
  (Restructure-Schritte 3+4, separate Specs).
- Automatisches Frischhalten des Fork-`main` (bewusst weggelassen, D5).
