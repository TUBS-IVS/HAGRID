@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\..\hagrid"
rem Waits for the 1c depot chain to finish, then starts the 1d fleet-size sweep.
rem "ping" is the sleep primitive on purpose: "timeout" needs a console and fails when this
rem script is launched detached via WMI Win32_Process.Create (no console attached).
set "LOG1C=hagrid-matsim-output\logs\depot1c_chain.log"
set "LOG=hagrid-matsim-output\logs\fleet1d_wrap.log"
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
set /a MAXTRIES=144
set /a TRIES=0
echo [%DATE% %TIME%] WRAP START - waiting for DEPOT1C CHAIN COMPLETE, poll 5 min, max 12 h >> "%LOG%"
:WAIT
findstr /c:"DEPOT1C CHAIN COMPLETE" "%LOG1C%" >nul 2>&1
if not errorlevel 1 goto GO
set /a TRIES+=1
if !TRIES! GEQ %MAXTRIES% (
  echo [%DATE% %TIME%] WRAP GIVING UP after 12 h - 1c chain never wrote COMPLETE, fleet sweep NOT started >> "%LOG%"
  exit /b 1
)
ping -n 301 127.0.0.1 >nul
goto WAIT
:GO
echo [%DATE% %TIME%] WRAP - 1c chain complete after !TRIES! polls, starting fleet sweep >> "%LOG%"
call run_fleet1d_chain.bat
echo [%DATE% %TIME%] WRAP DONE - fleet chain returned %ERRORLEVEL% >> "%LOG%"
endlocal
exit /b 0
