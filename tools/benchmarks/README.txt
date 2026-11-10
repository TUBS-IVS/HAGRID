ZoneBasedTransportCosts: reproducible comparisons
================================================

Measured baseline: HAGRID commit afbaf33 (origin/master when this task started).
The corrected source is in the attached zone-router-fixes worktree.
Runtime: Eclipse Adoptium Java 21.0.7+6, jsprit 1.8, MATSim 2025.0,
pinned freight source commit 2db6789b6c3602c3f10487cdf4d01ad6fdc0c1be.
Each measurement uses a fresh JVM with -Xms1g -Xmx1g. Old/new order alternates.
Do not run builds, simulations or other CPU-intensive work during measurements.

Preparation
-----------
Use separate baseline and corrected checkouts. Initialize external/matsim-libs
in each checkout at the pinned commit. Build each with the same JDK and Maven:

  mvn -pl hagrid/simulation -am test -Dtest=HAGRIDRouterUtilsTest -Dsurefire.failIfNoSpecifiedTests=false

Create an AuditDirectory outside the source tree. Copy the baseline's
hagrid/simulation/target/classes/hagrid/core/routing/ZoneBasedTransportCosts*.class
to AuditDirectory/baseline-classes/hagrid/core/routing/.
Keep all inner classes: the baseline has 14 class files.

Extract java.class.path from a corrected checkout's Surefire XML report:

  [xml]$report = Get-Content hagrid/simulation/target/surefire-reports/TEST-hagrid.core.routing.HAGRIDRouterUtilsTest.xml
  $report.testsuite.properties.property | Where-Object name -eq 'java.class.path' | ForEach-Object {
    [IO.File]::WriteAllText((Join-Path $auditDirectory 'classpath.txt'), $_.value)
  }

Run from the corrected checkout:

  ./tools/benchmarks/run-zone-router-benchmark.ps1 -AuditDirectory $auditDirectory

The runner compiles the benchmark with a Java argument file (Windows command
length limits), suppresses routine logging, and starts each phase separately.
The baseline class directory precedes all corrected classes only for 'before'.
No old and new static caches coexist in the same JVM.

Cases and interpretation
------------------------
startup: 70x70 directed grid, 4,900 nodes/19,320 links. Time to create eight
         worker-specific real SpeedyALT routers, including graph/landmark setup.
         Small independent 8x8 context warms code first. Five paired trials.
hot:     25x25 grid, 128 preloaded pairs, 200,000 warm-up calls followed by
         1,000,000 cached null-vehicle calls. Includes allocation bytes from the
         current HotSpot thread; checksum must match per old/new pair. Five trials.
concurrent: 70x70 grid, eight workers query 256 identical pairs concurrently.
         Uses real SpeedyALT with shared prepared graph, no artificial delays.
         Includes pooled calculator creation on first cache misses. Counts real
         path computations, not inferred cache misses. Targeted collision workload,
         not representative of every production carrier schedule. Five trials.
solve:   Real HAGRIDRouterUtils configuration, 60 services, capacity 40,
         infinite fleet, 30 maximum iterations, same fixed demand and seeds
         42/43/44. Includes algorithm creation, neighborhood setup and search.
         Records actual iterations, objective, unassigned jobs, tour count and
         canonical job-order SHA-256. Zone approximation is disabled.
solve-zones: Same fixture and budgets, with zone caching enabled. Reevaluate the
         assigned routes against exact costs and report longest exact tour
         duration. Zero service/wait monetary cost in this fixture. exact_cost
         excludes unassigned-job penalties, so interpret it together with the
         unassigned count. Different approximations can change search/termination.
zones:   30x30 grid, 500 random pairs, 5x5-cell zones. Reports mean absolute error
         in seconds versus exact paths, order-dependent pair count, and maximum
         absolute error. Three seeds; time column is unused for this case.
         First-pass error is an in-sample diagnostic: the old implementation
         stores the first requested pair as the zone value, so many first-pass
         requests are exact by construction. Do not interpret its lower first-
         pass error as better generalization. Use held-out pairs after independent
         priming for an unbiased approximation comparison. solve-zones instead
         reevaluates the resulting assigned routes with exact transport costs.

