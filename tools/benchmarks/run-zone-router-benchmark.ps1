param(
    [Parameter(Mandatory=$true)][string]$AuditDirectory,
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot',
    [switch]$Pilot
)
# Historical experiment retired with zone approximation, warmstart and adaptive search.
$ErrorActionPreference='Stop'
throw 'This experiment was removed; use its archived source/JAR package to reproduce historical measurements. Current HAGRID uses exact routing and fixed iteration budgets.'
