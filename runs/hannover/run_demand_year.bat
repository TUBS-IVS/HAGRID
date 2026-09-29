@echo off
setlocal
rem Jahreslauf des Nachfragemodells (Python) und Uebergabe der MATSim-Nachfrage an hagrid/simulation.
rem Aufruf: runs\hannover\run_demand_year.bat [run-id] [config]
rem   run-id  Standard: demand-%date%   config  Standard: hagrid/demand/model/configs/baseline-daily.json

cd /d "%~dp0..\.."

set "RUN_ID=%~1"
if "%RUN_ID%"=="" set "RUN_ID=demand-%DATE:~6,4%-%DATE:~3,2%-%DATE:~0,2%"
set "CONFIG=%~2"
if "%CONFIG%"=="" set "CONFIG=hagrid\demand\model\configs\baseline-daily.json"
set "PYTHONPATH=hagrid\demand\model\src"

python -m hagrid_demand baseline run --config "%CONFIG%" --run-id "%RUN_ID%"
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" goto :end

rem MATSim liest die Tagesdateien aus hagrid/simulation/input/hannover/demand/<run-id>/ (HagridPaths.demandDir)
set "SRC=hagrid\demand\runs\%RUN_ID%\matsim"
set "DST=hagrid\simulation\input\hannover\demand\%RUN_ID%"
if exist "%SRC%" (
  if not exist "%DST%" mkdir "%DST%"
  xcopy /Y /Q "%SRC%\*" "%DST%\" > nul
  echo MATSim demand: %DST%
)
if exist "hagrid\demand\runs\%RUN_ID%\annual_dashboard.html" echo Annual dashboard: hagrid\demand\runs\%RUN_ID%\annual_dashboard.html
echo Weitere Tage: python -m hagrid_demand baseline export-day --run hagrid\demand\runs\%RUN_ID% --date YYYY-MM-DD

:end
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
