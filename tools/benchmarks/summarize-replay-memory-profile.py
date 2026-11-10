"""Verify bounded native replay evidence before publishing descriptive envelopes.
No solver, subprocess, forced GC, admission estimate or production activation.
"""
import argparse
import hashlib
import json
import math
import re
import statistics
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

MIB = 1024**2
GIB = 1024**3
NATIVE_FIELDS = (
    "carrierId", "contextFingerprint", "jobs", "iterations", "vehicles", "finalCost",
    "heapBeforeBytes", "wholeJvmSampledPeakBytes", "heapAfterBytes", "returnedResultGraphBytes",
    "callerThreadAllocatedBytes", "elapsedNanos", "garbageCollections", "garbageCollectionMillis",
    "maximumHeapBytes", "activeProcessors", "samplingMillis",
)
METRICS = (
    "wholeJvmSampledPeakBytes", "returnedResultGraphBytes", "callerThreadAllocatedBytes",
    "elapsedNanos", "garbageCollections", "garbageCollectionMillis", "finalCost",
    "iterations", "vehicles",
)

def require(condition, message):
    if not condition:
        raise ValueError(message)

def integer(value, name, minimum=0, maximum=None):
    require(type(value) is int, f"{name} must be an integer")
    require(value >= minimum and (maximum is None or value <= maximum), f"Invalid {name}")
    return value

def number(value, name):
    require(type(value) in (int, float) and math.isfinite(value), f"{name} must be finite numeric data")
    require(value >= 0, f"Negative {name}")
    return value

def sha(value):
    require(type(value) is str and re.fullmatch(r"[a-fA-F0-9]{64}", value), "Invalid SHA256 identifier")
    return value.upper()

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result

def decode(raw):
    def bad_constant(value):
        raise ValueError(f"JSON requires finite values: {value}")
    return json.loads(raw, object_pairs_hook=unique_object, parse_constant=bad_constant)

def bounded(path, limit, label):
    require(path.is_file(), f"Missing {label}: {path}")
    require(path.stat().st_size <= limit, f"Oversized {label}: {path}")
    return path.read_bytes()

def read_json(path, limit=2*MIB, label="manifest"):
    return decode(bounded(path, limit, label).decode("utf-8-sig"))

def digest(path, limit, label):
    return hashlib.sha256(bounded(path, limit, label)).hexdigest().upper()

def stats(values):
    return {"minimum": min(values), "median": statistics.median(values), "maximum": max(values)}

def verify_case(case):
    directory = Path(case["directory"])
    for name in ("manifest.json", "carrier.xml", "types.xml"):
        require(digest(directory/name, MIB, "captured payload") == sha(case["hashes"][name]),
                f"Captured payload digest changed: {name}")
    capture = read_json(directory/"manifest.json", MIB, "captured manifest")
    require(capture["version"] == "hagrid-carrier-replay-v1", "Invalid captured version")
    for field in ("carrierId", "contextFingerprint", "inputFingerprint"):
        require(capture[field] == case[field], f"Captured certificate differs: {field}")
    sha(case["inputFingerprint"]); sha(case["contextFingerprint"])
    requested = integer(case["requestedIterations"], "requested iterations", 1, 20)
    require(capture["iterations"] == requested, "Captured requested iterations differ")
    context = Path(case["context"])
    require(digest(context, 8*MIB, "context") == sha(case["hashes"]["context"]) == sha(capture["contextDigest"]),
            "Captured context digest changed")
    for name in ("carrier.xml", "types.xml"):
        require(sha(capture["payloadDigests"][name]) == sha(case["hashes"][name]), "Captured payload digest differs")
    with zipfile.ZipFile(context) as archive:
        entries = archive.infolist()
        require(len(entries) == 3 and sorted(e.filename for e in entries) ==
                ["context.json", "network.xml", "snapshot.bin"], "Invalid context archive")
        require(sum(e.file_size for e in entries) <= 32*MIB and
                archive.getinfo("context.json").file_size <= MIB, "Oversized context metadata")
        config = decode(archive.read("context.json").decode("utf-8-sig"))["config"]
    integer(config["solverThreads"], "captured solver threads", 1, 1)
    effective, minimum = requested, 1
    if config.get("adaptive") is not None:
        policy = config["adaptive"]
        minimum = integer(policy["minimumIterations"], "minimum iterations", 1, 20)
        effective = integer(policy["maximumIterations"], "effective iterations", minimum, 20)
        integer(policy["plateauIterations"], "plateau iterations", 1)
    integer(case["effectiveIterations"], "effective iterations", 1, 20)
    require(case["effectiveIterations"] == effective, "Effective iteration certificate differs")
    jobs = integer(case["jobs"], "jobs", 1, 128)
    xml = ET.fromstring(bounded(directory/"carrier.xml", MIB, "carrier"))
    count = sum(e.tag.rsplit("}", 1)[-1] in ("service", "shipment") for e in xml.iter())
    require(count == jobs, "Captured job certificate differs")
    return minimum, integer(capture["seed"], "captured seed", -(2**63), 2**63-1)

