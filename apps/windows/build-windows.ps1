param(
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = "1.2.8"
)

$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot
& .\gradlew.bat --no-daemon test
if ($LASTEXITCODE -ne 0) {
    throw "Gradle tests failed with exit code $LASTEXITCODE"
}

$bundleBuildDirectory = Join-Path $PSScriptRoot "build\installer"
New-Item -ItemType Directory -Force -Path $bundleBuildDirectory | Out-Null
$iconFile = Join-Path $PSScriptRoot "src\main\resources\icons\syncdows.ico"
$csharpCompiler = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path -LiteralPath $csharpCompiler)) {
    throw "The Windows .NET Framework C# compiler was not found at $csharpCompiler"
}

& (Join-Path $PSScriptRoot "installer\tests\Test-UpdateInstaller.ps1") -Compiler $csharpCompiler

$uninstallerSource = Join-Path $PSScriptRoot "installer\UninstallSyncDows.cs"
$uninstaller = Join-Path $bundleBuildDirectory "Uninstall SyncDows.exe"
& $csharpCompiler /nologo /target:winexe /optimize+ /reference:System.Windows.Forms.dll `
    "/win32icon:$iconFile" "/out:$uninstaller" $uninstallerSource
if ($LASTEXITCODE -ne 0) {
    throw "Uninstaller compilation failed with exit code $LASTEXITCODE"
}

$migrationHelper = Join-Path $bundleBuildDirectory "SyncDowsInstallPreflight.exe"
$migrationSource = Join-Path $PSScriptRoot "installer\PreserveUserData.cs"
& $csharpCompiler /nologo /target:winexe /optimize+ /reference:System.Windows.Forms.dll `
    "/win32icon:$iconFile" "/out:$migrationHelper" $migrationSource
if ($LASTEXITCODE -ne 0) {
    throw "Install preflight helper compilation failed with exit code $LASTEXITCODE"
}

$migrationTestRoot = Join-Path $bundleBuildDirectory ("migration-test-" + [Guid]::NewGuid().ToString("N"))
$migrationTestSource = Join-Path $migrationTestRoot "source"
$migrationTestDestination = Join-Path $migrationTestRoot "destination"
$migrationTestInstall = Join-Path $migrationTestRoot "install"
New-Item -ItemType Directory -Force -Path $migrationTestSource | Out-Null
[System.IO.File]::WriteAllBytes((Join-Path $migrationTestSource "syncdows.db"), [byte[]](1, 2, 3, 4))
[System.IO.File]::WriteAllBytes((Join-Path $migrationTestSource "identity.p12"), [byte[]](5, 6, 7))
$migrationTest = Start-Process -FilePath $migrationHelper -ArgumentList @(
    "--source", $migrationTestSource,
    "--destination", $migrationTestDestination,
    "--install-path", $migrationTestInstall,
    "--skip-process-wait",
    "--quiet"
) -Wait -PassThru
if ($migrationTest.ExitCode -ne 0 -or
    -not (Test-Path -LiteralPath (Join-Path $migrationTestDestination "syncdows.db")) -or
    -not (Test-Path -LiteralPath (Join-Path $migrationTestDestination "identity.p12"))) {
    throw "Install preflight migration smoke test failed"
}
$overlapTest = Start-Process -FilePath $migrationHelper -ArgumentList @(
    "--source", $migrationTestSource,
    "--destination", $migrationTestDestination,
    "--install-path", $migrationTestDestination,
    "--skip-process-wait",
    "--quiet"
) -Wait -PassThru
if ($overlapTest.ExitCode -eq 0) {
    throw "Install preflight accepted a path that overlaps persistent data"
}

& .\gradlew.bat --no-daemon --rerun-tasks createDistributable packageMsi
if ($LASTEXITCODE -ne 0) {
    throw "Gradle MSI packaging failed with exit code $LASTEXITCODE"
}

