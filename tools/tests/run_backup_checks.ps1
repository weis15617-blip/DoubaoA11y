param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$JdkRoot=$env:JAVA_HOME,
    [string]$GradleCache=(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle')
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out=Join-Path $root 'build/backup-checks'
New-Item -ItemType Directory -Force -Path "$out/classes","$out/dex" | Out-Null
$stdlib=Get-ChildItem "$GradleCache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if(-not $stdlib){throw '需要已有 Kotlin 2.0.21 缓存'}
$android=Join-Path $SdkRoot 'platforms/android-35/android.jar'
$appClasses=Join-Path $root 'app/build/tmp/kotlin-classes/release'
$env:JAVA_HOME=$JdkRoot
& "$JdkRoot/bin/javac.exe" --release 8 -encoding UTF-8 -cp "$appClasses;$stdlib;$android" -d "$out/classes" "$PSScriptRoot/BackupFormatCheck.java"
if($LASTEXITCODE -ne 0){throw '测试编译失败，请先 assembleRelease'}
if(Test-Path -LiteralPath "$out/check.jar"){Remove-Item -LiteralPath "$out/check.jar"}
[IO.Compression.ZipFile]::CreateFromDirectory("$out/classes", "$out/check.jar")
& "$SdkRoot/build-tools/35.0.0/d8.bat" --min-api 26 --lib $android --output "$out/dex" "$out/check.jar"
if($LASTEXITCODE -ne 0){throw '测试DEX生成失败'}
if(Test-Path -LiteralPath "$out/runner.jar"){Remove-Item -LiteralPath "$out/runner.jar"}
[IO.Compression.ZipFile]::CreateFromDirectory("$out/dex", "$out/runner.jar")
$adb=Join-Path $SdkRoot 'platform-tools/adb.exe'
& $adb -s $Serial push "$out/runner.jar" /data/local/tmp/doubao-backup-check.jar
if($LASTEXITCODE -ne 0){throw '测试程序传输失败'}
& $adb -s $Serial push "$root/app/build/outputs/apk/release/app-release.apk" /data/local/tmp/doubao-backup-runtime.apk
if($LASTEXITCODE -ne 0){throw '测试运行库传输失败'}
# 只运行纯格式校验，不启动 Activity，不读取应用偏好，不安装测试包。
$result=& $adb -s $Serial shell 'CLASSPATH=/data/local/tmp/doubao-backup-check.jar:/data/local/tmp/doubao-backup-runtime.apk app_process /system/bin BackupFormatCheck'
$result
if($LASTEXITCODE -ne 0 -or ($result -join '') -notmatch 'PASS 18 backup format checks'){throw '备份格式检查未全部通过'}
