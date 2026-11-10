# Optional pre-Java evidence wrapper

The checked-in Hannover starter `runs/hannover/run_hagrid_sim.bat` launches Java directly; the Python demand-model pipeline is not that launcher. This wrapper therefore supplies an explicit pre-Java entry point. Existing starters are unchanged.

Create a JSON configuration based on `example-evidence-config.json`, replace every path, and choose an EPSG:25832 bounding rectangle covering the intended transit network, including boundary links. The example bounds are a **small smoke-test area**, not a complete Hannover routing region. The hashes in the example identify the two source PBFs used in the earlier evidence audit. A matching hash is required; no source or area is inferred. The network must be the exact one the Java run will use, and the catalog must be a single prepared catalog for the intended carrier (not a multi-catalog manifest).

```powershell
python run_with_evidence.py --evidence-config evidence.json -- java -jar path/to/hagrid.jar "concept=basecase,date=2025-05-13,routing.sectionMaxSpanMeters=200,routing.haltCatalogFile={halt_catalog}"
```

The wrapper scans network candidates, extracts the configured PBFs and prepares a catalog once for each fingerprint. It substitutes the absolute generated catalog path into exactly one `{halt_catalog}` placeholder in the Java argument vector and returns Java's exit status. Without `--evidence-config`, it passes the command through unchanged. On a cache hit it rechecks source, network, catalog, code and dependency identities, plus hashes of generated evidence files. A changed input gets a new cache entry; a tampered, incomplete or concurrently building cache entry fails closed. Incomplete entries require manual inspection/removal before retry. The cache includes extracted source files and can be large. The caller must configure matching Java routing network and catalog; this wrapper cannot infer or validate other Java arguments.

A source conflict or inability to bind a chain stays UNKNOWN. Preparing every candidate in the selected rectangle does not certify every candidate, and routes outside that rectangle or crossing its boundary are not covered. The existing router can still reject an optimal path that depends on an unresolved transition. The proof and unresolved statuses are in the cache entry's `proof.json`.
