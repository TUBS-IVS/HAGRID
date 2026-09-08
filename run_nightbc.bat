@echo off
setlocal
set MAVEN_OPTS=-Xmx16g -Xms4g -Dhagrid.log.dir=hagrid-output/logs --add-opens java.base/java.lang=ALL-UNNAMED
cd /d "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID"
echo ===== NIGHTBC START %date% %time% =====
echo ===== STEP0 BUILD AND FULL REGRESSION %time% =====
call mvn install
echo STEP0_BUILD_EXIT=%ERRORLEVEL%
if not %ERRORLEVEL%==0 goto :abort
echo ===== STEP1A BASEW21 PREPARE %time% =====
call mvn -pl parcel-demand-2-matsim-pipeline exec:java -Dexec.mainClass=hagrid.integrated.drt.PrepareLausitzDrtInputs -Dexec.args="concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=150,jspritIter=100,freight=true,tag=basew21"
echo STEP1A_EXIT=%ERRORLEVEL%
echo ===== STEP1B BASEW21 RUN %time% =====
call mvn -pl parcel-demand-2-matsim-pipeline exec:java -Dexec.mainClass=hagrid.HAGRIDSimulationRunner -Dexec.args="concept=drt_baseline,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=150,jspritIter=100,freight=true,tag=basew21"
echo STEP1B_EXIT=%ERRORLEVEL%
echo ===== STEP2A CHID600W21 PREPARE %time% =====
call mvn -pl parcel-demand-2-matsim-pipeline exec:java -Dexec.mainClass=hagrid.integrated.drt.PrepareLausitzDrtInputs -Dexec.args="concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=150,freight=false,tag=chid600w21,chiThreshold=600"
echo STEP2A_EXIT=%ERRORLEVEL%
if not %ERRORLEVEL%==0 goto :done
echo ===== STEP2B CHID600W21 RUN %time% =====
call mvn -pl parcel-demand-2-matsim-pipeline exec:java -Dexec.mainClass=hagrid.HAGRIDSimulationRunner -Dexec.args="concept=drt_shareduse,date=2025-05-13,studyArea=LAUSITZ_HOYERSWERDA,fleetSize=120,maxIter=150,freight=false,tag=chid600w21,chiThreshold=600"
echo STEP2B_EXIT=%ERRORLEVEL%
:done
echo ===== NIGHTBC_DONE %date% %time% =====
goto :eof
:abort
echo ===== NIGHTBC_ABORTED_STEP0 %date% %time% =====
