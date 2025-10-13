@echo off
setlocal

rem Optional: enable/disable zone-based caching. Use "true" or "false". Default true for scenario run.
set "ZONE_CACHING=true"
rem Optional: override the zone distance threshold in meters. Leave empty to use runner defaults (1500m when caching=true).
set "ZONE_THRESHOLD="

cd /d "%~dp0"

rem logs are now written to sim-output/logs/runid

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

set "SCENARIO_ARGS=concept=basecase,date=2025-05-13,maxIter=150,jspritIter=100"

if /I "%ZONE_CACHING%"=="true" goto zone_true
if /I "%ZONE_CACHING%"=="false" goto zone_false
goto zone_args_done

:zone_true
set "SCENARIO_ARGS=%SCENARIO_ARGS%,zoneCaching=true"
if defined ZONE_THRESHOLD set "SCENARIO_ARGS=%SCENARIO_ARGS%,zoneThreshold=%ZONE_THRESHOLD%"
goto zone_args_done

:zone_false
set "SCENARIO_ARGS=%SCENARIO_ARGS%,zoneCaching=false"
if defined ZONE_THRESHOLD set "SCENARIO_ARGS=%SCENARIO_ARGS%,zoneThreshold=%ZONE_THRESHOLD%"
goto zone_args_done

:zone_args_done

echo Running scenario with %SCENARIO_ARGS%
"%JAVA_EXE%" @vmargs.txt -jar "%JAR%" %SCENARIO_ARGS%


set "RC=%ERRORLEVEL%"
echo Exit code %RC%

endlocal
exit /b %RC%
