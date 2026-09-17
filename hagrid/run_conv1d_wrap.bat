@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "WLOG=hagrid-matsim-output\logs\conv1d_wrap.log"
set "WATCH=hagrid-matsim-output\logs\fleet1d_b_chain.log"
rem Waits for the f125/f120 chain to finish, then starts the maxIter=250 convergence arm.
rem 192 tries x 5 min = 16 h ceiling: a hung or crashed predecessor must not leave this polling
rem forever, and it must never start while f120 still holds the machine.
echo [%DATE% %TIME%] CONV1D WRAP START - waiting for FLEET1D-B CHAIN COMPLETE >> "%WLOG%"
set /a TRY=0
:WAIT
set /a TRY+=1
if %TRY% GTR 192 ( echo [%DATE% %TIME%] WRAP GIVING UP after %TRY% tries - NOT starting >> "%WLOG%" & exit /b 1 )
find "FLEET1D-B CHAIN COMPLETE" "%WATCH%" >nul 2>&1
if not errorlevel 1 goto GO
ping -n 301 127.0.0.1 >nul
goto WAIT
:GO
echo [%DATE% %TIME%] predecessor complete after %TRY% polls, starting conv chain >> "%WLOG%"
call run_conv1d_chain.bat
echo [%DATE% %TIME%] CONV1D WRAP DONE >> "%WLOG%"
endlocal
exit /b 0
