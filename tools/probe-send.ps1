param(
    [int]$Port = 0,
    [string]$Prompt = "hey",
    [int]$TimeoutSec = 90
)

# Backend send probe.
#
# REDACTION IS THE POINT OF THIS SCRIPT.
#
# GET /v1/workspaces and GET /v1/workspaces/{id}/config both embed the provider
# key in PLAINTEXT inside the config object:
#     config.providers.opencode-zen.api_key
# Dumping either response to a terminal has already leaked the real credential
# into a transcript twice on this project. So every response body is passed
# through Invoke-Safe below, which strips any api_key-ish field before anything
# is printed or written to disk.
#
# It also deliberately creates a throwaway workspace pointed at the SAME
# data_dir as the app, so Crush reuses the provider key it already has in
# crush.json. That keeps the key out of this script entirely - it never has to
# be read, passed, or displayed.

$ErrorActionPreference = 'Stop'

function Invoke-Safe {
    param(
        [string]$Method = 'GET',
        [string]$Uri,
        [string]$Body,
        [switch]$Quiet
    )
    $args = @{ Uri = $Uri; Method = $Method; TimeoutSec = 60; UseBasicParsing = $true }
    if ($Body) {
        $args.Body = $Body
        $args.ContentType = 'application/json'
    }
    $raw = (Invoke-WebRequest @args).Content
    if ($Quiet) { return $null }
    try {
        $o = $raw | ConvertFrom-Json
    } catch {
        return $raw
    }
    # Strip credentials at every depth before this ever reaches the console.
    if ($o -is [array]) {
        foreach ($i in $o) { Protect-Object $i }
    } else {
        Protect-Object $o
    }
    return $o
}

function Protect-Object {
    param($Node)
    if ($null -eq $Node) { return }
    if ($Node -is [System.Collections.IEnumerable] -and $Node -isnot [string]) {
        foreach ($i in $Node) { Protect-Object $i }
        return
    }
    if ($Node -is [pscustomobject]) {
        foreach ($p in @($Node.PSObject.Properties)) {
            if ($p.Name -match '(?i)api_?key|token|secret|password|authorization') {
                # Replace, never blank-and-hope.
                if ($p.Value -is [string]) { $p.Value = '***REDACTED***' }
                else { $p.Value = '***REDACTED***' }
            } elseif ($p.Value -is [pscustomobject] -or ($p.Value -is [System.Collections.IEnumerable] -and $p.Value -isnot [string])) {
                Protect-Object $p.Value
            }
        }
    }
}

if ($Port -le 0) { throw 'Pass -Port (the engine port from /proc/net/tcp).' }
$base = "http://127.0.0.1:$Port/v1"

# Unique path so we never collide with the app's own workspace.
$wsPath = "/data/data/com.opencode.chat/files/probe_send"
$wsId = [guid]::NewGuid().ToString()
$clientId = [guid]::NewGuid().ToString()

Write-Host "== create workspace (data_dir shared with app so the existing key is reused) =="
$create = @{
    id         = $wsId
    path       = $wsPath
    data_dir   = "/data/data/com.opencode.chat/files/crush/state"
    yolo       = $true
    client_id  = $clientId
} | ConvertTo-Json -Compress

$ws = Invoke-Safe -Method POST -Uri "$base/workspaces" -Body $create
# Crush may return its own id rather than echoing ours.
$actual = if ($ws.id) { $ws.id } else { $wsId }
Write-Host "   requested=$wsId"
Write-Host "   actual   =$actual"
if ($actual -ne $wsId) {
    Write-Host "   NOTE: Crush assigned a different id; using the returned one."
}

