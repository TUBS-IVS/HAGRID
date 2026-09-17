@echo off
setlocal
cd /d "%~dp0..\..\..\hagrid"
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
set "LOG=hagrid-matsim-output\logs\fleet1d_chain.log"
rem Fleet recalibration for 1d, on the WINNING depot stage (openDepots=all) so the curve lies on
rem the same depot logic as d1d_dep7 (fleet 150). The pre-existing f130t015 / f140t015 points sit
rem on the OLD provider-depot logic and are NOT comparable to these.
rem Everything except fleetSize is the standing 1d config, identical to d1d_dep7.
set "BASE=date=2025-05-13,idleThreshold=0.15,maxIter=150,jspritIter=100,writeDashboard=true,openDepots=all"
echo [%DATE% %TIME%] FLEET1D CHAIN START - 2 arms, dev, openDepots=all >> "%LOG%"
rem 140 first (user order): it brackets the ca. 135 the old-logic slope predicts for matching the
rem Baseline's passenger output, so it is the more informative point if only one finishes.
call :RUNARM "d1d_dep7_f140" "concept=drt_modular,%BASE%,fleetSize=140,tag=d1d_dep7_f140"
call :RUNARM "d1d_dep7_f130" "concept=drt_modular,%BASE%,fleetSize=130,tag=d1d_dep7_f130"
echo [%DATE% %TIME%] FLEET1D CHAIN COMPLETE >> "%LOG%"
endlocal
exit /b 0

:RUNARM
set "TAG=%~1"
set "SC=%~2"
echo [%DATE% %TIME%] ARM %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.lausitz.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] ARM %TAG% PREPARE FAILED - see %TAG%_prepare.log >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] ARM %TAG% prepare ok, simulation start >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter150_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] ARM %TAG% OK - exit %RC%, kpis_long.csv present >> "%LOG%" ) else ( echo [%DATE% %TIME%] ARM %TAG% FAILED - exit %RC%, no kpis_long.csv >> "%LOG%" )
exit /b 0
