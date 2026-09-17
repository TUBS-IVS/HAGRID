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
set "LOG=hagrid-matsim-output\logs\bud.log"
rem TASK 7 of the self-referential capacity budget plan (2026-09-04).
rem docs/superpowers/plans/2026-09-04-selfreferential-capacity-budget.md
rem
rem ONE FACTOR vs d1d_dep7_f130_it250 (plateau 8973): the budget. Same fleet 130, same
rem idleThreshold 0.15, same maxTourDuration 12600 (3.5h), same openDepots=all, no concurrency
rem cap, no windows. budgetHeadroom=0.15 EQUALS theta on purpose: for the current bin the two
rem gates are algebraically the same condition, so the budget reproduces the calibrated gate
rem there and changes ONLY the refusal of commitments that would collide in a LATER bin.
rem Setting headroom to 0 would have changed the gate sharpness and added look-ahead at once.
rem
rem THE JAR MATTERS HERE MORE THAN USUAL. An unknown scenario key is put into the map and
rem IGNORED, not rejected - so a jar predating this feature would silently run the plain f130
rem arm while the .bat claims a budget run. Jar rebuilt 2026-09-04 22:13 and verified to contain
rem PassengerLoadProfile.class and ModularBudgetStats.class before this file was written.
rem VERIFY IN THE CONSOLE LOG that the Scenario line shows budgetMode=SELFREF. If it does not,
rem the run is a plain f130 repeat and must be discarded.
rem
rem THIS ARM IS A GATE, NOT A RESULT. Before quoting any passenger number:
rem  1) tours_dispatched == tours_planned == 46 and tours_expired_pending == 0 and
rem     parcels_served == 6052. Same validity bar as every arm in this study.
rem  2) budget_overrides_expiry LOW. A high count means the budget was overridden into
rem     decoration and this is the old theta gate wearing a new name.
rem  3) CONVERGENCE RE-DERIVED ON THIS ARM S OWN TRAJECTORY. The dispatcher is now part of the
rem     fixed point (the budget reads iteration N-1, which the budget itself shaped), so the
rem     226-249 plateau window of METHODS-LOG 2.47 does NOT carry over automatically. Apply the
rem     block-slope test here before using it.
rem  4) WALL CLOCK against the ~17 h reference. When the budget blocks every pending tour they
rem     are all re-checked every simstep - O(pending x spanBins) budget() calls. A per-bin cache
rem     is the cheap fix if this bites.
rem  5) delta_parcels PER PROVIDER. Budget refusal is a per-tour continue, so under pressure it
rem     implicitly prefers SHORTER tours. The C7 interleave exists to keep dispatch order
rem     provider-neutral; if tour length correlates with provider, the budget could reintroduce
rem     exactly the bias C7 removes.
rem
rem Path length of the .gpkg is 219 chars, 32 below the 251 limit that killed arm B (METHODS-LOG
rem 2.51) after a full 16 h run. Do not lengthen this tag.
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=12600,budgetMode=selfref,budgetSmoothing=5,budgetHeadroom=0.15,tag=d1d_f130_bud"
echo [%DATE% %TIME%] BUD START - dev, f130, budgetMode=selfref k=5 headroom=0.15 >> "%LOG%"
call :RUNARM "d1d_f130_bud" "%SC%"
echo [%DATE% %TIME%] BUD COMPLETE >> "%LOG%"
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
