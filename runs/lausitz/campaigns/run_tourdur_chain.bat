@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\..\hagrid\simulation"
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE ( echo no java & exit /b 1 )
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\hagrid-1.0-SNAPSHOT.jar"
if not exist "%JAR%" ( echo no jar & exit /b 1 )
set "LOG=hagrid-matsim-output\logs\tourdur_chain.log"
set "PREV=hagrid-matsim-output\logs\weekend_chain.log"
echo [%DATE% %TIME%] TOURDUR CHAIN ARMED - waiting for the fleet chain to finish >> "%LOG%"
:WAIT
findstr /c:"CHAIN COMPLETE" /c:"CHAIN ABORTED" "%PREV%" >nul 2>&1
if errorlevel 1 ( timeout /t 300 /nobreak >nul & goto :WAIT )
findstr /c:"CHAIN ABORTED" "%PREV%" >nul 2>&1
if not errorlevel 1 ( echo [%DATE% %TIME%] previous chain ABORTED - not starting >> "%LOG%" & exit /b 1 )
echo [%DATE% %TIME%] fleet chain done - starting tour-duration probes >> "%LOG%"

call :STEP f150d25 9000
if errorlevel 1 goto :FAILED
call :STEP f150d45 16200
if errorlevel 1 goto :FAILED
echo [%DATE% %TIME%] TOURDUR CHAIN COMPLETE >> "%LOG%"
goto :EOF

:FAILED
echo [%DATE% %TIME%] TOURDUR CHAIN ABORTED >> "%LOG%"
exit /b 1

:STEP
set "TAG=%~1"
set "DUR=%~2"
set "SC=concept=drt_modular,date=2025-05-13,tag=%TAG%,fleetSize=150,idleThreshold=0.15,maxTourDuration=%DUR%,maxIter=150,jspritIter=100,writeDashboard=true"
ver >nul
echo [%DATE% %TIME%] STEP %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.lausitz.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] STEP %TAG% PREPARE FAILED >> "%LOG%" & exit /b 1 )
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter150_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] STEP %TAG% OK ^(exit %RC%, KPI file present^) >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] STEP %TAG% FAILED ^(exit %RC%, no kpis_long.csv^) >> "%LOG%"
exit /b 1
