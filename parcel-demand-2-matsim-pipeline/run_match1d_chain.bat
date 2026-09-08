@echo off
setlocal
cd /d "%~dp0"
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE ( echo No java.exe found & exit /b 1 )
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar"
if not exist "%JAR%" ( echo JAR not found %JAR% & exit /b 1 )
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
set "LOG=hagrid-matsim-output\logs\match1d_chain.log"
rem 1d CALIBRATION ARM at the fleet size predicted to match the NEW baseline.
rem
rem Target: basew21_it250 plateau (226-249) = 9183 rides. That baseline supersedes basew21@150
rem (9027) on two counts: it is converged, and it runs the same JAR as every 1d arm. The old one
rem predates REGRET_INSERTION (commit 158cc8c, 2026-08-11), which cut baseline freight from 52 to
rem 41 tours - 11 fewer vans on the network, which is exactly the iteration-0 loading gap.
rem
rem Fleet 135 comes from the one clean 150->250 anchor (f130: 9192 -> 8973, gap 219) applied to
rem the 150-iteration sweep. Proportional-decay model gives 135.9, constant-shift 138.2, and a
rem model-free bound on the corrected slope gives [134.4, 138.2] with midpoint 136.3. Rounded to
rem the nearest 5 that is 135. If this lands below 9183, 140 is the follow-up.
rem
rem Parcels need no calibration: 1d serves all 6052 at every fleet size 120-150 (46 tours, 0 late).
set "BASE=date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all"
echo [%DATE% %TIME%] MATCH1D CHAIN START - 1 arm, dev, fleet 135, maxIter=250 >> "%LOG%"
call :RUNARM "d1d_dep7_f135_it250" "concept=drt_modular,%BASE%,maxIter=250,fleetSize=135,tag=d1d_dep7_f135_it250"
echo [%DATE% %TIME%] MATCH1D CHAIN COMPLETE >> "%LOG%"
endlocal
exit /b 0

:RUNARM
set "TAG=%~1"
set "SC=%~2"
echo [%DATE% %TIME%] ARM %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.integrated.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] ARM %TAG% PREPARE FAILED - see %TAG%_prepare.log >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] ARM %TAG% prepare ok, simulation start >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter250_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] ARM %TAG% OK - exit %RC%, kpis_long.csv present >> "%LOG%" ) else ( echo [%DATE% %TIME%] ARM %TAG% FAILED - exit %RC%, no kpis_long.csv >> "%LOG%" )
exit /b 0
