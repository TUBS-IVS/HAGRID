@echo off
setlocal
rem Dekadenlauf 2025-2035 des Nachfragemodells (Python): je Szenario ein Lauf, danach das Dekaden-Dashboard.
rem Aufruf: runs\hannover\run_demand_decade.bat [szenario ...]   (Standard: trend saettigung boom)
rem Configs: hagrid\demand\model\configs\decade-<szenario>.json, Laeufe: hagrid\demand\runs\decade-<szenario>
rem Kein Kopieren nach hagrid\simulation: bei Bedarf runs\hannover\run_demand_year.bat-Logik nutzen oder export-day.

cd /d "%~dp0..\.."
set "PYTHONPATH=hagrid\demand\model\src"
set "SCENARIOS=%*"
if "%SCENARIOS%"=="" set "SCENARIOS=trend saettigung boom"
set "RC=0"
set "RUNS="

for %%S in (%SCENARIOS%) do (
  echo === Szenario %%S
  if exist "hagrid\demand\runs\decade-%%S\annual_dashboard.html" (
    echo Lauf decade-%%S ist schon fertig, wird nicht neu gerechnet
    call set "RUNS=%%RUNS%% --run %%S=hagrid\demand\runs\decade-%%S"
  ) else (
    python -m hagrid_demand baseline run --config "hagrid\demand\model\configs\decade-%%S.json" --run-id "decade-%%S"
    if errorlevel 1 (
      set "RC=1"
      echo Szenario %%S fehlgeschlagen
    ) else (
      call set "RUNS=%%RUNS%% --run %%S=hagrid\demand\runs\decade-%%S"
    )
  )
)

rem Das Dashboard entsteht aus allen fertigen Szenarien, auch wenn eines fehlgeschlagen ist (RC bleibt dann 1).
if "%RUNS%"=="" goto :end
python -m hagrid_demand baseline decade-dashboard %RUNS% --out "hagrid\demand\runs\decade_dashboard.html"
if errorlevel 1 (
  set "RC=1"
) else (
  echo Decade dashboard: hagrid\demand\runs\decade_dashboard.html
)

:end
echo EXIT_CODE=%RC%
endlocal
exit /b %RC%
