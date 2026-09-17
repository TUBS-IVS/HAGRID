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
set "LOG=hagrid-matsim-output\logs\w1117.log"
rem PURE PLACEMENT ARM (2026-09-03). Tour duration UNCHANGED at the 3.5h default, so this
rem arm carries NO efficiency penalty: 46 tours, not the 81 that arms A and B paid for.
rem
rem ONE FACTOR vs d1d_dep7_f130_it250 (plateau 8973): the dispatch window. Same fleet 130,
rem same idleThreshold 0.15, same openDepots=all, no concurrency cap. The short tag drops the
rem "dep7" label for path-length reasons ONLY - openDepots=all is the same configuration.
rem
rem WHY 11:00 AND NOT 10:00. The freight overhang against the baseline free capacity, measured
rem in 15-min bins on basew21_it250, runs 08:00-11:00 and PEAKS at 10:15-10:45 (10.6 / 8.7 /
rem 10.0 vehicles), not in the early morning. A window opening at 10:00 would leave the worst
rem hour open. 11:00 clears the whole overhang.
rem
rem WHY THE EVENING NEEDS NO SHORT TOURS. Expiry is now + 2*420s + plannedDuration > 21:00, so
rem at 3.5h the last admissible dispatch is 17:16 - but what matters is where the vehicle HOURS
rem land, not the dispatch instant: a tour dispatched at 17:00 executes until ~20:40, i.e. two
rem thirds of it after 18:00, in the genuinely free evening (baseline free 33.9 at 18:45,
rem 45.6 at 20:15, 63.3 at 20:45). Arm B reached that evening only by halving the tour cap,
rem which cost 238 trips and then gave 234 back - a wash. This arm skips the round trip.
rem
rem CAPACITY. 46 tours, ~3.6h vehicle binding, uncapped concurrency ~34 => ~9.4 tours/h over a
rem 6h window = ~56 tours against 46 needed, 22 % margin (arm B had 11 %). The idle-share gate
rem may throttle around 15:30-16:00 (baseline busy 108.6 of 120); that spreads the block, it
rem does not drop it.
rem
rem VALIDITY GATE, BEFORE ANY PASSENGER NUMBER: tours_dispatched == tours_planned == 46 and
rem tours_expired_pending == 0.
rem
rem PATH LENGTH (METHODS-LOG 2.51): the .gpkg path is 223 chars, 28 below the 251 limit that
rem killed arm B at 253 after a full 16 h run. Do not lengthen this tag.
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=12600,freightWindows=11:00-17:00,tag=d1d_f130_w1117"
echo [%DATE% %TIME%] W1117 START - dev, f130, 3.5h tours, freightWindows=11:00-17:00 >> "%LOG%"
call :RUNARM "d1d_f130_w1117" "%SC%"
echo [%DATE% %TIME%] W1117 COMPLETE >> "%LOG%"
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
