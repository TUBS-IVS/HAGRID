# HAGRID 2.0: existing inputs from Python to Java

This path uses HAGRID's own demand stop register, source street axes and actual
Java network. It does not fetch OSM/PBF files or add a pedestrian model.

## Start

Export demand with the current HAGRID Python pipeline. Alongside the demand
shapefile it writes `hagrid-section-inputs.json`, `section_stops.parquet` and
`section_streets.parquet`. Copy that whole directory when moving a demand day;
the JSON references its neighbouring files with relative paths. The stop bundle
contains the register actually used for that export, including OOH points.

Install the demand environment from `hagrid/demand/model/pyproject.toml`; section
preparation uses its declared geometry, scipy and networkx dependencies. Select
that Python executable via `HAGRID_SECTION_PYTHON` or
`routing.sectionEvidencePython` when it is not the default `python`.

Launch `hagrid.hannover.HagridV2RoutingRunner` with the single **program
argument** `config/hagrid2-sections.properties`, from the repository root. The
profile names one `run.concept` and `run.date` and is a standalone configuration,
not an overlay on `config/hagrid.properties`. This runner exports demand and
routes it with jsprit offline. It does not launch MATSim or write a simulation
script. Adjust the profile for the intended one-scenario run; its routing
settings include:

```properties
routing.sectionHagridInputs=auto
routing.sectionMaxSpanMeters=200
routing.sectionAllowStreetCrossing=false
routing.sectionUnknownTurnPolicy=NETWORK_REVERSAL
```

`auto` finds the bundle beside the **actual configured demand shapefile**. The
profile also sets the initial and offline jsprit iteration budgets. The runner
requires exactly one properties-file argument and uses that file's one concept
and date; it does not select a legacy multi-scenario list.

200 m is an available working setting, not a newly calibrated global optimum.
`routing.sectionAllowStreetCrossing=true` permits the two verified directions of
the same road for house deliveries. It is a modelling permission, not evidence
that crossing is physically or legally possible everywhere.

## What the pipeline preserves

1. Python exports the original customer coordinate, stop identity and source
   street metadata (`str_idx`, `part`, `side`, `position_m`, `section_id`).
2. Java snapshots its actual network and carriers. Automatic preparation matches
   straight/curved/composed roads to the existing HAGRID source axes and records
   original assignments separately from corrected vehicle-halt assignments.
3. jsprit chooses stop sections and permitted approaches together. Customer-side
   mode can select only the reverse of the old assignment; one-way links do not
   receive an invented reverse. Section boundaries preserve model link lengths
   and travel times. Original services are expanded again after the search.
4. Final validation checks every original service, demand, duration, time window,
   allowed selected halt and agreement between search and expanded-plan costs.
   Network, original services and binding evidence are persisted together.

The customer coordinate and vehicle anchor may differ at an existing HAGRID
street junction. This is a geometry mapping only: the current parcel-based
service time remains unchanged. No walking distance/time or shifted window is
added.

Registered OOH points (`locker`, `shared_locker`, `counter`, `shop`) use their
existing assigned-link service semantics. Their facility coordinate is retained;
no customer side or alternative vehicle entrance is invented. They remain at the
end of that original directed link, or its final child when house halts split it.
Different facility identities are not merged simply because they share a link.
Both existing HAGRID `PARCEL_LOCKER` service modes are supported through this
explicit fixed-link contract; HOME services cannot use it to bypass geometry.

## Uncertain turns and failures

`NETWORK_REVERSAL` keeps uncertain endpoint reversals labelled `UNKNOWN` and
charges the existing configured soft U-turn tariff. Search, expansion, validation
and stored evidence use the same policy. This does not certify a physical U-turn
or make it forbidden. Known and uncertain counts remain separate. `STRICT`
remains available for diagnostics that reject uncertain transitions.

An inconsistent stop ID/coordinate, unsupported source geometry or missing
network link produces a report and aborts preparation. No unsupported job is
silently dropped. A stale successful manifest is removed before a new attempt.
The report is next to the routed carrier file under `.hagrid-inputs/report.json`.
Legacy exports lacking identities must be regenerated; coordinate recovery is a
separate explicit diagnostic option, disabled by the generated bundle.

Do not additionally set `routing.haltCatalogFile` when using HAGRID automatic
inputs. Historical `routing.sectionEvidenceConfig` / `sectionEvidenceTool` are
rejected by this productive path; old tools remain standalone diagnostics.

Road-axis/topology matching is not an independent proof of medians, parking or
legal crossing. The input fixes and short jsprit checks are not a full Hannover
performance benchmark or a realism calibration. Section-mode multi-iteration
MATSim replanning is still outside this path and is explicitly rejected by its
existing execution guard. No MATSim run is needed for the routing checks.
