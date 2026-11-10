"""Black-box profile acceptance. Missing/corrupt evidence must never become a RAM estimate."""
import copy, hashlib, json, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SCRIPT = REPO / "tools/benchmarks/summarize-replay-memory-profile.py"
REFERENCE = Path(__file__).resolve().parent / "fixtures/replay-profile"

class ProfileSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="profile-summary-")
        self.root = Path(self.temp.name)
        self.input = self.root / "input"
        self.input.mkdir()
        self.output = self.root / "output"
        self.manifest = json.loads((REFERENCE / "manifest.json").read_text(encoding="utf-8-sig"))
        for case in self.manifest["cases"]:
            case["directory"] = str(REFERENCE/"captures"/case["carrierId"])
            case["context"] = str(REFERENCE/"context.zip")
        for file in REFERENCE.glob("*.log"):
            shutil.copyfile(file, self.input / file.name)
        self.reference_digest = hashlib.sha256((REFERENCE / "manifest.json").read_bytes()).hexdigest()

    def tearDown(self):
        self.assertEqual(self.reference_digest, hashlib.sha256((REFERENCE / "manifest.json").read_bytes()).hexdigest())
        self.temp.cleanup()

    def invoke(self):
        (self.input / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        return subprocess.run([sys.executable, str(SCRIPT), "--input-directory", str(self.input),
                               "--output-directory", str(self.output)], capture_output=True, text=True)

    def rejected(self, reason):
        result = self.invoke()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(reason.lower(), result.stderr.lower())
        self.assertFalse(self.output.exists(), "Invalid inputs must not publish a success directory")

    def change_row_and_log(self, field, value):
        row = self.manifest["measurements"][0]
        row[field] = value
        log = self.input / "case0-repeat1.log"
        lines = log.read_text(encoding="utf-8-sig").splitlines()
        for i, line in enumerate(lines):
            if line.startswith("REPLAY_MEMORY_JSON "):
                data = json.loads(line[len("REPLAY_MEMORY_JSON "):])
                data[field] = value
                lines[i] = "REPLAY_MEMORY_JSON " + json.dumps(data)
        log.write_text("\n".join(lines), encoding="utf-8")

    def test_manifest_replaced_during_validation_is_not_certified(self):
        import importlib.util
        (self.input/"manifest.json").write_text(json.dumps(self.manifest),encoding="utf-8")
        spec=importlib.util.spec_from_file_location("profile_summary_under_test",SCRIPT)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        original=module.verify_case
        changed=False
        def verify_then_replace(case):
            nonlocal changed
            result=original(case)
            if not changed:
                updated=copy.deepcopy(self.manifest);updated["status"]="FAILED_OR_DEFERRED"
                (self.input/"manifest.json").write_text(json.dumps(updated),encoding="utf-8")
                changed=True
            return result
        # Controlled concurrent filesystem mutation after real case validation; no fake measurements.
        module.verify_case=verify_then_replace
        with self.assertRaisesRegex(ValueError,"changed"):
            module.verify_profile(self.input)

    def test_short_compiler_without_cpu_sample_does_not_invent_zero(self):
        self.manifest["observations"][0]["run"]["MaximumObservedCpuPercent"]=None
        result=self.invoke()
        self.assertEqual(0,result.returncode,result.stderr)
        summary=json.loads((self.output/"summary.json").read_text(encoding="utf-8"))
        self.assertEqual(["compile"],summary["resources"]["processesWithoutCpuSamples"])
        self.assertEqual(35.49019607843137,summary["resources"]["maximumObservedCpuPercent"])

    def test_missing_prestart_cpu_measurement_is_rejected(self):
        self.manifest["observations"][0]["selection"]["MinimumIdleCpuPercent"]=None
        self.rejected("cpu")
    def test_complete_profile_publishes_separate_case_envelopes_not_admission_estimates(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        summary = json.loads((self.output / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual("VERIFIED_PROFILE", summary["status"])
        self.assertEqual(4, summary["measurementCount"])
        self.assertFalse(summary["calibrationEstablished"])
        self.assertNotIn("estimatedCarrierBytes", summary)
        self.assertEqual(2, len(summary["cases"]))
        services = summary["cases"][0]
        self.assertEqual(26796704, services["wholeJvmSampledPeakBytes"]["maximum"])
        self.assertEqual(26670004, services["wholeJvmSampledPeakBytes"]["median"])
        self.assertEqual(4080, services["returnedResultGraphBytes"]["maximum"])
        self.assertEqual(100.25, services["finalCost"]["minimum"])
        self.assertEqual(13872685056, summary["resources"]["minimumAvailableBytes"])
        self.assertEqual(35.49019607843137, summary["resources"]["maximumObservedCpuPercent"])
        self.assertIn("kein", (self.output / "overview.txt").read_text(encoding="utf-8").lower())

    def test_missing_repeat_is_not_a_completed_profile(self):
        self.manifest["measurements"].pop()
        self.rejected("measurement")

    def test_duplicate_repeat_is_rejected_even_with_expected_count(self):
        self.manifest["measurements"][-1] = copy.deepcopy(self.manifest["measurements"][0])
        self.rejected("duplicate")

    def test_deferred_run_is_not_accepted(self):
        self.manifest["status"] = "FAILED_OR_DEFERRED"
        self.rejected("completed")

    def test_missing_process_observation_is_not_invented(self):
        self.manifest["observations"].pop()
        self.rejected("observation")

    def test_resource_deferred_inside_measured_manifest_is_rejected(self):
        self.manifest["observations"][1]["run"]["ResourceDeferred"] = True
        self.rejected("process")

    def test_failed_process_is_rejected(self):
        self.manifest["observations"][1]["run"]["ExitCode"] = 1
        self.rejected("process")

    def test_missing_ram_measurement_is_rejected(self):
        self.manifest["observations"][1]["run"]["MinimumAvailableBytes"] = None
        self.rejected("memory")

    def test_below_six_gib_reserve_is_rejected(self):
        self.manifest["observations"][1]["run"]["MinimumAvailableBytes"] = 6 * 1024**3 - 1
        self.rejected("reserve")

    def test_nan_cannot_become_a_cost_or_time(self):
        self.change_row_and_log("finalCost", float("nan"))
        self.rejected("finite")

    def test_bool_cannot_become_a_numeric_measurement(self):
        self.change_row_and_log("jobs", True)
        self.rejected("integer")

    def test_peak_above_heap_capacity_is_rejected(self):
        self.change_row_and_log("wholeJvmSampledPeakBytes", 999999999)
        self.rejected("heap")

    def test_manifest_row_must_match_actual_probe_log(self):
        self.manifest["measurements"][0]["finalCost"] = 101.25
        self.rejected("log")

    def test_missing_probe_log_is_not_an_observation(self):
        (self.input / "case0-repeat1.log").unlink()
        self.rejected("log")

    def test_duplicate_log_protocol_message_is_rejected(self):
        log = self.input / "case0-repeat1.log"
        message = next(x for x in log.read_text(encoding="utf-8-sig").splitlines() if x.startswith("REPLAY_MEMORY_JSON "))
        with log.open("a", encoding="utf-8") as stream:
            stream.write("\n" + message + "\n")
        self.rejected("log")

    def test_changed_capture_digest_is_rejected(self):
        self.manifest["cases"][0]["hashes"]["carrier.xml"] = "0" * 64
        self.rejected("digest")

    def test_corrupt_package_identifier_is_rejected(self):
        self.manifest["jarSha256"] = "invalid"
        self.rejected("sha256")

    def test_changed_context_certificate_is_rejected(self):
        self.change_row_and_log("contextFingerprint", "f" * 64)
        self.rejected("certificate")

    def test_oversized_effective_budget_is_rejected(self):
        self.manifest["cases"][0]["effectiveIterations"] = 21
        self.rejected("iteration")

    def test_existing_output_is_preserved(self):
        self.output.mkdir()
        marker = self.output / "original.txt"
        marker.write_bytes(b"original proof")
        result = self.invoke()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("existing", result.stderr.lower())
        self.assertEqual(b"original proof", marker.read_bytes())
        self.assertEqual([marker], list(self.output.iterdir()))

if __name__ == "__main__":
    unittest.main()