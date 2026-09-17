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
set "LOG=hagrid-matsim-output\logs\convbase_chain.log"
rem BASELINE CONVERGENCE RUN. basew21 (the reference Baseline, created 2026-07-31) ran 150
rem iterations and is itself NOT converged: residual slope over 136-149 is -13.05 rides/iter,
rem steeper than any 1d sweep arm. Comparing the converged f130@250 (8973) against basew21@150
rem (9027 plateau) therefore compares a settled value to an unsettled one. This re-runs the
rem Baseline at maxIter=250 so both sides sit on the same footing.
rem
rem Config reproduces run_metadata.json of basew21 EXACTLY except maxIter: fleetSize=120,
rem jspritIter=100, chiThreshold=600 (inert for the baseline, passed to match the record),
rem seed 1337 (default), writeDashboard=true (basew21 has kpi_dashboard.html).
rem DELIBERATELY ABSENT: openDepots / maxJobsPerDistrict. The Baseline stays one depot per
rem provider - the depot rework is for the integrated arms only (user decision, spec D2).
rem
rem FREE REGRESSION CHECK: innovation switches off at 0.9*maxIter, i.e. 135 here vs 225 there,
rem so iterations 0-134 must come out bit-identical to basew21 if the 9 commits since
rem 2026-07-31 left the baseline path alone. A mismatch means iterations and code changed
rem together and the comparison is confounded - check before trusting the new plateau.
set "SC=concept=drt_baseline,date=2025-05-13,fleetSize=120,maxIter=250,jspritIter=100,writeDashboard=true,chiThreshold=600,tag=basew21_it250"
echo [%DATE% %TIME%] CONVBASE CHAIN START - 1 arm, dev, maxIter=250 >> "%LOG%"
call :RUNARM "basew21_it250" "%SC%"
echo [%DATE% %TIME%] CONVBASE CHAIN COMPLETE >> "%LOG%"
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
