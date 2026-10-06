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
    [string]$Version = "0.97.1"
)

$ErrorActionPreference = "Stop"

$repo = "https://github.com/charmbracelet/crush"
$asset = "crush_${Version}_Android_arm64.tar.gz"
$url = "$repo/releases/download/v$Version/$asset"

$root = Split-Path -Parent $PSScriptRoot
$destDir = Join-Path $root "app/src/main/jniLibs/arm64-v8a"
$destFile = Join-Path $destDir "libcrush.so"

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