Write-Host "== permissions/skip =="
try {
    Invoke-Safe -Method POST -Uri "$base/workspaces/$actual/permissions/skip" `
        -Body (@{ skip = $true } | ConvertTo-Json -Compress) -Quiet | Out-Null
    Write-Host "   ok"
} catch { Write-Host "   skipped: $($_.Exception.Message)" }

Write-Host "== create session =="
$sess = Invoke-Safe -Method POST -Uri "$base/workspaces/$actual/sessions" `
    -Body (@{ title = 'probe' } | ConvertTo-Json -Compress)
$sid = if ($sess.id) { $sess.id } elseif ($sess.session.id) { $sess.session.id } else { $null }
Write-Host "   session=$sid"

Write-Host "== agent/init =="
try {
    $init = Invoke-Safe -Method POST -Uri "$base/workspaces/$actual/agent/init" -Body '{}'
    Write-Host "   is_ready=$($init.is_ready)"
    if ($init.notes) { Write-Host "   notes=$($init.notes -join '; ')" }
} catch {
    Write-Host "   init returned $($_.Exception.Message)"
}

# Readiness is observable on GET /agent, not on the init response. Verified on
# device: init returned 200 with an EMPTY body while the agent was not ready,
# which previously read as "init succeeded" and hid the real failure.
Write-Host "== agent status (readiness) =="
try {
    $ag = Invoke-Safe -Uri "$base/workspaces/$actual/agent"
    Write-Host "   is_ready=$($ag.is_ready)  id=$($ag.id)"
} catch {
    Write-Host "   agent status failed: $($_.Exception.Message)"
}

$runId = [guid]::NewGuid().ToString()
Write-Host "== send: '$Prompt' =="
# Endpoint is POST /workspaces/{id}/agent with {session_id, prompt, run_id}.
# There is no /agent/message route - the spec lists exactly one "send message"
# endpoint and an earlier version of this script guessed a path and got a 404
# that looked like a product bug.
$msg = @{
    session_id = $sid
    prompt     = $Prompt
    run_id     = $runId
} | ConvertTo-Json -Depth 5 -Compress

try {
    Invoke-Safe -Method POST -Uri "$base/workspaces/$actual/agent" -Body $msg -Quiet | Out-Null
    Write-Host "   accepted (202), streaming..."
} catch {
    Write-Host "   SEND FAILED: $($_.Exception.Message)"
    exit 1
}

Write-Host "== stream (max ${TimeoutSec}s) =="
$runIdPat = $runId
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$assistantText = ''
$finished = $false
$finalRunId = ''

# Sync-over-async: we need the SSE body incrementally, and Invoke-WebRequest
# buffers it. HttpClient with ResponseHeadersRead is the simplest way to stream.
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
try {
    $req = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        "$base/workspaces/$actual/events?client_id=$clientId"
    )
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $resp.EnsureSuccessStatusCode() | Out-Null
    $stream = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = New-Object System.IO.StreamReader($stream)

    while ((Get-Date) -lt $deadline) {
        if (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if ($line -and $line.StartsWith('data:')) {
                $payload = $line.Substring(5).Trim()
                if ($payload) {
                    try {
                        $ev = $payload | ConvertFrom-Json
                        # The envelope is TWO levels deep:
                        #   {"type":..,"payload":{"type":..,"payload":{..}}}
                        # Reading ev.data was the bug that made this probe report
                        # assistantChars=0 next to run_complete=True. The app's own
                        # CrushEventStream.decode() unwraps both levels correctly;
                        # this script did not.
                        $inner = $ev.payload.payload
                        $t = ''
                        if ($ev.type -eq 'message' -and $inner) {
                            if ($inner.parts) {
                                $t = ($inner.parts |
                                       Where-Object { $_.type -eq 'text' } |
                                       ForEach-Object { $_.data.text }) -join ''
                            }
                            if ($inner.role -eq 'assistant' -and $t) { $assistantText = $t }
                        }
                        if ($ev.type -eq 'run_complete') {
                            $finished = $true
                            # run_complete also carries the final text, which is a
                            # more reliable source than replayed message frames.
                            if ($inner.text) { $assistantText = [string]$inner.text }
                            if ($inner.run_id) { $finalRunId = [string]$inner.run_id }
                            break
                        }
                    } catch { }
                }
            }
        } else {
            Start-Sleep -Milliseconds 200
        }
    }
} finally {
    $client.Dispose()
}

Write-Host ""
Write-Host "run_complete   = $finished"
Write-Host "run_id match   = $($finalRunId -eq $runId)"
Write-Host "assistantChars = $($assistantText.Length)"
if ($assistantText.Length -gt 0) {
    Write-Host "assistantText  ="
    Write-Host $assistantText
} else {
    Write-Host "NO ASSISTANT TEXT CAPTURED - parser regression, not a backend failure."
    Write-Host "Check the envelope depth: {type,payload:{type,payload:{..}}}"
}
if (-not $finished) { Write-Host "(stream timed out; a partial reply above is still real output)" }
