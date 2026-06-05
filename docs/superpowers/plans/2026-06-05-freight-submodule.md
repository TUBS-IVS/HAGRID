# Freight Submodule Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the MATSim freight contrib from Maven into an editable local Maven submodule inside the HAGRID repo, so the freight source can be modified directly and synced from upstream on demand.

**Architecture:** A new root `pom.xml` wraps both `freight/` and `parcel-demand-2-matsim-pipeline/` as Maven submodules. The freight source is extracted once via sparse-clone from `matsim-org/matsim-libs` at the currently-used version tag. A PowerShell script handles future upstream syncs.

**Tech Stack:** Maven 3.x multi-module, Java 21, PowerShell 5.1, GitHub API / git sparse-checkout

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Create | `pom.xml` | Root parent POM — shared properties, repos, module list |
| Create | `freight/pom.xml` | freight module POM — adapted from upstream, points to root parent |
| Create | `freight/src/...` | freight source extracted from matsim-libs |
| Modify | `parcel-demand-2-matsim-pipeline/pom.xml` | Add parent ref, remove shared props/repos, swap freight dep |
| Create | `sync-freight-upstream.ps1` | Script to pull updated freight sources from upstream |

---

## Task 1: Create Root POM

**Files:**
- Create: `pom.xml`

- [ ] **Step 1: Create `pom.xml` in repo root**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">

  <modelVersion>4.0.0</modelVersion>
  <groupId>hagrid</groupId>
  <artifactId>hagrid-parent</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>HAGRID Parent</name>

  <modules>
    <module>freight</module>
    <module>parcel-demand-2-matsim-pipeline</module>
  </modules>

  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <maven.compiler.release>21</maven.compiler.release>
    <matsim.version>2025.0-2025w13</matsim.version>
    <geotools.version>31.1</geotools.version>
    <jsprit.version>1.8</jsprit.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.plugin.version>3.11.0</maven.compiler.plugin.version>
    <maven.clean.plugin.version>3.1.0</maven.clean.plugin.version>
    <maven.resources.plugin.version>3.0.2</maven.resources.plugin.version>
    <maven.surefire.plugin.version>3.5.2</maven.surefire.plugin.version>
    <maven.jar.plugin.version>3.0.2</maven.jar.plugin.version>
    <maven.install.plugin.version>2.5.2</maven.install.plugin.version>
    <maven.deploy.plugin.version>2.8.2</maven.deploy.plugin.version>
    <maven.site.plugin.version>3.7.1</maven.site.plugin.version>
    <maven.project.info.reports.plugin.version>3.0.0</maven.project.info.reports.plugin.version>
  </properties>

  <repositories>
    <repository>
      <id>matsim</id>
      <url>https://repo.matsim.org/repository/matsim</url>
    </repository>
    <repository>
      <id>osgeo</id>
      <name>OSGeo Release Repository</name>
      <url>https://repo.osgeo.org/repository/release/</url>
      <snapshots><enabled>false</enabled></snapshots>
      <releases><enabled>true</enabled></releases>
    </repository>
    <repository>
      <id>pt2matsim</id>
      <url>https://repo.matsim.org/repository/matsim/</url>
    </repository>
  </repositories>

</project>
```

- [ ] **Step 2: Commit**

```powershell
git add pom.xml
git commit -m "build: add root parent POM for multi-module setup"
```

---

## Task 2: Extract Freight Sources via Sparse-Clone

**Files:**
- Create: `freight/src/` (all source files from matsim-libs)

This downloads only the `contribs/freight/src` directory from matsim-libs at the version tag matching the currently used `matsim.version` (`2025.0-2025w13`), without pulling the full ~15 GB repo history.

- [ ] **Step 1: Sparse-clone from matsim-libs at the correct tag**

Run from the HAGRID repo root:

```powershell
git clone --no-checkout --depth=1 --filter=blob:none `
  --branch matsim-2025.0-2025w13 `
  https://github.com/matsim-org/matsim-libs.git `
  _matsim-tmp
