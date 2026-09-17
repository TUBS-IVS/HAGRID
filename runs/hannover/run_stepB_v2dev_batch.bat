@echo off
setlocal
rem Step B batch for the DEV-PC v2 arm: 25 runs 160-400 (step 10) ascending, then 70v2.
rem 70v2 is LAST on purpose - it is the heaviest run here (~2800+ vehicles; the sim-PC
rem crash dump showed ZHeap used 87.8 GB) and this machine has 63.5 GB. Everything of
rem known-good memory profile is banked before the one run whose fit is unproven.
rem The final line MUST contain "batch done" - heartbeat.ps1 treats that as the
rem batch-completion sentinel (Test-BatchComplete).
set "JAVA_EXE=C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
set "JAR=target\hagrid-1.0-SNAPSHOT.jar"
set "VMARGS=-Xms8g -Xmx48g -Xss512k -XX:MaxDirectMemorySize=4g -XX:ActiveProcessorCount=12 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:ErrorFile=hagrid-output\logs\jvm\hs_err_%%p.log -Dhagrid.log.dir=hagrid-output/logs -Dhagrid.pipeline.root=. --add-opens=java.base/java.lang=ALL-UNNAMED"
set "ARGS_TAIL=,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true"
cd /d "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\hagrid"
if not exist "hagrid-output\logs\jvm" mkdir "hagrid-output\logs\jvm"
echo ===== STEPB_V2DEV BATCH START %date% %time% =====
echo ===== RUN 1/26  tag=160v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=160v2%ARGS_TAIL%
echo SCEN1_EXIT=%ERRORLEVEL%
echo ===== RUN 2/26  tag=170v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=170v2%ARGS_TAIL%
echo SCEN2_EXIT=%ERRORLEVEL%
echo ===== RUN 3/26  tag=180v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=180v2%ARGS_TAIL%
echo SCEN3_EXIT=%ERRORLEVEL%
echo ===== RUN 4/26  tag=190v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=190v2%ARGS_TAIL%
echo SCEN4_EXIT=%ERRORLEVEL%
echo ===== RUN 5/26  tag=200v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=200v2%ARGS_TAIL%
echo SCEN5_EXIT=%ERRORLEVEL%
echo ===== RUN 6/26  tag=210v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=210v2%ARGS_TAIL%
echo SCEN6_EXIT=%ERRORLEVEL%
echo ===== RUN 7/26  tag=220v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=220v2%ARGS_TAIL%
echo SCEN7_EXIT=%ERRORLEVEL%
echo ===== RUN 8/26  tag=230v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=230v2%ARGS_TAIL%
echo SCEN8_EXIT=%ERRORLEVEL%
echo ===== RUN 9/26  tag=240v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=240v2%ARGS_TAIL%
echo SCEN9_EXIT=%ERRORLEVEL%
echo ===== RUN 10/26  tag=250v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=250v2%ARGS_TAIL%
echo SCEN10_EXIT=%ERRORLEVEL%
echo ===== RUN 11/26  tag=260v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=260v2%ARGS_TAIL%
echo SCEN11_EXIT=%ERRORLEVEL%
echo ===== RUN 12/26  tag=270v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=270v2%ARGS_TAIL%
echo SCEN12_EXIT=%ERRORLEVEL%
echo ===== RUN 13/26  tag=280v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=280v2%ARGS_TAIL%
echo SCEN13_EXIT=%ERRORLEVEL%
echo ===== RUN 14/26  tag=290v2  START %date% %time% =====
"%JAVA_EXE%" %VMARGS% -jar "%JAR%" concept=basecase,date=2025-05-13,tag=290v2%ARGS_TAIL%
echo SCEN14_EXIT=%ERRORLEVEL%
echo ===== SKIPPED 300v2 - batch aborted after 290v2 =====
rem SKIPPED 300v2 - Step B not run, aborted after 290v2                                 
rem SCEN15 skipped           
echo ===== SKIPPED 310v2 - batch aborted after 290v2 =====
rem SKIPPED 310v2 - Step B not run, aborted after 290v2                                 
rem SCEN16 skipped           
echo ===== SKIPPED 320v2 - batch aborted after 290v2 =====
rem SKIPPED 320v2 - Step B not run, aborted after 290v2                                 
rem SCEN17 skipped           
echo ===== SKIPPED 330v2 - batch aborted after 290v2 =====
rem SKIPPED 330v2 - Step B not run, aborted after 290v2                                 
rem SCEN18 skipped           
echo ===== SKIPPED 340v2 - batch aborted after 290v2 =====
rem SKIPPED 340v2 - Step B not run, aborted after 290v2                                 
rem SCEN19 skipped           
echo ===== SKIPPED 350v2 - batch aborted after 290v2 =====
rem SKIPPED 350v2 - Step B not run, aborted after 290v2                                 
rem SCEN20 skipped           
echo ===== SKIPPED 360v2 - batch aborted after 290v2 =====
rem SKIPPED 360v2 - Step B not run, aborted after 290v2                                 
rem SCEN21 skipped           
echo ===== SKIPPED 370v2 - batch aborted after 290v2 =====
rem SKIPPED 370v2 - Step B not run, aborted after 290v2                                 
rem SCEN22 skipped           
echo ===== SKIPPED 380v2 - batch aborted after 290v2 =====
rem SKIPPED 380v2 - Step B not run, aborted after 290v2                                 
rem SCEN23 skipped           
echo ===== SKIPPED 390v2 - batch aborted after 290v2 =====
rem SKIPPED 390v2 - Step B not run, aborted after 290v2                                 
rem SCEN24 skipped           
echo ===== SKIPPED 400v2 - batch aborted after 290v2 =====
rem SKIPPED 400v2 - Step B not run, aborted after 290v2                                 
rem SCEN25 skipped           
echo ===== SKIPPED 70v2 - batch aborted after 290v2 =====
rem SKIPPED 70v2 - Step B not run, aborted after 290v2                                 
rem SCEN26 skipped           
echo ===== STEPB_V2DEV v2dev batch done %date% %time% =====
