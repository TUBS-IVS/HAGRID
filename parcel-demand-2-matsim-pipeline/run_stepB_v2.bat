@echo off
cd /d "%~dp0"
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe"
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar"
if not exist "hagrid-matsim-output\logs\jvm" mkdir "hagrid-matsim-output\logs\jvm"
if not exist "%JAR%" ( echo JAR not found %JAR% & exit /b 1 )
echo === STEP B v2 batch start %DATE% %TIME% ===
"%JAVA_EXE%" -version

echo Running 1/3 tag=30v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=30v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN1_EXIT=%ERRORLEVEL%

echo Running 2/3 tag=40v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=40v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN2_EXIT=%ERRORLEVEL%

echo Running 3/3 tag=50v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=50v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN3_EXIT=%ERRORLEVEL%

echo === STEP B v2 batch done %DATE% %TIME% ===
