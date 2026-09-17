@echo off
setlocal

cd /d "%~dp0"

if not exist "run_hagrid_sim.bat" (
  echo run_hagrid.bat not found in %cd%
  pause
  exit /b 1
)

rem open new PowerShell window, run script, keep it open
start "Run HAGRID" powershell -NoExit -Command "& { .\run_hagrid_sim.bat }"

endlocal
exit /b 0