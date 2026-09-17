@echo off
setlocal
cd /d "%~dp0..\..\hagrid"
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
set "LOG=hagrid-matsim-output\logs\depot1c_chain.log"
rem Standing 1c config: fleet 120 (8-seat pax DRT, parcels hitch-hike), 150 MATSim iters,
rem delivery window 07:30-21:00 (standing convention since 2026-07-30, no key needed).
rem chiThreshold=999999 = GATE OPEN. The spec (2026-08-17, D10 and section 7) names
rem chiThreshold=-1 for "gate open" - that token is WRONG: ChiGateInsertionCostCalculator:169
rem treats chi < 0 as HARD-CLOSED (no parcel ever boards), pinned by
rem ChiGateInsertionCostCalculatorTest "chi=-1 (hard-closed) rejects every parcel".
rem D10's stated intent is an inert gate, so a large positive threshold is what implements it.
rem maxJobsPerDistrict is deliberately absent: 1c never splits a catchment
rem (HAGRIDSimulationConfig:177), so the 1d partition controls have no 1c counterpart.
set "BASE=date=2025-05-13,fleetSize=120,maxIter=150,jspritIter=100,writeDashboard=true,chiThreshold=999999"
echo [%DATE% %TIME%] DEPOT1C CHAIN START - 3 arms, dev, chi OPEN >> "%LOG%"
rem Same ordering logic as the 1d chain: cleanest contrast first, then the extreme, then the
rem middle point. A failing arm is logged and the chain continues.
call :RUNARM "d1c_dep7" "concept=drt_shareduse,%BASE%,tag=d1c_dep7,openDepots=all"
call :RUNARM "d1c_dep1" "concept=drt_shareduse,%BASE%,tag=d1c_dep1,openDepots=hoy_sued"
call :RUNARM "d1c_dep3" "concept=drt_shareduse,%BASE%,tag=d1c_dep3,openDepots=wittichenau,hoy_sued,doergenhausen"
echo [%DATE% %TIME%] DEPOT1C CHAIN COMPLETE >> "%LOG%"
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
