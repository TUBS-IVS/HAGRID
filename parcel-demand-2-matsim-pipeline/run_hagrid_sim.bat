@echo off
setlocal

cd /d "%~dp0"

rem logs for each run land in sim-output/<runId...>/logs

rem harte Vorgabe JDK 21
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.3.9-hotspot\bin\java.exe"

rem evtl. Overrides entschärfen
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="

rem Sichtprüfung
"%JAVA_EXE%" -version

rem effektive VM Settings vor dem Start loggen
"%JAVA_EXE%" -XshowSettings:vm -version 2> "logs\vm_settings_before.txt"

rem Start mit Argumentdatei, kein Umbruch Chaos
set "JAR=target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar"
if not exist "%JAR%" (
  echo JAR not found %JAR%
  exit /b 1
)

echo Starting with args from vmargs.txt
type vmargs.txt

set "SCENARIO_ARGS=concept=basecase,date=2025-05-13,maxIter=150,jspritIter=10000,zoneThreshold=1500"

echo Running scenario with %SCENARIO_ARGS%
"%JAVA_EXE%" @vmargs.txt -jar "%JAR%" %SCENARIO_ARGS%


set "RC=%ERRORLEVEL%"
echo Exit code %RC%

endlocal
exit /b %RC%
