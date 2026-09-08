@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE ( echo no java & exit /b 1 )
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar"
if not exist "%JAR%" ( echo no jar & exit /b 1 )
set "LOG=hagrid-matsim-output\logs\dur70_chain.log"
set "PREV=hagrid-matsim-output\logs\dur40_chain.log"
echo [%DATE% %TIME%] DUR70 ARMED - waiting for the 4.0h run to finish >> "%LOG%"
:WAIT
findstr /c:"DUR40 COMPLETE" /c:"DUR40 ABORTED" "%PREV%" >nul 2>&1
if errorlevel 1 ( timeout /t 300 /nobreak >nul & goto :WAIT )
findstr /c:"DUR40 ABORTED" "%PREV%" >nul 2>&1
if not errorlevel 1 ( echo [%DATE% %TIME%] 4.0h run ABORTED - not starting the 7h arm >> "%LOG%" & exit /b 1 )
echo [%DATE% %TIME%] 4.0h run done - starting the 7.0h cap-parity control arm >> "%LOG%"
set "TAG=f150d70"
set "SC=concept=drt_modular,date=2025-05-13,tag=%TAG%,fleetSize=150,idleThreshold=0.15,maxTourDuration=25200,maxIter=150,jspritIter=100,writeDashboard=true"
ver >nul
echo [%DATE% %TIME%] STEP %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.integrated.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] STEP %TAG% PREPARE FAILED >> "%LOG%" & echo [%DATE% %TIME%] DUR70 ABORTED >> "%LOG%" & exit /b 1 )
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter150_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] STEP %TAG% OK ^(exit %RC%, KPI file present^) >> "%LOG%" & echo [%DATE% %TIME%] DUR70 COMPLETE >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] STEP %TAG% FAILED ^(exit %RC%, no kpis_long.csv^) >> "%LOG%"
echo [%DATE% %TIME%] DUR70 ABORTED >> "%LOG%"
exit /b 1
