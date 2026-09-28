[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ApkPath,
    [string]$PackageName = 'io.github.risumanager',
    [string]$ActivityName = 'io.github.risumanager/.MainActivity',
    [int]$BootTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
}

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $PSScriptRoot '..\.tooling\android-sdk' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'

if (!(Test-Path -LiteralPath $adb)) {
    throw "adb was not found at '$adb'. Set ANDROID_HOME or install the local SDK first."
}
if (!(Test-Path -LiteralPath $ApkPath)) {
    throw "APK was not found at '$ApkPath'. Build it with assembleDebug first."
}

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $output = & $adb @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed:`n$output"
    }
    return ($output -join "`n")
}

$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }

$devices = Invoke-Adb @('devices')
$deviceLines = $devices -split "`r?`n" | Where-Object { $_ -match "\tdevice$" }
if (!$Serial -and $deviceLines.Count -eq 1) {
    $Serial = ($deviceLines[0] -split "\s+")[0]
    $deviceArgs = @('-s', $Serial)
} elseif (!$Serial) {
    throw "Expected exactly one connected adb device; found $($deviceLines.Count). Pass -Serial explicitly."
}

$state = Invoke-Adb ($deviceArgs + @('get-state'))
if ($state.Trim() -ne 'device') {
    throw "Device '$Serial' is not online: $state"
}

$abiList = (Invoke-Adb ($deviceArgs + @('shell', 'getprop', 'ro.product.cpu.abilist'))).Trim()
$primaryAbi = (Invoke-Adb ($deviceArgs + @('shell', 'getprop', 'ro.product.cpu.abi'))).Trim()
if ($primaryAbi -ne 'arm64-v8a' -or $abiList -notmatch '(^|,)arm64-v8a(,|$)') {
    throw "AArch64 verification refused: device '$Serial' reports ABI list '$abiList' and primary ABI '$primaryAbi'. Use an arm64-v8a/aarch64 device or AVD."
}

Write-Host "AArch64 device accepted: $Serial (primary=$primaryAbi, abilist=$abiList)"
Write-Host "Installing $ApkPath"
Invoke-Adb ($deviceArgs + @('install', '-r', $ApkPath)) | Write-Host

Invoke-Adb ($deviceArgs + @('shell', 'am', 'force-stop', $PackageName)) | Out-Null
Invoke-Adb ($deviceArgs + @('shell', 'monkey', '-p', $PackageName, '1')) | Out-Null

$deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $focused = Invoke-Adb ($deviceArgs + @('shell', 'dumpsys', 'activity', 'activities'))
    $isFocused = $focused -match [regex]::Escape($ActivityName)
} while (!$isFocused -and (Get-Date) -lt $deadline)

if (!$isFocused) {
    throw "AArch64 app launch failed or timed out: $ActivityName was not focused on '$Serial'."
}

$installed = Invoke-Adb ($deviceArgs + @('shell', 'pm', 'path', $PackageName))
if ($installed -notmatch 'base\.apk') {
    throw "Package '$PackageName' is not installed correctly on '$Serial'."
}

Write-Host "AArch64 launch verification passed: $ActivityName"
