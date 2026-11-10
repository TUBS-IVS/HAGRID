import copy
import importlib.util
import unittest
from pathlib import Path

source = Path(__file__).resolve().parents[1] / "summarize-zone-threshold-comparison.py"
spec = importlib.util.spec_from_file_location("zone_summary", source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    manifest = {"status": "COMPLETE", "build": {"status": "VERIFIED", "jarSha256": "jar", "sourceTreeSha256": "source", "guardSha256": "guard"},
                "jarSha256": "jar", "sourceTreeSha256": "source", "guardSha256": "guard", "probeSha256": "probe",
                "heapMiB": 384, "activeProcessors": 1, "kinds": ["services"], "jobCounts": [16], "seeds": [42],
                "thresholdMeters": [3000], "iterations": 10, "observations": [{"run": {"ExitCode": 0, "ResourceDeferred": False}}]}
    exact = {"status": "COMPLETE", "kind": "services", "jobs": 16, "seed": 42, "thresholdMeters": 0,
             "actualIterations": 10, "requestedIterations": 10, "servedJobs": 16, "unassignedJobs": 0, "hardViolations": 0,
             "inputFingerprint": "input", "profileFingerprint": "profile", "evaluationFingerprint": "eval",
             "totalSeconds": 2, "searchSeconds": 1, "preparationSeconds": .1, "finalRoutingSeconds": .1, "validationSeconds": .1,
             "exactFinalCost": 100, "representativeQueries": 0}
    zone = {**exact, "thresholdMeters": 3000, "totalSeconds": 1, "exactFinalCost": 105, "representativeQueries": 10}
    return manifest, [exact, zone]


class ZoneThresholdSummaryTest(unittest.TestCase):
    def test_complete_pair_exposes_time_and_exact_quality_separately(self):
        report = module.summarize(*fixture())
        self.assertTrue(report["comparable"])
        self.assertEqual(report["comparisons"][0]["totalSpeedup"], 2)
        self.assertAlmostEqual(report["comparisons"][0]["exactCostChangePercent"], 5)

    def test_invalid_pairs_never_emit_speedup(self):
        mutations = [lambda m, r: r[1].update(inputFingerprint="changed"),
                     lambda m, r: r[1].update(profileFingerprint="changed"),
                     lambda m, r: r[1].update(evaluationFingerprint="changed"),
                     lambda m, r: r.pop(), lambda m, r: r.append(copy.deepcopy(r[1])),
                     lambda m, r: r[1].update(actualIterations=9),
                     lambda m, r: m.update(status="DEFERRED"),
                     lambda m, r: m["observations"][0]["run"].update(ResourceDeferred=True),
                     lambda m, r: m["build"].update(jarSha256="stale"),
                     lambda m, r: r[1].update(servedJobs=15, unassignedJobs=1),
                     lambda m, r: r[1].update(hardViolations=1),
                     lambda m, r: r[1].update(representativeQueries=0),
                     lambda m, r: r[1].update(totalSeconds=float("nan"))]
        for mutate in mutations:
            with self.subTest(case=mutate):
                manifest, rows = fixture(); mutate(manifest, rows)
                report = module.summarize(manifest, rows)
                self.assertFalse(report["comparable"])
                self.assertEqual(report["comparisons"], [])


if __name__ == "__main__":
    unittest.main()
