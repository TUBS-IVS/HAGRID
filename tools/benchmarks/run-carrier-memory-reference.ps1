param(
 [Parameter(Mandatory)][string]$OutputDirectory,
 [int[]]$Seeds=@(42,43,44,45,46),
 [switch]$CompareZones,
 [string]$ReferenceJar,
 [ValidateRange(1,128)][int[]]$JobCounts=@(16,32),
 [string]$JavaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot'
)
# Historical experiment retired with zone approximation, warmstart and adaptive search.
$ErrorActionPreference='Stop'
throw 'This experiment was removed; use its archived source/JAR package to reproduce historical measurements. Current HAGRID uses exact routing and fixed iteration budgets.'
