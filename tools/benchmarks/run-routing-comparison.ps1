param(
    [Parameter(Mandatory)][string]$BaselineCheckout,
    [Parameter(Mandatory)][string]$CandidateCheckout,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Checkpoint,
    [string[]]$Cases = @('services'),
    [int[]]$Seeds = @(42,43,44,45,46),
    [ValidateRange(1,100)][int]$Repeats = 3,
    [ValidateRange(256,512)][int]$HeapMb = 384,
    [ValidateRange(6144,262144)][int]$MinAvailableMemoryMb = 6144,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Set JavaHome to a Java 21 JDK.' }
if (($Cases | Where-Object { $_ -ne 'services' }).Count -gt 0) { throw 'Currently supported fixture: services. Further fixtures are added at the relevant implementation phase.' }
if ($Seeds.Count -ne (@($Seeds | Select-Object -Unique)).Count) { throw 'Seeds must be unique.' }
$baseline = (Resolve-Path -LiteralPath $BaselineCheckout).Path
$candidate = (Resolve-Path -LiteralPath $CandidateCheckout).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $output 'manifest.json')) { throw 'This checkpoint already exists; use a fresh output directory.' }
[IO.Directory]::CreateDirectory($output) | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$helper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../hagrid/simulation/src/test/java/hagrid/core/routing/benchmark/RoutingComparison.java'))
if (-not ('HagridBenchmarkMemory' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class HagridBenchmarkMemory {
 [StructLayout(LayoutKind.Sequential)] public struct Info {
  public uint length, load;
  public ulong total, available, pageTotal, pageAvailable, virtualTotal, virtualAvailable, extended;
 }
 [DllImport("kernel32.dll", SetLastError=true)] private static extern bool GlobalMemoryStatusEx(ref Info info);
 public static ulong Available() {
  var i = new Info(); i.length = (uint)Marshal.SizeOf<Info>();
  if (!GlobalMemoryStatusEx(ref i)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
  return i.available;
 }
}
'@
}
$logging = Join-Path $output 'silent-log4j.xml'
[IO.File]::WriteAllText($logging, '<Configuration status="ERROR"><Appenders><Console name="C"><PatternLayout pattern="%m%n"/></Console></Appenders><Loggers><Root level="error"><AppenderRef ref="C"/></Root></Loggers></Configuration>')
function Java-Arg([string]$value) { '"' + $value.Replace('\','/').Replace('"','\"') + '"' }
function Run-Java([string[]]$arguments, [string]$name) {
    if ([HagridBenchmarkMemory]::Available() -lt ($MinAvailableMemoryMb * 1MB)) {
        $config.status = 'DEFERRED_RESOURCE_PRESSURE'
        [IO.File]::WriteAllText((Join-Path $output 'manifest.json'), ($config | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
        throw 'Valhalla has priority: insufficient available memory for another HAGRID trial.'
    }
    $argFile = Join-Path $output ($name + '.args')
    $logFile = Join-Path $output ($name + '.log')
    [IO.File]::WriteAllLines($argFile, $arguments, [Text.UTF8Encoding]::new($false))
    $startInfo = [Diagnostics.ProcessStartInfo]::new($java)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.ArgumentList.Add('@' + $argFile)
    $child = [Diagnostics.Process]::new()
    $child.StartInfo = $startInfo
    $childStarted = $false
    try {
        $childStarted = $child.Start()
        if (-not $childStarted) { throw 'Could not start own benchmark process.' }
        $child.PriorityClass = [Diagnostics.ProcessPriorityClass]::Idle
        $stdoutTask = $child.StandardOutput.ReadToEndAsync()
        $stderrTask = $child.StandardError.ReadToEndAsync()
        $observedWorkingSet = [long]0
        while (-not $child.WaitForExit(200)) {
            $child.Refresh()
            if (-not $child.HasExited) { $observedWorkingSet = [Math]::Max($observedWorkingSet, $child.WorkingSet64) }
            if ([HagridBenchmarkMemory]::Available() -lt ($MinAvailableMemoryMb * 1MB)) {
                $child.Kill($true)
                $child.WaitForExit()
                $config.status = 'DEFERRED_RESOURCE_PRESSURE'
                [IO.File]::WriteAllText((Join-Path $output 'manifest.json'), ($config | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
                throw 'Own HAGRID trial stopped to preserve memory for Valhalla.'
            }
        }
        [IO.File]::WriteAllText($logFile, $stdoutTask.GetAwaiter().GetResult() + $stderrTask.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
        $resourceObservations[$name] = $observedWorkingSet
        if ($child.ExitCode -ne 0) { Get-Content -LiteralPath $logFile -Tail 15; throw "Java failed: $name" }
    } finally {
        if ($childStarted -and -not $child.HasExited) { $child.Kill($true); $child.WaitForExit() }
        $child.Dispose()
    }
    return @(Get-Content -LiteralPath $logFile)
}
function Read-Classpath([string]$checkout) {
    $reportFile = Join-Path $checkout 'hagrid/simulation/target/surefire-reports/TEST-hagrid.core.routing.HAGRIDRouterUtilsTest.xml'
    if (-not (Test-Path -LiteralPath $reportFile)) { throw "Build and run HAGRIDRouterUtilsTest first: $checkout" }
    [xml]$report = Get-Content -Raw -LiteralPath $reportFile
    $classpath = @($report.testsuite.properties.property | Where-Object name -eq 'java.class.path')[0].value
    if ([string]::IsNullOrWhiteSpace($classpath)) { throw "Missing test classpath: $reportFile" }
    return $classpath
}
function Checkout-Identity([string]$checkout) {
    $head = & git -c "safe.directory=$($checkout.Replace('\','/'))" -C $checkout rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify checkout.' }
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $checkout 'hagrid/simulation/src/main/java') -Filter '*.java' -Recurse | Sort-Object FullName)
    $entries = @($sources | ForEach-Object { $_.FullName.Substring($checkout.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
    foreach ($pom in @('pom.xml','external/freight/pom.xml','hagrid/simulation/pom.xml')) {
        $entries += $pom + ':' + (Get-FileHash -LiteralPath (Join-Path $checkout $pom) -Algorithm SHA256).Hash
    }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $hash = [Convert]::ToHexString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(($entries -join "`n")))) } finally { $hasher.Dispose() }
    $submodule = & git -c "safe.directory=$($checkout.Replace('\','/'))/external/matsim-libs" -C (Join-Path $checkout 'external/matsim-libs') rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify Freight source.' }
    return @{ checkout=$checkout; commit=$head; product_source_fingerprint=$hash; matsim_source_commit=$submodule }
}
$config = @{ checkpoint=$Checkpoint; seeds=$Seeds; repeats=$Repeats; heap_mb=$HeapMb; initial_heap_mb=64; active_processors=1; process_priority='Idle'; min_available_memory_mb=$MinAvailableMemoryMb; cases=$Cases; warmup_solves_per_jvm=2; heap_sampling_ms=5; probe_hash=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'RoutingComparisonProbe.java')).Hash; comparison_policy_hash=(Get-FileHash -LiteralPath $helper).Hash; java_home=$JavaHome; started_at=[DateTimeOffset]::Now.ToString('o'); status='RUNNING'; baseline=(Checkout-Identity $baseline); candidate=(Checkout-Identity $candidate) }
$resourceObservations = @{}
$config.process_memory_sampling_ms = 200
$config.background_load = 'Valhalla running; results require quiet-host confirmation before general speed claims'
[IO.File]::WriteAllText((Join-Path $output 'manifest.json'), ($config | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
$paths = @{}
foreach ($label in @('baseline','candidate')) {
    $root = if ($label -eq 'baseline') { $baseline } else { $candidate }
    $cp = Read-Classpath $root
    # The application test classpath contains hundreds of unrelated analysis/DRT jars.
    # javac indexes every entry, so compile the small probe against its actual API dependencies.
    $compileCp = (($cp -split ';') | Where-Object {
        $_ -match '[\\/]target[\\/]classes$' -or
        [IO.Path]::GetFileName($_) -match '^(matsim-[0-9][^-]*\.jar|jsprit-core-.*\.jar|log4j-api-.*\.jar|jfreechart-.*\.jar|guava-.*\.jar|fastutil-.*\.jar|jakarta\.inject-api-.*\.jar)$'
    }) -join ';'
    $classes = Join-Path $output ($label + '-probe-classes')
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    $compile = @('-proc:none','--release','21','-cp',(Java-Arg $compileCp),'-d',(Java-Arg $classes),(Java-Arg (Join-Path $PSScriptRoot 'RoutingComparisonProbe.java')),(Java-Arg $helper))
    $compileFile = Join-Path $output ($label + '-compile.args')
    [IO.File]::WriteAllLines($compileFile, $compile, [Text.UTF8Encoding]::new($false))
    & $javac '-J-Xmx256m' '-J-XX:ActiveProcessorCount=1' ('@' + $compileFile) *> (Join-Path $output ($label + '-compile.log'))
    if ($LASTEXITCODE -ne 0) {
        $config.status = 'FAILED_COMPILATION'
        [IO.File]::WriteAllText((Join-Path $output 'manifest.json'), ($config | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
        throw "Probe compilation failed: $label"
    }
    $paths[$label] = $classes + ';' + $cp
    [IO.File]::WriteAllText((Join-Path $output ($label + '-classpath.txt')), $cp, [Text.UTF8Encoding]::new($false))
    $config[$label]['classpath_hash'] = (Get-FileHash -LiteralPath (Join-Path $output ($label + '-classpath.txt'))).Hash
}
$csv = Join-Path $output 'measurements.csv'
$header = 'variant,repeat,fixture,seed,heap_mb,iteration_budget,actual_iterations,required_jobs,served_jobs,hard_violations,vehicles,search_cost,final_cost,milliseconds,preparation_ms,network_context_ms,search_ms,validation_ms,observed_peak_heap_bytes,main_thread_allocated_bytes,gc_ms,route_hash,input_fingerprint,rules_fingerprint,observed_process_working_set_bytes'
[IO.File]::WriteAllText($csv, $header + "`n", [Text.UTF8Encoding]::new($false))
foreach ($case in $Cases) { foreach ($seed in $Seeds) { for ($repeat = 0; $repeat -lt $Repeats; $repeat++) {
    $order = if (($repeat + [array]::IndexOf($Seeds, $seed)) % 2 -eq 0) { @('baseline','candidate') } else { @('candidate','baseline') }
    foreach ($label in $order) {
        $runArgs = @('-Xms64m', "-Xmx${HeapMb}m", '-XX:ActiveProcessorCount=1', ('-Dlog4j.configurationFile=' + (Java-Arg ([uri]$logging).AbsoluteUri)), '-Dorg.slf4j.simpleLogger.defaultLogLevel=error', '-cp', (Java-Arg $paths[$label]), 'hagrid.core.routing.RoutingComparisonProbe', $case, [string]$seed, [string]$HeapMb)
        $runName = "$label-$case-$seed-$repeat"
        $lines = Run-Java $runArgs $runName
        $row = @($lines | Where-Object { $_ -like 'MEASUREMENT,*' })
        if ($row.Count -ne 1) { throw 'Expected one measurement row.' }
        $csvRow = "$label,$repeat," + $row[0].Substring(12) + ',' + $resourceObservations[$runName]
        if (($csvRow -split ',').Count -ne ($header -split ',').Count) { throw 'Measurement/header field count differs.' }
        [IO.File]::AppendAllText($csv, $csvRow + "`n", [Text.UTF8Encoding]::new($false))
    }
} } }
function Median($values) {
    $sorted = @($values | Sort-Object)
    if ($sorted.Count % 2 -eq 1) { return $sorted[[int][Math]::Floor($sorted.Count / 2)] }
    return ($sorted[$sorted.Count / 2 - 1] + $sorted[$sorted.Count / 2]) / 2
}
$measurements = @(Import-Csv -LiteralPath $csv)
$report = @("# Vergleich $Checkpoint", '', 'Kleiner synthetischer Service-Fall, keine Produktionsaussage. Zeiten und beobachteter Heap sind Medianwerte nach zwei Aufwärmläufen pro JVM. Streuung und Einzelwerte stehen in measurements.csv; Speicher wird alle 5 ms beobachtet, kein garantierter Spitzenwert.', '', '| Seed | Alt ms | Neu ms | Alt Kosten | Neu Kosten | Alt/Neu bedient | Alt/Neu Verletzungen | Alt/Neu Heap MB | Einordnung |', '|---|---:|---:|---:|---:|---|---|---|---|')
$identicalSource = $config.baseline.product_source_fingerprint -eq $config.candidate.product_source_fingerprint
if ($identicalSource) { $report[2] += ' Beide Varianten verwenden denselben Quellstand; Zahlenunterschiede sind Messstreuung und keine Codeverbesserung.' }
foreach ($seed in $Seeds) {
    $byLabel = @{}
    foreach ($label in @('baseline','candidate')) {
        $rows = @($measurements | Where-Object { $_.variant -eq $label -and [int]$_.seed -eq $seed })
        $reference = $rows[0]
        $time = Median @($rows | ForEach-Object { [double]::Parse($_.milliseconds, [Globalization.CultureInfo]::InvariantCulture) })
        $cost = Median @($rows | ForEach-Object { [double]::Parse($_.final_cost, [Globalization.CultureInfo]::InvariantCulture) })
        $memory = Median @($rows | ForEach-Object { [long]$_.observed_peak_heap_bytes })
        $byLabel[$label] = @{ time=$time; cost=$cost; memory=[long][Math]::Round($memory); served=[int]($rows | Measure-Object -Property served_jobs -Minimum).Minimum; violations=[int]($rows | Measure-Object -Property hard_violations -Maximum).Maximum; row=$reference }
        $actualCounts = @($rows.actual_iterations | Select-Object -Unique)
        $byLabel[$label].actual_iterations = if ($actualCounts.Count -eq 1) { [int]$actualCounts[0] } else { -1 }
    }
    $metricArgs = @()
    foreach ($label in @('baseline','candidate')) {
        $m = $byLabel[$label]; $r = $m.row
        $metricArgs += Java-Arg ([string]::Join(',', @($r.fixture, $r.input_fingerprint, $r.rules_fingerprint, 'fixed-freeflow', $seed, $r.iteration_budget, $HeapMb, $r.required_jobs, $m.served, $m.violations, $r.vehicles, $m.cost.ToString([Globalization.CultureInfo]::InvariantCulture), $m.time.ToString([Globalization.CultureInfo]::InvariantCulture), $m.memory)))
    }
    $comparison = @(Run-Java @('-Xms32m','-Xmx64m','-XX:ActiveProcessorCount=1','-cp',(Java-Arg $paths['candidate']),'hagrid.core.routing.benchmark.RoutingComparison', $metricArgs[0], $metricArgs[1], [string]$byLabel.baseline.actual_iterations, [string]$byLabel.candidate.actual_iterations) "compare-$seed" | Where-Object { $_ -like 'COMPARISON,*' })
    if ($comparison.Count -ne 1) { throw 'Comparison result missing.' }
    $a=$byLabel.baseline; $b=$byLabel.candidate
    $classification = if ($comparison[0].StartsWith('COMPARISON,false,')) { $comparison[0].Substring(11) } elseif ($identicalSource) { 'identischer Stand; Messstreuung' } else { $comparison[0].Substring(11) }
    $report += '| ' + $seed + ' | ' + [Math]::Round($a.time,2) + ' | ' + [Math]::Round($b.time,2) + ' | ' + $a.cost + ' | ' + $b.cost + ' | ' + $a.served + '/' + $b.served + ' | ' + $a.violations + '/' + $b.violations + ' | ' + [Math]::Round($a.memory/1MB,2) + '/' + [Math]::Round($b.memory/1MB,2) + ' | ' + $classification + ' |'
}
$config.status='COMPLETE'
$report += @('', '## Streuung und Prozessspeicher', '', '| Seed | Variante | Zeit min–max ms | Beobachteter Prozessspeicher max MiB |', '|---|---|---:|---:|')
foreach ($seed in $Seeds) { foreach ($label in @('baseline','candidate')) {
    $rows = @($measurements | Where-Object { $_.variant -eq $label -and [int]$_.seed -eq $seed })
    $times = @($rows | ForEach-Object { [double]::Parse($_.milliseconds, [Globalization.CultureInfo]::InvariantCulture) })
    $range = $times | Measure-Object -Minimum -Maximum
    $workingSet = ($rows | Measure-Object -Property observed_process_working_set_bytes -Maximum).Maximum
    $report += '| ' + $seed + ' | ' + $label + ' | ' + [Math]::Round($range.Minimum,2) + '–' + [Math]::Round($range.Maximum,2) + ' | ' + [Math]::Round($workingSet/1MB,2) + ' |'
} }
$report += @('', '## Tatsächlich ausgeführte Iterationen', '', '| Seed | Alt | Neu |', '|---|---:|---:|')
foreach ($seed in $Seeds) {
    $before = @($measurements | Where-Object { $_.variant -eq 'baseline' -and [int]$_.seed -eq $seed })
    $after = @($measurements | Where-Object { $_.variant -eq 'candidate' -and [int]$_.seed -eq $seed })
    $report += '| ' + $seed + ' | ' + (($before.actual_iterations | Select-Object -Unique) -join ',') + ' | ' + (($after.actual_iterations | Select-Object -Unique) -join ',') + ' |'
}
$report += @('', 'Hintergrundlast: paralleler Valhalla-Lauf. Keine allgemeine Beschleunigungsaussage aus diesem Prüfpunkt; Heap und Prozessspeicher sind getrennte Messgrößen. Abweichende tatsächliche Iterationen schließen eine Verbesserungsaussage aus.')
$config.observed_process_working_set_bytes = $resourceObservations
$config.finished_at=[DateTimeOffset]::Now.ToString('o')
[IO.File]::WriteAllText((Join-Path $output 'comparison.md'), ($report -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $output 'manifest.json'), ($config | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output "Checkpoint complete: $output"
