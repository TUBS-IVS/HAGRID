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

## Verifizierte Fakten (2026-07-13, Planungs-Grounding)

- **KORREKTUR gegenüber der Brainstorming-Annahme:** `refs/pull/3552/head` (`3eecf93`) ist von
  **2024-11-13** und damit ÄLTER als unser Source (Package heißt dort noch `controler`; die
  Pipeline importiert `org.matsim.freight.carriers.controller` in 6+ Dateien). PR-Head als
  Branch-Basis würde die Pipeline brechen. **Richtige Basis = Tag `2025.0`** (der Tag heißt
  upstream `2025.0`, nicht `matsim-2025.0`): Import-Commit `a81bb9e` sagt "extracted from
  matsim-libs 2025.0", und der Diff Tag↔vendored bestätigt es — **nur die 3 bekannten
  Patch-Dateien differieren** (plus beim Import weggelassene Dateien: deprecated
  `controler/`-Package, `CarrierPlanXmlWriterV1/V2`, `CarrierVehicleTypeLoader`,
  `CarrierVehicleTypeWriterV1`, 2 Writer-Tests, Doxyfile/doxyfilter.sh).
- **Kompilier-Bruch gegen PR3552-Core sind exakt 2 Zeilen** (empirisch, `mvn -pl freight compile`):
  `CarrierVehicleTypeReaderV1.java:76` (`VehicleUtils.setFuelConsumptionLitersPerMeter`) und
  `jsprit/DistanceConstraint.java:86` (`getFuelConsumptionLitersPerMeter`) — die Helper gibt es
  im Nov-2024-Core noch nicht. Ersatz: direkter Attribut-Zugriff mit Key
  `"fuelConsumptionLitersPerMeter"` (identisch zu dem, was die 2025.0-Helper intern nutzen →
  beim späteren Core-Bump verlustfrei rückbaubar).
- Echte HAGRID-Patches (semantisch, gegen Tag `2025.0` verifiziert via `diff -w`):
  - `jsprit/NetworkBasedTransportCosts.java`: 3 Methoden bekommen try/finally um die
    Berechnung (`informEndCalc()` garantiert), `calcLeastCostPath`-Node-Args
    (`fromLink.getToNode()/toLink.getFromNode()` statt Links), einkommentierte
    `if (path == null) return Double.MAX_VALUE;`-Guards.
  - `controller/CarrierTimeAndSpaceTourRouter.java`: Node-Args + Null-Check mit
    RuntimeException ("Network may be disconnected").
  - `usecases/chessboard/PassengerScenarioCreator.java`: nur Node-Args (2 Call-Sites).
- Die Spec `2026-06-05-freight-submodule-design.md` ("Maven-Submodul", explizit "keine
  Git-Submodule") wird durch diese Spec bewusst **superseded** — das dortige Copy-Vendoring ist
  genau die Wartungslast, die hier abgelöst wird.

## Entscheidungen (User)

| # | Entscheidung | Gewählt |
|---|---|---|
| D1 | Update-Semantik | Git-nativ, bewusst gebumpt; Stand gepinnt auf Ref passend zur `matsim.version` |
| D2 | Mechanismus | **A: Fork + Submodule** (statt gefiltertem Mirror-Repo oder Patch-Datei-Vendoring) |
| D3 | Erst-Resync-Ziel | **Jetzt, kompatibel zur gepinnten `matsim.version` `2025.0-PR3552`** (nicht mit dem 1c-Bump zusammenlegen). Planungs-Korrektur: Branch-Basis = Tag `2025.0` (= Herkunft des vendored Source, pipeline-API-kompatibel) + 2-Zeilen-Kompat-Patch für den PR3552-Core — NICHT der PR-Head (der ist von Nov 2024 und API-inkompatibel zur Pipeline, s.u.) |
| D4 | Fork-Ort | `HBimmermann/matsim-libs` (User-Account; Org-Transfer später trivial, nur Submodule-URL ändert sich) |
| D5 | Wer bumpt | Claude in einer Session; zusätzlich reproduzierbares Skript `resync-freight.ps1`. Fork-`main` aktualisiert sich NICHT automatisch (bewusst — Build hängt nur am Patch-Branch) |

## Design

### 1. Fork & Branch-Layout

- Fork `HBimmermann/matsim-libs` (via `gh repo fork matsim-org/matsim-libs`).
- Branch **`hagrid/2025.0-PR3552`** = Tag **`2025.0`** + 3 Patch-Commits:
  1. Parity-Deletions (die beim Vendoring-Import weggelassenen deprecated Dateien:
     `controler/`-Package, alte Writer, Doxyfile — identischer Klassen-Satz wie heute),
  2. HAGRID-Fixes (Guards + try/finally-`informEndCalc` + Node-Args in den 3 Dateien —
     inhaltlich = Datei-Kopie des heutigen vendored Stands, dadurch konfliktfrei exakt),
  3. PR3552-Core-Kompat (2 Zeilen Fuel-Attribut-Zugriff statt der noch nicht existierenden
     `VehicleUtils`-Helper).
- Beim späteren MATSim-Bump (1c Task 1 → Core `2025.0`): Basis-Tag bleibt `2025.0`,
  es entfällt nur Patch-Commit 3 (Branch `hagrid/2025.0` = Tag + Patches 1-2).
  Bei größeren Bumps: neuen Branch vom neuen Tag, Patches cherry-picken (`resync-freight.ps1`).

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
- **Freight-Source (2025.0) ≠ Core-Artefakt (PR3552, Nov 2024)**: bewusste Diskrepanz — exakt
  der Zustand, mit dem alle bisherigen Ergebnisse produziert wurden (der stale `.m2`-Jar wurde
  aus 2025.0-Source gebaut und läuft seit Wochen gegen den PR3552-Core). Der Kompat-Patch
  macht diesen Zustand nur endlich reproduzierbar kompilierbar.

## Out of Scope

- MATSim-Bump auf `2025.0` (gehört zu 1c Task 1; danach ist der Resync ein Rebase).
- `hagrid-input`-Bootstrap und Multi-Module-Split `hagrid-core/hannover/lausitz`
  (Restructure-Schritte 3+4, separate Specs).
- Automatisches Frischhalten des Fork-`main` (bewusst weggelassen, D5).
