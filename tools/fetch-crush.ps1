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
    # Default tracks the bundled engine (libcrush.so in jniLibs). Running this
    # script as-is on a missing binary MUST NOT silently downgrade the app to an
    # older engine - the default advanced to 0.98.1 when the binary did.
    [string]$Version = "0.98.1",
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
    [string]$ExpectedSha256 = $(if ($Version -eq "0.98.1") {
        "88f42f9302cbf72cd268d05b52395266acecd9b44afa4a1c4dec8c6cf0879ab3"
    } elseif ($Version -eq "0.97.1") {
        "5b76ea03ee1382c6bcb91c3e56ee683a59abf19b678ab8c3d2dcfeb969f70711"
    } else { "" })
)

$ErrorActionPreference = "Stop"

$repo = "https://github.com/charmbracelet/crush"
$asset = "crush_${Version}_Android_arm64.tar.gz"
$url = "$repo/releases/download/v$Version/$asset"

$root = Split-Path -Parent $PSScriptRoot
$destDir = Join-Path $root "app/src/main/jniLibs/arm64-v8a"
$destFile = Join-Path $destDir "libcrush.so"

# FAIL CLOSED on an unpinned version.
#
# A version with no recorded hash would otherwise be fetched and trusted on
# structure alone, which is the exact gap this parameter exists to close. The
# alternative - warn and continue - means the one time somebody adds a version
# casually is the one time the guarantee silently disappears.
if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') {
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

    Write-Host "Extracting ..."
    tar -xzf $tarball -C $tmp

    $binary = Get-ChildItem -Path $tmp -Filter "crush" -Recurse -File |
        Where-Object { $_.DirectoryName -like "*Android_arm64*" } |
        Select-Object -First 1

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
    Write-Host "Verify it on device with: ./gradlew installDebug"
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
