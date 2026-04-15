# download-mpv.ps1 — Downloads portable mpv for YancoTV
# Run: powershell -ExecutionPolicy Bypass -File scripts/download-mpv.ps1

$ErrorActionPreference = "Stop"

$mpvDir = Join-Path $PSScriptRoot ".." "mpv"
$mpvExe = Join-Path $mpvDir "mpv.exe"

if (Test-Path $mpvExe) {
    Write-Host "mpv already exists at $mpvExe" -ForegroundColor Green
    exit 0
}

Write-Host "Fetching latest mpv release info..." -ForegroundColor Cyan
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest"
$asset = $release.assets | Where-Object { $_.name -match "^mpv-x86_64-\d" -and $_.name -like "*.7z" -and $_.name -notlike "*-v3-*" -and $_.name -notlike "*-dev-*" } | Select-Object -First 1

if (-not $asset) {
    Write-Error "Could not find mpv download in latest release"
    exit 1
}

Write-Host "Downloading $($asset.name) ($([math]::Round($asset.size / 1MB, 1)) MB)..." -ForegroundColor Cyan
$tempFile = Join-Path $env:TEMP "mpv-temp.7z"
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $tempFile

Write-Host "Extracting..." -ForegroundColor Cyan
$tempDir = Join-Path $env:TEMP "mpv-extract"
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }

# Try 7-Zip first, fall back to tar (Windows 10+)
$7zPath = "C:\Program Files\7-Zip\7z.exe"
if (Test-Path $7zPath) {
    & $7zPath x $tempFile -o"$tempDir" -y | Out-Null
} else {
    Write-Error "7-Zip is required to extract mpv. Install it from https://7-zip.org"
    exit 1
}

# Create mpv directory and copy needed files
if (-not (Test-Path $mpvDir)) { New-Item -ItemType Directory -Path $mpvDir | Out-Null }

Copy-Item (Join-Path $tempDir "mpv.exe") $mpvDir -Force
$d3d = Join-Path $tempDir "d3dcompiler_43.dll"
if (Test-Path $d3d) { Copy-Item $d3d $mpvDir -Force }

# Cleanup
Remove-Item $tempFile -Force -ErrorAction SilentlyContinue
Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "mpv installed to $mpvDir" -ForegroundColor Green
Write-Host "You can now run: pnpm dev" -ForegroundColor Green
