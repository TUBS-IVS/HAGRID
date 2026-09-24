@echo off
setlocal
rem Wrapper so start_detached.ps1 only has to launch ONE token. Passing the chainer's
rem parameters through cmd /c "powershell -File ... -P ""with spaces""" is exactly the
rem nested-quoting case that misparses on this machine (path contains a space).
cd /d "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\hagrid\simulation"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "C:\Users\Hendrik Bimmermann\hagrid-tools\chain_stepB.ps1" -StepALog "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\hagrid\simulation\hagrid-output\logs\stepA_v2dev.log" -DonePattern "STEPA_V2DEV_DONE" -StepBBat "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\runs\hannover\run_stepB_v2dev_batch.bat" -StepBLog "hagrid-output\logs\stepB_v2dev_batch.log" -WorkDir "C:\Users\Hendrik Bimmermann\Documents\GitHub\HAGRID\hagrid\simulation" -StartDetached "C:\Users\Hendrik Bimmermann\hagrid-tools\start_detached.ps1" -OwnLog "C:\Users\Hendrik Bimmermann\hagrid-tools\chain_v2dev.log"
echo CHAIN_WRAPPER_EXIT=%ERRORLEVEL%
