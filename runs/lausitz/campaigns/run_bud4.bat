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
set "LOG=hagrid-matsim-output\logs\bud4.log"
rem
rem
rem
rem
rem
rem
rem ====================================================================================
rem THETA-RIEGEL GELOEST: idleThreshold 0,15 -> 0,02, sonst identisch zu run_bud3.bat
rem
rem WARUM. run_bud3.bat (3,0-h-Touren + selfref-Budget) hat 34 von 54 Touren disponiert
rem und 3.551 von 6.052 Paketen zugestellt - praktisch dasselbe Scheitern wie bud2 bei
rem 3,5 h. Der Grund ist NICHT die Tourdauer und NICHT der terminale Zweig der Rampe.
rem Gemessen an it.250: alle 20 Verfaelle liegen zwischen 17:29 und 17:53, jeder exakt
rem an der Wand now+Kette=75600, und budget_overrides_expiry war 0.
rem
rem Die Ursache steht in ModularTourDispatcher Zeile 375-377: idleThreshold ist eine
rem harte Konjunktion der while-Bedingung und steht VOR dem Budget-Gate. Faellt der
rem Leerlaufanteil unter 0,15, wird der Schleifenkoerper nie betreten - die Tour wird
rem nie angesehen, die Rampe nie ausgewertet, der terminale Zweig nie erreicht.
rem Gemessen im Sterbefenster (STAY-Spalte, task_time_profiles, Schwelle 19,5 Fz):
rem   17:15 STAY=17,0 (0,131) | 17:30 STAY=17,4 (0,134) | 17:40 STAY=14,9 (0,115)
rem   18:30 STAY=13,7 (0,105) | 18:35 STAY=20,2 (0,155) <- Tasche oeffnet, alle tot
rem
rem Die Fracht draengt sich dort SELBST unter die Schwelle. Gegen den Anker um 17:30:
rem   Anker STAY 31,7 / Fracht 0   / Pax-belegt  98,3
rem   bud3  STAY 17,4 / Fracht 8,5 / Pax-belegt 104,1
rem 8,5 der 14,3 fehlenden Leerfahrzeuge sind die Fracht selbst, 5,8 sind zusaetzlich
rem Pax-belegt (bessere Servicequalitaet zieht Nachfrage). Das System begrenzt sich bei
rem 13 Frachtfahrzeugen am Abend selbst.
rem
rem theta=0,15 und budgetHeadroom=0,15 reservieren DIESELBEN 19,5 Fahrzeuge doppelt,
rem zusammen 30 Prozent der Flotte - und die groebere der beiden Reserven entscheidet.
rem Mit theta=0,02 wird das Budget der alleinige Regler; die Reserve wird weiterhin
rem gehalten, aber vorausschauend und binweise statt als flacher Boden.
rem
rem EIN FAKTOR geaendert gegenueber bud3. Alles andere identisch, damit ein schlechtes
rem Ergebnis zuordenbar bleibt.
rem
rem VERIFIZIEREN, bevor der Lauf zaehlt: die Scenario-Zeile muss
rem   idleThreshold=0.02 maxTourDuration=10800 budgetMode=SELFREF budgetSmoothing=5
rem   budgetHeadroom=0.15 budgetUrgencyLeadS=3600.0
rem zeigen. Ein unbekannter Schluessel wird still ignoriert, nicht abgelehnt.
rem
rem GATE, KEIN ERGEBNIS:
rem  1) tours_dispatched == tours_planned (54) und tours_expired_pending == 0 und
rem     parcels_served == 6052. Darunter ist der Arm wieder unbrauchbar.
rem  2) budget_overrides_expiry > 0 ERWARTET - der terminale Zweig ist jetzt erreichbar.
rem     Ein Wert nahe 20 heisst: das Budget verschiebt, verhindert aber nicht. Das ist
rem     das Ziel ("weniger gierig"), NICHT ein Defekt. Ein Wert nahe 54 hiesse, das
rem     Budget ist dekorativ.
rem  3) Fracht-Zeitverteilung gegen den Anker: MODULAR_FREIGHT_* muss zwischen 17:30 und
rem     21:00 deutlich ueber dem Anker (dort null) liegen, und um 10:00 darunter. Ohne
rem     diese Verschiebung waere eine gute Paketzahl aus einem anderen Grund entstanden.
rem  4) PAX-SEITE PRUEFEN: theta war der Schutz der Passagiere. wait_mean und
rem     drt_rejections gegen Anker (689,5 s / 39) und bud3 (706,5 s / 39) halten. Eine
rem     stark verschlechterte Pax-Seite waere der Preis und muss benannt werden.
rem  5) KONVERGENZ am eigenen Verlauf neu herleiten.
rem ====================================================================================
set "SC=concept=drt_modular,date=2025-05-13,idleThreshold=0.02,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=10800,budgetMode=selfref,budgetSmoothing=5,budgetHeadroom=0.15,budgetUrgencyLeadS=3600,tag=d1d_f130_d30_th02"
echo [%DATE% %TIME%] BUD4 START - dev, f130, 3,0h, selfref k=5 h=0.15 lead=3600, theta=0.02 >> "%LOG%"
call :RUNARM "d1d_f130_d30_th02" "%SC%"
echo [%DATE% %TIME%] BUD4 COMPLETE >> "%LOG%"
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
