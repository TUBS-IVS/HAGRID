@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED"

rem Lausitz passenger-only DRT_BASELINE: preprocessing then the MATSim DRT sim.
rem Both invocations use IDENTICAL scenario args so the runId (and therefore the
rem clipped-input + output paths) matches between the two steps.

rem Scenario args shared by both steps (runId must match).
set "ARGS=concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=80,maxIter=150,jspritIter=100,tag=fleet80_depot_railpt"

rem 1) Preprocess: clipped DRT network, person-only plans, DVRP fleet + rail schedule.
rem NOTE: call is REQUIRED -- mvn is mvn.cmd; without call, control transfers to it and
rem never returns, so the second mvn step (the actual simulation) would silently never run.
call mvn -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass="hagrid.integrated.drt.PrepareLausitzDrtInputs" ^
  -Dexec.args="%ARGS%"

set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" (
  echo EXIT_CODE=%RC%
  endlocal
  exit /b %RC%
)

rem 2) Run integrated passenger-DRT + freight simulation.
call mvn -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass="hagrid.HAGRIDSimulationRunner" ^
  -Dexec.args="%ARGS%"

set "RC=%ERRORLEVEL%"
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
