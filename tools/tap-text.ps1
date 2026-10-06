# Taps a UI element by its visible text, locating it via the accessibility
# tree instead of guessing pixel coordinates.
#
# Why this exists: screenshots returned to tooling are downscaled, so
# hand-computed coordinates keep missing. uiautomator dump reports real device
# bounds, which do not need any scaling.

param(
    [Parameter(Mandatory = $true)]
    [string]$Text,

    [string]$Serial = "bbf8ca69"
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Dump until it succeeds; Compose can briefly keep the tree non-idle.
$remote = "/data/local/tmp/uixml.xml"
$local = Join-Path $env:TEMP "uixml.xml"

$dump = $null
for ($i = 0; $i -lt 6; $i++) {
    $dump = (& $adb -s $Serial shell "uiautomator dump --compressed $remote 2>&1") -join " "
    if ($dump -match "dumped to") { break }
    Start-Sleep -Milliseconds 900
}

if ($dump -notmatch "dumped to") {
    Write-Output "DUMP-FAILED: $dump"
    exit 2
}

& $adb -s $Serial pull $remote $local 2>&1 | Out-Null
$xml = Get-Content $local -Raw

# Find the node whose text or content-desc matches, then tap its centre.
$pattern = 'text="' + [regex]::Escape($Text) + '"|content-desc="' + [regex]::Escape($Text) + '"'
$nodes = [regex]::Matches($xml, '<node[^>]*' + $pattern + '[^>]*/?>')

if ($nodes.Count -eq 0) {
    Write-Output "NOT-FOUND: '$Text'"
    $all = [regex]::Matches($xml, 'text="([^"]+)"') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -ne "" } |
        Select-Object -Unique
    Write-Output ("visible texts: " + ($all -join " | "))
    exit 3
}

foreach ($n in $nodes) {
    $b = [regex]::Match($n.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $b.Success) { continue }
    $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
    $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
    $cx = [int](($x1 + $x2) / 2)
    $cy = [int](($y1 + $y2) / 2)
    Write-Output "tapping '$Text' at ($cx,$cy)  bounds=[$x1,$y1][$x2,$y2]"
    & $adb -s $Serial shell input tap $cx $cy 2>&1 | Out-Null
    exit 0
}

Write-Output "NO-BOUNDS for '$Text'"
exit 4