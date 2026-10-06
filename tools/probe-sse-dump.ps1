param(
    [int]$Port,
    [string]$Prompt = 'hey',
    [int]$TimeoutSec = 70,
    [string]$DumpDir = 'C:\Users\aditya\AppData\Local\Temp\opencode\sse'
)

# Raw SSE shape dump. Prints the first few event envelopes with any credential
# field redacted, so the payload structure can be read without inventing field
# names. Reuses probe-send.ps1's bootstrap by duplicating only the send+stream.

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port/v1"
if (-not (Test-Path $DumpDir)) { New-Item -ItemType Directory -Path $DumpDir -Force | Out-Null }

function Redact([string]$s) {
    if (-not $s) { return $s }
    # Never let a bearer token or api key reach the console or disk.
    $s = [regex]::Replace($s, '(?i)("(?:api_?key|token|secret|password|authorization)"\s*:\s*")([^"]*)(")', '$1***REDACTED***$3')
    $s = [regex]::Replace($s, 'oc_sk_[A-Za-z0-9_\-]+', 'oc_sk_***REDACTED***')
    return $s
}

$wsId = [guid]::NewGuid().ToString()
$clientId = [guid]::NewGuid().ToString()
$create = @{
    id = $wsId; path = "/data/data/com.opencode.chat/files/probe_dump"
    data_dir = "/data/data/com.opencode.chat/files/crush/state"
    yolo = $true; client_id = $clientId
} | ConvertTo-Json -Compress
$ws = Invoke-RestMethod -Method Post -Uri "$base/workspaces" -Body $create -ContentType 'application/json' -TimeoutSec 60
$actual = if ($ws.id) { $ws.id } else { $wsId }
Write-Host "workspace=$actual"

try { Invoke-RestMethod -Method Post -Uri "$base/workspaces/$actual/permissions/skip" -Body (@{skip=$true}|ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 30 | Out-Null } catch {}
$sess = Invoke-RestMethod -Method Post -Uri "$base/workspaces/$actual/sessions" -Body (@{title='dump'}|ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 60
$sid = if ($sess.id) { $sess.id } else { $sess.session.id }
Write-Host "session=$sid"
try { Invoke-RestMethod -Method Post -Uri "$base/workspaces/$actual/agent/init" -Body '{}' -ContentType 'application/json' -TimeoutSec 60 | Out-Null } catch {}

$runId = [guid]::NewGuid().ToString()
Invoke-RestMethod -Method Post -Uri "$base/workspaces/$actual/agent" -Body (@{session_id=$sid;prompt=$Prompt;run_id=$runId}|ConvertTo-Json -Compress) -ContentType 'application/json' -TimeoutSec 60 | Out-Null
Write-Host "sent, streaming..."

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
try {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$base/workspaces/$actual/events?client_id=$clientId")
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $resp.EnsureSuccessStatusCode() | Out-Null
    $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $n = 0
    $types = New-Object System.Collections.Generic.List[string]
    while ((Get-Date) -lt $deadline) {
        if ($reader.EndOfStream) { Start-Sleep -Milliseconds 150; continue }
        $line = $reader.ReadLine()
        if (-not $line) { continue }
        if ($line.StartsWith('data:')) {
            $payload = $line.Substring(5).Trim()
            if (-not $payload) { continue }
            $n++
            $types.Add($payload)
            if ($n -le 12) {
                Write-Host ""
                Write-Host "----- event $n -----"
                Write-Host (Redact $payload)
            }
            if ($payload -match '"type"\s*:\s*"run_complete"') { Write-Host "`n(run_complete seen)"; break }
        }
    }
    $out = Join-Path $DumpDir "sse-$((Get-Date).ToString('HHmmss')).jsonl"
    ($types | ForEach-Object { Redact $_ }) | Set-Content -Path $out -Encoding UTF8
    Write-Host ""
    Write-Host "total events = $($types.Count)"
    Write-Host "saved(redacted) = $out"
} finally { $client.Dispose() }
