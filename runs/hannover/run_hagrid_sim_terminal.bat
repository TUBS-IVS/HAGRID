@echo off
setlocal

cd /d "%~dp0..\..\hagrid"

if not exist "%~dp0..\..\hagrid\run_hagrid_sim.bat" (
  echo run_hagrid_sim.bat not found in %~dp0..\..\hagrid - let SimulationBatGenerator write it first
  pause
  exit /b 1
)

rem open new PowerShell window, run script, keep it open
start "Run HAGRID" powershell -NoExit -Command "& { & '%~dp0..\..\hagrid\run_hagrid_sim.bat' }"

endlocal
exit /b 0