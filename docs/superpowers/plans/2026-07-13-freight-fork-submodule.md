# Freight Fork + Submodule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the copy-vendored `freight/src` with a git-native setup: fork `TUBS-IVS/matsim-libs` carries branch `hagrid/2025.0-PR3552` (= upstream tag `2025.0` + 3 HAGRID patch commits), consumed as a shallow+sparse git submodule at `external/matsim-libs`; HAGRID's own `freight/pom.xml` redirects its source dirs into the submodule.

**Architecture:** See spec `docs/superpowers/specs/2026-07-13-freight-fork-submodule-design.md`. The Maven module `freight` keeps its artifact identity (`hagrid:freight:1.0-SNAPSHOT`) — only where its sources live changes. Future MATSim bumps become: new branch on the fork from the new tag + cherry-picked patches + submodule pointer bump (scripted in `resync-freight.ps1`).

**Tech Stack:** git submodules (shallow, sparse-checkout cone mode), Maven 3.x, Java 21, PowerShell 5.1, `gh` CLI.

## Global Constraints

- `matsim.version` stays `2025.0-PR3552` — this plan does NOT bump MATSim.
- Work on branch `hendrik`. Never push HAGRID to origin without the user asking; pushing the FORK (new repo `TUBS-IVS/matsim-libs`) is part of the plan and pre-approved.
- Windows: `git config --global core.longpaths true` before any matsim-libs checkout (deep upstream paths exceed MAX_PATH otherwise — empirically hit during planning).
- PowerShell 5.1: no `&&`/`||` chaining; `-Encoding utf8` when writing files other tools read. NEVER create/edit `.bat` files with Edit/Write tools.
- All commands run from repo root `c:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID` unless stated.
- Fork work happens in the scratchpad dir (see Task 2), NOT inside the HAGRID working tree.
- Upstream tag is named `2025.0` (verified via ls-remote; there is NO `matsim-2025.0` tag).

## File Map

| Action | Path | Purpose |
|---|---|---|
| Create | `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/freight/NetworkBasedTransportCostsGuardTest.java` | Pins the null-path guard behavior across the source switch |
| Create (GitHub) | fork `TUBS-IVS/matsim-libs`, branch `hagrid/2025.0-PR3552` | Patched freight source of record |
| Create | `.gitmodules` + gitlink `external/matsim-libs` | Submodule wiring |
| Modify | `freight/pom.xml` | sourceDirectory/testSourceDirectory redirect, surefire workingDirectory, enforcer guard |
| Delete | `freight/src/`, `freight/test/`, `sync-freight-upstream.ps1` | Vendored copy + old sync mechanism |
| Create | `resync-freight.ps1` | Scripted future bumps |
| Modify | `README.md` | Clone/bootstrap instructions |

---

### Task 1: Guard-Pin Test (before touching anything)

A regression test that exercises the HAGRID null-path guard in `NetworkBasedTransportCosts` on a disconnected network. It must pass against the CURRENT vendored source (proving it pins existing behavior), and again after the submodule switch (proving the patches survived the port). It lives in the pipeline module (HAGRID-owned code), not in the freight source tree (which will come from the submodule).

**Files:**
- Create: `parcel-demand-2-matsim-pipeline/src/test/java/hagrid/freight/NetworkBasedTransportCostsGuardTest.java`

**Interfaces:**
- Consumes: `org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts` (from module `hagrid:freight`), jsprit-core `VehicleImpl`/`VehicleTypeImpl`/`Location` (transitive via freight).
- Produces: nothing for later tasks except the invariant itself. Task 4 re-runs this exact test.

- [ ] **Step 1: Write the test**

