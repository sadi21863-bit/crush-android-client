$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "bbf8ca69"

$log = (& $adb -s $dev logcat -d -s CrushEngine:* 2>&1) -join "`n"
$m = [regex]::Match($log, "port=(\d+)")
if (-not $m.Success) { Write-Output "no engine"; exit 1 }
$port = $m.Groups[1].Value
$base = "http://127.0.0.1:$port"
Write-Output "port=$port"

$ws = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
$body = '{"id":"' + $ws + '","path":"/data/local/tmp","yolo":true}'
$body | Set-Content "$env:TEMP\w.json" -Encoding ascii -NoNewline
& $adb -s $dev push "$env:TEMP\w.json" /data/local/tmp/w.json 2>&1 | Out-Null

Write-Output ""
Write-Output "=== create workspace ==="
& $adb -s $dev shell "curl -s -m 8 -X POST -H 'Content-Type: application/json' -d @/data/local/tmp/w.json $base/v1/workspaces | head -c 300" 2>&1 | Out-String

Write-Output ""
Write-Output "=== list workspaces ==="
& $adb -s $dev shell "curl -s -m 8 $base/v1/workspaces | head -c 200" 2>&1 | Out-String

Write-Output ""
Write-Output "=== providers (first 1200 chars) ==="
& $adb -s $dev shell "curl -s -m 15 $base/v1/workspaces/$ws/providers | head -c 1200" 2>&1 | Out-String