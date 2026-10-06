param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [switch]$DisposableMachine,
    [string]$LogDirectory = (Join-Path $PSScriptRoot '..\..\build\installer-smoke-logs')
)
$ErrorActionPreference = 'Stop'
if (-not $DisposableMachine) { throw 'Run this install/uninstall smoke test only on a disposable Windows test machine, with -DisposableMachine.' }
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) { throw 'This test requires x64 Windows.' }
$Installer = (Resolve-Path -LiteralPath $Installer).Path
. (Join-Path $PSScriptRoot '..\Uninstall-SyncDows.ps1') -LogDirectory $LogDirectory
if (@(Get-SyncDowsRegistrations).Count -ne 0) { throw 'Refusing to replace an existing SyncDows installation.' }
$installFolder = Join-Path $env:ProgramW6432 'SyncDows'
if (Test-Path -LiteralPath $installFolder) { throw "Refusing to use an existing folder: $installFolder" }
$LogDirectory = [IO.Path]::GetFullPath($LogDirectory)
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$dataFolder = Join-Path $env:LOCALAPPDATA 'Fullm3t41\SyncDows'
New-Item -ItemType Directory -Force -Path $dataFolder | Out-Null
$sentinel = Join-Path $dataFolder ('installer-smoke-' + [Guid]::NewGuid().ToString('N') + '.txt')
Set-Content -LiteralPath $sentinel -Value 'keep mesh data'
$started = $false
try {
    $started = $true
    $process = Start-Process -FilePath $Installer -ArgumentList ('/install /quiet /norestart /log "{0}"' -f (Join-Path $LogDirectory 'install.log')) -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "Install failed with $($process.ExitCode). See $LogDirectory." }
    foreach ($file in @('SyncDows.exe', 'Uninstall SyncDows.exe', 'Uninstall-SyncDows.ps1')) {
        if (-not (Test-Path -LiteralPath (Join-Path $installFolder $file))) { throw "Missing installed file: $file" }
    }
    $registrations = @(Get-SyncDowsRegistrations)
    if ($registrations.Count -eq 0 -or @($registrations | Where-Object Hive -eq 'CurrentUser').Count -ne 0) {
        throw 'Installation was not registered machine-wide.'
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $installFolder 'Uninstall-SyncDows.ps1') -Quiet -LogDirectory (Join-Path $LogDirectory 'uninstall')
    if ($LASTEXITCODE -notin @(0, 3010)) { throw "Uninstall script failed with $LASTEXITCODE." }
    if (Test-Path -LiteralPath (Join-Path $installFolder 'SyncDows.exe')) { throw 'Application remained after uninstall.' }
    if (@(Get-SyncDowsRegistrations).Count -ne 0) { throw 'Installer registrations remained after uninstall.' }
    if ((Get-Content -LiteralPath $sentinel) -ne 'keep mesh data') { throw 'Uninstall changed mesh data.' }
    $started = $false
    Write-Host 'Default Program Files install and scripted uninstall passed; mesh data was retained.'
} finally {
    if ($started) {
        # Cleanup only the installation this test started; never recursively delete app/data folders.
        try {
            $cleanup = Start-Process -FilePath $Installer -ArgumentList ('/uninstall /quiet /norestart /log "{0}"' -f (Join-Path $LogDirectory 'cleanup.log')) -Wait -PassThru
            if ($cleanup.ExitCode -notin @(0, 3010)) { Write-Warning "Cleanup returned $($cleanup.ExitCode)." }
        } catch { Write-Warning "Cleanup failed: $_" }
    }
    Remove-Item -LiteralPath $sentinel -ErrorAction SilentlyContinue
}