```java
package hagrid.freight;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;

/**
 * Pins the HAGRID-local patch in NetworkBasedTransportCosts (null-path guard for
 * disconnected networks): unroutable relations must yield Double.MAX_VALUE instead
 * of throwing an NPE. Guards the patch across freight-source resyncs.
 */
public class NetworkBasedTransportCostsGuardTest {

	@Test
	void disconnectedNetwork_returnsMaxValue_insteadOfNpe() {
		// two-component network: n1->n2 (link a), n3->n4 (link b), no connection between them
		Network network = NetworkUtils.createNetwork();
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n3"), new Coord(0, 1000));
		Node n4 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n4"), new Coord(100, 1000));
		Link a = NetworkUtils.createAndAddLink(network, Id.createLinkId("a"), n1, n2, 100, 10, 500, 1);
		Link b = NetworkUtils.createAndAddLink(network, Id.createLinkId("b"), n3, n4, 100, 10, 500, 1);

		NetworkBasedTransportCosts.Builder builder = NetworkBasedTransportCosts.Builder.newInstance(network);
		builder.addVehicleTypeSpecificCosts("guardType", 1.0, 1.0, 1.0);
		NetworkBasedTransportCosts costs = builder.build();

		VehicleTypeImpl jspritType = VehicleTypeImpl.Builder.newInstance("guardType")
				.setMaxVelocity(10.0).build();
		Vehicle vehicle = VehicleImpl.Builder.newInstance("guardVehicle")
				.setStartLocation(Location.newInstance(a.getId().toString()))
				.setType(jspritType).build();

		double distance = costs.getDistance(
				Location.newInstance(a.getId().toString()),
				Location.newInstance(b.getId().toString()),
				0.0, vehicle);
		Assertions.assertEquals(Double.MAX_VALUE, distance,
				"unroutable relation must hit the null-path guard, not NPE");

		double time = costs.getTransportTime(
				Location.newInstance(a.getId().toString()),
				Location.newInstance(b.getId().toString()),
				0.0, null, vehicle);
		Assertions.assertEquals(Double.MAX_VALUE, time);
	}
}
```

Note for the implementer: if `createAndAddLink`'s signature differs (it takes `double length, double freespeed, double capacity, double numLanes`), adapt to the actual `NetworkUtils` API in MATSim `2025.0-PR3552` — check an existing pipeline test for network-building precedent first (e.g. grep `createAndAddLink` under `parcel-demand-2-matsim-pipeline/src/test`). If the guard code path additionally NPEs on vehicle-type lookup before routing, register the type exactly as shown (`addVehicleTypeSpecificCosts("guardType", ...)` + jsprit typeId `guardType` must match).

- [ ] **Step 2: Run it — must PASS against the current vendored source**

```powershell
mvn -pl parcel-demand-2-matsim-pipeline test "-Dtest=NetworkBasedTransportCostsGuardTest" -DfailIfNoTests=true
```

Expected: `Tests run: 1, Failures: 0, Errors: 0` — BUILD SUCCESS. (The guard is already present in the vendored source; this test pins it. If it FAILS with NPE, the test found a real gap between vendored code and expectation — stop and investigate, do not proceed.)

Note: this uses the already-installed `hagrid:freight:1.0-SNAPSHOT` from `.m2` (the vendored freight module itself doesn't compile against PR3552 — that's the point of this whole plan). If the test-compile fails because Maven insists on building freight first, run with `-o -pl parcel-demand-2-matsim-pipeline` or temporarily `mvn install -pl parcel-demand-2-matsim-pipeline` only; document what was needed.

- [ ] **Step 3: Commit**

```powershell
git add parcel-demand-2-matsim-pipeline/src/test/java/hagrid/freight/NetworkBasedTransportCostsGuardTest.java
git commit -m "test(freight): pin null-path guard behavior before submodule switch"
```

---

### Task 2: Fork + Patch-Branch `hagrid/2025.0-PR3552`

Create the fork and build the patch branch: tag `2025.0` + 3 commits. Commit 2 is literally "copy the 3 files from HAGRID's vendored tree" — the vendored files ARE the desired end state (verified during planning: `diff -w` tag↔vendored shows only these 3 files differ). Commit 3 fixes the 2 fuel-helper lines for the PR3552 core.

**Files:**
- Create (GitHub): repo `TUBS-IVS/matsim-libs`, branch `hagrid/2025.0-PR3552`
- Working dir: `$env:TEMP`-scratchpad clone (short path!), NOT inside HAGRID

**Interfaces:**
- Consumes: HAGRID's current `freight/src` (as patch source for commit 2).
- Produces: branch `hagrid/2025.0-PR3552` on `https://github.com/TUBS-IVS/matsim-libs.git` — Task 3 adds it as submodule. Patch commits carry the marker string `[HAGRID]` in their subject so `resync-freight.ps1` can find them mechanically.

