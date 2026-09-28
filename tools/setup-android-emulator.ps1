param(
    [string]$AvdName = "PocketRisu_API35",
    [string]$ApiLevel = "35",
    [switch]$Start
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SdkRoot = Join-Path $ProjectRoot ".tooling\android-sdk"
$JavaHome = Join-Path $ProjectRoot ".tooling\jdk17\jdk-17.0.20.1+1"
$CmdlineToolsRoot = Join-Path $SdkRoot "cmdline-tools\latest"
$SdkManager = Join-Path $SdkRoot "cmdline-tools\latest\cmdline-tools\bin\sdkmanager.bat"
$AvdManager = Join-Path $SdkRoot "cmdline-tools\latest\cmdline-tools\bin\avdmanager.bat"
$Emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$AvdHome = Join-Path $ProjectRoot ".tooling\avd"
$SystemImage = "system-images;android-$ApiLevel;google_apis;x86_64"

function New-ExplicitAvdConfig {
    param([string]$Name, [string]$Api, [string]$Home)
    $directory = Join-Path $Home "$Name.avd"
    New-Item -ItemType Directory -Force $directory | Out-Null
    $configLines = @(
        "AvdId=$Name", "PlayStore.enabled=false", "abi.type=x86_64",
        "avd.ini.displayname=$Name", "avd.ini.encoding=UTF-8",
        "disk.dataPartition.size=6442450944", "fastboot.forceColdBoot=yes",
        "hw.accelerometer=yes", "hw.audioInput=yes", "hw.camera.back=virtualscene",
        "hw.camera.front=emulated", "hw.cpu.arch=x86_64", "hw.cpu.ncore=4",
        "hw.device.manufacturer=Google", "hw.device.name=pixel_6", "hw.gps=yes",
        "hw.gpu.enabled=yes", "hw.gpu.mode=auto", "hw.initialOrientation=Portrait",
        "hw.keyboard=yes", "hw.lcd.density=420", "hw.lcd.height=2400",
        "hw.lcd.width=1080", "hw.ramSize=2048",
        "image.sysdir.1=system-images\android-$Api\google_apis\x86_64\",
        "runtime.network.latency=none", "runtime.network.speed=full",
        "showDeviceFrame=no", "skin.dynamic=yes", "skin.name=1080x2400",
        "tag.display=Google APIs", "tag.id=google_apis", "vm.heapSize=256"
    )
    Set-Content -LiteralPath (Join-Path $directory "config.ini") -Value ($configLines -join "`n") -Encoding ascii
    $indexLines = @(
        "avd.ini.encoding=UTF-8",
        "path=$directory",
        "path.rel=avd\$Name.avd",
        "target=android-$Api"
    )
    Set-Content -LiteralPath (Join-Path $Home "$Name.ini") -Value ($indexLines -join "`n") -Encoding ascii
}

if (!(Test-Path $SdkManager)) { throw "SDK command-line tools not found: $SdkManager" }
if (!(Test-Path $JavaHome)) { throw "Bundled JDK not found: $JavaHome" }

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_AVD_HOME = $AvdHome
# This checkout contains cmdline-tools/latest/cmdline-tools rather than the
# standard cmdline-tools/latest layout. Override the tools directory so
# avdmanager resolves the actual SDK root instead of C:\.android.
$env:AVDMANAGER_OPTS = "-Dcom.android.sdkmanager.toolsdir=$CmdlineToolsRoot"
$env:Path = "$JavaHome\bin;$SdkRoot\platform-tools;$SdkRoot\emulator;$env:Path"
New-Item -ItemType Directory -Force $AvdHome | Out-Null

& $SdkManager --sdk_root=$SdkRoot --licenses | Out-Null
& $SdkManager --sdk_root=$SdkRoot "emulator" $SystemImage

$existing = (& $AvdManager list avd 2>$null) -join "`n"
if ($existing -notmatch "(?m)^Name:s+$([regex]::Escape($AvdName))$") {
    $avdOutput = "no" | & $AvdManager create avd --force --name $AvdName --package $SystemImage --device "pixel_6" 2>&1
    if ($LASTEXITCODE -ne 0) {
        New-ExplicitAvdConfig -Name $AvdName -Api $ApiLevel -Home $AvdHome
        Write-Warning "avdmanager could not load the system-image device catalog; created an explicit AVD config. Output: $avdOutput"
    }
}

Write-Host "AVD ready: $AvdName"
Write-Host "Start with: .\tools\setup-android-emulator.ps1 -Start"

if ($Start) {
    if (!(Test-Path $Emulator)) { throw "Emulator executable not found: $Emulator" }
    Start-Process -FilePath $Emulator -ArgumentList "-avd", $AvdName, "-no-snapshot", "-no-boot-anim" -WorkingDirectory $SdkRoot
}
