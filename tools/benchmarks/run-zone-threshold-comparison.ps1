param(
 [Parameter(Mandatory)][string]$OutputDirectory,
 [long[]]$Seeds=@(42,43,44),
 [ValidateRange(1,10000)][double[]]$ThresholdMeters=@(1500,3000,5000),
 [ValidateRange(1,32)][int[]]$JobCounts=@(16,32),
 [switch]$Pilot,
 [string]$VerifiedBuildManifest,
 [string]$JavaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot'
)
# Historical experiment retired with zone approximation, warmstart and adaptive search.
$ErrorActionPreference='Stop'
throw 'This experiment was removed; use its archived source/JAR package to reproduce historical measurements. Current HAGRID uses exact routing and fixed iteration budgets.'
