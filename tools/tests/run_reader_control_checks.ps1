param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$JdkRoot=$env:JAVA_HOME,
    [string]$GradleCache=$(if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else {
        Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
    })
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$out = Join-Path $root 'build/reader-control-checks'
$stdlibRoot = Join-Path $GradleCache 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21'
$stdlib = Get-ChildItem -LiteralPath $stdlibRoot -Filter 'kotlin-stdlib-2.0.21.jar' -Recurse |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $stdlib) { throw 'Kotlin 2.0.21 standard library not found in the Gradle cache' }
$android = Join-Path $SdkRoot 'platforms/android-35/android.jar'
if (-not (Test-Path -LiteralPath $android -PathType Leaf)) { throw "Android 35 SDK not found at $android" }
$appClasses = Join-Path $root 'app/build/tmp/kotlin-classes/release'
if (-not (Test-Path -LiteralPath $appClasses -PathType Container)) { throw 'Run assembleRelease first' }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$classpath = "$appClasses;$stdlib"
& "$JdkRoot/bin/javac.exe" --release 8 -encoding UTF-8 -cp $classpath -d $out "$PSScriptRoot/ReaderControlCheck.java"
if ($LASTEXITCODE -ne 0) { throw 'Reader control check compilation failed' }
# Run the policies without calling Android framework methods.
& "$JdkRoot/bin/java.exe" -cp "$out;$classpath;$android" ReaderControlCheck
if ($LASTEXITCODE -ne 0) { throw 'Reader detection/control policy checks failed' }

# Guard the Android adapter too. Both fast-input settings must reach the same policy.
$adapter = Join-Path $root 'app/src/main/java/io/github/doubaoa11y/hook/ReaderControl.kt'
$source = Get-Content -LiteralPath $adapter -Raw -Encoding UTF8
$syncMethod = [regex]::Match($source, '(?ms)^    fun sync\(\) \{.*?^    \}')
if (-not $syncMethod.Success -or $syncMethod.Value -notmatch '\bpolicy\.sync\(' -or
    $syncMethod.Value -match '"tatansFast"') {
    throw 'ReaderControl.sync must use the same policy with fast input enabled or disabled'
}
Write-Output 'PASS ReaderControl.sync fast-input independence'
