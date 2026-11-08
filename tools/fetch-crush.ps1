# Fetches the Crush engine binary into the app.
#
# Crush (github.com/charmbracelet/crush) is a statically linked aarch64 Go
# binary (CGO_ENABLED=0, zero DT_NEEDED entries). We place it in
# app/src/main/jniLibs/arm64-v8a/libcrush.so so that Android extracts it to
# nativeLibraryDir at install time, and we can then exec() it.
#
# It must NOT live in assets/ copied to filesDir: Android 10+ blocks execve()
# from the app home directory (SELinux W^X). nativeLibraryDir is read-only
# /data/app and remains permitted.
#
# It must also NOT be renamed to a non-lib*.so name, or the installer will not
# extract it and exec() will fail.

param(
    # Default tracks the engine of record. Running this script as-is on a
    # missing binary MUST NOT silently downgrade the app to an older engine -
    # the default advanced to 0.98.1 with the binary in 2026-10-18, and to
    # 0.98.2 on 2026-10-31 once the upstream release checksum was recorded.
    # One honest caveat: the libcrush.so currently in jniLibs is a locally
    # instrumented "0.98.2+dirty" build of that upstream release (landed
    # in-tree at d50f26e, 2026-10-29); a fresh-clone fetch reconstitutes the
    # clean upstream 0.98.2 engine - same version, minus the instrumentation.
    #
    # 2026-11-09 ENGINE-V0100-LADDER (branch engine-v0100-ladder; per
    # docs/research-crush-v0.100.0-2026-11-08.md section 6): the default
    # advances to 0.100.0 on THIS branch only - main keeps 0.99.0 until the
    # owner's go. What this step changes and why:
    # - No SHA-256 pin is recorded in this script yet for 0.100.0, so the
    #   expected hash is resolved AT FETCH TIME from the release's own
    #   checksums.txt (see the FAIL CLOSED block below for what that does and
    #   does not guarantee; the recorded 2026-11-09 cross-check lives in the
    #   version pin chain).
    # - The artifact is the OFFICIAL release tarball
    #   crush_0.100.0_Android_arm64.tar.gz (research doc section 3) - the
    #   per-platform GOOS=android/GOARCH=arm64 shape (v0.99.0 shipped the
    #   identically named asset, so the ladder fetches the official binary
    #   instead of repackaging any desktop binary). Do not confuse the
    #   release's "apk" assets with anything Android: they are Alpine
    #   (Linux) packages.
    # - The tool catalogue CHANGED (sub-agents grew agent_dispatch/
    #   agent_send/agent_status/agent_stop): after the fetch, the FRESH
    #   libcrush.so must be strings-confirmed against CHAT_DISABLED_TOOLS and
    #   the ratchet snapshot - any further NEW ids found get added to both
    #   BEFORE the device gate.
    [string]$Version = "0.100.0",
    # Expected SHA-256 of the release TARBALL, not the extracted binary.
    #
    # WHY THIS EXISTS
    #
    # The script verified only that the download was a 64-bit AArch64 PIE. That
    # says nothing about WHERE it came from: anyone able to serve the download -
    # a compromised mirror, a hijacked release asset, a poisoned proxy - could
    # ship a perfectly well-formed backdoor and the check would pass. The app then
    # exec()s it, so this is arbitrary code execution on the device, not a
    # cosmetic supply-chain concern.
    #
    # Upstream publishes checksums.txt and a Sigstore bundle with each release.
    # Sigstore/cosign verification is the stronger check; this pin is the floor.
    # The value is read from the SIGNED release's checksums.txt for the exact
    # asset, not from a local download of that same download - pinning against a
    # hash derived from the thing you are verifying verifies nothing.
    #
    # Recorded 2026-10-17 from
    #   https://github.com/charmbracelet/crush/releases/download/v0.98.1/checksums.txt
    #   88f42f93...879ab3  crush_0.98.1_Android_arm64.tar.gz
    # (Zero-arg runs of THIS script are where the pin matters; the local
    # tarball was byte-verified against the hash below on 2026-10-17 and the
    # extracted libcrush.so hashes 7bbfb0fe... aa052.)
    [string]$ExpectedSha256 = $(if ($Version -eq "0.100.0") {
        # ENGINE-V0100-LADDER (2026-11-09): no recorded pin for this version
        # yet. For an un-pinned run the expected value resolves AT FETCH TIME
        # from the SIGNED release's checksums.txt (see the FAIL CLOSED block
        # below); an explicit -ExpectedSha256 still wins for offline runs.
        #
        # Recorded for the record (2026-11-09, read from the release's
        # checksums.txt and cross-checked against the tarball fetched that
        # same day - both hash to the same digest):
        #   cdb15f23393c7e334b059a37d2c41a12fcb5f4d02d6e940795b7707e4b0afeaa
        #   crush_0.100.0_Android_arm64.tar.gz
        ""
    } elseif ($Version -eq "0.99.0") {
        # Recorded 2026-10-31 from the SIGNED release's checksums.txt
        # (v0.99.0, published 2026-10-29), asset crush_0.99.0_Android_arm64.tar.gz.
        # This is the engine of record since the 2026-11-01 promotion: the
        # v099-experiment lane proved it end to end (UDS boot, Go model
        # access, turns, file-diff) and the Go session-header support plus
        # the corrected model wiring require it.
        "d18cd1f961a4c587f26a50aad8ef1fed38ad4362bb6237807e57cda1c170f78c"
    } elseif ($Version -eq "0.98.2") {
        # Recorded 2026-10-31 from the SIGNED release's checksums.txt (v0.98.2,
        # published 2026-10-23), for asset crush_0.98.2_Android_arm64.tar.gz:
        #   5224fab3...9b08  crush_0.98.2_Android_arm64.tar.gz
        # Cross-checked: the downloaded checksums.txt hashed to the digest the
        # GitHub release API attests for it (343f90ac...4a688).
        "5224fab3b8eea3555b7ba3d8b9c6f3558fa60d8bafe018f77ac61520c7a49b08"
    } elseif ($Version -eq "0.98.1") {
        "88f42f9302cbf72cd268d05b52395266acecd9b44afa4a1c4dec8c6cf0879ab3"
    } elseif ($Version -eq "0.97.1") {
        "5b76ea03ee1382c6bcb91c3e56ee683a59abf19b678ab8c3d2dcfeb969f70711"
    } else { "" })
)

