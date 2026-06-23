@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED"

rem Lausitz passenger-only DRT_BASELINE: preprocessing then the MATSim DRT sim.
rem Both invocations use IDENTICAL scenario args so the runId (and therefore the
rem clipped-input + output paths) matches between the two steps.

rem 1) Preprocess: produce the clipped DRT network, person-only plans and DVRP fleet.
mvn -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass="hagrid.integrated.drt.PrepareLausitzDrtInputs" ^
  -Dexec.args="concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20,maxIter=1"

set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  echo EXIT_CODE=%RC%
  endlocal
  exit /b %RC%
)

rem 2) Run the passenger DRT simulation on the just-produced inputs.
mvn -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass="hagrid.HAGRIDSimulationRunner" ^
  -Dexec.args="concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=20,maxIter=1"

set "RC=%ERRORLEVEL%"
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
