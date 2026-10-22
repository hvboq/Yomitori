# Dot-source this file to configure only the current PowerShell process.
$ErrorActionPreference = 'Stop'
$studioHome = Join-Path $env:ProgramFiles 'Android/Android Studio'
$sdkHome = $env:ANDROID_HOME
if (-not $sdkHome) {
    $localProperties = Join-Path (Split-Path $PSScriptRoot -Parent) 'local.properties'
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $sdkHome = $sdkLine.Substring(8).Replace('\\', '\').Replace('\:', ':') }
    }
}
if (-not $sdkHome) { $sdkHome = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$jdkHome = if (Test-Path "$studioHome/jbr/bin/java.exe") { "$studioHome/jbr" } else { $env:JAVA_HOME }
if (-not $jdkHome -or -not (Test-Path "$jdkHome/bin/java.exe")) { throw 'JDK 21 is required. Set JAVA_HOME.' }
if (-not (Test-Path "$sdkHome/platform-tools/adb.exe")) { throw 'Android SDK is required. Set ANDROID_HOME.' }
$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkHome
$env:ANDROID_SDK_ROOT = $sdkHome
$toolPaths = @("$jdkHome/bin", "$sdkHome/platform-tools", "$sdkHome/emulator", "$sdkHome/cmdline-tools/latest/bin")
$env:Path = (($toolPaths + ($env:Path -split ';') | Where-Object { $_ } | Select-Object -Unique) -join ';')
