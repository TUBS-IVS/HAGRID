"""Protect historical results while enforcing the new distinct case/reference seed protocol."""
import csv, hashlib, json, shutil, subprocess, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SCRIPT = REPO/"tools/benchmarks/summarize-search-policy-comparison.ps1"
REFERENCE = Path(__file__).resolve().parent/"fixtures/search-policies"

class SearchPolicySummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="seed-summary-")
        self.input = Path(self.temp.name)
        self.manifest = json.loads((REFERENCE/"manifest.json").read_text(encoding="utf-8-sig"))
        with (REFERENCE/"measurements.csv").open(encoding="utf-8-sig",newline="") as stream:
            self.rows = list(csv.DictReader(stream))
        self.original = hashlib.sha256((REFERENCE/"measurements.csv").read_bytes()).hexdigest()

    def tearDown(self):
        self.assertEqual(self.original,hashlib.sha256((REFERENCE/"measurements.csv").read_bytes()).hexdigest())
        self.temp.cleanup()

    def v2(self):
        self.manifest["seedProtocol"] = "paired-distinct-v2"
        self.manifest["seedProtocolSha256"] = "a"*64
        for row in self.rows:
            index = int(row["carrier"][-1])
            current = int(row["seed"])*8 + index*2
            row.update(caseSeed=str(current),previousSeed=str(current+1))

    def prepare(self):
        (self.input/"manifest.json").write_text(json.dumps(self.manifest),encoding="utf-8")
        with (self.input/"measurements.csv").open("w",encoding="utf-8",newline="") as stream:
            writer=csv.DictWriter(stream,fieldnames=list(self.rows[0]))
            writer.writeheader();writer.writerows(self.rows)
    def invoke(self):
        self.prepare()
        return subprocess.run([shutil.which("pwsh"),"-NoProfile","-File",str(SCRIPT),"-InputDirectory",str(self.input)],
                              capture_output=True,text=True,encoding="utf-8",errors="replace")

    def rejected(self):
        result=self.invoke()
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertFalse((self.input/"paired-summary.json").exists())

    def test_late_publication_cannot_overwrite_a_newly_existing_summary(self):
        self.prepare()
        lines=SCRIPT.read_text(encoding="utf-8-sig").splitlines()
        boundaries=[i+1 for i,line in enumerate(lines) if
                    "WriteAllText((Join-Path $directory 'paired-summary.json')" in line or
                    "Publish-HagridBenchmarkJson -Path" in line]
        self.assertEqual(1,len(boundaries))
        harness=self.input/"late-publisher.ps1"
        harness.write_text("""param([string]$Script,[string]$InputDirectory,[int]$PublicationLine)
$global:HagridSummaryRaceTarget=Join-Path $InputDirectory 'paired-summary.json'
Set-PSBreakpoint -Script $Script -Line $PublicationLine -Action {
 [IO.File]::WriteAllText($global:HagridSummaryRaceTarget,'late original proof')
} | Out-Null
& $Script -InputDirectory $InputDirectory
""",encoding="utf-8")
        result=subprocess.run([shutil.which("pwsh"),"-NoProfile","-File",str(harness),"-Script",str(SCRIPT),
                               "-InputDirectory",str(self.input),"-PublicationLine",str(boundaries[0])],
                              capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=40)
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertEqual(b"late original proof",(self.input/"paired-summary.json").read_bytes())
    def test_legacy_inputs_remain_readable_with_overlap_disclosed(self):
        result=self.invoke()
        self.assertEqual(0,result.returncode,result.stderr)
        summary=json.loads((self.input/"paired-summary.json").read_text(encoding="utf-8-sig"))
        self.assertEqual("legacy-overlapping-v1",summary["seedProtocol"])
        self.assertEqual(8,summary["uniqueCarrierSeedsPerKind"])

    def test_v2_counts_twenty_distinct_starts_without_statistical_independence_claim(self):
        self.v2()
        result=self.invoke()
        self.assertEqual(0,result.returncode,result.stderr)
        summary=json.loads((self.input/"paired-summary.json").read_text(encoding="utf-8-sig"))
        self.assertEqual("paired-distinct-v2",summary["seedProtocol"])
        self.assertEqual(20,summary["uniqueCarrierSeedsPerKind"])
        self.assertIn("independence",summary["interpretation"])

    def test_wrong_current_seed_is_rejected(self):
        self.v2();self.rows[0]["caseSeed"]=self.rows[1]["caseSeed"]
        self.rejected()

    def test_reference_and_candidate_seed_overlap_is_rejected(self):
        self.v2();self.rows[0]["previousSeed"]=self.rows[0]["caseSeed"]
        self.rejected()

    def test_missing_seed_columns_are_not_accepted_as_v2(self):
        self.manifest["seedProtocol"]="paired-distinct-v2"
        self.manifest["seedProtocolSha256"]="a"*64
        self.rejected()

    def test_unknown_seed_protocol_is_not_silently_legacy(self):
        self.manifest["seedProtocol"]="unknown"
        self.rejected()

    def test_existing_summary_is_preserved(self):
        prior=self.input/"paired-summary.json";prior.write_bytes(b"original result")
        result=self.invoke()
        self.assertNotEqual(0,result.returncode)
        self.assertEqual(b"original result",prior.read_bytes())

if __name__=="__main__":
    unittest.main()