- [ ] **Step 1: Create the fork (no clone)**

```powershell
gh repo fork matsim-org/matsim-libs --clone=false
```

Expected: `✓ Created fork TUBS-IVS/matsim-libs` (or "already exists" — both fine).

- [ ] **Step 2: Shallow-clone the fork at tag 2025.0, sparse to contribs/freight**

Use a SHORT work dir to stay clear of MAX_PATH even before longpaths kicks in:

```powershell
git config --global core.longpaths true
$work = "$env:USERPROFILE\ml-fork-work"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
git clone --no-checkout --filter=blob:none https://github.com/TUBS-IVS/matsim-libs.git $work
git -C $work fetch --depth=1 origin tag 2025.0
git -C $work sparse-checkout set contribs/freight
git -C $work checkout -b hagrid/2025.0-PR3552 2025.0
```

Expected: checkout succeeds; `$work\contribs\freight\src` populated. (`--filter=blob:none` keeps it small; blobs fetch on demand.)

- [ ] **Step 3: Commit 1 — parity deletions**

Delete exactly the files the June vendoring import dropped (verified list from planning diff):

```powershell
$base = "$work\contribs\freight"
Remove-Item -Recurse -Force "$base\src\main\java\org\matsim\freight\carriers\controler"
Remove-Item -Force "$base\src\main\java\org\matsim\freight\carriers\CarrierPlanXmlWriterV1.java"
Remove-Item -Force "$base\src\main\java\org\matsim\freight\carriers\CarrierPlanXmlWriterV2.java"
Remove-Item -Force "$base\src\main\java\org\matsim\freight\carriers\CarrierVehicleTypeLoader.java"
Remove-Item -Force "$base\src\main\java\org\matsim\freight\carriers\CarrierVehicleTypeWriterV1.java"
Remove-Item -Force "$base\src\test\java\org\matsim\freight\carriers\CarrierPlanXmlWriterV1Test.java"
Remove-Item -Force "$base\src\test\java\org\matsim\freight\carriers\CarrierPlanXmlWriterV2Test.java"
Remove-Item -Force "$base\src\test\java\org\matsim\freight\logistics\Doxyfile"
Remove-Item -Force "$base\src\test\java\org\matsim\freight\logistics\doxyfilter.sh"
git -C $work add -A contribs/freight
git -C $work commit -m "[HAGRID] drop deprecated controler package and legacy writers (parity with HAGRID vendored tree)"
```

If any single path is missing, note it and continue (upstream layout may drift slightly); if MORE than two are missing, stop — wrong base ref.

- [ ] **Step 4: Commit 2 — HAGRID guard/robustness patches (byte-exact copy from vendored tree)**

```powershell
$h = "c:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\freight\src\main\java\org\matsim\freight\carriers"
$f = "$work\contribs\freight\src\main\java\org\matsim\freight\carriers"
Copy-Item "$h\jsprit\NetworkBasedTransportCosts.java" "$f\jsprit\NetworkBasedTransportCosts.java" -Force
Copy-Item "$h\controller\CarrierTimeAndSpaceTourRouter.java" "$f\controller\CarrierTimeAndSpaceTourRouter.java" -Force
Copy-Item "$h\usecases\chessboard\PassengerScenarioCreator.java" "$f\usecases\chessboard\PassengerScenarioCreator.java" -Force
git -C $work add -A contribs/freight
git -C $work commit -m "[HAGRID] null-path guards, informEndCalc try/finally, calcLeastCostPath Node args"
```

Semantic content (for review): `NetworkBasedTransportCosts` — in `getTransportTime`, `getBackwardTransportCost`-equivalent and `getDistance`, wrap the calc in `try { ... } finally { informEndCalc(); }`, call `calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(), ...)` and reinstate `if (path == null) return Double.MAX_VALUE;`. `CarrierTimeAndSpaceTourRouter` — Node args + `if (path == null) throw new RuntimeException("No path found between " + fromLinkId + " and " + toLinkId + ". Network may be disconnected.");`. `PassengerScenarioCreator` — Node args only (2 call sites).

- [ ] **Step 5: Commit 3 — PR3552-core compat (fuel helpers → direct attribute access)**

