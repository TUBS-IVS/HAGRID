@echo off
setlocal
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
set "LOG=hagrid-matsim-output\logs\theta1d_chain.log"
rem THETA SENSITIVITY around the inherited winner, at the CALIBRATED fleet (135) on the NEW
rem depot stage. theta=0.15 was won on the OLD depot logic (cost campaign, superseded by the
rem 2026-08-17 depot rework) and has been carried into every new-stage run untested. This chain
rem tests whether that inheritance is harmless.
rem
rem The gate (ModularTourDispatcher:145) dispatches WHILE idle/fleet > theta, so LOWER theta =
rem more freight pushed out at once. Measured in d1d_dep7_f135_it250: all 46 tours go pending at
rem 07:16, but only 15 dispatch in that simstep - the loop stops when idle hits 0.15*135 = 20.
rem The remaining 31 trickle out until 11:45. So ~35 of 135 vehicles are idle at the morning
rem peak and the gate IS binding, even though nothing expired (46/46 dispatched, 0 expired,
rem 0 splice-rejected, 6052/6052 parcels).
rem
rem Predicted surge diversion (idle 35 at 07:16, loop stops at floor(theta*135)):
rem   theta=0.10 -> stops at 13 idle -> ~22 tours in the surge (7 MORE than now)
rem   theta=0.15 -> stops at 20 idle -> 15 tours          (measured)
rem   theta=0.20 -> stops at 27 idle -> ~8 tours in the surge (7 FEWER than now)
rem That is ~5%% of the fleet redirected at the worst hour of the day - plausibly above the
rem 48-trip seed noise band, which is what makes the sweep worth 18 h.
rem
rem theta=0.20 runs FIRST: it is the only arm that can fail outright (tours delayed past their
rem expiry envelope -> tours_expired_pending > 0), and that bit bounds the usable theta range
rem from above. An early failure is more actionable than an early success.
rem
rem Everything else is held identical to d1d_dep7_f135_it250 so the three points form a clean
rem one-factor sweep: fleet 135, maxIter 250, jsprit 100, openDepots=all, seed 1337 (default).
rem NOTE the verification glob below is iter250, not the iter150 of the older chains.
set "BASE=date=2025-05-13,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=135"
echo [%DATE% %TIME%] THETA1D CHAIN START - 2 arms, dev, fleet 135, maxIter=250, theta 0.20 then 0.10 >> "%LOG%"
call :RUNARM "d1d_dep7_f135_t020_it250" "concept=drt_modular,%BASE%,idleThreshold=0.20,tag=d1d_dep7_f135_t020_it250"
call :RUNARM "d1d_dep7_f135_t010_it250" "concept=drt_modular,%BASE%,idleThreshold=0.10,tag=d1d_dep7_f135_t010_it250"
echo [%DATE% %TIME%] THETA1D CHAIN COMPLETE >> "%LOG%"
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