def verify_profile(directory):
    manifest_path = directory/"manifest.json"
    manifest_bytes = bounded(manifest_path, 2*MIB, "manifest")
    manifest = decode(manifest_bytes.decode("utf-8-sig"))
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest().upper()
    require(manifest["status"] == "MEASURED" and manifest.get("deferredSelection") is None, "Expected completed profile")
    for name in ("jarSha256", "runnerSha256", "probeSha256", "agentSha256"):
        sha(manifest[name])
    repeats = integer(manifest["repeats"], "repeats", 1, 3)
    integer(manifest["heapMiB"], "heap MiB", 384, 384)
    integer(manifest["activeProcessors"], "visible processors", 1, 1)
    reserve = integer(manifest["reserveGiB"], "RAM reserve GiB", 6, 512)*GIB
    cases = manifest["cases"]
    require(type(cases) is list and 1 <= len(cases) <= 4, "Invalid case domain")
    require(len({str(Path(c["directory"]).resolve()).casefold() for c in cases}) == len(cases), "Duplicate case directory")
    verified = [verify_case(c) for c in cases]
    observations = manifest["observations"]
    require(type(observations) is list and len(observations) == 1+len(cases)*repeats,
            "Incomplete process observations")
    processes = {}
    for observation in observations:
        name = observation["name"]
        require(name not in processes, "Duplicate process observation")
        selection, run = observation["selection"], observation["run"]
        require(selection["Eligible"] is True and run["ResourceDeferred"] is False and
                type(run["ExitCode"]) is int and run["ExitCode"] == 0 and not run.get("Reason"),
                "Failed or deferred process observation")
        integer(selection["ActiveProcessors"], "selection processors", 1, 1)
        integer(selection["TestHeapMiB"], "selection heap", 384, 384)
        require(integer(selection["ReserveGiB"], "selection reserve", 6)*GIB >= reserve,
                "Selection reserve differs")
        require(integer(selection["MinimumAvailableBytes"], "selection memory", 0) >= reserve and
                integer(run["MinimumAvailableBytes"], "observed memory", 0) >= reserve, "Observed RAM below reserve")
        idle = number(selection["MinimumIdleCpuPercent"], "prestart CPU observation")
        require(20 <= idle <= 100, "Insufficient prestart CPU reserve")
        if run["MaximumObservedCpuPercent"] is not None:
            number(run["MaximumObservedCpuPercent"], "CPU observation")
            require(run["MaximumObservedCpuPercent"] <= 100, "Invalid CPU observation")
        integer(run["ObservedWorkingSetBytes"], "observed process working set", 1)
        processes[name] = observation
    expected_processes = {"compile"} | {f"case{i}-repeat{r}" for i in range(len(cases)) for r in range(1,repeats+1)}
    require(set(processes) == expected_processes, "Unexpected process observation domain")
    rows = manifest["measurements"]
    require(type(rows) is list and len(rows) == len(cases)*repeats, "Incomplete measurement domain")
    seen, log_digests = set(), {}
    for row in rows:
        index = integer(row["caseIndex"], "case index", 0, len(cases)-1)
        repeat = integer(row["repeat"], "repeat index", 1, repeats)
        key = index, repeat
        require(key not in seen, "Duplicate case/repeat measurement")
        seen.add(key)
        case = cases[index]
        for field in ("jobs", "iterations", "vehicles", "heapBeforeBytes", "wholeJvmSampledPeakBytes",
                      "heapAfterBytes", "returnedResultGraphBytes", "callerThreadAllocatedBytes",
                      "elapsedNanos", "garbageCollections", "garbageCollectionMillis",
                      "maximumHeapBytes", "activeProcessors", "samplingMillis"):
            integer(row[field], field, 0)
        number(row["finalCost"], "final cost")
        require(row["carrierId"] == case["carrierId"] and row["contextFingerprint"] == case["contextFingerprint"]
                and row["jobs"] == case["jobs"] and row["requestedIterations"] == case["requestedIterations"]
                and row["effectiveIterations"] == case["effectiveIterations"], "Replay measurement certificate differs")
        require(verified[index][0] <= row["iterations"] <= case["effectiveIterations"], "Actual iteration certificate differs")
        require(row["activeProcessors"] == 1 and row["samplingMillis"] == 5 and row["vehicles"] >= 1 and
                row["elapsedNanos"] > 0 and row["returnedResultGraphBytes"] > 0, "Invalid measurement dimensions")
        maximum = row["maximumHeapBytes"]
        require(0 < maximum <= 384*MIB and 0 < row["wholeJvmSampledPeakBytes"] <= maximum and
                row["heapBeforeBytes"] <= row["wholeJvmSampledPeakBytes"] and
                row["heapAfterBytes"] <= row["wholeJvmSampledPeakBytes"] and
                row["returnedResultGraphBytes"] <= maximum, "Inconsistent heap observation")
        log_name = f"case{index}-repeat{repeat}.log"
        raw = bounded(directory/log_name, 4*MIB, "probe log")
        messages = [s[len("REPLAY_MEMORY_JSON "):] for s in raw.decode("utf-8-sig").splitlines()
                    if s.startswith("REPLAY_MEMORY_JSON ")]
        require(len(messages) == 1, "Missing or duplicate probe log observation")
        actual = decode(messages[0])
        require(set(actual) == set(NATIVE_FIELDS), "Unexpected probe log fields")
        require(all(type(actual[f]) is type(row[f]) and actual[f] == row[f] for f in NATIVE_FIELDS),
                "Manifest measurement differs from raw probe log")
        log_digests[log_name] = hashlib.sha256(raw).hexdigest().upper()
    result_cases = []
    for index, case in enumerate(cases):
        selected = [r for r in rows if r["caseIndex"] == index]
        result = {f: case[f] for f in ("carrierId", "contextFingerprint", "inputFingerprint", "jobs",
                                      "requestedIterations", "effectiveIterations")}
        result.update(caseIndex=index, seed=verified[index][1], repeats=repeats)
        result.update({f: stats([r[f] for r in selected]) for f in METRICS})
        result["observedProcessWorkingSetBytes"] = stats([
            processes[f"case{index}-repeat{r}"]["run"]["ObservedWorkingSetBytes"] for r in range(1,repeats+1)])
        result["qualityVariesAcrossRepeats"] = len({r["finalCost"] for r in selected}) > 1
        result_cases.append(result)
    require(digest(manifest_path, 2*MIB, "manifest") == manifest_sha,
            "Manifest changed during validation")
    for case in cases:
        verify_case(case)
    for name, expected in log_digests.items():
        require(digest(directory/name, 4*MIB, "probe log") == expected,
                "Probe log changed during validation")
    observed_cpu = [o["run"]["MaximumObservedCpuPercent"] for o in observations
                    if o["run"]["MaximumObservedCpuPercent"] is not None]
    return {
        "version": "hagrid-replay-profile-summary-v1", "status": "VERIFIED_PROFILE",
        "calibrationEstablished": False, "measurementCount": len(rows), "cases": result_cases,
        "build": {k: manifest[k] for k in ("jarSha256", "runnerSha256", "probeSha256", "agentSha256")},
        "evidence": {"inputDirectory": str(directory), "manifestSha256": manifest_sha,
                     "rawProbeLogDigests": log_digests},
        "resources": {"scope": "all recorded owned processes including compiler",
                      "minimumAvailableBytes": min(o["run"]["MinimumAvailableBytes"] for o in observations),
                      "maximumObservedCpuPercent": max(observed_cpu, default=None),
                      "processesWithoutCpuSamples": [o["name"] for o in observations
                                                     if o["run"]["MaximumObservedCpuPercent"] is None],
                      "reserveGiB": manifest["reserveGiB"]},
        "interpretation": [
            "Whole-JVM sampled replay envelope includes shared state, garbage and measurement overhead.",
            "Returned graph is observed after the context closes; excludes full MATSim base and staged-group storage.",
            "Caller allocation excludes solver-worker allocation; cumulative allocation is not resident memory.",
            "Observed process working set includes runtime/native memory; sampled values may miss brief maxima.",
            "Elapsed replay excludes JVM startup/probe setup and returned-graph traversal.",
            "No exclusive carrier estimate, admission policy, production calibration or speedup is established.",
        ],
    }