In `$f\CarrierVehicleTypeReaderV1.java` (~line 76) replace:

```java
			VehicleUtils.setFuelConsumptionLitersPerMeter(engineInfo, Double.parseDouble(attributes.getValue( "gasConsumption" )));
```

with:

```java
			// [HAGRID] matsim core 2025.0-PR3552 predates VehicleUtils.setFuelConsumptionLitersPerMeter;
			// write the same attribute key the 2025.0 helper uses
			engineInfo.getAttributes().putAttribute("fuelConsumptionLitersPerMeter", Double.parseDouble(attributes.getValue( "gasConsumption" )));
```

In `$f\jsprit\DistanceConstraint.java` (~line 86) replace:

```java
			consumptionPerMeter = VehicleUtils.getFuelConsumptionLitersPerMeter(vehicleTypeOfNewVehicle.getEngineInformation());
```

with:

```java
			// [HAGRID] matsim core 2025.0-PR3552 predates VehicleUtils.getFuelConsumptionLitersPerMeter;
			// read the same attribute key the 2025.0 helper uses
			consumptionPerMeter = (Double) vehicleTypeOfNewVehicle.getEngineInformation().getAttributes().getAttribute("fuelConsumptionLitersPerMeter");
```

Then:

```powershell
git -C $work add -A contribs/freight
git -C $work commit -m "[HAGRID] compat with matsim core 2025.0-PR3552: inline fuel-consumption attribute access"
```

- [ ] **Step 6: Verify — fork tree vs vendored tree must differ ONLY by commit 3**

```powershell
git -C $work diff --no-index --stat "$work\contribs\freight\src" "c:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\freight\src"
```

Expected: exactly 2 files (`CarrierVehicleTypeReaderV1.java`, `DistanceConstraint.java`), a few lines each. Anything else listed = a missed parity file — fix before pushing.

- [ ] **Step 7: Push the branch**

```powershell
git -C $work push origin hagrid/2025.0-PR3552
```

Expected: branch visible at `https://github.com/TUBS-IVS/matsim-libs/tree/hagrid/2025.0-PR3552`. Keep `$work` around until Task 6 passes (cheap re-push if fixes needed).

---

### Task 3: Add the submodule

**Files:**
- Create: `.gitmodules`
- Create: gitlink entry `external/matsim-libs`

**Interfaces:**
- Produces: worktree path `external/matsim-libs/contribs/freight/` — Task 4's POM points there. `.gitmodules` entries: `path = external/matsim-libs`, `url = https://github.com/TUBS-IVS/matsim-libs.git`, `branch = hagrid/2025.0-PR3552`, `shallow = true`.

- [ ] **Step 1: Pre-check ignore rules**

```powershell
git check-ignore -v external/matsim-libs
```

Expected: NO output (exit 1 = not ignored — good). If a `.gitignore` rule matches, add a negation `!external/` line to `.gitignore` in this task's commit.

- [ ] **Step 2: Add submodule (shallow), then sparse-checkout inside it**

```powershell
git submodule add -b hagrid/2025.0-PR3552 https://github.com/TUBS-IVS/matsim-libs.git external/matsim-libs
git config -f .gitmodules submodule.external/matsim-libs.shallow true
git -C external/matsim-libs sparse-checkout set contribs/freight
```

Expected: `external/matsim-libs/contribs/freight/src` exists; other contribs absent from the worktree after sparse-checkout. If the initial `submodule add` full checkout hits "Filename too long" despite `core.longpaths true` (set globally in Task 2), run `git -C external/matsim-libs config core.longpaths true` then `git -C external/matsim-libs checkout -- .`.

- [ ] **Step 3: Sanity check the pinned commit**

```powershell
git -C external/matsim-libs log --oneline -4
```

Expected: the 3 `[HAGRID]` commits on top of the `2025.0` tag commit (`7f2ea0d adapt license file`).

- [ ] **Step 4: Commit**

```powershell
git add .gitmodules external/matsim-libs
git commit -m "build: add matsim-libs fork submodule (hagrid/2025.0-PR3552) at external/"
```

---

### Task 4: Rewire freight/pom.xml, delete the vendored copy