These synthetic directed road grids prove specific mechanisms and controlled
solver behavior. They do not establish total Hannover/Lausitz MATSim wall time.
Current Hannover offline CarrierRouter selects NetworkBasedTransportCosts;
ZoneBasedTransportCosts is selected in zone-configured simulation replanning.
Dynamic travel times, modal filtering and endpoint-distance fixes intentionally
correct formerly wrong values; production outputs may therefore change.

Upstream counterexample
-----------------------
AffectedJobLowerBoundProbe.java uses the actual jsprit 2.0.0 package-private
AffectedJobTracker and BoundedInsertionQueue. Compile/run it with jsprit 2.0.0
first on the classpath. A job midway between two route stops adds zero distance,
but the tracker rejects it against second-best insertion cost 10 because its
nearest-stop distance is 50. This proves the claimed lower bound is not valid;
it does not measure whole-solver quality loss. The same rule is present in
upstream commit 2a4ccbc2f53a39e936ee45d512bd54c8029cf9be (2.1.0-SNAPSHOT).

Separate upgrade smoke check (not part of the timed comparisons)
--------------------------------------------------------------
All 370 current HAGRID and freight main sources compiled against core/analysis
2.0.0, the existing jsprit-io 1.8, the same Java 21 and existing dependencies.
Lombok annotation processing and the freight-provided jsr305 annotation jar
were required. Classes were written to a separate audit directory, leaving
the tested 1.8 build intact. One real solve fixture (seed 42, 60 services,
30 iterations) ran with 0 unassigned jobs and 2 routes. Its exact cost was 3820
versus 3720 on 1.8: compilation and this smoke check do not prove equal quality,
XML-algorithm compatibility, or production readiness of the mixed io dependency.
The repository dependency versions remain unchanged in that earlier smoke check.

Recurring integration comparisons (2026-10-07 onwards)
------------------------------------------------------
run-routing-comparison.ps1 compiles the same small probe separately against two
built checkouts. First run HAGRIDRouterUtilsTest with Java 21 in each checkout;
the runner reads that checkout's own Surefire classpath. Example:

  ./tools/benchmarks/run-routing-comparison.ps1 `
    -BaselineCheckout <built-reference-checkout> `
    -CandidateCheckout <built-candidate-checkout> `
    -Checkpoint B1 -Cases services -Seeds 42,43,44,45,46 -Repeats 3 `
    -JavaHome <Java-21-JDK> -OutputDirectory tools/benchmarks/results/B1

Only services is supported initially; shipment, temporal, modal and group cases
are added with their integration phase. The 35x35 grid and fixed 60-service
demand are generated by the versioned probe. Each fresh JVM performs two full
warmup solves before one measurement. Baseline/candidate order alternates.
Preparation includes algorithm construction; search and final validation are
also measured separately. Final costs, capacity and time constraints are
reevaluated independently using the exact Manhattan paths of this road grid.

Defaults while Valhalla runs: one trial at a time, 384 MiB maximum Java heap,
64 MiB initial heap, one visible CPU and Windows Idle process priority. Below
6144 MiB available physical memory only our own trial is stopped/deferred.
The compiler is bounded to 256 MiB and one visible CPU. Heap samples (5 ms)
and process working-set samples (200 ms) are observations, not guaranteed peaks;
heap limit is not a total process-memory limit. Allocated bytes are cumulative
main-thread allocations, not resident RAM. Background competition is recorded;
general speed claims require a later check on a quiet host.

Each fresh output directory contains manifest.json, measurements.csv and
comparison.md. Keep all three when recording a checkpoint. Generated classes,
launcher arguments, large classpath text and logs are ignored in results/.
The manifest records source, probe, policy and classpath hashes and exact budgets.
Identical product sources are classified as measurement variation, never a code
improvement. Inspect all repeated measurements: median numeric differences alone
do not establish a speedup. Incomplete or infeasible plans, changed rules/inputs,
seeds or budgets cannot qualify as an improvement. B0 freezes the unchanged
1.8 reference independently of the previously prepared ZoneBased patches.

