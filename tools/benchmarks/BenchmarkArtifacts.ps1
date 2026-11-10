# Publish completed benchmark evidence without replacing another writer's output.
function Publish-HagridBenchmarkJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Value
    )
    $target = [IO.Path]::GetFullPath($Path)
    $temporary = Join-Path ([IO.Path]::GetDirectoryName($target)) (
        '.' + [IO.Path]::GetFileName($target) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $stream = $null
    $owned = $false
    try {
        $stream = [IO.FileStream]::new($temporary, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None)
        $owned = $true
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 9))
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
        $stream.Dispose()
        $stream = $null
        [IO.File]::Move($temporary, $target, $false)
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
        if ($owned -and [IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
