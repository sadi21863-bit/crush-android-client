#Requires -Version 5.1
<#
.SYNOPSIS
  Scans tracked files for credential-shaped tokens.

.DESCRIPTION
  Prints file:line, the token's family and a masked preview. It never prints a
  full token. Exits 1 when an unapproved hit is found, so it can be used as a
  release pre-flight.

  The allowlist is by SHA-256, not by path: two test files contain deliberately
  synthetic credentials used to prove the log redactor. Pinning the exact
  values means a real key added to those files is still caught.

  Scope: git-tracked text files only. This is not a history scanner and not a
  replacement for GitHub secret scanning.

.EXAMPLE
  pwsh -File tools/check-secrets.ps1
#>
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$patterns = [ordered]@{
    'opencode'  = 'oc_sk_[A-Za-z0-9_\-]{20,}'
    'groq'      = 'gsk_[A-Za-z0-9]{20,}'
    'openrouter' = 'sk-or-v1-[A-Za-z0-9]{20,}'
    'openai'    = 'sk-proj-[A-Za-z0-9_\-]{20,}'
    'google'    = 'AIza[0-9A-Za-z_\-]{30,}'
}

# SHA-256 of the synthetic fixtures in KeyMaskTest, AppLogTest and
# DiagnosticsReportTest (the last one is the V2-11 report-masking test).
$allowlisted = @(
    '586b7a5cc995403422eb9fac99238e7429f5b1175b582418bce1ba2367158670',
    '94349e7be9c26b33ece4351889056d9721bc0f4cb7158ce4efa266908c7e7bc6',
    '34ec1feea5c46780745839d3763c522a04314e729aa2156df42d9978e0ad1a11'
)

$binaryExtensions = @(
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.ico', '.so', '.jar', '.apk',
    '.aab', '.zip', '.gz', '.keystore', '.jks', '.pdf', '.woff', '.woff2'
)

$sha = [Security.Cryptography.SHA256]::Create()
$hits = 0
$allowHits = 0

Push-Location $root
try {
    $raw = (& git ls-files -z) -join ''
    if (-not $raw) {
        throw 'git ls-files returned nothing; run this from the repository'
    }
    $files = $raw.Split([char]0) | Where-Object { $_ }

    foreach ($relative in $files) {
        $extension = [IO.Path]::GetExtension($relative).ToLowerInvariant()
        if ($binaryExtensions -contains $extension) { continue }

        $path = Join-Path $root $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }

        $lineNumber = 0
        foreach ($line in [IO.File]::ReadLines($path)) {
            $lineNumber++
            foreach ($family in $patterns.Keys) {
                foreach ($match in [regex]::Matches($line, $patterns[$family])) {
                    $bytes = [Text.Encoding]::UTF8.GetBytes($match.Value)
                    $hash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()

                    if ($allowlisted -contains $hash) {
                        $allowHits++
                        continue
                    }

                    $preview = $match.Value.Substring(0, [Math]::Min(6, $match.Value.Length))
                    Write-Host ("{0}:{1}  {2}  {3}... ({4} chars)" -f `
                        $relative, $lineNumber, $family, $preview, $match.Value.Length)
                    $hits++
                }
            }
        }
    }

    if ($hits -gt 0) {
        Write-Host ""
        Write-Host "FAIL: $hits credential-shaped token(s) outside the allowlist."
        exit 1
    }

    Write-Host ("PASS: no credential-shaped tokens in {0} tracked files ({1} allowlisted fixture(s))." -f `
        $files.Count, $allowHits)
} finally {
    Pop-Location
}