**Files:**
- Modify: `freight/pom.xml`
- Delete: `freight/src/`, `freight/test/`

**Interfaces:**
- Consumes: `external/matsim-libs/contribs/freight/{src,test}` from Task 3.
- Produces: unchanged artifact `hagrid:freight:1.0-SNAPSHOT` — the pipeline module's dependency keeps working. Enforcer rule name: file-exists check on the submodule source root.

- [ ] **Step 1: Add `<build>` section to `freight/pom.xml`**

Insert after `</dependencies>`, before `</project>`:

```xml
  <build>
    <sourceDirectory>${project.basedir}/../external/matsim-libs/contribs/freight/src/main/java</sourceDirectory>
    <testSourceDirectory>${project.basedir}/../external/matsim-libs/contribs/freight/src/test/java</testSourceDirectory>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <!-- MatsimTestUtils resolves test/input relative to the working directory -->
          <workingDirectory>${project.basedir}/../external/matsim-libs/contribs/freight</workingDirectory>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.5.0</version>
        <executions>
          <execution>
            <id>require-freight-submodule</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireFilesExist>
                  <files>
                    <file>${project.basedir}/../external/matsim-libs/contribs/freight/src/main/java/org/matsim/freight/carriers/CarriersUtils.java</file>
                  </files>
                  <message>freight submodule missing or empty. Run: git submodule update --init external/matsim-libs ; git -C external/matsim-libs sparse-checkout set contribs/freight</message>
                </requireFilesExist>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

- [ ] **Step 2: Delete the vendored tree**

```powershell
git rm -r -q freight/src freight/test
```

- [ ] **Step 3: Build the freight module — the moment of truth**

```powershell
mvn install -pl freight
```

Expected: BUILD SUCCESS, tests run from submodule sources with fixtures found (MatsimTestUtils reads `test/input/...` relative to the surefire workingDirectory). This is the first time freight compiles against `2025.0-PR3552` from source. Failure triage: (a) "Symbol nicht gefunden" on fuel methods → Task 2 commit 3 not in the checked-out submodule commit; (b) test-input FileNotFound → workingDirectory config wrong; (c) other API errors → STOP, report — the 2025.0↔PR3552 drift is bigger than the 2 known lines, needs a user decision (do NOT silently patch more API).

Note: upstream `contribs/freight/test/input` lives OUTSIDE `src/` — verify the sparse pattern `contribs/freight` includes it (`Test-Path external\matsim-libs\contribs\freight\test\input` → True).

- [ ] **Step 4: Re-run the guard-pin test and the pipeline suite**

```powershell
mvn -pl parcel-demand-2-matsim-pipeline test "-Dtest=NetworkBasedTransportCostsGuardTest" -DfailIfNoTests=true
mvn -pl parcel-demand-2-matsim-pipeline test
```

Expected: guard test PASS (patches survived the port); full pipeline suite green (~271 tests, several minutes — e2e included). Any pipeline compile error against the new freight jar = API drift the planning diff missed → STOP and report.

- [ ] **Step 5: Commit**

```powershell
git add freight/pom.xml
git commit -m "build: freight module sources from external/matsim-libs submodule; drop vendored copy"
```

(The `git rm` from Step 2 is already staged.)

---

### Task 5: `resync-freight.ps1` + retire the old sync script

**Files:**
- Create: `resync-freight.ps1`
- Delete: `sync-freight-upstream.ps1`

**Interfaces:**
- Consumes: the `[HAGRID]` commit-subject marker from Task 2; fork URL; submodule path `external/matsim-libs`.
- Produces: the documented bump procedure README (Task 6) points to.

- [ ] **Step 1: Create `resync-freight.ps1`**

```powershell
# resync-freight.ps1
# Rebuilds the HAGRID freight patch branch on a new upstream matsim-libs ref and
# repoints the submodule. Deliberate, on-demand — run this when (and only when)
# matsim.version is being bumped. See docs/superpowers/specs/2026-07-13-freight-fork-submodule-design.md
#
# Usage:
#   .\resync-freight.ps1 -UpstreamRef 2025.0 -NewBranch hagrid/2025.0
#
# After success: bump <matsim.version> in pom.xml in the SAME commit as the
# submodule pointer, then run the full suite.

