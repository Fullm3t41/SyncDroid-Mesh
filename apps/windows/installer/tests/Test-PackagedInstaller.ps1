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
    # A legacy per-user MSI must still be rejected. The temporary registration
    # belongs only to this disposable-machine test and is removed before installation.
    $legacyKeyPath = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\SyncDows-smoke-' + [Guid]::NewGuid().ToString('N')
    $userRegistry = [Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryView]::Registry64)
    try {
        $legacyKey = $userRegistry.CreateSubKey($legacyKeyPath)
        try {
            $legacyKey.SetValue('DisplayName', 'SyncDows')
            $legacyKey.SetValue('Publisher', 'Fullm3t41')
            $legacyKey.SetValue('WindowsInstaller', 1, [Microsoft.Win32.RegistryValueKind]::DWord)
        } finally { $legacyKey.Dispose() }
        $legacyLog = Join-Path $LogDirectory 'legacy-check.log'
        $blocked = Start-Process -FilePath $Installer -ArgumentList ('/install /quiet /norestart /log "{0}"' -f $legacyLog) -Wait -PassThru
        if ($blocked.ExitCode -in @(0, 3010) -or (Test-Path -LiteralPath (Join-Path $installFolder 'SyncDows.exe'))) {
            throw 'Setup did not block an existing per-user MSI before installing application files.'
        }
        if ((Get-Content -LiteralPath ($legacyLog + '.preflight.log') -Raw) -notmatch 'A per-user SyncDows installation is already registered') {
            throw 'Setup failed without identifying the existing per-user MSI.'
        }
    } finally {
        $userRegistry.DeleteSubKeyTree($legacyKeyPath, $false)
        $userRegistry.Dispose()
    }
    $process = Start-Process -FilePath $Installer -ArgumentList ('/install /quiet /norestart /log "{0}"' -f (Join-Path $LogDirectory 'install.log')) -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) { throw "Install failed with $($process.ExitCode). See $LogDirectory." }
    foreach ($file in @('SyncDows.exe', 'Uninstall SyncDows.exe', 'Uninstall-SyncDows.ps1')) {
        if (-not (Test-Path -LiteralPath (Join-Path $installFolder $file))) { throw "Missing installed file: $file" }
    }
    $registrations = @(Get-SyncDowsRegistrations)
    # Burn retains the initiating user's context for preflight/data preservation.
    # Its bundle registration is per-user; both installed MSI products must be per-machine.
    $products = @($registrations | Where-Object WindowsInstaller -eq 1)
    foreach ($name in @('SyncDows', 'SyncDows Uninstaller')) {
        if (@($products | Where-Object { $_.DisplayName -eq $name -and $_.Hive -eq 'LocalMachine' }).Count -eq 0) {
            throw "$name was not registered machine-wide."
        }
    }
    if (@($products | Where-Object Hive -eq 'CurrentUser').Count -ne 0) {
        throw 'A per-user MSI remained after installation.'
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $installFolder 'Uninstall-SyncDows.ps1') -Quiet -LogDirectory (Join-Path $LogDirectory 'uninstall')
    if ($LASTEXITCODE -notin @(0, 3010)) { throw "Uninstall script failed with $LASTEXITCODE." }
    if (Test-Path -LiteralPath (Join-Path $installFolder 'SyncDows.exe')) { throw 'Application remained after uninstall.' }
    if (@(Get-SyncDowsRegistrations).Count -ne 0) { throw 'Installer registrations remained after uninstall.' }
    if ((Get-Content -LiteralPath $sentinel) -ne 'keep mesh data') { throw 'Uninstall changed mesh data.' }
    $started = $false
    Write-Host 'Default Program Files install and scripted uninstall passed; mesh data was retained.'
} catch {
    if ($env:GITHUB_ACTIONS -eq 'true') {
        # Put the useful error in the job summary as well as the downloadable logs.
        # These logs come from this disposable test installation, never a user's machine.
        function Write-InstallerAnnotation([string]$Title, [string]$Message) {
            $escaped = $Message.Replace('%', '%25').Replace("`r", '%0D').Replace("`n", '%0A')
            Write-Host "::error title=${Title}::$escaped"
        }
        Write-InstallerAnnotation 'SyncDows installation check' $_.Exception.Message
        Get-ChildItem -LiteralPath $LogDirectory -Filter '*.log' -File -Recurse -ErrorAction SilentlyContinue |
            ForEach-Object {
                $details = Select-String -LiteralPath $_.FullName -Pattern '(?i)(error 0x|error [0-9]|e[0-9]{3}:|exception|return value 3|failed to)' -Context 2, 2 |
                    Select-Object -Last 4 | Out-String
                if (-not [string]::IsNullOrWhiteSpace($details)) {
                    Write-InstallerAnnotation ('SyncDows log - ' + $_.Name) $details.Substring(0, [Math]::Min(6000, $details.Length))
                }
            }
    }
    throw
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
