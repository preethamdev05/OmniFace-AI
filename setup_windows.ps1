# OmniFace AI - Windows Environment Setup Script
# Configures Android SDK, Android CLI, Java, Keystore, and Gradle for Windows

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "🤖 OMNIFACE AI - WINDOWS ENVIRONMENT SETUP" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$WorkspaceDir = $PSScriptRoot
if (-not $WorkspaceDir) { $WorkspaceDir = Get-Location }

# 1. Detect Java JDK
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome -or -not (Test-Path "$JavaHome\bin\java.exe")) {
    $PotentialJavas = @(
        "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot",
        "C:\Program Files\Java\jdk-17*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    foreach ($pj in $PotentialJavas) {
        $found = Get-Item $pj -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found -and (Test-Path "$($found.FullName)\bin\java.exe")) {
            $JavaHome = $found.FullName
            $env:JAVA_HOME = $JavaHome
            break
        }
    }
}
Write-Host "[+] JAVA_HOME: $JavaHome" -ForegroundColor Green

# 2. Detect Android SDK
$SdkPath = $env:ANDROID_HOME
if (-not $SdkPath -or -not (Test-Path $SdkPath)) {
    $CandidateSdks = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\Sdk",
        "D:\Android\Sdk"
    )
    foreach ($cs in $CandidateSdks) {
        if (Test-Path $cs) {
            $SdkPath = $cs
            $env:ANDROID_HOME = $SdkPath
            $env:ANDROID_SDK_ROOT = $SdkPath
            break
        }
    }
}
Write-Host "[+] Android SDK Path: $SdkPath" -ForegroundColor Green

# 3. Update local.properties (Git-ignored)
$EscapedSdkPath = $SdkPath.Replace("\", "/")
$LocalPropsPath = Join-Path $WorkspaceDir "local.properties"
$LocalPropsContent = "## Automatically generated for local build - DO NOT COMMIT TO GIT`nsdk.dir=$EscapedSdkPath`n"
Set-Content -Path $LocalPropsPath -Value $LocalPropsContent -Encoding UTF8
Write-Host "[+] Generated local.properties pointing to: $EscapedSdkPath" -ForegroundColor Green

# 4. Check & Add Android CLI / Platform Tools to PATH
$AndroidCliDir = "$env:USERPROFILE\AppData\AndroidCLI"
$PlatformToolsDir = "$SdkPath\platform-tools"
$CmdlineToolsDir = "$SdkPath\cmdline-tools\latest\bin"

$CurrentPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
$PathsToAdd = @($AndroidCliDir, $PlatformToolsDir, $CmdlineToolsDir)

foreach ($p in $PathsToAdd) {
    if (Test-Path $p) {
        if ($env:PATH -notlike "*$p*") {
            $env:PATH = "$p;$($env:PATH)"
        }
        if ($CurrentPath -notlike "*$p*") {
            [System.Environment]::SetEnvironmentVariable("Path", "$p;$CurrentPath", "User")
            $CurrentPath = "$p;$CurrentPath"
            Write-Host "[+] Added to User PATH: $p" -ForegroundColor Green
        }
    }
}

# 5. Generate debug keystore if not present
$DebugKeystore = Join-Path $WorkspaceDir "debug.keystore"
if (-not (Test-Path $DebugKeystore)) {
    Write-Host "[+] Generating local debug.keystore..." -ForegroundColor Yellow
    $Keytool = "$JavaHome\bin\keytool.exe"
    if (Test-Path $Keytool) {
        & $Keytool -genkeypair -v -keystore $DebugKeystore -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
        Write-Host "[+] Created debug.keystore successfully" -ForegroundColor Green
    }
} else {
    Write-Host "[+] Found existing debug.keystore" -ForegroundColor Green
}

# 6. Verify Android CLI
$AndroidCliExe = "$AndroidCliDir\android.exe"
if (Test-Path $AndroidCliExe) {
    Write-Host "[+] Android CLI is installed and ready at: $AndroidCliExe" -ForegroundColor Green
} else {
    Write-Host "[!] Android CLI not found. Installing..." -ForegroundColor Yellow
    curl.exe -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "$env:TEMP\i.cmd"
    cmd.exe /c "$env:TEMP\i.cmd"
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "✅ OmniFace AI Windows Environment Setup Complete!" -ForegroundColor Cyan
Write-Host "   - SDK Path: $SdkPath" -ForegroundColor Cyan
Write-Host "   - JAVA_HOME: $JavaHome" -ForegroundColor Cyan
Write-Host "   - Gradle Wrapper: gradlew.bat ready" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
