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
set "LOG=hagrid-matsim-output\logs\f120_s3337.log"
set "CHAIN=hagrid-matsim-output\logs\seedfan1d.log"
rem ====================================================================================
rem 1d FLOTTENPUNKT f120, SEED 3337 - wartet auf das Ende des laufenden Seed-Faechers
rem
rem WARUM SEED 3337 UND NICHT 1337. Auf IVS100 ist laut PAPER-RUNS.md ein Flottensweep bei
rem Seed 1337 vorgesehen (f110/f120/f140/f150). Am 11.09. 12:0x war dort nachweislich NICHTS
rem aktiv - kein java-Prozess, kein Log, juengstes Ausgabeverzeichnis ein LMD-Smoketest vom
rem 10.09. Der Punkt f120/s1337 ist damit weder belegt noch sicher frei. Nutzerentscheidung
rem 2026-09-11: Seed 3337, weil der garantiert nicht kollidiert.
rem
rem WAS DIESER LAUF IST UND WAS NICHT. Er ist EIN Flottenpunkt, kein Faecherarm: bei f120
rem liegt danach n=1 vor. Sein Partner bei f130 ist d1d_f130_d30_s3337 (derselbe Seed), also
rem ist die Differenz f130->f120 gepaart lesbar. Gegen die f130-Werte der anderen beiden
rem Seeds ist er NICHT gepaart.
rem
rem GLEICHER JAR, ZWINGEND. Nutzt target\parcel-demand-...-SNAPSHOT.jar vom 06.09. 13:55 -
rem derselbe, der th02, s2337 und s3337 erzeugt hat. Wird vorher neu gebaut, mischt sich ein
rem Codeunterschied in die Flottendifferenz. NICHT "mvn package" laufen lassen.
rem
rem EIN Faktor gegenueber d1d_f130_d30_s3337 geaendert: fleetSize 130 -> 120.
rem
rem VERIFIZIEREN vor der ersten zitierten Zahl: die Scenario-Zeile muss
rem   fleetSize=120 seed=3337 idleThreshold=0.02 maxTourDuration=10800 budgetMode=SELFREF
rem   budgetSmoothing=5 budgetHeadroom=0.15 budgetUrgencyLeadS=3600.0
rem zeigen. Die LETZTE Scenario-Zeile im Log lesen, nicht die erste - die .bat haengt an.
rem
rem GATE: tours_dispatched == tours_planned und parcels_served == 6052. Bei 120 statt 130
rem Fahrzeugen ist die Vollzustellung NICHT selbstverstaendlich - faellt sie, ist das das
rem Ergebnis dieses Laufs und kein Defekt.
rem ====================================================================================
echo [%DATE% %TIME%] WAIT - warte auf FAN COMPLETE in %CHAIN% >> "%LOG%"
set /a TRIES=0
:WAIT
if not exist "%CHAIN%" goto SLEEP
findstr /C:"FAN COMPLETE" "%CHAIN%" >nul 2>&1
if not errorlevel 1 goto GO
:SLEEP
set /a TRIES+=1
if %TRIES% GTR 576 ( echo [%DATE% %TIME%] ABBRUCH - 48 h ohne FAN COMPLETE >> "%LOG%" & exit /b 1 )
ping -n 301 127.0.0.1 >nul
goto WAIT
:GO
echo [%DATE% %TIME%] Faecher fertig, starte f120 >> "%LOG%"
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.02,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=120,maxTourDuration=10800,budgetMode=selfref,budgetSmoothing=5,budgetHeadroom=0.15,budgetUrgencyLeadS=3600,seed=3337,tag=d1d_f120_d30_s3337"
call :RUNARM "d1d_f120_d30_s3337" "%SC%"
echo [%DATE% %TIME%] F120 COMPLETE >> "%LOG%"
endlocal
exit /b 0

:RUNARM
set "TAG=%~1"
set "SC=%~2"
echo [%DATE% %TIME%] ARM %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.lausitz.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] ARM %TAG% PREPARE FAILED >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] ARM %TAG% prepare ok, simulation start >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter250_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] ARM %TAG% OK - exit %RC%, kpis_long.csv present >> "%LOG%" ) else ( echo [%DATE% %TIME%] ARM %TAG% FAILED - exit %RC%, no kpis_long.csv >> "%LOG%" )
exit /b 0