Actual iterations are checked separately from the configured ceiling. Existing
no-improvement termination may stop versions at different iterations: B1 seed
46 is 30/27 and is excluded from a performance/improvement claim. The regression
test failed first, then passed. The raw B1 CSV is unchanged; manifest.json keeps
the as-run policy hash and a separate corrected reevaluation policy hash.
GoldenMigrationProbe.java reuses the existing tiny stageLmdFixture through the
built test classpath in each version-specific JVM; its old/new XML snapshots and
version-specific golden hashes document the isolated equivalent-order change.
Phase 1 full verify, shaded JAR launch/content/fingerprint and large scenarios
remain pending while Valhalla has resource priority. See results/B1/migration-status.md.

B3a small travel-time snapshot capture (2026-10-07):
  ./tools/benchmarks/run-snapshot-capture.ps1 -OutputDirectory <fresh-directory>
Requires a Java 21 build and a fresh HAGRIDRouterUtilsTest Surefire classpath.
1000 generated links, two modes, 96 bins, one variable profile; two warmups and
three captures. One visible CPU, 128 MiB Java heap, Idle priority; 256 MiB javac
heap. Minimum 6 GiB free physical memory checked before/after compilation and
during the own child process. Only the own probe is stopped on pressure.
measurements.csv and manifest.json record capture duration, logical numeric
payload, equivalent dense numeric fields, main-thread allocated bytes,
instantaneous process-wide heap and sampled process working set separately.
None is a measured retained-snapshot heap or a general solver-speed comparison.
A reproducible checked-in run is results/B3a-snapshot-2026-10-07.
The snapshot is bin-start sampled; FIFO and arbitrary within-bin event/interpolation
preservation are separate integration gates. RoutingContext/lifecycle wiring is pending.

Single-carrier replay (K148)
---------------------------
Optional API: hagrid.core.routing.CarrierReplay.exportCase(caseDirectory,
carrier, frozenRequest, resolvedConfig, seed).
Sibling cases reference one parent/.contexts/<fingerprint>.zip; include that
directory when moving a case. Original graph, compressed snapshot, fractional
network events and exact resolved settings are re-read and certified before
publication. Missing/corrupt context is an error, never a free-speed fallback.
Input metadata omitted by native XML is rejected rather than silently changed.
Existing case directories are not overwritten.

CLI after verifying the actual packaged JAR:
  java -Xms32m -Xmx384m -XX:ActiveProcessorCount=1 -cp <HAGRID-jar> hagrid.core.routing.CarrierReplay <case-directory>

While Valhalla is active, launch this only through Invoke-HagridGuardedProcess
(ResourceGuard.ps1), with the same 6 GiB free physical reserve. JVM caps alone
are insufficient. The small standalone JVM is tested; packaged JAR acceptance
remains separate. Replay uses the production CarrierOptimizationService and
the explicit replanning/offline iteration value from the captured config.
A different runtime/code/jsprit build is a distinct experiment, not an assured
reproduction of the old search result. This is not a MATSim checkpoint.
Aktuelle geschützte Paket- und Referenzprüfungen (2026-10-09)
---------------------------------------------------------
run-bounded-tests.ps1 -Tests <kleine Rootklassen> -MavenPhase verify -Log <neuer Logpfad>
run-package-replay-smoke.ps1 -OutputDirectory <neues Verzeichnis>
run-carrier-memory-reference.ps1 -OutputDirectory <neues Verzeichnis>
run-carrier-memory-reference.ps1 -CompareZones -OutputDirectory <neues Verzeichnis>

Diese Launcher benutzen ResourceGuard auch für eigene javac-/java-Prozesse.
6 GiB freie physische Reserve, niedrige Priorität und CPU-Druck-Abbruch bleiben aktiv.
CompareZones: exakt/Zone, gleiche 64 Knoten/224 Links, Integer-Zonen, 200m Schwelle,
Services/Shipments, 16/32 Jobs, fünf Seeds, abwechselnde Variantenreihenfolge,
fünf serielle Gruppen mit je vier Carriern; erste zwei Gruppen Aufwärmung.
Explizit zehn tatsächliche Suchiterationen, 1 sichtbare CPU und 384 MiB je JVM.
Das adaptive Minimum=Maximum=10 erzwingt nur dieses feste Vergleichsbudget;
es aktiviert keine neue Produktionsvorgabe. Endkosten werden unabhängig geprüft.
Phasenzeiten, Fahrzeuge, Router-/Cache-Bestand, Heap und Allokationen im CSV.
Die Instrumentierung misst zusätzlich erreichbare Ergebnisobjekte, keine exklusive
transiente Carrier-RAM-Spitze. Kumulative Thread-Allokation ist kein gehaltener Heap.
Historische ungeschützte Benchmark-Launcher während Valhalla nicht ausführen.
Neue Verzeichnisse sind Pflicht; vorhandene Evidenz wird nicht überschrieben.
K176: 1332/1332 kleine Tests und Paketbau bestanden.
K177: genau dieses shadedJAR in separaten Service-/Shipment-Replay-JVMs bestanden.
Repräsentative Produktionsmessungen und größere Parallelitätsversuche bleiben offen.

