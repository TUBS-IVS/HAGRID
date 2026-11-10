# HAGRID Section 2.0 routing inputs

## Production: existing HAGRID inputs

The productive path uses only the current HAGRID demand stop register, HAGRID
street observations, the routed carrier services and the Java network. The
Python demand export writes `hagrid-section-inputs.json`,
`section_stops.parquet` and `section_streets.parquet` beside its demand
shapefile. Keep these files together. The bundle includes the stops actually
used for that demand export, including registered pickup points.

Launch `hagrid.hannover.HagridV2RoutingRunner` with the single **program
argument** `config/hagrid2-sections.properties` from the repository root. Set
one `run.concept` and `run.date` in that standalone profile. The runner performs
one demand and offline jsprit routing run; it does not launch MATSim. Set
`routing.sectionHagridInputs=auto` to resolve the bundle beside the configured
demand shapefile. See [HAGRID_ONLY.md](HAGRID_ONLY.md) for preparation behavior,
failure reports and the Section 2.0 routing settings.

`prepare_hagrid_inputs.py` is the HAGRID-only producer invoked by the Java
bridge. It writes a complete catalog manifest only when every service is
supported, and always writes a per-service report. Registered pickup points
retain their original assigned-link service semantics. No external OSM/PBF
source, historical extract, road download or pedestrian model is required.

## Optional historical diagnostics

The other scripts in this directory support earlier source-evidence
investigations. They are standalone diagnostics and are not part of the
productive HAGRID-only Section 2.0 configuration. Their sample
`section-routing.example.properties` and `section-evidence.example.json` contain
placeholder paths, hashes and bounds; do not use them as production settings.
The old `routing.sectionEvidenceConfig` and `routing.sectionEvidenceTool`
settings are rejected by the productive HAGRID-only path.

The historical diagnostic workflow, when deliberately needed for a separate
investigation, uses pinned external sources and additional dependencies
(`lxml`, `pyproj`, and `pyosmium` for PBF extraction):

1. Run `python network_transitions.py --network NETWORK --bounds XMIN YMIN XMAX YMAX --output WITNESS` to enumerate reverse-endpoint link pairs in the declared EPSG:25832 rectangle.
2. Run `python extract_sources.py --witness WITNESS --pbf SOURCE.pbf --sha256 DIGEST --output EXTRACT.json` for each pinned source.
3. Run `python prepare_network_evidence.py --network NETWORK --catalog CATALOG --source EXTRACT.json [EXTRACT2.json ...] --bounds XMIN YMIN XMAX YMAX --output CATALOG_OUT --proof PROOF --witness GENERATED_WITNESS` to build a diagnostic catalog and proof.

The diagnostic proof binds network and source hashes. Unresolved turn pairs
remain unknown. Historical source coverage does not establish full-network
coverage or physical road legality, and none of these commands is a prerequisite
for the productive routing run above.
