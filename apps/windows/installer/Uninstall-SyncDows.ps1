#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [switch]$Quiet,
    [string]$LogDirectory = (Join-Path ([IO.Path]::GetTempPath()) ('SyncDows-uninstall-' + [Guid]::NewGuid().ToString('N')))
)

$ErrorActionPreference = 'Stop'

function Get-SyncDowsRegistrations {
    $uninstallPath = 'Software\Microsoft\Windows\CurrentVersion\Uninstall'
    foreach ($hive in @([Microsoft.Win32.RegistryHive]::CurrentUser, [Microsoft.Win32.RegistryHive]::LocalMachine)) {
        foreach ($view in @([Microsoft.Win32.RegistryView]::Registry64, [Microsoft.Win32.RegistryView]::Registry32)) {
            $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey($hive, $view)
            try {
                $uninstall = $base.OpenSubKey($uninstallPath)
                if ($null -eq $uninstall) { continue }
                try {
                    foreach ($name in $uninstall.GetSubKeyNames()) {
                        $entry = $uninstall.OpenSubKey($name)
                        if ($null -eq $entry) { continue }
                        try {
                            if ($entry.GetValue('Publisher') -ne 'Fullm3t41') { continue }
                            if ($entry.GetValue('DisplayName') -notin @('SyncDows', 'SyncDows Uninstaller')) { continue }
                            [pscustomobject]@{
                                Hive = $hive.ToString()
                                KeyName = $name
                                DisplayName = $entry.GetValue('DisplayName')
                                WindowsInstaller = $entry.GetValue('WindowsInstaller')
                                BundleUpgradeCode = $entry.GetValue('BundleUpgradeCode')
                                UninstallString = $entry.GetValue('UninstallString')
                            }
                        } finally { $entry.Dispose() }
                    }
                } finally { $uninstall.Dispose() }
            } finally { $base.Dispose() }
        }
    }
}

function Get-SyncDowsUninstallPlan {
    param([object[]]$Registrations)
    # Do not use Win32_Product: enumerating it repairs unrelated MSI applications.
    $entries = @($Registrations | Sort-Object Hive, KeyName -Unique)
    $bundles = @($entries | Where-Object {
        @($_.BundleUpgradeCode) -contains '{43D9DC72-284C-4270-BC4B-F6C6DDA16ED1}'
    })
    if ($bundles.Count -gt 1) {
        throw 'Multiple SyncDows setups are registered. Remove the intended installation through Windows Settings > Apps.'
    }
    if ($bundles.Count -eq 1) {
        $command = [string]$bundles[0].UninstallString
        # Parse only the executable; never evaluate a registry command as PowerShell.
        if ($command -match '^\s*"([^"]+\.exe)"(?:\s|$)' -or
            $command -match '^\s*([^"\r\n]+?\.exe)(?:\s|$)') {
            return [pscustomobject]@{ Kind = 'Bundle'; Executable = $Matches[1]; ProductCode = ''; Hive = $bundles[0].Hive }
        }
        throw 'The registered SyncDows setup command is invalid. Run the original setup EXE with /uninstall.'
    }
    # Legacy jpackage installers register /I (maintenance). Use /x with the product GUID.
    $msis = @($entries | Where-Object {
        $_.WindowsInstaller -eq 1 -and $_.KeyName -match '^\{[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\}$'
    })
    if ($msis.Count -eq 0) { throw 'No registered SyncDows installation was found. No files were removed.' }
    foreach ($entry in $msis) {
        [pscustomobject]@{
            Kind = 'Msi'
            Executable = (Get-SyncDowsMsiExecPath)
            ProductCode = $entry.KeyName
            Hive = $entry.Hive
        }
    }
}

function Get-SyncDowsMsiExecPath {
    Join-Path ([Environment]::GetFolderPath('System')) 'msiexec.exe'
}

