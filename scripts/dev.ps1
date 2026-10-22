[CmdletBinding()]
param(
    [ValidateSet('doctor', 'build', 'models', 'emulator', 'install', 'studio')]
    [string]$Action = 'doctor',
    [string]$Serial,
    [string]$Avd = 'Pixel_10_Pro',
    [System.Net.IPAddress[]]$DnsServers = @(),
    [switch]$Headless
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/android-env.ps1"
$projectRoot = Split-Path $PSScriptRoot -Parent
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE)." }
}
Push-Location $projectRoot
try {
    switch ($Action) {
        'doctor' {
            Write-Output "Project: $projectRoot"
            Write-Output "JAVA_HOME: $env:JAVA_HOME"
            Write-Output "ANDROID_HOME: $env:ANDROID_HOME"
            Invoke-Checked "$env:JAVA_HOME/bin/java.exe" @('-version')
            $required = @('platforms/android-37.0/android.jar', 'build-tools/37.0.0/aapt2.exe',
                'ndk/28.2.13676358/source.properties', 'cmake/3.22.1/bin/cmake.exe',
                'cmdline-tools/latest/bin/sdkmanager.bat')
            foreach ($relative in $required) {
                if (-not (Test-Path "$env:ANDROID_HOME/$relative")) { throw "Missing SDK component: $relative" }
                Write-Output "OK: $relative"
            }
            Invoke-Checked 'git' @('submodule', 'status', '--recursive')
            Invoke-Checked 'git' @('-C', 'yakuyomi-engine', 'rev-parse', '--verify', 'HEAD')
            Invoke-Checked 'git' @('-C', 'yakuyomi-engine', 'diff', '--check')
            Invoke-Checked '.\gradlew.bat' @('--version')
            Invoke-Checked $adb @('version')
            Invoke-Checked $adb @('devices', '-l')
            Invoke-Checked "$env:ANDROID_HOME/emulator/emulator.exe" @('-list-avds')
            Write-Output 'Environment checks passed. Use models to verify/download OCR models.'
        }
        'build' { Invoke-Checked '.\gradlew.bat' @(':app:assembleDebug', '--console=plain') }
        'models' { Invoke-Checked 'python' @('scripts/prepare_translation_models.py') }
        'emulator' {
            $avds = & "$env:ANDROID_HOME/emulator/emulator.exe" -list-avds
            if ($LASTEXITCODE -ne 0 -or $Avd -notin $avds) { throw "AVD not found: $Avd" }
            $emulatorArgs = @('-avd', $Avd, '-no-audio', '-no-snapshot', '-gpu', 'swiftshader', '-memory', '8192')
            if ($DnsServers.Count -gt 0) {
                if ($DnsServers.Count -gt 4) { throw 'The emulator supports at most 4 DNS servers.' }
                $emulatorArgs += @('-dns-server', ($DnsServers -join ','))
            }
            if ($Headless) { $emulatorArgs += '-no-window' }
            Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList $emulatorArgs -WindowStyle Hidden
            Write-Output 'Emulator starting with 8 GB RAM. Wait for Android to finish booting before install.'
        }
        'install' {
            $apk = 'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
            if (-not (Test-Path $apk)) { throw 'APK missing. Run build first.' }
            $deviceArgs = @()
            if ($Serial) { $deviceArgs = @('-s', $Serial) }
            Invoke-Checked $adb ($deviceArgs + @('install', '--abi', 'arm64-v8a', '-r', $apk))
            Invoke-Checked $adb ($deviceArgs + @('shell', 'am', 'start', '-n', 'app.yomitori.yakuyomi.dev/eu.kanade.tachiyomi.ui.main.MainActivity'))
        }
        'studio' {
            $launcher = Join-Path $env:ProgramFiles 'Android/Android Studio/bin/studio64.exe'
            if (-not (Test-Path $launcher)) { throw "Android Studio not found: $launcher" }
            Start-Process -FilePath $launcher -ArgumentList ('"' + $projectRoot + '"') -WindowStyle Hidden
        }
    }
} finally { Pop-Location }
