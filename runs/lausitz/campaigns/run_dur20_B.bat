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
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
set "LOG=hagrid-matsim-output\logs\dur20_B.log"
rem ARM B of the short-tour / evening-window ladder (2026-09-01). Waits for A, rebuilds, runs.
rem
rem WINDOWS SIZED FROM A's MEASURED TOUR COUNT, not from an estimate: A's jsprit run produced
rem 81 freight tours at the 2.0h cap (6052 parcels, 74.7 per tour, was 131.6 at 3.5h). With
rem maxConcurrentFreight=25 and ~2.15h vehicle binding the throughput is ~11.6 tours/h:
rem   morning 07:00-13:00 (6.00h) -> ~70 tours capacity
rem   evening 16:45-18:30 (1.75h) -> ~20 tours capacity
rem   total ~90 against 81 needed = 11 % margin; evening share ~11 tours = ~24 vehicle-hours,
rem   more than the 17.3 vehicle-hours of morning overhang the arm is meant to relieve.
rem Evening window ends 18:30, inside the expiry bound 18:46 (21:00 - 2*420s - 2.0h).
rem
rem THE REBUILD IS REQUIRED, NOT COSMETIC. freightWindows needs TWO windows in one scenario
rem string, but comma is also the token delimiter and the tokenizer only continued openDepots,
rem so "freightWindows=07:00-13:00,16:45-18:30" died with "Invalid token: 16:45-18:30".
rem SimulationRunnerUtils now carries MULTI_VALUE_KEYS = {openDepots, freightWindows}; the
rem change is behaviour-neutral for every existing config (it only ACCEPTS a form that was
rem previously rejected), so campaign comparability is preserved. Covered by
rem ParseScenarioFreightWindowsTest (6 tests, mutation-checked: 2 fail without the change).
rem The build cannot run while arm A holds the jar open, hence it sits here after the wait.
rem
rem VALIDITY GATE, CHECK BEFORE ANY PASSENGER NUMBER: tours_dispatched == tours_planned and
rem tours_expired_pending == 0. The evening window is only ~11 % over-provisioned and the
rem idle-share gate is thin there (baseline busy 102-108 of 120 at 16:45-18:00), so expiry is
rem the realistic failure mode. Expired tours invalidate the passenger comparison - but they
rem are themselves the answer that the evening is unreachable even at a 2.0h cap.
rem
rem CONFOUND, STATED ON PURPOSE: B differs from A by TWO factors (cap AND windows), because
rem windows alone cannot lower morning concurrency - only the cap can, and the overhang is a
rem concurrency problem. Attributing B-A to placement rests on the measured near-null of the
rem cap at 3.5h (f130_c25 8996 vs f130 8973, +23 = inside the 48-trip seed band). If B shows a
rem real gain, a third run must separate the two.
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=7200,maxConcurrentFreight=25,freightWindows=07:00-13:00,16:45-18:30,tag=d1d_dep7_f130_dur20_win_it250"
set /a WAITED=0
echo [%DATE% %TIME%] DUR20 ARM B QUEUED - waiting for arm A to finish >> "%LOG%"
:WAIT
findstr /C:"DUR20 ARM A COMPLETE" "hagrid-matsim-output\logs\dur20_A.log" >nul 2>&1
if not errorlevel 1 goto BUILD
set /a WAITED+=1
if !WAITED! GEQ 360 ( echo [%DATE% %TIME%] ARM B - waited 30h, arm A never reported COMPLETE, giving up >> "%LOG%" & exit /b 1 )
ping -n 301 127.0.0.1 >nul
goto WAIT
:BUILD
echo [%DATE% %TIME%] ARM B - arm A done, rebuilding jar (freightWindows tokenizer) >> "%LOG%"
call mvn -o -q -pl . -am package -DskipTests >> hagrid-matsim-output\logs\dur20_B_build.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] ARM B ABORTED - maven package failed, see dur20_B_build.log >> "%LOG%" & exit /b 1 )
if not exist "%JAR%" ( echo [%DATE% %TIME%] ARM B ABORTED - jar missing after build >> "%LOG%" & exit /b 1 )
echo [%DATE% %TIME%] ARM B - build ok >> "%LOG%"
echo [%DATE% %TIME%] DUR20 ARM B START - dev, f130, 2.0h cap + concurrency 25 + windows >> "%LOG%"
call :RUNARM "d1d_dep7_f130_dur20_win_it250" "%SC%"
echo [%DATE% %TIME%] DUR20 ARM B COMPLETE >> "%LOG%"
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
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter250_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] ARM %TAG% OK - exit %RC%, kpis_long.csv present >> "%LOG%" ) else ( echo [%DATE% %TIME%] ARM %TAG% FAILED - exit %RC%, no kpis_long.csv >> "%LOG%" )
exit /b 0