```

Expected output ends with: `Resolving deltas: 100%`

If the tag name `matsim-2025.0-2025w13` fails with "Remote branch not found", find the correct tag:
```powershell
cd _matsim-tmp
git tag | Select-String "2025w13"
cd ..
```
Then rerun the clone with the correct tag name.

- [ ] **Step 2: Enable sparse-checkout and pull only freight/src**

```powershell
cd _matsim-tmp
git sparse-checkout set contribs/freight/src
git checkout
cd ..
```

Expected: only `contribs/freight/src/` is populated, rest is absent.

- [ ] **Step 3: Create freight directory and copy sources**

```powershell
New-Item -ItemType Directory -Force freight
Copy-Item -Recurse _matsim-tmp\contribs\freight\src freight\src
```

- [ ] **Step 4: Remove temp clone**

```powershell
Remove-Item -Recurse -Force _matsim-tmp
```

- [ ] **Step 5: Verify source files are present**

```powershell
Get-ChildItem freight\src\main\java -Recurse -Filter "*.java" | Measure-Object | Select-Object -ExpandProperty Count
```

Expected: a number > 100 (freight has ~200 Java files).

- [ ] **Step 6: Commit**

```powershell
git add freight/src
git commit -m "build: add freight source extracted from matsim-libs 2025.0-2025w13"
```

---

## Task 3: Create freight/pom.xml

**Files:**
- Create: `freight/pom.xml`

The upstream freight pom references matsim-libs internals (`${project.parent.version}`, version-less deps inherited from matsim parent). This adapted version replaces all of that with explicit versions and our root parent.

- [ ] **Step 1: Create `freight/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">

  <parent>
    <groupId>hagrid</groupId>
    <artifactId>hagrid-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <modelVersion>4.0.0</modelVersion>
  <groupId>hagrid</groupId>
  <artifactId>freight</artifactId>
  <version>1.0-SNAPSHOT</version>
  <name>freight</name>

  <dependencies>
    <dependency>
      <groupId>org.matsim</groupId>
      <artifactId>matsim</artifactId>
      <version>${matsim.version}</version>
    </dependency>
    <dependency>
      <groupId>com.graphhopper</groupId>
      <artifactId>jsprit-core</artifactId>
      <version>${jsprit.version}</version>
    </dependency>
    <dependency>
      <groupId>org.jfree</groupId>
      <artifactId>jfreechart</artifactId>
      <version>1.5.6</version>
    </dependency>
    <dependency>
      <groupId>com.graphhopper</groupId>
      <artifactId>jsprit-io</artifactId>
      <version>${jsprit.version}</version>
      <exclusions>
        <exclusion>
          <artifactId>xml-apis</artifactId>
          <groupId>xml-apis</groupId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>com.graphhopper</groupId>
      <artifactId>jsprit-analysis</artifactId>
      <version>${jsprit.version}</version>
      <exclusions>
        <exclusion>
          <groupId>junit</groupId>
          <artifactId>junit</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>org.matsim.contrib</groupId>
      <artifactId>roadpricing</artifactId>
      <version>${matsim.version}</version>
    </dependency>
    <dependency>
      <groupId>org.matsim.contrib</groupId>
      <artifactId>otfvis</artifactId>
      <version>${matsim.version}</version>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <version>5.14.2</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.logging.log4j</groupId>
      <artifactId>log4j-slf4j2-impl</artifactId>
      <version>2.24.3</version>
    </dependency>
    <dependency>
      <groupId>org.matsim</groupId>
      <artifactId>matsim-examples</artifactId>
      <version>${matsim.version}</version>
    </dependency>
    <dependency>
      <groupId>org.hsqldb</groupId>
      <artifactId>hsqldb</artifactId>
      <version>2.7.4</version>
    </dependency>
    <dependency>
      <groupId>xerces</groupId>
      <artifactId>xercesImpl</artifactId>
      <version>2.12.2</version>
    </dependency>
    <dependency>
      <groupId>org.locationtech.jts</groupId>
      <artifactId>jts-core</artifactId>
      <version>1.16.1</version>
    </dependency>
    <dependency>
      <groupId>jakarta.annotation</groupId>
      <artifactId>jakarta.annotation-api</artifactId>
      <version>2.1.1</version>
    </dependency>
    <dependency>
      <groupId>com.google.inject</groupId>
      <artifactId>guice</artifactId>
      <version>7.0.0</version>
    </dependency>
    <dependency>
      <groupId>jakarta.validation</groupId>
      <artifactId>jakarta.validation-api</artifactId>
      <version>3.1.1</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter-api</artifactId>
      <version>5.11.4</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <repositories>
    <repository>
      <id>jsprit-releases</id>
      <url>https://github.com/jsprit/mvn-rep/raw/master/releases</url>
    </repository>
  </repositories>

</project>
```

- [ ] **Step 2: Commit**

```powershell
git add freight/pom.xml
git commit -m "build: add freight/pom.xml adapted for HAGRID multi-module build"
```

---

## Task 4: Update parcel-demand-2-matsim-pipeline/pom.xml

**Files:**
- Modify: `parcel-demand-2-matsim-pipeline/pom.xml`

Two changes: (1) add `<parent>` so shared properties/repos are inherited from root, (2) swap the freight dependency from the Maven artifact to the local module.

- [ ] **Step 1: Add `<parent>` block directly after `<modelVersion>`**

Insert after line 6 (`<modelVersion>4.0.0</modelVersion>`):

```xml
  <parent>
    <groupId>hagrid</groupId>
    <artifactId>hagrid-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
    <relativePath>../pom.xml</relativePath>
  </parent>
