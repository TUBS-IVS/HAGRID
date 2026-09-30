@echo off
rem Detached wrapper for run_nightbc.bat; console output goes to the module's run-log folder.
rem Fixed 2026-09-28: the target used to be HAGRID\hagrid-output\logs, a folder that exists only
rem where some earlier run happened to create it - elsewhere the redirect failed and nothing ran.
set "LOGDIR=%~dp0..\..\hagrid\simulation\hagrid-matsim-output\logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
call "%~dp0run_nightbc.bat" > "%LOGDIR%\nightbc.console.log" 2>&1
