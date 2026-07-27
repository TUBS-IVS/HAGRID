@echo off
cd /d "%~dp0"
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe"
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "JAR=target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar"
if not exist "hagrid-matsim-output\logs\jvm" mkdir "hagrid-matsim-output\logs\jvm"
if not exist "%JAR%" ( echo JAR not found %JAR% & exit /b 1 )
echo === STEP B weekend batch start %DATE% %TIME% ===
"%JAVA_EXE%" -version

echo Running 1/10 tag=60v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=60v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN1_EXIT=%ERRORLEVEL%

echo Running 2/10 tag=70v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=70v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN2_EXIT=%ERRORLEVEL%

echo Running 3/10 tag=80v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=80v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN3_EXIT=%ERRORLEVEL%

echo Running 4/10 tag=90v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=90v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN4_EXIT=%ERRORLEVEL%

echo Running 5/10 tag=100v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=100v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN5_EXIT=%ERRORLEVEL%

echo Running 6/10 tag=110v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=110v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN6_EXIT=%ERRORLEVEL%

echo Running 7/10 tag=120v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=120v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN7_EXIT=%ERRORLEVEL%

echo Running 8/10 tag=130v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=130v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN8_EXIT=%ERRORLEVEL%

echo Running 9/10 tag=140v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=140v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN9_EXIT=%ERRORLEVEL%

echo Running 10/10 tag=150v2  %DATE% %TIME%
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -jar "%JAR%" concept=basecase,date=2025-05-13,tag=150v2,maxIter=150,jspritIter=1000,zoneCaching=true,zoneThreshold=1500,writeDashboard=true
echo SCEN10_EXIT=%ERRORLEVEL%

echo === STEP B weekend batch done %DATE% %TIME% ===
