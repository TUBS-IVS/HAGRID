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
set "LOG=hagrid-matsim-output\logs\conv1d_chain.log"
rem CONVERGENCE TEST. The 125/130 arms of the fleet sweep were still drifting at iteration 149
rem (-15.6 and -10.1 trips/iter over 136-149, monotone with fleet size), so their values are
rem upper bounds, not equilibria, and the ~125 calibration derived from them does not hold.
rem This re-runs f130 with maxIter=250 (innovation off at 225, so 25 settled iterations instead
rem of 15) to find where the small-fleet end actually settles. Everything else is byte-identical
rem to d1d_dep7_f130 so the two are directly comparable.
rem NOTE: the output dir is *_iter250_jsprit100, hence the changed glob in :RUNARM below.
set "BASE=date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all"
echo [%DATE% %TIME%] CONV1D CHAIN START - 1 arm, dev, maxIter=250 >> "%LOG%"
call :RUNARM "d1d_dep7_f130_it250" "concept=drt_modular,%BASE%,maxIter=250,fleetSize=130,tag=d1d_dep7_f130_it250"
echo [%DATE% %TIME%] CONV1D CHAIN COMPLETE >> "%LOG%"
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
