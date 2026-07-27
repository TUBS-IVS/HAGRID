@echo off
cd /d "%~dp0"
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe"
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar"
if not exist "hagrid-matsim-output\logs\jvm" mkdir "hagrid-matsim-output\logs\jvm"
if not exist "%JAR%" ( echo JAR not found %JAR% & exit /b 1 )
echo === STEP B 70v2 REDO start %DATE% %TIME% ===
"%JAVA_EXE%" -version
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=70v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo REDO70_EXIT=%ERRORLEVEL%
echo === STEP B 70v2 REDO done %DATE% %TIME% ===
