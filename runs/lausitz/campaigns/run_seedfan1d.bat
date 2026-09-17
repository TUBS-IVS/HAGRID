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
set "LOG=hagrid-matsim-output\logs\seedfan1d.log"
rem
rem
rem
rem
rem
rem
rem
rem
rem
rem
rem
rem
rem
rem ====================================================================================
rem SEED-FAECHER 1d - Replikation von d1d_f130_d30_th02 (METHODS-LOG 2.64)
rem
rem WARUM. 2.64 haelt ausdruecklich fest: n = 1, kein Seed-Faecher. Alle drei Kernaussagen
rem des Arms haengen daran, dass sie sich bei anderem Seed wiederholen:
rem   (a) Vollzustellung 54/54 Touren, 6.052 Pakete, 22 Nullen in Folge im Plateau
rem   (b) Verlagerung: 66,9 Fracht-Fz-h vor 14:00 (Anker 153,8), 95,7 ab 17:00 (Anker 0,0)
rem   (c) Preis gegen Anker: +206 Fahrten, +11,6 s Wartezeit, +3,5 Ablehnungen
rem
rem (a) und (b) pruefen diese zwei Laeufe. (c) NICHT - dazu braeuchte auch der Anker einen
rem Faecher, siehe die Notiz am Ende.
rem
rem DER BESTEHENDE LAUF IST MITGLIED DES FAECHERS. d1d_f130_d30_th02 hat matsim_seed=1337
rem (aus seiner run_metadata.json). Deshalb nur zwei neue Seeds, nicht drei - das spart
rem 14 h und ist derselbe Faecher.
rem
rem GLEICHER JAR, ZWINGEND. Diese Laeufe benutzen target\hagrid-1.0-SNAPSHOT.jar vom
rem 06.09. 13:55 - exakt den, der th02 erzeugt hat. Geprueft: keine Datei unter src/main/java
rem ist neuer als der JAR, und der JAR traegt CHAIN_BOOTSTRAP_FACTOR=1.15 sowie
rem DEFAULT_BUDGET_URGENCY_LEAD_S=3600.0. Wird vorher neu gebaut, ist es kein Seed-Faecher
rem mehr, sondern ein Codevergleich - dann sind Seed- und Codeeffekt nicht mehr trennbar.
rem NICHT "mvn package" laufen lassen, solange dieser Faecher laeuft.
rem
rem EIN Faktor variiert: seed. Alles andere ist zeichengleich zu th02.
rem
rem AUSWERTUNG, wenn beide durch sind:
rem  1) tours_dispatched == tours_planned == 54 und parcels_served == 6052 in BEIDEN.
rem     Faellt einer darunter, ist (a) NICHT repliziert und die Vollzustellung war
rem     seedabhaengig - das waere ein Befund und kein Ausreisser.
rem  2) Verfaelle je Iteration im Plateau 226-249. In th02 waren es 1,1,0,0,...(22 Nullen).
rem     Erwartet wird wieder ein Auslaufen gegen null, nicht zwingend dieselbe Zahl.
rem  3) Fracht-Fz-h vor 14:00 / ab 17:00 aus task_time_profiles it.250 gegen 66,9 / 95,7.
rem  4) Pax im PLATEAU-MITTEL 226-249, nicht aus it.250 allein - th02: 9.179 Fahrten
rem     (Spanne 131), 710,0 s, 36,1 Ablehnungen. Die Streuung UEBER die drei Seeds ist die
rem     eigentliche Ausbeute: sie ersetzt den geerbten Richtwert von 48 Fahrten durch eine
rem     an diesem Arm gemessene Zahl.
rem  5) chain_ratio_p90 gegen 1,1507. Der Wert sass bei th02 knapp UEBER dem Bootstrap 1,15;
rem     ob das ein Seed-Zufall war, zeigt sich hier.
rem
rem WAS DIESER FAECHER NICHT LEISTET. Der Anker d1d_dep7_f130_it250 lief am 25.08. mit
rem einem AELTEREN JAR - seither haben 2200a89, 2ff5dbb und 9a37dd1 Java angefasst. Die
rem Pax-Differenz in 2.64 vergleicht damit zwei Codestaende, nicht nur zwei Konfigurationen.
rem Die betroffenen Commits sind 1c- bzw. Dashboard-seitig und sollten 1d nicht beruehren,
rem aber gepruegft ist das nicht. Sauber wird (c) erst mit einem Ankerlauf auf DIESEM JAR.
rem ====================================================================================
set "SCBASE=concept=drt_modular,date=2025-05-13,idleThreshold=0.02,jspritIter=100,writeDashboard=true,openDepots=all,maxIter=250,fleetSize=130,maxTourDuration=10800,budgetMode=selfref,budgetSmoothing=5,budgetHeadroom=0.15,budgetUrgencyLeadS=3600"
echo [%DATE% %TIME%] FAN START - 1d seed fan, seeds 2337 and 3337 (1337 = existing th02) >> "%LOG%"
call :RUNARM "d1d_f130_d30_s2337" "%SCBASE%,seed=2337,tag=d1d_f130_d30_s2337"
call :RUNARM "d1d_f130_d30_s3337" "%SCBASE%,seed=3337,tag=d1d_f130_d30_s3337"
echo [%DATE% %TIME%] FAN COMPLETE >> "%LOG%"
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
