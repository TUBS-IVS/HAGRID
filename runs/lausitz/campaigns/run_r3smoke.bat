@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\..\hagrid\simulation"
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE ( echo No java.exe found & exit /b 1 )
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\hagrid-1.0-SNAPSHOT.jar"
if not exist "%JAR%" ( echo JAR not found %JAR% & exit /b 1 )
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
rem ====================================================================================
rem END-TO-END SMOKE fuer den Modulumzug nach hagrid/simulation (Spec 2026-09-21 #6.4).
rem Passenger-only DRT_BASELINE, EINE Iteration, kein jsprit, kein LMD. Prueft Input-
rem aufloesung, log4j-Dateiappender, Iterationsausgabe, run_metadata.json und den
rem KPI-Dashboard-Trigger ueber zwei Ordnerebenen. Keine zitierfaehigen Zahlen.
rem ====================================================================================
set "TAG=r3smoke"
set "SC=concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=80,maxIter=1,freight=false,tag=%TAG%"
echo [%DATE% %TIME%] SMOKE START :: %SC%
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.lausitz.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo SMOKE PREPARE FAILED & exit /b 2 )
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
echo [%DATE% %TIME%] SMOKE END exit %RC%
endlocal & exit /b %RC%
