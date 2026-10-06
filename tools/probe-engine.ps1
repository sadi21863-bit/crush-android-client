$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dev = "bbf8ca69"

function Sh([string]$cmd) {
    (& $adb -s $dev shell $cmd 2>&1) -join "`n"
}

function Push([string]$local, [string]$remote) {
    & $adb -s $dev push $local $remote 2>&1 | Out-Null
}

# Current engine port from the app's own log
$log = (& $adb -s $dev logcat -d -s CrushEngine:* 2>&1) -join "`n"
$m = [regex]::Match($log, "port=(\d+)")
if (-not $m.Success) { Write-Output "NO ENGINE RUNNING"; exit 1 }
$port = $m.Groups[1].Value
$base = "http://127.0.0.1:$port"
Write-Output "port=$port"

# Valid UUID required for client_id / workspace id
$ws = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
$app = "/data/user/0/com.opencode.chat/files"

Write-Output ""
Write-Output "### does workspace dir exist? ###"
Write-Output (Sh "ls -d $app/workspace 2>&1")

Write-Output ""
Write-Output "### POST /workspaces  (yolo=true) ###"
$j1 = '{"id":"' + $ws + '","path":"' + $app + '/workspace","data_dir":"' + $app + '/crush/state","yolo":true}'
$j1 | Set-Content "$env:TEMP\p1.json" -Encoding ascii -NoNewline
Push "$env:TEMP\p1.json" /data/local/tmp/p1.json
Write-Output (Sh "curl -s -X POST -H 'Content-Type: application/json' -d @/data/local/tmp/p1.json $base/workspaces")

Write-Output ""
Write-Output "### GET /workspaces/{id}/agent ###"
Write-Output (Sh "curl -s $base/workspaces/$ws/agent")

Write-Output ""
Write-Output "### POST /workspaces/{id}/sessions ###"
$j2 = '{"id":"ses_probe000000001","parent_session_id":"","title":"probe","message_count":0,"prompt_tokens":0,"completion_tokens":0,"summary_message_id":"","cost":0,"created_at":0,"updated_at":0,"is_busy":false,"attached_clients":0}'
$j2 | Set-Content "$env:TEMP\p2.json" -Encoding ascii -NoNewline
Push "$env:TEMP\p2.json" /data/local/tmp/p2.json
Write-Output (Sh "curl -s -X POST -H 'Content-Type: application/json' -d @/data/local/tmp/p2.json $base/workspaces/$ws/sessions")

Write-Output ""
Write-Output "### GET /workspaces/{id}/sessions ###"
Write-Output (Sh "curl -s $base/workspaces/$ws/sessions")

Write-Output ""
Write-Output "### GET /workspaces/{id}/permissions/skip ###"
Write-Output (Sh "curl -s $base/workspaces/$ws/permissions/skip")

Write-Output ""
Write-Output "### SSE /events (5s sample) ###"
Write-Output (Sh "timeout 5 curl -sN '$base/workspaces/$ws/events?client_id=$ws' 2>&1 | head -c 900")