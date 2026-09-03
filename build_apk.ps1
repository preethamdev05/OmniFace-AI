# OmniFace AI - Windows APK Build Script
param (
    [Parameter()]
    [ValidateSet("debug", "release")]
    [string]$BuildType = "debug"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "OMNIFACE AI APK BUILD ($($BuildType.ToUpper()))" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$WorkspaceDir = $PSScriptRoot
if (-not $WorkspaceDir) { $WorkspaceDir = (Get-Location).Path }

# Run setup to ensure SDK, Java, and PATH are ready
& "$WorkspaceDir\setup_windows.ps1"

# Prepare asset directory
$AssetsDir = Join-Path $WorkspaceDir "app\src\main\assets"
if (-not (Test-Path $AssetsDir)) {
    New-Item -ItemType Directory -Path $AssetsDir -Force | Out-Null
}

$ClassLabelsSource = Join-Path $WorkspaceDir "models\class_labels.json"
if (Test-Path $ClassLabelsSource) {
    Copy-Item -Path $ClassLabelsSource -Destination (Join-Path $AssetsDir "class_labels.json") -Force
}

$GradleTask = if ($BuildType -eq "release") { "assembleRelease" } else { "assembleDebug" }

Write-Host "`n[+] Running Gradle Task: $GradleTask..." -ForegroundColor Yellow
$GradleBat = Join-Path $WorkspaceDir "gradlew.bat"

& $GradleBat $GradleTask --build-cache --parallel

$ApkRelativePath = if ($BuildType -eq "release") {
    "app\build\outputs\apk\release\app-release.apk"
} else {
    "app\build\outputs\apk\debug\app-debug.apk"
}

$ApkFullPath = Join-Path $WorkspaceDir $ApkRelativePath
$OutputApk = Join-Path $WorkspaceDir "OmniFace-AI.apk"

if (Test-Path $ApkFullPath) {
    Copy-Item -Path $ApkFullPath -Destination $OutputApk -Force
    $ApkItem = Get-Item $OutputApk
    $ApkSizeMb = [math]::Round($ApkItem.Length / 1MB, 2)
    
    Write-Host "`n==========================================================" -ForegroundColor Green
    Write-Host "OMNIFACE AI APK READY: $OutputApk" -ForegroundColor Green
    Write-Host "Size: $ApkSizeMb MB" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
} else {
    Write-Host "`n[!] APK build output not found at: $ApkFullPath" -ForegroundColor Red
}
