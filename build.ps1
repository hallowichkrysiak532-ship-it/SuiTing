param([string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug'), [switch]$Offline)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tooling'
$localSettingsPath = Join-Path $toolRoot 'build-local.psd1'
$localSettings = if (Test-Path -LiteralPath $localSettingsPath) { Import-PowerShellDataFile -LiteralPath $localSettingsPath } else { @{} }
$gradleBat = if ($localSettings.GradleExecutable) { $localSettings.GradleExecutable } else { Join-Path $projectRoot 'gradlew.bat' }
if ($localSettings.JavaHome) { $env:JAVA_HOME = $localSettings.JavaHome }
if ($localSettings.AndroidSdk) { $env:ANDROID_HOME = $localSettings.AndroidSdk }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Set JAVA_HOME to JDK 17 or newer before building.' }
if (!$env:ANDROID_HOME -and !(Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties'))) { throw 'Set ANDROID_HOME or configure sdk.dir in local.properties.' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home'
$readOnlyCache = if ($localSettings.ReadOnlyDependencyCache) { $localSettings.ReadOnlyDependencyCache } else { Join-Path $env:USERPROFILE '.gradle\caches' }
if (Test-Path -LiteralPath $readOnlyCache) { $env:GRADLE_RO_DEP_CACHE = $readOnlyCache } else { Remove-Item Env:GRADLE_RO_DEP_CACHE -ErrorAction SilentlyContinue }
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user-home'
$env:TEMP = Join-Path $toolRoot 'tmp'
$env:TMP = $env:TEMP
@($env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, $env:TEMP) | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }
$tmpJava = $env:TEMP.Replace('\','/')
$taskHome = $toolRoot.Replace('\','/')
$env:GRADLE_OPTS = '-Djava.io.tmpdir="' + $tmpJava + '" -Duser.home="' + $taskHome + '"'
$gradleHomeProperties = Join-Path $env:GRADLE_USER_HOME 'gradle.properties'
$existingProperties = if (Test-Path -LiteralPath $gradleHomeProperties) { @(Get-Content -LiteralPath $gradleHomeProperties | Where-Object { $_ -notmatch '^org\.gradle\.jvmargs\s*=' }) } else { @() }
$daemonProperties = 'org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 "-Djava.io.tmpdir=' + $tmpJava + '" "-Duser.home=' + $taskHome + '"'
@($existingProperties) + $daemonProperties | Set-Content -LiteralPath $gradleHomeProperties -Encoding utf8
if (!(Test-Path -LiteralPath $gradleBat)) { throw "Gradle launcher is missing: $gradleBat" }
Push-Location $projectRoot
try {
    $buildArgs = @('--no-daemon', '--console=plain', '-Dorg.gradle.java.installations.auto-download=false')
    if ($Offline) { $buildArgs += '--offline' }
    & $gradleBat @buildArgs @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed, exit code $LASTEXITCODE" }
    if (@($Tasks | Where-Object { $_ -match '(^|:)assembleDebug$' }).Count -gt 0) {
        $apkDirectory = Join-Path $projectRoot 'app\build\outputs\apk\debug'
        $apkMetadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
        $apkVersion = $apkMetadata.elements[0].versionName
        if ($apkVersion -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid APK version name' }
        $distDirectory = Join-Path $projectRoot 'dist'
        New-Item -ItemType Directory -Force -Path $distDirectory | Out-Null
        Copy-Item -LiteralPath (Join-Path $apkDirectory 'app-debug.apk') -Destination (Join-Path $distDirectory ('SuiTing-' + $apkVersion + '-debug.apk')) -Force
    }
} finally { Pop-Location }
