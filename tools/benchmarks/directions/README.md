# Hannover: native jsprit with two approach directions

This is the reproducible **benchmark adapter**, not the general HAGRID input pipeline. Native jsprit 2.0.0 searches stop order; exact prefix/suffix dynamic programming chooses the permitted approach directions. It uses the corrected Hannover fixture: 69 halts, 92 parcels, one vehicle, fixed departure 50360, precomputed road matrices, and both permitted approach directions. Opposite-side stopping and walking across are allowed; no calibrated walking-crossing time is included.

The production change is separately active in `HAGRIDRouterUtils`: a public factory installs the uniquely named `HagridServiceInsertionCalculator`. `-Dhagrid.jsprit.insertionDiagnostics=true` restores native diagnostic breakdowns. The reusable `RouteOrderCache` is applied here to structural direction states only; it must not cache time-, load-, vehicle-, or traffic-dependent values without additional invalidation.

## Reproduce

Requirements: Python 3, JDK 21, original jsprit 2.0.0 plus Jackson, Trove, Commons Math and SLF4J dependency JARs. Supply a platform-separated classpath text file. Directory entries are discarded; installed JARs are never rewritten. `--input-root` points to the preserved `hagrid-joint-day-2026-11-05` fixture containing `joint-inputs.json` and the original matrix files.

```powershell
python tools/benchmarks/directions/run.py --input-root INPUTS --output-dir NEW_OUTPUT --classpath-file CLASSPATH_FILE --java-home JAVA_HOME --penalty 0.1 --seed 42 --iterations 2000
```

Add `--native` for the unoptimized adapter. For equal time, replace `--iterations 2000` with `--seconds 5`; optional profiles are `--profile small` (ruin sizes 4–14) and `--profile string` (additional native STRING_REGRET weight 1). Default retains the established native strategy mix. Repeat penalties 0, 0.1, 1 and seeds 194535502, 42, 1337, serially with rotated arm order. Do not run competing heavy processes during timings.

Fixed-iteration runs retain a 120-second safety cap. The CLI fails explicitly if this cap prevents the requested work from completing. Do not treat such a partial output as a completed fixed-work comparison. A selected best-of-three result costs three separate runs; a five-second per-run budget is not five seconds for the whole selection procedure.

Fixed-work comparisons use identical iteration cooling. Timed comparisons use common elapsed-time Schrimpf cooling: alpha .15, initial threshold .03, normalized by the deadline. The deadline includes native construction, search, finalization, decoding, and the complete main JSON export; input parsing/VRP preparation and JVM startup are excluded. Check `result.json.timing.json`: **discard results with `eligible=false` before ranking**. Main-thread allocated bytes measure allocation, not peak heap.

`completedIterations` counts iteration-end callbacks. Native termination occurs before the final callback, so a time-terminated run executed one additional iteration. The improvement callback log is not an exhaustive anytime trace. Frozen historical measurements retain these field names.

The pairwise travel time used during search is an optimistic bound. A low static objective does not prove a feasible executed day. Selected plans must be expanded onto the split network and validated in full-day MATSim with the same demand, time windows, turn count, distance, and exact executed path. This benchmark does not replace the 150-iteration replanning test or establish the speedup of the whole production simulation.

On the measured Hannover fixture, all three optimizations retain exactly the native routes, costs and search traces at 2,000 iterations for all nine penalty/seed cases. Alternative search profiles change the policy and are exploratory; there is no universal winner in the three-seed test. Keep `default` as the conservative configuration.


## Safe position pruning (option 3)

The regular `HagridServiceInsertionCalculator` now accepts an explicit `InsertionCostLowerBound` through its public factory overload. This runner enables it by default. The bound is the existing exact directional insertion delta plus unchanged service/fixed costs. It is valid across all time windows because this adapter's objective is static. The calculator adds route-level/access-egress costs and skips only finite bounds strictly above the incumbent plus 1e-9. All promising positions still undergo the original hard constraints; advancing route time is never skipped.

Use `--no-position-pruning` to compare against the already accelerated adapter. `--native` still disables all adapter optimizations. Use `--validate-insertions` for candidate spot checks; compare timings with this option disabled in both arms. Final complete-solution and decoded-objective checks remain active in every run. The fixed-work CLI still fails if it does not finish all requested iterations.

Diagnostic mode delegates to the original unfiltered calculator. Soft activity constraints disable pruning, because their potentially negative contributions are not included in the bound. Requests for all insertion positions remain complete. General production callers that use `factory(false)` without a bound keep full enumeration. In particular, this does not infer a bound for HAGRID's time-dependent replay, nor claim a full-regional MATSim speed/RAM improvement. Only option 3 was promoted; the time-window-array allocation experiment is not included.
