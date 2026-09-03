param (
    [string]$Bucket = "omniface-models",
    [string]$ModelPath = "$PSScriptRoot/../app/src/main/assets/unified_omniface.tflite"
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "🚀 Cloudflare R2 Single Unified Model Upload Tool" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$resolvedPath = Resolve-Path $ModelPath -ErrorAction SilentlyContinue
if (-not $resolvedPath -or -not (Test-Path $resolvedPath)) {
    Write-Error "Unified model file not found at: $ModelPath"
    exit 1
}

$fileItem = Get-Item $resolvedPath
$fileMb = [math]::Round($fileItem.Length / 1MB, 2)
Write-Host "📦 Model Artifact: $($fileItem.FullName)" -ForegroundColor White
Write-Host "📏 Size: $fileMb MB ($($fileItem.Length) bytes)" -ForegroundColor White
Write-Host "☁️ Target Bucket: '$Bucket/unified/unified_omniface.tflite'" -ForegroundColor Yellow

$cdnDir = Join-Path $PSScriptRoot "omniface-model-cdn"
Push-Location $cdnDir
try {
    Write-Host "`n⬆️ Executing: npx wrangler r2 object put `"$Bucket/unified/unified_omniface.tflite`" --file `"$($fileItem.FullName)`" --remote..." -ForegroundColor Cyan
    npx wrangler r2 object put "$Bucket/unified/unified_omniface.tflite" --file "$($fileItem.FullName)" --content-type "application/octet-stream" --remote
    Write-Host "`n✅ Successfully uploaded unified_omniface.tflite to Cloudflare R2!" -ForegroundColor Green
    Write-Host "🌐 Download endpoints on your worker:" -ForegroundColor White
    Write-Host "   - Direct alias:  https://<your-worker>.workers.dev/unified" -ForegroundColor Cyan
    Write-Host "   - Full path:     https://<your-worker>.workers.dev/download/unified" -ForegroundColor Cyan
} finally {
    Pop-Location
}
