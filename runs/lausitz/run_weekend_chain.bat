@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\hagrid\simulation"
set "JAVA_EXE="
if defined HAGRID_JAVA_EXE if exist "%HAGRID_JAVA_EXE%" set "JAVA_EXE=%HAGRID_JAVA_EXE%"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%J in (java.exe) do set "JAVA_EXE=%%~$PATH:J"
if not defined JAVA_EXE ( echo no java & exit /b 1 )
set "PY="
for %%P in (python.exe) do set "PY=%%~$PATH:P"
if not defined PY ( echo no python & exit /b 1 )
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\hagrid-1.0-SNAPSHOT.jar"
if not exist "%JAR%" ( echo no jar & exit /b 1 )
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
set "LOG=hagrid-matsim-output\logs\weekend_chain.log"
echo [%DATE% %TIME%] CHAIN START >> "%LOG%"

call :STEP drt_baseline b120rg 120 ""
if errorlevel 1 goto :FAILED
call :STEP drt_modular f150t010 150 0.10
if errorlevel 1 goto :FAILED

echo [%DATE% %TIME%] deciding theta >> "%LOG%"
"%PY%" -u "%~dp0decide_theta.py" b120rg >> hagrid-matsim-output\logs\decide_theta.log 2>&1
set "TH="
for /f "usebackq delims=" %%i in ("%~dp0chosen_theta.txt") do set "TH=%%i"
if not defined TH ( echo [%DATE% %TIME%] NO THETA CHOSEN - stopping >> "%LOG%" & goto :FAILED )
set "TS=!TH:.=!"
echo [%DATE% %TIME%] theta=!TH! >> "%LOG%"

call :STEP drt_modular f140t!TS! 140 !TH!
if errorlevel 1 goto :FAILED
call :STEP drt_modular f130t!TS! 130 !TH!
if errorlevel 1 goto :FAILED
echo [%DATE% %TIME%] CHAIN COMPLETE >> "%LOG%"
goto :EOF

:FAILED
echo [%DATE% %TIME%] CHAIN ABORTED >> "%LOG%"
exit /b 1

:STEP
set "CPT=%~1"
set "TAG=%~2"
set "FS=%~3"
set "TH4=%~4"
set "SC=concept=%CPT%,date=2025-05-13,tag=%TAG%,fleetSize=%FS%,maxIter=150,jspritIter=100,writeDashboard=true"
if not "%TH4%"=="" set "SC=%SC%,idleThreshold=%TH4%"
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
