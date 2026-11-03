#!/usr/bin/env bash
# jsprit cache production proof for Linux (same runs and checks as tools/jsprit-cache-proof.ps1):
# three short paper-Baseline runs (maxIter 2, jspritIter 100): on -> miss, on -> hit, verify -> verified,
# each routed carriers file with SHA-256 2c1195c3... Usage:
#   jsprit-cache-proof.sh <java> <module-dir> <jar> [xmx] [tag-prefix]
# Status lines: <module-dir>/hagrid-output/logs/cache-proof/proof_status.txt
set -u
JAVA="$1"; MODULE="$2"; JAR_SOURCE="$3"; XMX="${4:-24g}"; PREFIX="${5:-cproof}"
EXPECTED_SHA="2c1195c3300d411f"
LOG="$MODULE/hagrid-output/logs/cache-proof"
mkdir -p "$LOG"
STATUS="$LOG/proof_status.txt"
mark() { echo "$(date +%Y-%m-%dT%H:%M:%S)  $*" | tee -a "$STATUS"; }

JAR="hagrid-output/logs/cache-proof/proof-hagrid-shaded.jar"   # relative to $MODULE
cp -f "$JAR_SOURCE" "$MODULE/$JAR"
mark "START host=$(hostname) jar_sha256=$(sha256sum "$MODULE/$JAR" | cut -c1-64) java=$JAVA"

BLOCKED="$MODULE/hagrid-output/shared/jsprit-cache/BLOCKED.json"
if [ -e "$BLOCKED" ]; then mark "ABORT: the cache is blocked ($BLOCKED)"; exit 2; fi

JVM="-Xms8g -Xmx$XMX -Xss512k -XX:MaxDirectMemorySize=4g -XX:ActiveProcessorCount=12 -XX:+UseZGC \
-XX:+ExitOnOutOfMemoryError --add-opens java.base/java.lang=ALL-UNNAMED \
-Dlog4j2.configurationFile=logging/log4j2_runlocal.xml -Dhagrid.pipeline.root=."
SPEC="concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=2,jspritIter=100,freight=true,seed=1337,kpiDashboard=false"

run_java() {   # tag main-class cache-mode
  local short="${2##*.}"
  ( cd "$MODULE" && "$JAVA" $JVM "-Dhagrid.jsprit.cache=$3" -cp "$JAR" "$2" "$SPEC,tag=$1" \
      > "$LOG/$1.$short.out" 2> "$LOG/$1.$short.err" )
  return $?
}

json_field() {   # file field
  python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2]))" "$1" "$2" 2>/dev/null || echo "?"
}

failed=0
for spec in "1 on miss" "2 on hit" "3 verify verified"; do
  set -- $spec; n=$1; mode=$2; expect=$3
  tag="${PREFIX}_$n"
  mark "$tag START cache=$mode"
  t0=$(date +%s)
  if ! run_java "$tag" hagrid.lausitz.drt.PrepareLausitzDrtInputs "$mode"; then
    mark "$tag PREP_EXIT=nonzero - see $tag.*.err/out"; failed=1; break
  fi
  run_java "$tag" hagrid.core.simulation.HAGRIDSimulationRunner "$mode"; e2=$?
  run_id="DRT_BASELINE_13052025_$tag"
  meta="$MODULE/hagrid-matsim-output/${run_id}_iter2_jsprit100/run_metadata.json"
  routed="$MODULE/hagrid-output/$run_id/carriers/${run_id}_lmd_carriers_routed.xml"
  cache="?"; key="?"; sha="?"
  [ -f "$meta" ] && cache=$(json_field "$meta" jsprit_cache) && key=$(json_field "$meta" jsprit_cache_key)
  [ -f "$routed" ] && sha=$(sha256sum "$routed" | cut -c1-64)
  minutes=$(( ($(date +%s) - t0) / 60 ))
  if [ "$e2" -eq 0 ] && [ "$cache" = "$expect" ] && [ "${sha:0:16}" = "$EXPECTED_SHA" ]; then verdict=OK; else verdict=FAIL; failed=1; fi
  mark "$tag RUN_EXIT=$e2 jsprit_cache=$cache (expected $expect) key=$key routed_sha256=$sha minutes=$minutes $verdict"
done
if [ -e "$BLOCKED" ]; then mark "FAILED: the cache got blocked ($BLOCKED)"; failed=1; fi
if [ "$failed" -ne 0 ]; then mark "PROOF FAILED"; exit 1; else mark "PROOF PASSED"; exit 0; fi
