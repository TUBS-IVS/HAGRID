@echo off
setlocal

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

"%JAVA_EXE%" @vmargs.txt -jar "%JAR%" ^
concept=batchmoderate,date=2025-05-12,maxIter=150,jspritIter=100 ^
concept=batchmoderate,date=2025-05-13,maxIter=150,jspritIter=100 ^
concept=batchmoderate,date=2025-05-14,maxIter=150,jspritIter=100 ^
concept=batchmoderate,date=2025-05-15,maxIter=150,jspritIter=100 ^
concept=batchmoderate,date=2025-05-16,maxIter=150,jspritIter=100 ^
concept=batchmoderate,date=2025-05-17,maxIter=150,jspritIter=100

set "RC=%ERRORLEVEL%"
echo Exit code %RC%

endlocal
exit /b %RC%
