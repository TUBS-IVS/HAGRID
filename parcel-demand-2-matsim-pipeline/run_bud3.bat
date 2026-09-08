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
set "LOG=hagrid-matsim-output\logs\bud3.log"
rem ====================================================================================
rem 3,0-h-TOUREN + SELBSTREFERENZIELLES BUDGET, Zustellschluss unveraendert 21:00
rem docs/superpowers/plans/2026-09-05-honest-envelope-and-urgency-ramp.md
rem
rem WARUM DIESE KOMBINATION. Der Vorgaenger d1d_f130_bud2 hat 30 von 46 Touren disponiert
rem und nur 3.639 von 6.052 Paketen zugestellt. Die Diagnose steht in METHODS-LOG 2.58:
rem der Regler zielt RICHTIG - er verschiebt Fracht vom Mittag (33,0 -> 14,0 Fahrzeuge um
rem 10 Uhr) in das Abendtal (10,0 / 10,0 / 8,0 Fahrzeuge um 18/19/20 Uhr, wo der Anker
rem exakt null hat) - aber er kommt nicht weit genug. Alle 16 verfallenen Touren sterben
rem zwischen 17:00 und 17:41, an der letzten Dispatchgelegenheit.
rem
rem GERECHNET, nicht geraten (Task-Time-Profile des Ankers, it.250, Flotte 130, h=0,15):
rem die Kapazitaet hat ZWEI Taschen mit einem Loch dazwischen - Vormittag 08-13 Uhr mit
rem 16-28 freien Fahrzeugen, Loch 13:30-17:15 (0 bis -11, die Tagesspitze bei 116,2
rem Pax-belegt um 16 Uhr), Abendtasche ab 17:15. Eine Tour muss GANZ in eine Tasche
rem passen. Bei Zustellschluss 21:00 ist die Abendtasche 3,8 h breit, und mit
rem   Kette = (Tourdauer + 2 x 420 s Retooling) x Routing-Verhaeltnis
rem (gemessen: p90 1,094 / max 1,137) traegt sie eine Tourdauer von hoechstens 3,19 h,
rem konservativ 3,06 h. Die heutigen 3,5 h passen NICHT - deshalb sterben sie um 17 Uhr.
rem 3,0 h passen, mit duenner aber positiver Marge, und kosten ~54 statt 46 Touren, also
rem +17 Prozent Depot-Trips (2,0 h waeren ~80 Touren und +75 Prozent gewesen).
rem
rem ZWEI FIXES seit bud2, beide im Hauptpfad, beide mutationsgeprueft:
rem  A) Der terminale Zweig der Rampe testete slack <= 0 und war damit UNERREICHBAR: die
rem     gelernte Kettendauer ist eine Fliesskommazahl, die Frist liegt zwischen zwei Ticks.
rem     bud2 meldete budget_overrides_expiry = 0 und verlor trotzdem 16 Touren. Jetzt
rem     slack <= observedSimstepS, also "der naechste Tick faende sie verfallen".
rem  B) CHAIN_BOOTSTRAP_FACTOR 1,25 -> 1,15, gemessen aus bud2 (p90 1,094 / max 1,137)
rem     statt geraten. Bindet ohnehin nur vor der ersten Beobachtung einer Tour.
rem
rem GEGEN DEN ANKER d1d_dep7_f130_it250 (Plateau 8973, 46 Touren, 6.052 Pakete) sind ZWEI
rem Faktoren geaendert - Tourdauer UND Budget. Das ist bewusst: einfaktoriell waere hier
rem sinnlos, weil jeder Faktor allein nachweislich scheitert (3,5 h + Budget = bud2, 30
rem Touren; 3,0 h ohne Budget waere der dur20-Fall, der alles um 07:16 rauswirft). Die
rem Frage ist, ob die KOMBINATION traegt. Der Vergleichsarm fuer die Tourdauer allein ist
rem d1d_dep7_f130_dur20_it250 (81 Touren, alle Pakete, -238 Fahrten).
rem
rem VERIFIZIEREN IN DER KONSOLEN-LOG, bevor der Lauf zaehlt: die Scenario-Zeile muss
rem   maxTourDuration=10800 ... budgetMode=SELFREF budgetSmoothing=5 budgetHeadroom=0.15
rem   budgetUrgencyLeadS=3600.0
rem zeigen. Ein unbekannter Schluessel wird still ignoriert, nicht abgelehnt.
rem
rem GATE, KEIN ERGEBNIS. Vor der ersten zitierten Fahrtenzahl:
rem  1) tours_dispatched == tours_planned und tours_expired_pending == 0 und
rem     parcels_served == 6052. tours_planned ist NICHT mehr 46 - bei 3,0 h teilt jsprit
rem     die gleiche Arbeit in ~54 Touren. Die Zahl gegen tours_planned pruefen, nicht
rem     gegen 46.
rem  2) budget_urgency_admits > 0. Bei 0 hat die Rampe nie gebunden.
rem  3) budget_overrides_expiry: jetzt erreichbar. Ein MODERATER Wert ist erwartet und
rem     gesund; ein sehr hoher heisst, die Rampe kommt weiterhin zu spaet.
rem  4) chain_ratio_p90 gegen den neuen Bootstrap 1,15 halten.
rem  5) Fracht im Abendfenster: task_time_profiles_drt.txt, MODULAR_FREIGHT_* zwischen
rem     17:30 und 21:00. Ist die dort null, hat die Verlagerung nicht stattgefunden und
rem     eine gute Fahrtenzahl waere aus einem anderen Grund entstanden.
rem  6) KONVERGENZ am eigenen Verlauf neu herleiten (METHODS-LOG 2.47 traegt nicht).
rem  7) delta_parcels je Provider (C7-Verzerrung).
rem  8) Depot-Trips: tours_planned x 2 Retoolings gegen die 46 x 2 des Ankers.
rem ====================================================================================
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.15,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=10800,budgetMode=selfref,budgetSmoothing=5,budgetHeadroom=0.15,budgetUrgencyLeadS=3600,tag=d1d_f130_d30_bud"
echo [%DATE% %TIME%] BUD3 START - dev, f130, 3,0h-Touren + selfref k=5 h=0.15 lead=3600 >> "%LOG%"
call :RUNARM "d1d_f130_d30_bud" "%SC%"
echo [%DATE% %TIME%] BUD3 COMPLETE >> "%LOG%"
endlocal
exit /b 0

