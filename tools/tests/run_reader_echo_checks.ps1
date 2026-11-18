param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$JdkRoot=$env:JAVA_HOME,
    [string]$GradleCache=(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle')
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out=Join-Path $root 'build/reader-echo-checks'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$stdlib=Get-ChildItem "$GradleCache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if(-not $stdlib){throw '需要已有 Kotlin 2.0.21 缓存'}
$android=Join-Path $SdkRoot 'platforms/android-35/android.jar'
$appClasses=Join-Path $root 'app/build/tmp/kotlin-classes/release'
$classpath="$out;$appClasses;$stdlib;$android"
& "$JdkRoot/bin/javac.exe" --release 8 -encoding UTF-8 -cp $classpath -d $out "$PSScriptRoot/ReaderEchoCheck.java"
if($LASTEXITCODE -ne 0){throw '检查编译失败，请先 assembleRelease'}
# 只运行纯数据策略，无 adb、无 Activity，也不调用 android.jar 中的桩方法。
& "$JdkRoot/bin/java.exe" -cp $classpath ReaderEchoCheck
if($LASTEXITCODE -ne 0){throw '回声与候选策略检查未通过'}
