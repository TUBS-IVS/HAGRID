@echo off
setlocal

rem Kein absoluter Classpath mehr: der frueher hier eingebettete ~3000-Zeichen-cp
rem zeigte auf das .m2-Repo eines anderen Benutzers und war auf jeder anderen
rem Maschine unbenutzbar. Maven loest die Abhaengigkeiten selbst auf.
cd /d "%~dp0parcel-demand-2-matsim-pipeline"

set "MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED"

mvn exec:java ^
  -Dexec.mainClass="hagrid.HAGRIDAnalysisRunner" ^
  -Dexec.args="concept=basecase,date=2025-05-13,maxIter=150,jspritIter=10000"

set "RC=%ERRORLEVEL%"
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