param(
    [Parameter(Mandatory)][string]$UpstreamRef,
    [Parameter(Mandatory)][string]$NewBranch,
    [string]$Fork = "https://github.com/TUBS-IVS/matsim-libs.git",
    [string]$OldBranch = ""  # default: current submodule branch from .gitmodules
)

$ErrorActionPreference = "Continue"
function Invoke-Git {
    git @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed (exit $LASTEXITCODE)" }
}

if ($NewBranch -notmatch '^[a-zA-Z0-9._/-]+$' -or $UpstreamRef -notmatch '^[a-zA-Z0-9._/-]+$') {
    throw "Invalid ref/branch name"
}
if (-not $OldBranch) {
    $OldBranch = git config -f .gitmodules submodule.external/matsim-libs.branch
    if (-not $OldBranch) { throw "Could not read current branch from .gitmodules" }
}

git config --global core.longpaths true
$work = "$env:USERPROFILE\ml-fork-work"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }

Write-Host "Cloning fork (blob-filtered)..."
Invoke-Git clone --no-checkout --filter=blob:none $Fork $work
Invoke-Git -C $work sparse-checkout set contribs/freight
Write-Host "Fetching upstream ref '$UpstreamRef' and old patch branch '$OldBranch'..."
Invoke-Git -C $work remote add upstream https://github.com/matsim-org/matsim-libs.git
Invoke-Git -C $work fetch --depth=1 upstream $UpstreamRef
Invoke-Git -C $work fetch --depth=10 origin $OldBranch

# collect [HAGRID] patch commits from the old branch, oldest first
$patches = git -C $work log --reverse --format="%H %s" "origin/$OldBranch" |
    Where-Object { $_ -match '\[HAGRID\]' }
if (-not $patches) { throw "No [HAGRID] commits found on $OldBranch - aborting" }
Write-Host "Patch commits to carry over:"
$patches | ForEach-Object { Write-Host "  $_" }

Invoke-Git -C $work checkout -b $NewBranch FETCH_HEAD
foreach ($p in $patches) {
    $sha = ($p -split ' ')[0]
    git -C $work cherry-pick $sha
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        Write-Host "CONFLICT cherry-picking '$p'." -ForegroundColor Red
        Write-Host "Resolve in $work, then: git cherry-pick --continue, re-run remaining picks,"
        Write-Host "push with: git -C $work push origin $NewBranch"
        Write-Host "NOTE: if the upstream ref now contains a fix natively (e.g. the fuel-attribute"
        Write-Host "compat patch after a core bump), DROP that commit with: git cherry-pick --skip"
        throw "cherry-pick conflict - manual resolution required"
    }
}

Invoke-Git -C $work push origin $NewBranch

Write-Host "Repointing submodule..."
Invoke-Git config -f .gitmodules submodule.external/matsim-libs.branch $NewBranch
Invoke-Git -C external/matsim-libs fetch origin $NewBranch
Invoke-Git -C external/matsim-libs checkout FETCH_HEAD
Invoke-Git -C external/matsim-libs sparse-checkout set contribs/freight

Write-Host ""
Write-Host "Done. Now (manually, same commit):"
Write-Host "  1. bump <matsim.version> in pom.xml to match '$UpstreamRef'"
Write-Host "  2. git add .gitmodules external/matsim-libs pom.xml"
Write-Host "  3. mvn install  (full suite must be green)"
Write-Host "  4. commit: build: bump matsim to <version>, freight branch $NewBranch"
```

- [ ] **Step 2: Syntax-validate the script (no live run — a real resync only happens at a version bump)**

```powershell
$errs = $null
[System.Management.Automation.Language.Parser]::ParseFile("$PWD\resync-freight.ps1", [ref]$null, [ref]$errs) | Out-Null
if ($errs.Count -gt 0) { $errs } else { "PARSE OK" }
```

Expected: `PARSE OK`.

- [ ] **Step 3: Delete the old script and commit**

```powershell
git rm -q sync-freight-upstream.ps1
git add resync-freight.ps1
git commit -m "build: replace copy-sync with resync-freight.ps1 (fork patch-branch bump)"
```

---

### Task 6: Docs + fresh-clone validation

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: the canonical clone instructions; acceptance evidence for the spec.

- [ ] **Step 1: Add a "Setup / Cloning" section to README.md**

Read `README.md` first; insert near the top (after the project intro, before build instructions if present — adapt placement to the existing structure):

```markdown
## Setup