$internalMsi = (Get-ChildItem "build\compose\binaries\main\msi\*.msi" | Select-Object -First 1).FullName
$wixDirectory = Join-Path $PSScriptRoot "build\wix311"
$candle = Join-Path $wixDirectory "candle.exe"
$light = Join-Path $wixDirectory "light.exe"
$balExtension = Join-Path $wixDirectory "WixBalExtension.dll"
if (-not (Test-Path -LiteralPath $candle) -or -not (Test-Path -LiteralPath $light) -or -not (Test-Path -LiteralPath $balExtension)) {
    throw "The WiX toolset downloaded by the Compose packaging task was not found in $wixDirectory"
}

$releaseDirectory = Join-Path $PSScriptRoot "build\release"
New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
$releaseExe = Join-Path $releaseDirectory "SyncDows-$Version-Windows-x64.exe"
Get-ChildItem -LiteralPath $releaseDirectory -File -Filter "SyncDows-*-Windows-x64.exe" |
    Where-Object FullName -ne $releaseExe |
    Remove-Item -Force
$bundleSource = Join-Path $PSScriptRoot "installer\SyncDowsBundle.wxs"
$themeFile = Join-Path $PSScriptRoot "installer\theme\SyncDowsTheme.xml"
$localizationFile = Join-Path $PSScriptRoot "installer\theme\SyncDowsTheme.wxl"
$logoFile = Join-Path $PSScriptRoot "installer\theme\logo.png"
# Keep the setup artwork identical to the application/tray source.
Add-Type -AssemblyName System.Drawing
$logoSource = [System.Drawing.Image]::FromFile((Join-Path $PSScriptRoot "..\..\design\icon\syncdows-icon-source.png"))
$logoBitmap = New-Object System.Drawing.Bitmap 68, 68
$logoGraphics = [System.Drawing.Graphics]::FromImage($logoBitmap)
try {
    $logoGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $logoGraphics.DrawImage($logoSource, 0, 0, 68, 68)
    $logoBitmap.Save($logoFile, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $logoGraphics.Dispose()
    $logoBitmap.Dispose()
    $logoSource.Dispose()
}
$uninstallerPackageSource = Join-Path $PSScriptRoot "installer\SyncDowsUninstaller.wxs"
$uninstallerPackageObject = Join-Path $bundleBuildDirectory "SyncDowsUninstaller.wixobj"
$uninstallerPackage = Join-Path $bundleBuildDirectory "SyncDowsUninstaller.msi"
$bundleObject = Join-Path $bundleBuildDirectory "SyncDowsBundle.wixobj"

& $candle -nologo -arch x64 `
    "-dPackageVersion=$Version" `
    "-dUninstallerPath=$uninstaller" `
    -out $uninstallerPackageObject $uninstallerPackageSource
if ($LASTEXITCODE -ne 0) {
    throw "Uninstaller package compilation failed with exit code $LASTEXITCODE"
}
& $light -nologo -spdb -sice:ICE91 -out $uninstallerPackage $uninstallerPackageObject
if ($LASTEXITCODE -ne 0) {
    throw "Uninstaller package linking failed with exit code $LASTEXITCODE"
}

& $candle -nologo -ext $balExtension -arch x64 `
    "-dMsiPath=$internalMsi" `
    "-dBundleVersion=$Version.0" `
    "-dThemePath=$themeFile" `
    "-dLocalizationPath=$localizationFile" `
    "-dLogoPath=$logoFile" `
    "-dIconPath=$iconFile" `
    "-dMigrationHelperPath=$migrationHelper" `
    "-dUninstallerMsiPath=$uninstallerPackage" `
    -out $bundleObject $bundleSource
if ($LASTEXITCODE -ne 0) {
    throw "WiX bootstrapper compilation failed with exit code $LASTEXITCODE"
}

& $light -nologo -spdb -ext $balExtension -out $releaseExe $bundleObject
if ($LASTEXITCODE -ne 0) {
    throw "WiX bootstrapper linking failed with exit code $LASTEXITCODE"
}

Write-Host "SyncDows installer is ready in $releaseDirectory"
