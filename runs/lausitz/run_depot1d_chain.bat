@echo off
setlocal
cd /d "%~dp0..\..\hagrid\simulation"
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
set "LOG=hagrid-matsim-output\logs\depot1d_chain.log"
rem Standing 1d config: theta=0.15 winner (campaign 2026-08-18), fleet 150, 150 MATSim iters.
rem Depot logic per spec 2026-08-17 section 7 stage 1. Baseline is NOT re-run (D2).
set "BASE=date=2025-05-13,fleetSize=150,idleThreshold=0.15,maxIter=150,jspritIter=100,writeDashboard=true"
echo [%DATE% %TIME%] DEPOT1D CHAIN START - 5 arms, dev >> "%LOG%"
rem Ordered by information value: dep7 is the cleanest one-variable contrast against f150t015
rem (same 7 physical yards, only the assignment rule changes), then the extreme, then the middle,
rem then the two partition controls. A failing arm is logged and the chain continues.
call :RUNARM "d1d_dep7" "concept=drt_modular,%BASE%,tag=d1d_dep7,openDepots=all"
call :RUNARM "d1d_dep1" "concept=drt_modular,%BASE%,tag=d1d_dep1,openDepots=hoy_sued"
call :RUNARM "d1d_dep3" "concept=drt_modular,%BASE%,tag=d1d_dep3,openDepots=wittichenau,hoy_sued,doergenhausen"
call :RUNARM "d1d_nopart" "concept=drt_modular,%BASE%,tag=d1d_nopart,openDepots=hoy_sued,maxJobsPerDistrict=99999"
call :RUNARM "d1d_nopart_s2" "concept=drt_modular,%BASE%,tag=d1d_nopart_s2,openDepots=hoy_sued,maxJobsPerDistrict=99999,seed=2337"
echo [%DATE% %TIME%] DEPOT1D CHAIN COMPLETE >> "%LOG%"
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
