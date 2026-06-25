@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED"

REM Dedicated LMD baseline (Lausitz). Adjust date/jspritIter as needed.
call mvnw.cmd -q -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass=hagrid.simulation.HAGRIDSimulationRunner ^
  -Dexec.args="concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=100,tag=v1"

set "RC=%ERRORLEVEL%"
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