def publish(summary, output):
    require(not output.exists(), "Existing output is preserved; use a new directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".profile-summary-", dir=output.parent) as scratch:
        stage = Path(scratch)/"complete"
        stage.mkdir()
        (stage/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2,allow_nan=False),encoding="utf-8")
        lines = ["HAGRID Carrier-Replay: geprüfte kleine Speicherprofile", "",
                 "Vollständige Nachweise geprüft. Keine Produktionskalibrierung und kein exklusiver Carrier-Schätzwert.",
                 "JVM-Heap, Ergebnisgraph, Prozessspeicher und Allokation bleiben getrennte Messgrößen.", ""]
        for case in summary["cases"]:
            lines.extend([f"Fall {case['caseIndex']}: {case['carrierId']} / {case['jobs']} Aufträge / {case['repeats']} Wiederholungen",
                          f"  Gesamt-JVM-Heapspitze, beobachtetes Maximum: {case['wholeJvmSampledPeakBytes']['maximum']/MIB:.2f} MiB",
                          f"  Prozess-Working-Set, beobachtetes Maximum: {case['observedProcessWorkingSetBytes']['maximum']/MIB:.2f} MiB",
                          f"  Zurückgegebener Ergebnisgraph, Maximum: {case['returnedResultGraphBytes']['maximum']} Byte",
                          f"  Endkosten min/median/max: {case['finalCost']}",
                          f"  Replayzeit min/median/max in Sekunden: { {k:v/1e9 for k,v in case['elapsedNanos'].items()} }", ""])
        lines.extend(["Vor produktiver Zulassung fehlen repräsentative Carrier, gemeinsame Modellbasis,",
                      "zurückgehaltene Gruppenergebnisse und konservativ validierte Reserven.",
                      "Die JSON-Datei enthält die getrennten Kennzahlen und Kennungen der unveränderten Eingangsnachweise."])
        (stage/"overview.txt").write_text("\n".join(lines)+"\n",encoding="utf-8")
        stage.rename(output)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-directory", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        require(not args.output_directory.exists(), "Existing output is preserved; use a new directory")
        summary = verify_profile(args.input_directory.resolve())
        publish(summary, args.output_directory.resolve())
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"Profile rejected: {error}", file=sys.stderr)
        return 1
    print(f"VERIFIED_PROFILE: {summary['measurementCount']} observations; calibrationEstablished=false")
    return 0

if __name__ == "__main__":
    sys.exit(main())