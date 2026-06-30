@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED"

REM Dedicated LMD baseline (Lausitz). tag=localdepots_stagger = staggered dispatch [8h,14h].
REM maxIter=0: jsprit routes offline, one mobsim pass + dashboard. LMD is freight-only
REM (no background car traffic -> no congestion), so MATSim replanning iterations add nothing.
REM call so control returns and the exit-code reporting below runs (mvn is mvn.cmd).
call mvn -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass=hagrid.HAGRIDSimulationRunner ^
  -Dexec.args="concept=LMD_BASELINE,date=2025-05-13,maxIter=0,jspritIter=100,tag=localdepots_stagger,writeDashboard=true"

set "RC=%ERRORLEVEL%"
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
