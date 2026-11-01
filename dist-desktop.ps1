# Desktop distribution packager for Windows
# Packages: remote-control-desktop.jar + start_desktop.bat into a zip for GitHub releases

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$jarPath = Join-Path $scriptDir "desktop-controller/build/libs/remote-control-desktop.jar"
$batPath = Join-Path $scriptDir "start_desktop.bat"
$distDir = Join-Path $scriptDir "dist/desktop"
$zipPath = Join-Path $scriptDir "dist/remote-control-desktop-windows.zip"

if (-not (Test-Path $jarPath)) {
    Write-Error "Fat JAR not found at $jarPath. Run: .\gradlew.bat :desktop-controller:fatJar"
}

New-Item -ItemType Directory -Path $distDir -Force | Out-Null

Copy-Item -Path $jarPath -Destination $distDir -Force
Copy-Item -Path $batPath -Destination $distDir -Force

# Remove old zip if exists
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

Compress-Archive -Path "$distDir/*" -DestinationPath $zipPath

Write-Host "Desktop distribution created at: $zipPath"
Write-Host "Contents:"
Get-ChildItem -Path $distDir | ForEach-Object { Write-Host "  $($_.Name)" }
