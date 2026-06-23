@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED"

rem freight wird hier nicht benoetigt, ist aber nach run_hagrid_sim bereits im lokalen Maven-Repo
mvn -pl parcel-demand-2-matsim-pipeline exec:java ^
  -Dexec.mainClass="hagrid.HAGRIDAnalysisRunner" ^
  -Dexec.args="concept=basecase,date=2025-05-13,maxIter=150,jspritIter=10000"

set "RC=%ERRORLEVEL%"
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
