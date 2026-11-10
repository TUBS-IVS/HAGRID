"""Compare only current, complete, resource-safe paired measurements; stdlib only."""
import argparse
import json
import math
import statistics
from pathlib import Path


def summarize(manifest, rows):
    reasons = []
    build = manifest.get("build", {})
    if manifest.get("status") != "COMPLETE":
        reasons.append("run is not COMPLETE")
    if build.get("status") != "VERIFIED":
        reasons.append("current build is not VERIFIED")
    for key in ("jarSha256", "sourceTreeSha256", "guardSha256", "probeSha256"):
        if not manifest.get(key):
            reasons.append(f"missing {key}")
    for key in ("jarSha256", "sourceTreeSha256", "guardSha256"):
        if build.get(key) != manifest.get(key):
            reasons.append(f"build {key} mismatch")
    if manifest.get("heapMiB") != 384 or manifest.get("activeProcessors") != 1:
        reasons.append("fixed JVM budget differs from comparison contract")
    for obs in manifest.get("observations", []):
        run = obs.get("run", {}) or {}
        if run.get("ResourceDeferred") or run.get("ExitCode") != 0:
            reasons.append("failed/resource-deferred subprocess")
    expected = {(kind, jobs, seed, float(threshold))
                for kind in manifest.get("kinds", []) for jobs in manifest.get("jobCounts", [])
                for seed in manifest.get("seeds", []) for threshold in [0, *manifest.get("thresholdMeters", [])]}
    by_key = {}
    for row in rows:
        key = (row.get("kind"), row.get("jobs"), row.get("seed"), row.get("thresholdMeters"))
        if key in by_key:
            reasons.append(f"duplicate observation {key}")
        by_key[key] = row
        if row.get("status") != "COMPLETE":
            reasons.append(f"incomplete result {key}")
        if row.get("actualIterations") != row.get("requestedIterations") or row.get("actualIterations") != manifest.get("iterations"):
            reasons.append(f"actual search budget differs {key}")
        if row.get("servedJobs") != row.get("jobs") or row.get("unassignedJobs") != 0 or row.get("hardViolations") != 0:
            reasons.append(f"invalid/incomplete jobs {key}")
        for name in ("totalSeconds", "searchSeconds", "exactFinalCost", "preparationSeconds", "finalRoutingSeconds", "validationSeconds"):
            value = row.get(name)
            if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                reasons.append(f"invalid measured {name} {key}")
        for name in ("inputFingerprint", "profileFingerprint", "evaluationFingerprint"):
            if not row.get(name):
                reasons.append(f"missing {name} {key}")
    if not expected or set(by_key) != expected:
        reasons.append("required exact/threshold/seed/job/kind observations missing or unexpected")
    comparisons = []
    for kind, jobs, seed, threshold in sorted(expected):
        if threshold == 0 or (kind, jobs, seed, threshold) not in by_key or (kind, jobs, seed, 0) not in by_key:
            continue
        exact, zone = by_key[kind, jobs, seed, 0], by_key[kind, jobs, seed, threshold]
        for name in ("inputFingerprint", "profileFingerprint", "evaluationFingerprint", "actualIterations"):
            if exact.get(name) != zone.get(name):
                reasons.append(f"paired {name} mismatch {(kind, jobs, seed, threshold)}")
        if zone.get("representativeQueries", 0) <= 0:
            reasons.append(f"no observed representative calculation coverage {(kind, jobs, seed, threshold)}")
        if reasons:
            continue
        if zone["totalSeconds"] <= 0 or exact["exactFinalCost"] <= 0:
            reasons.append("zero denominator in comparison")
            continue
        comparisons.append({"kind": kind, "jobs": jobs, "seed": seed, "thresholdMeters": threshold,
                            "totalSpeedup": exact["totalSeconds"] / zone["totalSeconds"],
                            "exactCostChangePercent": 100 * (zone["exactFinalCost"] / exact["exactFinalCost"] - 1),
                            "exactSearchSeconds": exact["searchSeconds"], "zoneSearchSeconds": zone["searchSeconds"],
                            "representativeQueries": zone["representativeQueries"],
                            "representativeNoPathFallbacks": zone.get("representativeNoPathFallbacks", 0),
                            "exactHeapPeakBytes": exact.get("wholeJvmSampledUsedHeapPeakBytes"),
                            "zoneHeapPeakBytes": zone.get("wholeJvmSampledUsedHeapPeakBytes")})
    if reasons:
        return {"comparable": False, "reasons": sorted(set(reasons)), "comparisons": []}
    medians = []
    for key in sorted({(r["kind"], r["jobs"], r["thresholdMeters"]) for r in comparisons}):
        selected = [r for r in comparisons if (r["kind"], r["jobs"], r["thresholdMeters"]) == key]
        medians.append({"kind": key[0], "jobs": key[1], "thresholdMeters": key[2], "independentSeeds": len(selected),
                        "medianTotalSpeedup": statistics.median(r["totalSpeedup"] for r in selected),
                        "medianExactCostChangePercent": statistics.median(r["exactCostChangePercent"] for r in selected),
                        "minimumTotalSpeedup": min(r["totalSpeedup"] for r in selected),
                        "maximumTotalSpeedup": max(r["totalSpeedup"] for r in selected)})
    return {"comparable": True, "reasons": [], "comparisons": comparisons, "medians": medians,
            "scope": "Synthetic 24x24 grid, 500m spacing, 2km zones; warmups excluded; sampled used heap is not physical/tree RAM. No production default decision."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.directory / "manifest.json").read_text(encoding="utf-8-sig"))
    result_file = args.directory / "results.json"
    rows = json.loads(result_file.read_text(encoding="utf-8-sig")) if result_file.exists() else []
    report = summarize(manifest, rows)
    (args.directory / "summary.json").write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
