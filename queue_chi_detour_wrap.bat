@echo off
rem Detached wrapper for the 1c chi=600 detour rerun queue. Catches any unhandled
rem PowerShell exception that the script's own log would not see.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\queue_chi_detour_rerun.ps1" > "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\parcel-demand-2-matsim-pipeline\hagrid-output\logs\chi_detour_queue_wrapper.log" 2>&1
echo QUEUE_WRAPPER_EXIT=%ERRORLEVEL%
