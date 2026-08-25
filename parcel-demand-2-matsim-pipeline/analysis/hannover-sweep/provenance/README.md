# Provenance of the sim-PC sweep series

## `v4-sim-working-tree.patch`

The v4 series (38 runs, capacity 30–400 step 10, `BASECASE_13052025_<cap>v4_iter150_jsprit1000`)
was produced on the sim-PC from committed HEAD **`019fd5f`** *plus* the working-tree diff in this
patch. The patch touches exactly two files:

- `src/main/java/hagrid/HAGRID2MATSimPipelineRunner.java` — the `SCENARIOS` array (the 38 v4 tags,
  ordered by informativeness, not ascending, so an aborted batch still leaves an interpretable set)
- `run_hagrid_sim.bat` — the batch this array generates

Nothing else differed from `019fd5f`. That is what makes the v4−v3 distance a **pure reseed
spread**: the tag is part of the runId, `runId.hashCode()` seeds the demand layer and
`CarrierVehicleFactory`, and the model code was byte-identical between the two series.

The patch is archived here because the sim-PC working tree was reset on **2026-08-25** to pull the
district-depot work for the Lausitz 1c campaign. Without it, `019fd5f` alone does not reproduce v4:
the committed `SCENARIOS` array holds the cap-30 3-replicate set, not the v4 grid.

To reproduce the v4 series:

```bash
git checkout 019fd5f
git apply parcel-demand-2-matsim-pipeline/analysis/hannover-sweep/provenance/v4-sim-working-tree.patch
```

Demand input at the time of the v4 runs: sim-PC state `BC86ECC5…` (see METHODS-LOG §2.30 — this is
one PANDA state behind the dev-PC and was **not** synced before v4, deliberately, so v2/v3/v4 share
one demand file).
