@echo off
setlocal

cd /d "%~dp0"

rem logs for each run land in sim-output/<runId...>/logs

rem JDK aufloesen: HAGRID_JAVA_EXE > JAVA_HOME > PATH (JDK 21 erwartet)
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE (
  echo No java.exe found. Set JAVA_HOME or HAGRID_JAVA_EXE to a JDK 21 installation.
  exit /b 1
)
echo Using JAVA_EXE=%JAVA_EXE%

rem evtl. Overrides entschärfen
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="

rem Sichtprüfung
"%JAVA_EXE%" -version

rem effektive VM Settings vor dem Start loggen
if not exist "hagrid-output\logs\jvm" mkdir "hagrid-output\logs\jvm"
"%JAVA_EXE%" -XshowSettings:vm -version 2> "hagrid-output\logs\jvm\vm_settings_before.txt"

rem Versionspruefung: MATSim/HAGRID sind auf 21 gebaut (pom.xml release=21)
findstr /r /c:"version .21\." "hagrid-output\logs\jvm\vm_settings_before.txt" >nul
if errorlevel 1 echo WARNING: JDK 21 expected - see hagrid-output\logs\jvm\vm_settings_before.txt

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
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SCENARIO_ARGS%


set "RC=%ERRORLEVEL%"
echo Exit code %RC%

endlocal
exit /b %RC%
