@echo off
REM =========================================================================
REM HAGRID Input Structure Setup Script
REM =========================================================================
REM Creates the new hagrid-input/ directory structure and copies/links files
REM from the old scattered locations into the clean new structure.
REM
REM Run this ONCE from the project root (HAGRID/) to set up the new structure.
REM =========================================================================

set PIPELINE=parcel-demand-2-matsim-pipeline

echo.
echo ============================================================
echo   HAGRID - Setting up new I/O directory structure
echo ============================================================
echo.

REM --- Create hagrid-input directories ---
echo [1/7] Creating hagrid-input directories...
mkdir "%PIPELINE%\hagrid-input\config" 2>nul
mkdir "%PIPELINE%\hagrid-input\demand" 2>nul
mkdir "%PIPELINE%\hagrid-input\geodata" 2>nul
mkdir "%PIPELINE%\hagrid-input\hubs" 2>nul
mkdir "%PIPELINE%\hagrid-input\network" 2>nul
mkdir "%PIPELINE%\hagrid-input\vehicles" 2>nul

REM --- Create hagrid-output and hagrid-matsim-output base directories ---
echo [2/7] Creating hagrid-output and hagrid-matsim-output directories...
mkdir "%PIPELINE%\hagrid-output" 2>nul
mkdir "%PIPELINE%\hagrid-matsim-output" 2>nul

REM --- Copy/Move config files ---
echo [3/7] Copying config files...
if exist "%PIPELINE%\input\config.xml" (
    copy "%PIPELINE%\input\config.xml" "%PIPELINE%\hagrid-input\config\config.xml" >nul
    echo     config.xml copied
)
if exist "%PIPELINE%\sim-input\sim-config.xml" (
    copy "%PIPELINE%\sim-input\sim-config.xml" "%PIPELINE%\hagrid-input\config\sim-config.xml" >nul
    echo     sim-config.xml copied
)
if exist "%PIPELINE%\sim-input\algorithm.xml" (
    copy "%PIPELINE%\sim-input\algorithm.xml" "%PIPELINE%\hagrid-input\config\jsprit-algorithm.xml" >nul
    echo     jsprit-algorithm.xml copied
)

REM --- Copy network files ---
echo [4/7] Copying network files...
if exist "%PIPELINE%\sim-input\network\car_network_filtered_V2.xml.gz" (
    copy "%PIPELINE%\sim-input\network\car_network_filtered_V2.xml.gz" "%PIPELINE%\hagrid-input\network\" >nul
    echo     car_network_filtered_V2.xml.gz copied
)
if exist "%PIPELINE%\sim-input\network\cargobike_network_zones_MH_V3_clean.xml.gz" (
    copy "%PIPELINE%\sim-input\network\cargobike_network_zones_MH_V3_clean.xml.gz" "%PIPELINE%\hagrid-input\network\" >nul
    echo     cargobike_network_zones_MH_V3_clean.xml.gz copied
)
if exist "%PIPELINE%\sim-input\network\RH_useful__zone.shp" (
    copy "%PIPELINE%\sim-input\network\RH_useful__zone.*" "%PIPELINE%\hagrid-input\network\" >nul
    echo     RH_useful__zone.shp + sidecar files copied
)

REM --- Copy vehicle types ---
echo [5/7] Copying vehicle type definitions (base templates)...
if exist "%PIPELINE%\input\HAGRID_vehicleTypes2.0.xml" (
    copy "%PIPELINE%\input\HAGRID_vehicleTypes2.0.xml" "%PIPELINE%\hagrid-input\vehicles\" >nul
    echo     HAGRID_vehicleTypes2.0.xml copied
)

REM --- Copy hub/geodata files ---
echo [6/7] Copying hub and geodata files...
if exist "%PIPELINE%\input\hubs\KEP-hubs_v3.csv" (
    copy "%PIPELINE%\input\hubs\KEP-hubs_v3.csv" "%PIPELINE%\hagrid-input\hubs\" >nul
    echo     KEP-hubs_v3.csv copied
)
if exist "%PIPELINE%\input\hubs\standorte_von_dhl.de.csv" (
    copy "%PIPELINE%\input\hubs\standorte_von_dhl.de.csv" "%PIPELINE%\hagrid-input\hubs\" >nul
    echo     standorte_von_dhl.de.csv copied
)
if exist "%PIPELINE%\input\hubs\standorte_von_paket.net" (
    xcopy "%PIPELINE%\input\hubs\standorte_von_paket.net" "%PIPELINE%\hagrid-input\hubs\standorte_von_paket.net\" /E /I /Q >nul
    echo     standorte_von_paket.net/ copied
)
if exist "%PIPELINE%\input\geodata" (
    xcopy "%PIPELINE%\input\geodata" "%PIPELINE%\hagrid-input\geodata\" /E /I /Q >nul
    echo     geodata/ copied
)

REM --- Copy demand folders ---
echo.
echo [7/7] Copying demand shapefiles...
if exist "%PIPELINE%\input\demand" (
    xcopy "%PIPELINE%\input\demand" "%PIPELINE%\hagrid-input\demand\" /E /I /Q >nul
    echo     demand/ copied (all scenario subfolders)
) else (
    echo     WARNING: No demand folder found at %PIPELINE%\input\demand
)

echo ============================================================
echo   Setup complete!
echo ============================================================
echo.
echo   New structure:
echo     %PIPELINE%\hagrid-input\       All pipeline inputs
echo     %PIPELINE%\hagrid-output\      Pipeline results (per run)
echo     %PIPELINE%\hagrid-matsim-output\  MATSim simulation results
echo.
echo   Run the pipeline and outputs will be created under:
echo     hagrid-output\{RUN_ID}\carriers\    Carrier plans (unrouted/merged/routed)
echo     hagrid-output\{RUN_ID}\vehicles\    Run-specific vehicle types (used by simulation)
echo     hagrid-output\{RUN_ID}\network\     Filtered network for this run
echo     hagrid-output\{RUN_ID}\routing\     Routing metrics and status
echo     hagrid-output\{RUN_ID}\demand\clustering\  Clustering plots
echo     hagrid-output\{RUN_ID}\summary\     Configuration summary
echo     hagrid-output\{RUN_ID}\logs\        Run logs
echo.
echo   Vehicle types flow:
echo     hagrid-input\vehicles\  (base template, read by pipeline)
echo       -^> hagrid-output\{RUN_ID}\vehicles\  (adapted for run, used by simulation)
echo.
pause
