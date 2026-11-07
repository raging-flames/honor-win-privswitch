#requires -version 5.1
<#
  隐私开关（PrivSwitch）手工构建脚本
  依赖：JDK 17+、Android SDK（build-tools + 任一 android-3x platform）
  用法：powershell -ExecutionPolicy Bypass -File scripts\build.ps1

  可选环境变量：
    ANDROID_SDK_ROOT / ANDROID_HOME  Android SDK 根目录
    JAVA_HOME                        JDK 根目录（缺省时尝试 Android Studio 自带 JBR）
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent

function Find-Sdk {
    $candidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk")
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'build-tools'))) { return $c }
    }
    throw '未找到 Android SDK，请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME'
}
$sdk = Find-Sdk

$btDir = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $btDir) { throw '未找到 build-tools' }
$bt = $btDir.FullName

$plat = Get-ChildItem (Join-Path $sdk 'platforms') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$jar = if ($plat) { Join-Path $plat.FullName 'android.jar' } else { $null }
if (-not $jar -or -not (Test-Path $jar)) { throw '未找到 android.jar（需安装 Android SDK Platform）' }

$jdk = $env:JAVA_HOME
if (-not $jdk) {
    foreach ($c in @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
        if (Test-Path (Join-Path $c 'bin\javac.exe')) { $jdk = $c; break }
    }
}
if (-not $jdk) {
    $jc = Get-Command javac -ErrorAction SilentlyContinue
    if ($jc) { $jdk = Split-Path (Split-Path $jc.Source -Parent) -Parent }
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { throw '未找到 JDK，请设置 JAVA_HOME' }

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$out = Join-Path $root 'build'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
foreach ($d in 'gen', 'classes', 'dex') { New-Item -ItemType Directory -Path (Join-Path $out $d) -Force | Out-Null }

Write-Host '== aapt2 compile =='
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'res') -o (Join-Path $out 'res.zip')
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }

Write-Host '== aapt2 link =='
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $out 'base.apk') -I $jar `
    --manifest (Join-Path $root 'AndroidManifest.xml') -R (Join-Path $out 'res.zip') `
    --java (Join-Path $out 'gen') --auto-add-overlay --min-sdk-version 29 --target-sdk-version 36
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

Write-Host '== javac =='
$srcs = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += @(Get-ChildItem (Join-Path $out 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath $jar -d (Join-Path $out 'classes') @srcs
if ($LASTEXITCODE) { throw 'javac failed' }

Write-Host '== d8 =='
$classes = @(Get-ChildItem (Join-Path $out 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& (Join-Path $bt 'd8.bat') --release --min-api 29 --lib $jar --output (Join-Path $out 'dex') @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Write-Host '== merge classes.dex =='
Copy-Item (Join-Path $out 'base.apk') (Join-Path $out 'unsigned.apk') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $out 'unsigned.apk'), 'Update')
try {
    $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open()
    try { [System.IO.File]::OpenRead((Join-Path $out 'dex\classes.dex')).CopyTo($es) } finally { $es.Dispose() }
} finally { $zip.Dispose() }

Write-Host '== zipalign =='
& (Join-Path $bt 'zipalign.exe') -f 4 (Join-Path $out 'unsigned.apk') (Join-Path $out 'aligned.apk')
if ($LASTEXITCODE) { throw 'zipalign failed' }

Write-Host '== keystore =='
$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE) { throw 'keytool failed' }
}

Write-Host '== apksigner =='
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out (Join-Path $out 'privswitch.apk') (Join-Path $out 'aligned.apk')
if ($LASTEXITCODE) { throw 'apksigner failed' }

& (Join-Path $bt 'apksigner.bat') verify --print-certs (Join-Path $out 'privswitch.apk') | Select-Object -First 4
Get-Item (Join-Path $out 'privswitch.apk') | Select-Object FullName, Length
