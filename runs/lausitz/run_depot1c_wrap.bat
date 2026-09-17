@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\..\hagrid"
rem Waits for the 1d depot chain to finish, then starts the 1c depot chain.
rem "ping" is the sleep primitive on purpose: "timeout" needs a console and fails when this
rem script is launched detached via WMI Win32_Process.Create (no console attached).
set "LOG1D=hagrid-matsim-output\logs\depot1d_chain.log"
set "LOG=hagrid-matsim-output\logs\depot1c_wrap.log"
if not exist "hagrid-matsim-output\logs" mkdir "hagrid-matsim-output\logs"
set /a MAXTRIES=144
set /a TRIES=0
echo [%DATE% %TIME%] WRAP START - waiting for DEPOT1D CHAIN COMPLETE, poll 5 min, max 12 h >> "%LOG%"
:WAIT
findstr /c:"DEPOT1D CHAIN COMPLETE" "%LOG1D%" >nul 2>&1
if not errorlevel 1 goto GO
set /a TRIES+=1
if !TRIES! GEQ %MAXTRIES% (
  echo [%DATE% %TIME%] WRAP GIVING UP after 12 h - 1d chain never wrote COMPLETE, 1c NOT started >> "%LOG%"
  exit /b 1
)
ping -n 301 127.0.0.1 >nul
goto WAIT
:GO
echo [%DATE% %TIME%] WRAP - 1d chain complete after !TRIES! polls, starting 1c chain >> "%LOG%"
call "%~dp0run_depot1c_chain.bat"
echo [%DATE% %TIME%] WRAP DONE - 1c chain returned %ERRORLEVEL% >> "%LOG%"
endlocal
exit /b 0