:RUNARM
set "TAG=%~1"
set "SC=%~2"
echo [%DATE% %TIME%] ARM %TAG% START :: %SC% >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -cp "%JAR%" hagrid.integrated.drt.PrepareLausitzDrtInputs "%SC%" >> hagrid-matsim-output\logs\%TAG%_prepare.log 2>&1
if errorlevel 1 ( echo [%DATE% %TIME%] ARM %TAG% PREPARE FAILED - see %TAG%_prepare.log >> "%LOG%" & exit /b 0 )
echo [%DATE% %TIME%] ARM %TAG% prepare ok, simulation start >> "%LOG%"
"%JAVA_EXE%" @vmargs_dev.txt -Dhagrid.pipeline.root=. -jar "%JAR%" %SC% >> hagrid-matsim-output\logs\%TAG%_console.log 2>&1
set "RC=%ERRORLEVEL%"
set "OK="
for /d %%D in ("hagrid-matsim-output\*_%TAG%_iter250_jsprit100") do if exist "%%D\analysis\kpis_long.csv" set "OK=1"
if defined OK ( echo [%DATE% %TIME%] ARM %TAG% OK - exit %RC%, kpis_long.csv present >> "%LOG%" ) else ( echo [%DATE% %TIME%] ARM %TAG% FAILED - exit %RC%, no kpis_long.csv >> "%LOG%" )
exit /b 0
