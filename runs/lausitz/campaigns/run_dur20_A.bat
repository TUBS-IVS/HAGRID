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
set "LOG=hagrid-matsim-output\logs\dur20_A.log"
rem ARM A of the short-tour / evening-window ladder (2026-09-01).
rem
rem LADDER - each step changes exactly ONE factor:
rem   d1d_dep7_f130_it250        3.5h cap, no windows   -> plateau 8973   (anchor, exists)
rem   A: d1d_dep7_f130_dur20     2.0h cap, no windows   -> THIS RUN
rem   B: d1d_dep7_f130_dur20_win 2.0h cap, + windows    -> queued after A
rem A therefore isolates the COST of shorter tours (more tours, more retooling, more
rem deadhead) with placement held constant. B then isolates the placement GAIN.
rem
rem WHY 2.0h AND NOT 3.0h: the expiry rule is now + 2*420s + plannedDuration > 21:00.
rem At 3.5h the last admissible dispatch is 17:16, at 3.0h it is 17:46 - both inside the
rem afternoon maximum (baseline busy 101.6 / 107.7 of 120 at 17:45 / 18:00). At 2.0h it
rem moves to 18:46, which is the first time the evening hole is actually reachable
rem (baseline free 33.9 at 18:45, 45.6 at 20:15, 63.3 at 20:45).
rem
rem WHAT TO CHECK FIRST: tours_planned and the tour-duration distribution from the
rem PREPARE step - B's dispatch windows are sized from A's MEASURED tour count, not from
rem the ~86-tour estimate. Then tours_dispatched == tours_planned and
rem tours_expired_pending == 0. Anything else invalidates the arm.
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=7200,tag=d1d_dep7_f130_dur20_it250"
echo [%DATE% %TIME%] DUR20 ARM A START - dev, f130, maxTourDuration=7200 (2.0h) >> "%LOG%"
call :RUNARM "d1d_dep7_f130_dur20_it250" "%SC%"
echo [%DATE% %TIME%] DUR20 ARM A COMPLETE >> "%LOG%"
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
