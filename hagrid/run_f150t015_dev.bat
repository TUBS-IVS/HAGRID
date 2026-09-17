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
set "LOG=hagrid-matsim-output\logs\f150t015_wrap.log"
set "SC=concept=drt_modular,date=2025-05-13,tag=f150t015,fleetSize=150,idleThreshold=0.15,maxIter=150,jspritIter=100,writeDashboard=true"
echo [%DATE% %TIME%] STEP1 prepare DRT inputs >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.integrated.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\f150t015_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] STEP1 FAILED %ERRORLEVEL% >> "%LOG%" & exit /b 1 )
echo [%DATE% %TIME%] STEP1 ok >> "%LOG%"
echo [%DATE% %TIME%] STEP2 simulation start >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\f150t015_console.log 2>&1
echo [%DATE% %TIME%] STEP2 EXIT %ERRORLEVEL% >> "%LOG%"
endlocal
