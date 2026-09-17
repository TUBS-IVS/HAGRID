# -*- coding: utf-8 -*-
"""Run metadata: read run_metadata.json (RunMetadataWriter) or fall back to
parsing the legacy hagrid-matsim-output directory name."""
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

_LEGACY = re.compile(
    r"^(?P<concept>[A-Z0-9_]+?)_(?P<date>\d{8})"
    r"(?:_(?P<tag>.+?))?_iter(?P<it>\d+)_jsprit(?P<js>\d+)$")


@dataclass
class RunMeta:
    run_id: str
    run_dir_name: str
    scenario: str
    study_area: str
    operation_mode: str
    tag: str
    matsim_iterations: int
    jsprit_iterations: int
    fleet_size: Optional[int]
    prefix: str
    #: "run_metadata.json" or "dir-name" -- which source the fields above came
    #: from. The dir-name fallback cannot recover fleet_size (stays None, which
    #: silently suppresses every economics cost KPI) nor operation_mode (defaults
    #: to "conventional"), so callers must be able to see which one they got.
    meta_source: str = "run_metadata.json"
    #: DRT_SHAREDUSE sweep coordinates (I2/M5): chiThreshold/noParcels are NOT
    #: part of the runId, so run_metadata.json is the only machine-readable
    #: binding. None on old metadata files (pre-2026-07 writer) and on the
    #: dir-name fallback -- both simply predate the fields.
    chi_threshold: Optional[float] = None
    no_parcels: Optional[bool] = None
    #: MATSim global random seed (F3): error-band replicate runs differ only in
    #: it (and the tag), so sweep assembly binds replicates via this field. None
    #: on old metadata files (pre-2026-07-28 writer) and the dir-name fallback.
    matsim_seed: Optional[int] = None


def load_run_meta(run_dir):
    run_dir = Path(run_dir)
    meta_file = run_dir / "run_metadata.json"
    if meta_file.exists():
        j = json.loads(meta_file.read_text(encoding="utf-8"))
        return RunMeta(
            run_id=j["run_id"], run_dir_name=j["run_dir_name"],
            scenario=j["scenario"], study_area=j["study_area"],
            operation_mode=j["operation_mode"], tag=j.get("tag", ""),
            matsim_iterations=int(j["matsim_iterations"]),
            jsprit_iterations=int(j["jsprit_iterations"]),
            fleet_size=j.get("fleet_size"), prefix=j["run_id"],
            meta_source="run_metadata.json",
            chi_threshold=j.get("chi_threshold"), no_parcels=j.get("no_parcels"),
            matsim_seed=j.get("matsim_seed"))
    # writeRunMetadataSafely swallows any writer failure, so a missing file is a
    # normal (and easy to miss) outcome of a completed run. Say so loudly: the
    # degraded metadata silently changes which KPIs get emitted at all.
    print("[run_meta] WARNING: no run_metadata.json in " + str(run_dir)
          + " -- falling back to dir-name parsing; fleet_size unknown, so the"
          + " economics cost KPIs will be omitted and operation_mode defaults"
          + " to 'conventional'.")  # ASCII only
    return parse_legacy_dir_name(run_dir.name)


def parse_legacy_dir_name(name):
    m = _LEGACY.match(name)
    if not m:
        raise ValueError("cannot parse run dir name: " + name)
    concept, date, tag = m.group("concept"), m.group("date"), m.group("tag") or ""
    run_id = concept + "_" + date + ("_" + tag if tag else "")
    study = "lausitz_hoyerswerda" if concept.startswith(("DRT_", "LMD_")) else "hannover"
    return RunMeta(run_id=run_id, run_dir_name=name, scenario=concept,
                   study_area=study, operation_mode="conventional", tag=tag,
                   matsim_iterations=int(m.group("it")),
                   jsprit_iterations=int(m.group("js")),
                   fleet_size=None, prefix=run_id, meta_source="dir-name")
