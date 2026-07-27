@echo off
cd /d "%~dp0"
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe"
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
if not exist "hagrid-output\logs\jvm" mkdir "hagrid-output\logs\jvm"
echo === SMOKE STEP B start %DATE% %TIME% ===
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar" concept=basecase,date=2025-05-13,tag=30R1,maxIter=1,jspritIter=10,zoneCaching=true,zoneThreshold=1500,writeDashboard=true,kpiDashboard=false
echo STEPB_SMOKE_EXIT=%ERRORLEVEL%