The freight module sources live in a git submodule (`external/matsim-libs`, a patched
fork of matsim-libs — see `docs/superpowers/specs/2026-07-13-freight-fork-submodule-design.md`).

**Fresh clone:**

    git config --global core.longpaths true   # Windows only, required once
    git clone --recurse-submodules https://github.com/<org>/HAGRID.git
    cd HAGRID
    git -C external/matsim-libs sparse-checkout set contribs/freight   # optional, trims ~1 GB of unrelated contribs
    mvn install

**Existing clone (after pulling this change):**

    git submodule update --init external/matsim-libs
    git -C external/matsim-libs sparse-checkout set contribs/freight

**Bumping the MATSim/freight version:** see `resync-freight.ps1` (header comment).
```

Replace `<org>` with the actual origin URL from `git remote get-url origin`.

- [ ] **Step 2: Fresh-clone validation (submodule fetches from the real GitHub fork)**

```powershell
$fresh = "$env:USERPROFILE\hagrid-fresh-validate"
if (Test-Path $fresh) { Remove-Item -Recurse -Force $fresh }
git clone --recurse-submodules --branch hendrik "file:///c:/Users/Hendrik Bimmermann/Documents/GitHub/HAGRID" $fresh
git -C "$fresh\external\matsim-libs" sparse-checkout set contribs/freight
mvn -f "$fresh\pom.xml" install
```

Expected: Reactor `HAGRID Parent / freight / parcel-demand-2-matsim-pipeline` all SUCCESS, full suite green — **from a clone with zero hand-copied artifacts** (matsim-lausitz comes from `libs/`, freight from the submodule). This is the spec's core acceptance criterion. Note: the submodule URL points at GitHub, so this needs network; the local `file://` clone only covers HAGRID itself.

Afterwards clean up: `Remove-Item -Recurse -Force $fresh` (and `$work` from Task 2).

- [ ] **Step 3: Commit + report**

```powershell
git add README.md
git commit -m "docs: clone/bootstrap instructions for freight submodule setup"
```

Report to the user: build times, test counts, any deviations.

---

### Task 7: Sim-PC validation (checkpoint — needs `ssh sim`)

No file changes in HAGRID. Requires the `hendrik` branch to be PUSHED first — **ask the user** (never push without asking; the unpushed stack is large).

- [ ] **Step 1: Ask the user to push / for permission to push `hendrik`**
- [ ] **Step 2: On the sim PC (`ssh sim`), in the HAGRID clone:**

```
git config --global core.longpaths true
git pull
git submodule update --init external/matsim-libs
git -C external/matsim-libs sparse-checkout set contribs/freight
mvn install
```

Expected: full build + suite green without any hand-copied freight artifacts in `.m2`. (The previously hand-copied freight jar in the sim-PC `.m2` gets overwritten by the fresh `install`.) Watch the known sim-PC quoting gotchas; if the sim PC is not Windows, skip the longpaths line.

- [ ] **Step 3: Report results; update the restructure memory (Step 1 done) via session close**

---

## Self-Review Notes

- Spec coverage: fork+branch (Task 2), submodule+shallow+sparse (Task 3), POM redirect+surefire workingDirectory+enforcer (Task 4), delete vendored+old script (Tasks 4/5), resync script (Task 5), README/sim-PC docs (Task 6), fresh-clone+suite+sim-PC acceptance (Tasks 6/7), guard-patch evidence (Task 1+4). Out-of-scope respected: no matsim.version change.
- The guard-pin test intentionally passes both before AND after the switch — it is a pin, not a red-green TDD test; Task 1 Step 2 documents why.
- Type/name consistency: branch `hagrid/2025.0-PR3552`, path `external/matsim-libs`, marker `[HAGRID]`, enforcer file `CarriersUtils.java` — used identically across tasks.
- Known risk carried forward (spec §Risiken): if Task 4 Step 3 surfaces API drift beyond the 2 fuel lines, STOP-and-ask is written into the step.