```

- [ ] **Step 2: Remove the `<properties>` block that is now inherited from root**

Delete the entire `<properties>` block (lines 15–33 in the original file). All those properties are now defined in the root POM.

- [ ] **Step 3: Remove the `<repositories>` block that is now inherited from root**

Delete the entire `<repositories>` block (lines 35–55 in the original file).

- [ ] **Step 4: Swap the freight dependency**

Replace:
```xml
    <dependency>
      <groupId>org.matsim.contrib</groupId>
      <artifactId>freight</artifactId>
      <version>${matsim.version}</version>
      <exclusions>
        <exclusion>
          <groupId>org.apache.logging.log4j</groupId>
          <artifactId>log4j-slf4j2-impl</artifactId>
        </exclusion>
        <exclusion>
          <groupId>org.apache.logging.log4j</groupId>
          <artifactId>log4j-slf4j-impl</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
```

With:
```xml
    <dependency>
      <groupId>hagrid</groupId>
      <artifactId>freight</artifactId>
      <version>1.0-SNAPSHOT</version>
    </dependency>
```

- [ ] **Step 5: Commit**

```powershell
git add parcel-demand-2-matsim-pipeline/pom.xml
git commit -m "build: wire pipeline module to root parent and local freight dependency"
```

---

## Task 5: Verify Multi-Module Build

- [ ] **Step 1: Build freight module alone first**

Run from repo root:
```powershell
mvn install -pl freight -DskipTests
```

Expected output ends with:
```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: ...
```

If it fails with missing dependency errors, check that `matsim.version` resolves correctly and the MATSim repository in root pom.xml is reachable.

- [ ] **Step 2: Build full multi-module project**

```powershell
mvn install -DskipTests
```

Expected:
```
[INFO] Reactor Summary:
[INFO] HAGRID Parent ...................................... SUCCESS
[INFO] freight ............................................ SUCCESS
[INFO] parcel-demand-2-matsim-pipeline ................... SUCCESS
[INFO] BUILD SUCCESS
```

If the pipeline fails with `hagrid:freight:1.0-SNAPSHOT not found`, freight was not installed to local Maven cache — re-run Step 1 first.

- [ ] **Step 3: Commit if any fixes were needed during build verification**

```powershell
git add -p
git commit -m "build: fix dependency versions after multi-module verification"
```

Skip this step if no fixes were needed.

---

## Task 6: Create sync-freight-upstream.ps1

**Files:**
- Create: `sync-freight-upstream.ps1`

- [ ] **Step 1: Create `sync-freight-upstream.ps1` in repo root**

```powershell
# sync-freight-upstream.ps1
# Pulls the latest freight sources from matsim-org/matsim-libs.
# Usage:
#   .\sync-freight-upstream.ps1                        # pulls from main branch
#   .\sync-freight-upstream.ps1 -Tag matsim-2025.0-2025w13  # pulls specific release tag

param(
    [string]$Tag = "main"
)

$ErrorActionPreference = "Stop"
$TempDir = "_matsim-sync-tmp"

if (Test-Path $TempDir) {
    Write-Host "Removing leftover temp dir..."
    Remove-Item -Recurse -Force $TempDir
}

Write-Host "Cloning freight sources from matsim-libs ($Tag)..."
git clone --no-checkout --depth=1 --filter=blob:none --branch $Tag `
    https://github.com/matsim-org/matsim-libs.git $TempDir

Push-Location $TempDir
git sparse-checkout set contribs/freight/src
git checkout
Pop-Location

Write-Host "Replacing freight/src ..."
Remove-Item -Recurse -Force freight\src
Copy-Item -Recurse "$TempDir\contribs\freight\src" freight\src

Remove-Item -Recurse -Force $TempDir

Write-Host ""
Write-Host "Sync complete. Changed files:"
git status freight/src --short

Write-Host ""
Write-Host "Review changes: git diff freight/src"
Write-Host "Commit when ready: git add freight/src && git commit -m 'sync: freight from matsim-libs $Tag'"
```

- [ ] **Step 2: Test the script with a dry run (verify it runs without errors)**

```powershell
.\sync-freight-upstream.ps1 -Tag matsim-2025.0-2025w13
```

Expected: script completes, `git status freight/src --short` shows no changes (since sources are already at that tag), and temp dir is cleaned up.

- [ ] **Step 3: Commit**

```powershell
git add sync-freight-upstream.ps1
git commit -m "build: add sync-freight-upstream.ps1 for pulling upstream freight changes"
```

---

## Self-Review Notes

- All `${matsim.version}`, `${jsprit.version}`, `${geotools.version}` references in freight/pom.xml resolve from root parent ✓
- freight is built before pipeline (Maven module order: freight first) ✓
- The `log4j-slf4j2-impl` exclusions previously in pipeline's freight dep are no longer needed — freight/pom.xml now manages its own logging deps ✓
- Upstream freight pom had version-less deps inherited from matsim-libs parent — all made explicit in freight/pom.xml ✓
- Sync script idempotent: re-running at same tag produces no git changes ✓
