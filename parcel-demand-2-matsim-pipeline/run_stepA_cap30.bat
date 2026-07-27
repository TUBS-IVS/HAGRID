@echo off
cd /d "%~dp0"
set "JAVA_EXE=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\java.exe"
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
if not exist "hagrid-output\logs\jvm" mkdir "hagrid-output\logs\jvm"
echo === STEP A start %DATE% %TIME% ===
"%JAVA_EXE%" @vmargs.txt -Dhagrid.pipeline.root=. -cp "target\parcel-demand-2-matsim-pipeline-1.0-SNAPSHOT.jar" hagrid.HAGRID2MATSimPipelineRunner
echo STEPA_EXIT=%ERRORLEVEL%
