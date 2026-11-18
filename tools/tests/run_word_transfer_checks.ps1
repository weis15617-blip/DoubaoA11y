param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$JdkRoot=$env:JAVA_HOME,
    [string]$GradleCache=(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle')
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out=Join-Path $root 'build/word-transfer-checks'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$stdlib=Get-ChildItem "$GradleCache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if(-not $stdlib){throw '需要已有 Kotlin 2.0.21 缓存'}
$android=Join-Path $SdkRoot 'platforms/android-35/android.jar'
$classes=Join-Path $root 'app/build/tmp/kotlin-classes/release'
$classpath="$out;$classes;$stdlib;$android"
& "$JdkRoot/bin/javac.exe" --release 17 -encoding UTF-8 -cp $classpath -d $out "$PSScriptRoot/WordTransferCheck.java"
if($LASTEXITCODE -ne 0){throw '检查编译失败，请先 assembleRelease'}
# 只运行格式与合并计划，无 adb，无配置写入。
& "$JdkRoot/bin/java.exe" -cp $classpath WordTransferCheck
if($LASTEXITCODE -ne 0){throw '解释词导入导出检查未通过'}