$ErrorActionPreference = "Stop"

$repo = "https://github.com/charmbracelet/crush"
$asset = "crush_${Version}_Android_arm64.tar.gz"
$url = "$repo/releases/download/v$Version/$asset"
# The release's own checksums.txt - the 0.100.0 ladder's verification source
# when no recorded pin is available for $Version.
$checksumsUrl = "$repo/releases/download/v$Version/checksums.txt"

$root = Split-Path -Parent $PSScriptRoot
$destDir = Join-Path $root "app/src/main/jniLibs/arm64-v8a"
$destFile = Join-Path $destDir "libcrush.so"

# FAIL CLOSED on an unpinned version.
#
# A version with no recorded hash would otherwise be fetched and trusted on
# structure alone, which is the exact gap this parameter exists to close. The
# alternative - warn and continue - means the one time somebody adds a version
# casually is the one time the guarantee silently disappears.
#
# 0.100.0 LADDER EXCEPTION (2026-11-09, branch engine-v0100-ladder): v0.100.0
# has no recorded pin yet, so for THAT version only - and only when no
# explicit -ExpectedSha256 was passed - the expected hash is resolved at fetch
# time from the release's OWN checksums.txt. Honest caveat, in the spirit of
# the WHY above: both downloads travel the same release channel, so this
# proves the tarball matches the checksums.txt served NEXT TO IT, not
# provenance end to end - it is the FLOOR, the exact weakness the recorded
# pins close. The next rung, after the owner's first verified fetch, is to
# promote that cross-checked hash into the pin table, where it stops
# travelling with the binary.
$verifyViaChecksumsTxt = $false

if ($ExpectedSha256 -match '^[0-9a-fA-F]{64}$') {
    # A recorded or explicitly passed pin: compared as-is after download
    # (pre-ladder shape).
}
elseif ($Version -eq "0.100.0" -and
        -not $PSBoundParameters.ContainsKey("ExpectedSha256") -and
        $ExpectedSha256 -eq "") {
    $verifyViaChecksumsTxt = $true
}
else {
    throw @"
No SHA-256 pin for Crush $Version.

Refusing to fetch. Structure alone cannot establish provenance: a hijacked
release asset or a poisoned mirror would produce a well-formed AArch64 PIE that
passes every other check here, and the app then exec()s it.

To pin a new version, read the hash from the SIGNED release and pass it in:
  ./tools/fetch-crush.ps1 -Version <v> -ExpectedSha256 <sha>
"@
}

if (Test-Path $destFile) {
    $existing = (Get-Item $destFile).Length
    Write-Host "libcrush.so already present ($([math]::Round($existing/1MB,1)) MB). Delete it to re-fetch."
    exit 0
}

New-Item -ItemType Directory -Force -Path $destDir | Out-Null

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "crush-fetch-$Version"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$tarball = Join-Path $tmp $asset