Latest paired zone-index reference (K196):
run-carrier-memory-reference.ps1 -OutputDirectory <fresh-directory> -ReferenceJar <preserved-old-jar> -JobCounts 16
summarize-zone-comparison.ps1 -InputDirectory <completed-directory> -CompareBuilds
The protected runner keeps one CPU / 384 MiB per own measurement JVM, six eligibility samples, the shared mutex and live RAM/CPU guard. It compiles one current probe for both archived/current builds and alternates build order by seed.
Five seeds, five groups (two warmups), exact 10 executed iterations per carrier. Summary rejects differing costs/vehicles in every structural-refactor pair. Added sharedContextGraphBytes and canonicalInputsAndPublishedPlansGraphBytes are retained identity graphs, not total native MATSim base RAM or exclusive transient solver peaks. Reported totalSeconds sums solver-service phases and excludes context/provider construction, sizing, publication and CLI startup.
K196 reference and current packages are archived byte-identically at progress/k179-reference-hagrid.jar and progress/k193-zone-context-current-hagrid.jar. Results do not license production extrapolation or changing the exact-routing default.
Small native search-policy comparison (K206):
run-search-policy-comparison.ps1 -OutputDirectory <fresh-directory> [-Seeds 42,43,44,45,46]
summarize-search-policy-comparison.ps1 -InputDirectory <completed-directory>

All own javac/java processes use ResourceGuard; fixed1CPU/384MiB, serial.
Exact8x8network,16jobs,4carriers/5groups, first2warmup. Fixed10 vs warm10 vs
adaptive minimum5/maximum10/plateau3. Identical old tours are generated with a
separate configuration-matched fixed-reference context and certified per pair.
Current candidates and independently selected/publishable winners are complete.
CSV separates candidate/selected costs, actualiterations, warmacceptance,
phases/selection times and cumulative thread allocation. Summary requires five
completed root seeds, every row/triple/certificate and counts individual worse
winners in addition to seed medians. Timed pipeline excludes old-tour/context
setup and CLI startup; adaptive changes work, no equal-work speedup claim.
Historical K206 uses seed+carrierIndex: eight unique carrier seeds across the
five four-carrier root blocks. Its evidence stays unchanged. New runs use
paired-distinct-v2: 8*rootSeed+2*carrierIndex for input/current search, plus1 for
the separately solved reference. Five distinct signed-int roots produce20
distinct carrier starts per kind; repeated groups reuse them. Distinct seeds
are not proof of statistical independence. The summary validates the protocol,
source hash identifier and row seeds, discloses legacy overlap, and publishes
without overwriting even a concurrently created output. Defaults remain false
for warm/adaptive. K230 is only a one-root/two-job native smoke, not a five-seed
performance campaign.
K207 actual invalid-data controls reject missing/duplicate rows, short budgets,
changed old-plan hashes, NaN and rejected warm inputs. All raw runs retained.
Run-owned admission budget (Task 14, K208-K214)
CarrierRunBudget.State takes explicit heap limit/shared current heap/heap reserve and available physical memory/physical reserve/native headroom, all in bytes. Available reservation ceiling is the positive minimum of both overflow-safe margins. The optional forCurrentJvm observer counts aggregate heap including MATSim/context/cache/published plans and uncollected garbage; it is neither exclusive carrier sizing nor a live watchdog. The owner must supply valid physical observations, conservative reserves and calibrated carrier estimates.
CarrierBatchExecutor(int, LongSupplier, Duration) takes one fresh snapshot per nonempty group AFTER claiming exclusive group ownership. A concurrent caller is rejected before sampling; observation/preflight failures release ownership for retry. Existing constant-budget and serial constructors remain compatible. All staged carrier reservations remain held through caller-thread publication; previously published plans belong to the next shared base observation. Empty groups need no memory sample. No auto production pool or default memory policy is enabled.
K214: 68/68 targeted controls including the deterministic stale-snapshot counterexample, failed-probe retry and actual native two-group publication preservation. Synthetic byte assertions are accounting controls, not representative calibration. K213 was resource-deferred for sustained CPU pressure; K215 passed 1362/1362 tests from 158 small roots and verify/package; K216 passed fresh packaged Service/Shipment replay (complete, same context, costs100.25/94.0). Current package SHA256 461F5972F2970EE56C007778B7BD8B2ECAE7FD91E620BA9C6915E2B82348001C. All JVM launches still require ResourceGuard with the agreed 6 GiB reserve; this admission API does not replace it.
The K202/K206 search-policy package is preserved byte-for-byte in .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k202-search-policy-reference-hagrid.jar, SHA256 3AA0B685EADEF4580315B6D11FCD432E5EB7A48BCBD047192664EE174A4F71FE. Later package builds do not retroactively change those recorded comparisons.

