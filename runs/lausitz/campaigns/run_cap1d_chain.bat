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
set "LOG=hagrid-matsim-output\logs\cap1d_chain.log"
rem FREIGHT CONCURRENCY CAP - two arms at fleet 135 and 130, new key maxConcurrentFreight.
rem
rem WHY A CAP AND NOT WINDOWS. Measured on basew21_it250 (passenger-only baseline, 120 veh):
rem free capacity is 27.0 / 33.9 / 25.0 vehicles at 08 / 09 / 10 h, while the 1d arm at
rem theta=0.15 puts 32.5 / 34.4 / 34.0 vehicles on freight in exactly those hours - an overhang
rem of 14.9 vehicle-hours in three morning hours, against 133 vehicle-hours left unused in the
rem same delivery window after 16:00. cap=25 is the tightest of those three baseline values
rem (10:00 = 25.0), i.e. the passenger-side budget stated directly. The idle-share gate cannot
rem express it: it measures the MODULAR fleet's own slack, which is larger because that fleet is.
rem
rem WHY NO EVENING WINDOW, although that was the original idea. The evening trough is only
rem reachable THROUGH the passenger peak, and two measurements close that door:
rem   - expiry: last admissible dispatch is 21:00 - 2*420s - 12600s = 17:16 for a full tour;
rem   - the theta gate is SHUT at 16:00 (idle share 0.130 < 0.15) and only reopens at 17:00
rem     (0.216) - a 16-minute dispatch window before the envelope lapses.
rem An evening window would therefore not defer those tours, it would EXPIRE them (~21 tours,
rem ~2700 parcels). The freightWindows key exists and is tested, but stays unset here.
rem
rem WHAT TO CHECK FIRST in the results: tours_expired_pending and tours_dispatched. The cap must
rem spread the morning block into 11-14 h, NOT drop tours. Anything other than 46/46 dispatched
rem and 0 expired invalidates the arm. Second thing to check: the hourly freight profile must
rem stay EMPTY after 15:00 - a flat cap also permits 25 vehicles at 15-17 h, where the baseline
rem has only 13.9 / 15.2 / 17.8 free. It should not get there (the 25.9 deferred vehicle-hours
rem fit four times over into the 93.8 free at 11-14 h, and the dispatcher is greedy), but if
rem freight shows up in the passenger peak the cap needs a companion window.
rem
rem Reference points, same JAR, theta=0.15, no cap: f135 plateau 9214 / 87.950 EUR,
rem f130 plateau 8973, baseline basew21_it250 plateau 9183 / 88.942 EUR. Seed noise = 48 trips.
set "BASE=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,maxConcurrentFreight=25"
echo [%DATE% %TIME%] CAP1D CHAIN START - 2 arms, dev, maxConcurrentFreight=25, fleets 135 then 130 >> "%LOG%"
call :RUNARM "d1d_dep7_f135_c25_it250" "%BASE%,fleetSize=135,tag=d1d_dep7_f135_c25_it250"
call :RUNARM "d1d_dep7_f130_c25_it250" "%BASE%,fleetSize=130,tag=d1d_dep7_f130_c25_it250"
echo [%DATE% %TIME%] CAP1D CHAIN COMPLETE >> "%LOG%"
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
