param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$JdkRoot=$env:JAVA_HOME,
    [string]$GradleCache=(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle')
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out=Join-Path $root 'build/zip-backup-checks'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$stdlib=Get-ChildItem "$GradleCache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if(-not $stdlib){throw '需要已有 Kotlin 2.0.21 缓存'}
$android=Join-Path $SdkRoot 'platforms/android-35/android.jar'
$classes=Join-Path $root 'app/build/tmp/kotlin-classes/release'
$classpath="$out;$classes;$stdlib;$android"
& "$JdkRoot/bin/javac.exe" --release 8 -encoding UTF-8 -cp $classpath -d $out "$PSScriptRoot/ZipBackupCheck.java"
if($LASTEXITCODE -ne 0){throw '检查编译失败，请先 assembleRelease'}
Push-Location $out
try { & "$JdkRoot/bin/java.exe" -cp $classpath ZipBackupCheck; if($LASTEXITCODE -ne 0){throw 'ZIP备份检查未通过'} }
finally { Pop-Location }