try {
    Write-Host "Downloading $asset ..."
    $ProgressPreference = "SilentlyContinue"
    Invoke-WebRequest -Uri $url -OutFile $tarball -UseBasicParsing
    Write-Host ("Downloaded {0:N1} MB" -f ((Get-Item $tarball).Length / 1MB))

    # PROVENANCE, before anything is extracted or executed.
    #
    # Deliberately before extraction: a tarball is not inspected for its contents
    # before the checksum, so verifying afterwards would mean a malicious archive
    # had already been unpacked into the temp directory.
    $actual = (Get-FileHash $tarball -Algorithm SHA256).Hash.ToLowerInvariant()

    if ($verifyViaChecksumsTxt) {
        # The 0.100.0 ladder path: the EXACT asset line out of the release's
        # checksums.txt is the only accepted expected value. Deliberately
        # strict: the line must be the canonical sha256sum shape (64 hex + a
        # separator + THIS asset name at line end); a missing or unparseable
        # line fails closed exactly like a mismatch - nothing here is
        # verified by absence.
        $checksumsPath = Join-Path $tmp "checksums.txt"
        Write-Host "Verifying against the release's checksums.txt ..."
        Invoke-WebRequest -Uri $checksumsUrl -OutFile $checksumsPath -UseBasicParsing
        $pattern = "^\s*([0-9a-fA-F]{64})\s+\*?" + [regex]::Escape($asset) + "\s*$"
        $line = Select-String -Path $checksumsPath -Pattern $pattern |
            Select-Object -First 1
        if (-not $line) {
            throw @"
NO CHECKSUM LINE for $asset in the release's checksums.txt.

Nothing here is verified by absence: without the asset's own line the
fingerprint contract cannot run, so the download is refused.
"@
        }
        $expected = $line.Matches[0].Groups[1].Value
        if ($actual -ne $expected.ToLowerInvariant()) {
            throw @"
SHA-256 MISMATCH for $asset (against the release's checksums.txt)

  expected $expected
  actual   $actual

The download does not match the release's own checksums.txt. Delete the temp
copy and do NOT retry until the cause is understood.
"@
        }
        Write-Host "SHA-256 verified against the release's checksums.txt."
    }
    else {
        if ($actual -ne $ExpectedSha256.ToLowerInvariant()) {
            throw @"
SHA-256 MISMATCH for $asset

  expected $ExpectedSha256
  actual   $actual

The download is not the artifact Charm signed. Delete the temp copy and do NOT
retry until the cause is understood.
"@
        }
        Write-Host "SHA-256 verified against the pinned release hash."
    }

    Write-Host "Extracting ..."

    # Inspected 2026-11-09: crush_0.100.0_Android_arm64.tar.gz unpacks to
    # crush_0.100.0_Android_arm64/{LICENSE.md,README.md,completions/,manpages/,
    # crush}, so the Android_arm64 directory filter below matches the official
    # artifact directly (same packed shape the 0.99.0 asset carried). The
    # fallback only covers a hypothetical future flat single-binary tarball -
    # the AArch64 PIE checks below keep the fallback exactly as strict.
    tar -xzf $tarball -C $tmp

    $crushes = Get-ChildItem -Path $tmp -Filter "crush" -Recurse -File
    $binary = $crushes |
        Where-Object { $_.DirectoryName -like "*Android_arm64*" } |
        Select-Object -First 1
    if (-not $binary) { $binary = $crushes | Select-Object -First 1 }

    if (-not $binary) { throw "Could not find the crush binary in the extracted archive." }

    Copy-Item $binary.FullName $destFile -Force

    # Verify it is a 64-bit AArch64 PIE before we ship it.
    $fs = [System.IO.File]::OpenRead($destFile)
    $br = New-Object System.IO.BinaryReader($fs)
    $fs.Position = 0x10
    $eType = $br.ReadUInt16()
    $eMachine = $br.ReadUInt16()
    $br.Close(); $fs.Close()

    if ($eType -ne 3) { throw "Not a PIE (e_type=$eType, expected 3)." }
    if ($eMachine -ne 183) { throw "Not AArch64 (e_machine=$eMachine, expected 183)." }

    Write-Host ""
    Write-Host "OK: $destFile"
    Write-Host ("    {0:N1} MB, AArch64 PIE, statically linked" -f ((Get-Item $destFile).Length / 1MB))
    Write-Host ""
    Write-Host "v0.100.0 ladder reminder (branch engine-v0100-ladder): BEFORE the"
    Write-Host "device gate, strings-confirm this fresh libcrush.so's catalogue"
    Write-Host "(agent_dispatch/agent_send/agent_status/agent_stop must be present;"
    Write-Host "any further NEW ids land in CHAT_DISABLED_TOOLS + the ratchet"
    Write-Host "snapshot first). Device rails and the owner go decision are laid out"
    Write-Host "in docs/research-crush-v0.100.0-2026-11-08.md section 6."
    Write-Host ""
    Write-Host "Verify it on device with: ./gradlew installDebug"
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