function Copy-SyncDowsLegacyFiles {
    param([string]$Source, [string]$Destination)
    $currentDatabaseExists = Test-Path -LiteralPath (Join-Path $Destination 'syncdows.db')
    if (-not $currentDatabaseExists) {
        foreach ($sidecar in @('syncdows.db-wal', 'syncdows.db-shm', 'syncdows.db-journal')) {
            if (Test-Path -LiteralPath (Join-Path $Destination $sidecar)) {
                throw 'The current data folder contains SQLite sidecars without a database. Resolve these files before migrating legacy data.'
            }
        }
    }
    foreach ($name in @('syncdows.db', 'syncdows.db-wal', 'syncdows.db-shm', 'syncdows.db-journal', 'identity.p12')) {
        if ($currentDatabaseExists -and $name.StartsWith('syncdows.db')) { continue }
        $original = Join-Path $Source $name
        $target = Join-Path $Destination $name
        if (-not (Test-Path -LiteralPath $original -PathType Leaf) -or (Test-Path -LiteralPath $target)) { continue }
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
        $temporary = $target + '.' + [Guid]::NewGuid().ToString('N') + '.migrating'
        try {
            [IO.File]::Copy($original, $temporary, $false)
            [IO.File]::Move($temporary, $target)
        } finally {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
        }
    }
}

function Protect-SyncDowsLegacyData {
    $localData = [Environment]::GetFolderPath('LocalApplicationData')
    $source = Join-Path $localData 'SyncDows'
    if (-not (Test-Path -LiteralPath $source -PathType Container)) { return }
    if (Get-Process -Name SyncDows -ErrorAction SilentlyContinue) {
        throw 'Quit SyncDows from the notification area before uninstalling so legacy mesh data can be preserved.'
    }
    Copy-SyncDowsLegacyFiles -Source $source -Destination (Join-Path $localData 'Fullm3t41\SyncDows')
}

function Invoke-SyncDowsUninstall {
    [CmdletBinding(SupportsShouldProcess=$true)]
    param([switch]$Quiet, [string]$LogDirectory)
    $plan = @(Get-SyncDowsUninstallPlan -Registrations @(Get-SyncDowsRegistrations))
    $result = 0
    $index = 0
    $legacyDataChecked = $false
    foreach ($action in $plan) {
        if (-not $PSCmdlet.ShouldProcess("SyncDows ($($action.Hive))", 'Uninstall application; keep mesh data and synced folders')) { continue }
        if (-not (Test-Path -LiteralPath $action.Executable -PathType Leaf)) {
            throw "The registered installer is missing: $($action.Executable). Run the original setup EXE with /uninstall. No application folders were manually deleted."
        }
        if ($action.Hive -eq 'CurrentUser' -and -not $legacyDataChecked) {
            Protect-SyncDowsLegacyData
            $legacyDataChecked = $true
        }
        $directory = [IO.Path]::GetFullPath($LogDirectory)
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        $log = Join-Path $directory ("uninstall-$index.log")
        $index++
        if ($action.Kind -eq 'Bundle') {
            $arguments = '/uninstall /norestart /log "{0}"' -f $log
        } else {
            $arguments = '/x {0} /norestart /L*v "{1}"' -f $action.ProductCode, $log
        }
        if ($Quiet) { $arguments += ' /quiet' }
        $parameters = @{ FilePath = $action.Executable; ArgumentList = $arguments; Wait = $true; PassThru = $true }
        # Burn requests elevation itself while retaining the original user's context.
        if ($action.Kind -eq 'Msi' -and $action.Hive -eq 'LocalMachine') { $parameters.Verb = 'RunAs' }
        Write-Host "Uninstall log: $log"
        $process = Start-Process @parameters
        if ($process.ExitCode -notin @(0, 3010, 1641)) {
            throw "SyncDows uninstall returned $($process.ExitCode). See $log."
        }
        if ($process.ExitCode -ne 0) { $result = 3010 }
    }
    if ($result -eq 3010) { Write-Host 'Uninstall completed. Restart Windows to finish.' }
    return $result
}

# Dot-sourcing exposes functions for fixture tests without uninstalling anything.
if ($MyInvocation.InvocationName -ne '.') {
    try {
        $result = Invoke-SyncDowsUninstall -Quiet:$Quiet -LogDirectory $LogDirectory -WhatIf:$WhatIfPreference
        exit $result
    } catch {
        Write-Error $_ -ErrorAction Continue
        exit 1
    }
}
