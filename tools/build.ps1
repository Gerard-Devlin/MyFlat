param([string]$JavaHome = '', [string]$Sdk = '')
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if ($Sdk) { $env:ANDROID_HOME = $Sdk; $env:ANDROID_SDK_ROOT = $Sdk }
Push-Location -LiteralPath $ProjectRoot
try {
    & .\gradlew.bat :app:testDebugUnitTest :scanner:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    Write-Host 'APK: app/build/outputs/apk/debug/app-debug.apk'
} finally { Pop-Location }
