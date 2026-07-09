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
            fleet_size=j.get("fleet_size"), prefix=j["run_id"])
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
                   fleet_size=None, prefix=run_id)
