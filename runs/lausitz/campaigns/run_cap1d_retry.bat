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
set "LOG=hagrid-matsim-output\logs\cap1d_retry.log"
rem RETRY of the arm that died in run_cap1d_chain.bat on 30.08 at 19:10.
rem
rem CAUSE WAS NOT THE CONFIG. MatsimXmlParser fetches
rem http://www.matsim.org/files/dtd/vehicleDefinitions_v2.0.xsd over HTTP while reading
rem lausitz-transitVehicles.xml.gz; the fetch hit "Read timed out" and, unlike the DTD path
rem (which falls back to classpath:dtd/...), the XSD has no local fallback - so validation
rem failed hard and prepare aborted. The very same parse succeeded two minutes later in the
rem f130 arm, which is what makes it transient rather than structural.
rem MITIGATION NOT APPLIED HERE ON PURPOSE: -Dmatsim.preferLocalDtds=true would remove the
rem network dependency, but vmargs_dev.txt is shared by every run of this campaign and is not
rem something to change mid-flight. Retry first; if it fails again, that is the moment to decide.
rem
rem The retry is bit-identical to the failed spec, so it pairs with d1d_dep7_f135_it250
rem (no cap, plateau 9214) exactly as intended.
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,maxConcurrentFreight=25,fleetSize=135,tag=d1d_dep7_f135_c25_it250"
echo [%DATE% %TIME%] CAP1D RETRY START - 1 arm, dev, f135 maxConcurrentFreight=25 >> "%LOG%"
call :RUNARM "d1d_dep7_f135_c25_it250" "%SC%"
echo [%DATE% %TIME%] CAP1D RETRY COMPLETE >> "%LOG%"
endlocal
exit /b 0

:RUNARM
set "TAG=%~1"
set "SC=%~2"
echo [%DATE% %TIME%] ARM %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.lausitz.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] ARM %TAG% PREPARE FAILED AGAIN - see %TAG%_prepare.log >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] ARM %TAG% prepare ok, simulation start >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter250_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] ARM %TAG% OK - exit %RC%, kpis_long.csv present >> "%LOG%" ) else ( echo [%DATE% %TIME%] ARM %TAG% FAILED - exit %RC%, no kpis_long.csv >> "%LOG%" )
exit /b 0