Captured-carrier memory profiling (Tasks 13/14, K217-K223)
------------------------------------------------------
./tools/benchmarks/run-replay-memory-profile.ps1 -CaseDirectories @('<captured-service-case>','<captured-shipment-case>') -Repeats 2 -OutputDirectory '<new-output-directory>'

Uses the current shaded JAR and the SAME certified CarrierReplay.replay API. Captured demand, old plan, network, exact travel-time context, settings and seeds are read unchanged; payload/context/JAR digests are checked before and after every run. One to four distinct cases, one to three fresh serial JVM repeats, one visible CPU/384 MiB; each javac/java uses ResourceGuard with live 6 GiB reserve and CPU-pressure abort. Captured internal solver threads must be1. Small-run bounds: 128 jobs, payload files<=1MiB each, compressed context<=8MiB/expanded<=32MiB, effective iteration ceiling<=20. Native adaptive maximum overrides the manifest iteration value: both requested/effective ceilings are recorded and the effective native ceiling is gated BEFORE any JVM. Config is never rewritten to fit limits.

Each run records sampled WHOLE-JVM used heap during replay (5ms observations), before/after heap, reachable RETURNED result graph, caller-thread allocated bytes, GC count/time, elapsed replay time, actual iterations, cost and vehicles. The observer stops and drains before the separate graph traversal. Elapsed replay time includes context/input restoration and optimization/validation, excludes JVM startup/probe setup and the separate graph traversal. The returned graph is measured after replay closed its context; it is neither total simulation retained state nor staged-group storage. No forced GC. Sampled peaks may miss brief maxima; observations include runtime, shared objects, garbage and profiling overhead. Caller allocation omits native worker-thread allocation. Neither value is exclusive carrier RAM or an automatically calibrated production reservation.

manifest.json preserves inputs, package/probe/runner/agent hashes, raw measurements, own resource observations and failure/deferral reasons; measurements.csv is only published after all cases complete. Reused output directories are rejected without overwriting evidence. This is a measurement tool, no automatic budget activation or performance recommendation.

K222 final smoke: four fresh JVM observations, two repetitions each for the certified one-job Service/Shipment cases from K216, same complete costs100.25/94.0 and2iterations. Separate clean captures confirm effective21 rejected before any JVM, requested2/effective8 accepted with8actual iterations. K223 nine rejection controls pass, including rehashed semantic corruption still rejected by the native canonical certificate and preserved old output. These are tiny SYNTHETIC captured controls, not representative Hannover/Lausitz calibration. Data: tools/benchmarks/results/k222-replay-memory-20261009 and .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k220-adaptive-profile-bound/k222-fixed8. K221 was CPU-deferred before replay; no success inferred. Product JAR remains K215 SHA461F5972F2970EE56C007778B7BD8B2ECAE7FD91E620BA9C6915E2B82348001C.

Strict replay-profile aggregation (K224-K235)
-------------------------------------------
python tools/benchmarks/summarize-replay-memory-profile.py --input-directory <completed-profile> --output-directory <new-summary-directory>
python -m unittest discover -s tools/benchmarks/tests

