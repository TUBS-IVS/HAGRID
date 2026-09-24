@echo off
setlocal enabledelayedexpansion
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
set "LOG=hagrid-matsim-output\logs\base_f140_dev.log"
set "CHAIN=hagrid-matsim-output\logs\f120_s3337.log"
set "WATCH=hagrid-matsim-output\logs\d1d_f120_d30_s3337_console.log"
set "PREVKPI=hagrid-matsim-output\DRT_MODULAR_13052025_d1d_f120_d30_s3337_iter250_jsprit100\analysis\kpis_long.csv"
rem ====================================================================================
rem BASELINE-FLOTTENPUNKT f140 AUF DEM DEV - wartet auf das Ende des laufenden 1d-f120-Laufs
rem
rem WAS DIESER LAUF IST. Der Baseline-Flottensweep in PAPER-RUNS.md hat Punkte bei f100, f110,
rem f120 und f130; f140 fehlt. Diese Zeile schliesst die Luecke. Nutzerauftrag 2026-09-12.
rem
rem KONFIG = basew21_it250 MIT EINEM GEAENDERTEN FAKTOR. Referenz ist der Dev-Lauf
rem   concept=drt_baseline,date=2025-05-13,fleetSize=120,maxIter=250,jspritIter=100,
rem   writeDashboard=true,chiThreshold=600,tag=basew21_it250
rem (26.08.-27.08., 9.143 Fahrten, deckungsgleich mit b120rgs vom Sim). Geaendert: fleetSize
rem 120 -> 140. Sonst NICHTS, insbesondere KEIN seed-Schluessel - der Default bleibt derselbe
rem wie in der ganzen b*rgs-Familie.
rem
rem KEIN openDepots. Die LMD-Baseline bleibt bei einem Depot je Provider; die Depotoeffnung
rem gilt ausschliesslich fuer 1c/1d. Ein openDepots= in dieser Zeile waere ein Fehler.
rem
rem chiThreshold=600 ist in der Baseline INERT (keine Pakete in DRT-Fahrzeugen). Steht nur
rem drin, weil die Referenzzeile es traegt - nicht als Einstellung zitieren.
rem
rem JAR-VINTAGE, OFFEN BENANNT. Dieser Lauf nutzt den JAR vom 06.09. 13:55 (8a3e8bb), die
rem b*rgs-Familie lief auf 101e093 (28.08.). Der Diff 101e093..8a3e8bb fasst am Mobsim-Pfad
rem der Baseline nichts an: alle Aenderungen liegen in hagrid.lausitz.modular (nur
rem DRT_MODULAR), und die neuen Schluessel in HAGRIDSimulationConfig/SimulationRunnerUtils
rem sind additiv und defaulten auf das Vorverhalten. Der Emissionskanal hat sich mit 27d7a2e
rem dagegen ECHT geaendert - CO2e aus diesem Lauf ist nur gegen KPI-Staende NACH diesem Fix
rem vergleichbar. NICHT "mvn package" laufen lassen, der JAR bleibt wie er ist.
rem
rem FREIER INVARIANTEN-TEST. Die Frachtseite der Baseline haengt an jsprits eigenem festen
rem Seed, nicht an der Flotte: alle fuenf b120rgs-Laeufe haben 41 Touren und 2.701,54 km.
rem Zeigt dieser Lauf dieselben Werte, ist der jsprit-/Frachtpfad ueber den JAR-Wechsel
rem hinweg nachweislich unveraendert - das kostet nichts und pruefen wir am Ende.
rem
rem VERIFIZIEREN vor der ersten zitierten Zahl: LETZTE Scenario-Zeile im Console-Log lesen
rem (die .bat haengt an), sie muss fleetSize=140 und concept DRT_BASELINE zeigen.
rem
rem LAUFZEIT. basew21_it250 brauchte 21,3 h, davon ~5,9 h jsprit VOR Iteration 0. Bei f140
rem eher 23-25 h.
rem
rem WARTELOGIK. Primaer der Marker "F120 COMPLETE". Zweiter Ausloeser: das Console-Log des
rem f120-Laufs waechst 12 Abfragen (1 h) nicht mehr - dann ist der Lauf tot, die Maschine
rem frei, und Warten bringt nichts. Ein MATSim-Log waechst bei 7,6 min/it staendig.
rem Obergrenze 576 Abfragen (48 h), damit der Warter nicht ewig pollt.
rem ====================================================================================
echo [%DATE% %TIME%] WAIT - warte auf F120 COMPLETE in %CHAIN% >> "%LOG%"
set /a TRIES=0
set /a STALE=0
set "LASTSIZE="
:WAIT
if not exist "%CHAIN%" goto SLEEP
findstr /C:"F120 COMPLETE" "%CHAIN%" >nul 2>&1
if not errorlevel 1 (
  echo [%DATE% %TIME%] Marker gefunden >> "%LOG%"
  goto GO
)
set "SIZE="
if exist "%WATCH%" for %%A in ("%WATCH%") do set "SIZE=%%~zA"
if not defined SIZE goto SLEEP
if "!SIZE!"=="!LASTSIZE!" ( set /a STALE+=1 ) else ( set /a STALE=0 )
set "LASTSIZE=!SIZE!"
if !STALE! GEQ 12 (
  echo [%DATE% %TIME%] LIVENESS - f120-Log seit 1 h unveraendert ^(!SIZE! B^), kein Marker. Lauf gilt als tot, starte trotzdem. >> "%LOG%"
  goto GO
)
:SLEEP
set /a TRIES+=1
if !TRIES! GTR 576 ( echo [%DATE% %TIME%] ABBRUCH - 48 h ohne F120 COMPLETE >> "%LOG%" & exit /b 1 )
ping -n 301 127.0.0.1 >nul
goto WAIT
:GO
if exist "%PREVKPI%" ( echo [%DATE% %TIME%] f120 hat kpis_long.csv geschrieben >> "%LOG%" ) else ( echo [%DATE% %TIME%] WARNUNG - f120 ohne kpis_long.csv, der 1d-Flottenpunkt fehlt >> "%LOG%" )
echo [%DATE% %TIME%] f120 fertig, starte Baseline f140 >> "%LOG%"
set "SC=concept=drt_baseline,date=2025-05-13,fleetSize=140,maxIter=250,jspritIter=100,writeDashboard=true,chiThreshold=600,tag=b140rgs_dev"
call :RUNARM "b140rgs_dev" "%SC%"
echo [%DATE% %TIME%] BASE F140 COMPLETE >> "%LOG%"
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
