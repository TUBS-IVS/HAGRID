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
set "LOG=hagrid-matsim-output\logs\bud2.log"
rem ====================================================================================
rem HONEST DISPATCH ENVELOPE + URGENCY RAMP
rem docs/superpowers/plans/2026-09-05-honest-envelope-and-urgency-ramp.md
rem
rem WHY THIS ARM EXISTS. The first budget arm (d1d_f130_bud) was killed at iteration 130.
rem Gate 1 failed and got worse monotonically: 31 of 46 tours expired pending by it.129,
rem against 0 on the anchor. The log shows the cause as a three-line chain in one second:
rem the budget refuses the tour, the expiry envelope fires its "last opportunity" override,
rem and the SPLICER REFUSES THAT OVERRIDE - routed completion exceeds min(latestEnd,
rem vehicle service end). Override count = splicer rejections = expiries, per iteration.
rem
rem MEASURED on the anchor d1d_dep7_f130_it250 it.250, all 46 dispatch events: the old
rem envelope (2*RETOOLING_S + plannedDuration) is optimistic against the splicer by a
rem median of 943 s, a p90 of 1635 s and a max of 2403 s. It omitted the approach leg,
rem used jsprit car-network time instead of DRT-routed time, and ignored serviceEnd.
rem
rem TWO CHANGES, and only the second is the user-requested "penalty":
rem  Fix 1  the envelope now uses the LEARNED routed excursion length per tour
rem         (FreightChainProfile, k=5, max over the window), bootstrapped from the
rem         scheduler free-flow DRT-network lower bound x CHAIN_BOOTSTRAP_FACTOR 1.25,
rem         and capped by min(latestEnd, max vehicle serviceEnd).
rem  Fix 2  the binary override is replaced by a RAMP: urgency grows from 0 to the full
rem         headroom reserve over budgetUrgencyLeadS of remaining slack, so a tour leaves
rem         while the splicer can still place it instead of at the last instant.
rem
rem Fix 1 is deliberately NOT gated on budgetMode - it is a correctness fix, and gating it
rem would leave this arm differing from every existing arm in two ways at once. It is
rem expected to be INERT where slack is generous (the anchor dispatches at ~07:16 against
rem 21:00). That expectation is pinned by test, NOT re-measured: the anchor was not re-run.
rem
rem ONE FACTOR vs d1d_dep7_f130_it250 (plateau 8973): fleet 130, idleThreshold 0.15,
rem maxTourDuration 12600, openDepots=all, no cap, no windows - all identical.
rem
rem THE JAR MATTERS. An unknown scenario key is put into the map and IGNORED, not
rem rejected, so a stale jar would silently run the plain f130 arm while this file claims
rem a budget run. VERIFY IN THE CONSOLE LOG that the Scenario line shows
rem   budgetMode=SELFREF budgetSmoothing=5 budgetHeadroom=0.15 budgetUrgencyLeadS=3600.0
rem If it does not, discard the run.
rem
rem THIS ARM IS A GATE, NOT A RESULT. Before quoting any passenger number:
rem  1) tours_dispatched == tours_planned == 46, tours_expired_pending == 0,
rem     parcels_served == 6052. Same validity bar as every arm in this study. This is the
rem     gate the previous arm failed, so it is the first thing to read.
rem  2) budget_urgency_admits > 0. If ZERO the ramp never bound and this is the plain
rem     budget arm under a new name - the mechanism was not tested, however healthy the
rem     rest of the CSV looks.
rem  3) budget_overrides_expiry LOW. High means the terminal branch carried the arm, i.e.
rem     the ramp came too late and the budget is decorative again.
rem  4) chain_ratio_p90 / chain_ratio_max. These retire CHAIN_BOOTSTRAP_FACTOR (1.25) with
rem     measurement. If p90 exceeds 1.25 the bootstrap is too small and iteration 0 is
rem     under-protected; record the number either way.
rem  5) CONVERGENCE RE-DERIVED ON THIS ARM S OWN TRAJECTORY. The dispatcher is part of the
rem     fixed point, so the 226-249 plateau of METHODS-LOG 2.47 does NOT carry over.
rem  6) WALL CLOCK against the ~17 h anchor. The killed arm ran at 6.36 min/It vs 3.67
rem     (+73%), but that is partly a SYMPTOM - it had ~31 tours pending all day, each
rem     re-checked every simstep. A healthy arm should be much closer to the anchor.
rem  7) delta_parcels PER PROVIDER (C7 bias): refusal is still a per-tour continue.
rem ====================================================================================
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=12600,budgetMode=selfref,budgetSmoothing=5,budgetHeadroom=0.15,budgetUrgencyLeadS=3600,tag=d1d_f130_bud2"
echo [%DATE% %TIME%] BUD2 START - dev, f130, selfref k=5 h=0.15 lead=3600, honest envelope >> "%LOG%"
call :RUNARM "d1d_f130_bud2" "%SC%"
echo [%DATE% %TIME%] BUD2 COMPLETE >> "%LOG%"
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
