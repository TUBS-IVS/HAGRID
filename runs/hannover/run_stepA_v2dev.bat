@echo off
setlocal
rem Step A for the DEV-PC v2 arm (160-400 step 10, then 70v2) - 26 tags, one invocation.
rem JDK 21 is PINNED: "java" on this machine PATH is JDK 25, which HAGRID/MATSim has
rem never been run on. JAVA_HOME points at 21.0.10; the sim-PC uses 21.0.8.
set "JAVA_EXE=C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
set "JAR=target\hagrid-1.0-SNAPSHOT.jar"
rem Heap sized for 63.5 GB physical, NOT the sim-PC 124g:
rem  - no AlwaysPreTouch: it commits the whole heap at startup, which is why the
rem    sim-PC showed ~106 GB RSS regardless of actual need.
rem  - G1 instead of ZGC: result-neutral (GC choice does not affect the simulation)
rem    and the only JVM crash ever seen here was inside a ZGC frame (hs_err_19108, 70v2).
rem  - ExitOnOutOfMemoryError WITHOUT HeapDumpOnOutOfMemoryError: an unattended run
rem    should die fast and visibly, not spend minutes writing a 48 GB dump.
set "VMARGS=-Xms8g -Xmx48g -Xss512k -XX:MaxDirectMemorySize=4g -XX:ActiveProcessorCount=12 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dhagrid.log.dir=hagrid-output/logs -Dhagrid.pipeline.root=. --add-opens=java.base/java.lang=ALL-UNNAMED"
cd /d "%~dp0..\..\hagrid\simulation"
if not exist "hagrid-output\logs\jvm" mkdir "hagrid-output\logs\jvm"
echo ===== STEPA_V2DEV START %date% %time% =====
"%JAVA_EXE%" -version
"%JAVA_EXE%" %VMARGS% -cp "%JAR%" hagrid.hannover.HAGRID2MATSimPipelineRunner
echo STEPA_EXIT=%ERRORLEVEL%
echo ===== STEPA_V2DEV_DONE %date% %time% =====