No Java, solver or live routing request is started by aggregation. Complete
manifest/capture/context/raw-log/process evidence is required. Failed, deferred,
missing, conflicting, changed or incomplete evidence is rejected; retained
input files are never rewritten. The manifest digest is bound to the bytes
actually validated. A valid prestart CPU observation is mandatory; a short
process without a runtime CPU sample is explicitly reported, never assigned0.
A fresh summary directory contains summary.json and plain German overview.txt.
Whole-JVM heap, returned graph, allocation, GC, replay time and process working
set are separate descriptive observations, not an admission estimate.

K233 aggregates unchanged K222 synthetic one-job evidence. K232:31 controls
passed; K235: the same31 passed from an isolated tree containing only the tool
sources and bounded fixtures (no historical result directories or product JAR).
Requires Python3 and PowerShell7/pwsh on PATH. fixtures/provenance.json records
byte-identical native fixture sources and hashes; fixture manifests retain
historical metadata, while tests resolve input paths to fixture-local copies.
These fixtures are not fresh measurements or representative production data.


Isolated native core-version comparison (K242-K249)
--------------------------------------------------
./tools/benchmarks/run-jsprit-core-version-comparison.ps1 -OutputDirectory '<new-output-directory>'
Optional -Seeds @(42,43,44,45,46) -Repeats 3;1-5 distinct signed-int roots,1-3 repeats.
Standalone jsprit-core1.8/2.0.0 with identical commons-math3/SLF4J/trove JARs.
No application/MATSim classes or core2fallback in1.8classpath. Static8x8matrix,
16services,finite2vans/cap8,common fixed100+transport-time objective. Actual10
iterations,no premature termination. Native job/capacity/location/depot/horizon/
objective certificate. Two warmups then measured search; no setup/validation/
start timing. Caller allocation=-1 if unavailable, not peakRAM.
Every javac/java serial1CPU/256compiler or384probe under ResourceGuard.
Own fresh directory; existing evidence preserved. Manifest binds launcher/probe/
guard/core/dependency hashes and resources; incomplete/deferred is not MEASURED.
K2485real preflight rejection controls passed without JVMs.
K249 final5roots/3repeats/core comparison MEASURED/31own completed processes:
median searchtime2.0/1.8=1.382,costratio1.0588,allocationratio1.269;
3worse/1equal/1better costs. Short-runtime CPU samples unavailable, valid prestart.
No full B0/B1/B7 integration/production recommendation. K244full/K247partial
preserved separately; do not average. K24693/93 combined integration and
K25017/17 XML-Builder controls pass. Multi-bin decrease remains a FIFO-policy
decision; static single-bin acceptance does not repair it.

2026-10-10 current audit comparison (SOURCE PREPARED, NOT EXECUTED)
  run-zone-threshold-comparison.ps1 -OutputDirectory <new> -Pilot
  then -Seeds @(42,43,44) -ThresholdMeters @(1500,3000,5000) -JobCounts @(16,32)
Requires current VERIFIED source/JAR/guard evidence from protected acceptance first.
Every compiler/Java invocation: serial ResourceGuard, 1 visible CPU, compiler 256MiB,
probe 384MiB, reserve6GiB +1536MiB own start headroom. No stale JAR/pilot bypass.
Fixture: 24x24/500m bidirectional grid, 2km integer zones, four finite vans,
services and shipments, root-seeded identical demand, exactly10 actual iterations.
Fresh JVM per variant, alternating order, first-seed warmups excluded. Exact final
road evaluation, separate preparation/search/routing/validation/adapter-certificate
time, sampled used heap and query/mode diagnostics. Used heap is not physical RAM.
The old LargeThresholdProbe draft was read for fixture ideas; never run or trusted.
Python: -m unittest discover -s tools/benchmarks/tests -p test_zone_threshold_summary.py
Summary: summarize-zone-threshold-comparison.py <measurement directory>.
Run Python checks via ResourceGuard too while Vroom/Valhalla have priority. Unequal
input/profile/evaluation fingerprints, actual budgets, missing observations, failed
jobs/violations or resource deferral yield incomparable, never a speedup claim.
No production default/zone threshold or parallelism recommendation from this fixture.