[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [string]$ApkPath,
    [string]$TermuxApkPath
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
}
if ([string]::IsNullOrWhiteSpace($TermuxApkPath)) {
    $TermuxApkPath = Join-Path $PSScriptRoot '..\.tooling\downloads\com.termux_1002.apk'
}

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $PSScriptRoot '..\.tooling\android-sdk' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (!(Test-Path -LiteralPath $adb)) { throw "adb was not found at '$adb'." }
if (!(Test-Path -LiteralPath $ApkPath)) { throw "Manager APK was not found at '$ApkPath'." }
if (!(Test-Path -LiteralPath $TermuxApkPath)) { throw "Termux APK was not found at '$TermuxApkPath'." }

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $adb @('-s', $Serial) @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed:`n$output" }
    return ($output -join "`n")
}

function Send-TermuxText {
    param([Parameter(Mandatory)][string]$Text)
    Invoke-Adb @('shell', 'input', 'text', $Text) | Out-Null
}

function Send-TermuxSpace { Invoke-Adb @('shell', 'input', 'keyevent', '62') | Out-Null }
function Send-TermuxEnter { Invoke-Adb @('shell', 'input', 'keyevent', '66') | Out-Null }

Write-Host "Installing Termux 0.118.3 on $Serial"
Invoke-Adb @('install', '-r', $TermuxApkPath) | Write-Host
Write-Host "Installing Manager APK on $Serial"
Invoke-Adb @('install', '-r', $ApkPath) | Write-Host
Invoke-Adb @('shell', 'pm', 'grant', 'io.github.risumanager', 'com.termux.permission.RUN_COMMAND') | Out-Null

# Termux intentionally requires this user-controlled setting before accepting
# external RUN_COMMAND requests. Configure it through the visible Termux shell;
# no private app data is modified from adb shell directly.
Invoke-Adb @('shell', 'am', 'force-stop', 'com.termux') | Out-Null
Invoke-Adb @('shell', 'monkey', '-p', 'com.termux', '1') | Out-Null
Start-Sleep -Seconds 2
Send-TermuxEnter
Send-TermuxText 'echo'
Send-TermuxSpace
Send-TermuxText 'allow-external-apps=true'
Send-TermuxSpace
# The escaped greater-than is passed to Termux input as a literal redirection.
Send-TermuxText '\>'
Send-TermuxSpace
Send-TermuxText '/data/data/com.termux/files/home/.termux/termux.properties'
Send-TermuxEnter
Start-Sleep -Milliseconds 500

Invoke-Adb @('shell', 'am', 'force-stop', 'com.termux') | Out-Null
Invoke-Adb @('shell', 'am', 'force-stop', 'io.github.risumanager') | Out-Null
Invoke-Adb @('shell', 'pm', 'grant', 'io.github.risumanager', 'com.termux.permission.RUN_COMMAND') | Out-Null

$primaryAbi = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
$abiList = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abilist')).Trim()
Write-Host "AVD/device configured: primary ABI=$primaryAbi, ABI list=$abiList"
Write-Host "Next: run connected bridge instrumentation or verify-aarch64-device.ps1 on an ARM64 target."
