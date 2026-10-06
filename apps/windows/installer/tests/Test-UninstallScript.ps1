$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Uninstall-SyncDows.ps1')

function Assert-True($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Fails([scriptblock]$Action, [string]$Expected) {
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notlike "*$Expected*") { throw }
        return
    }
    throw "Expected failure containing: $Expected"
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ("SyncDows uninstall test ' " + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $script:fakeSetup = Join-Path $fixture 'Cached Setup.exe'
    Set-Content -LiteralPath $fakeSetup -Value 'fixture only'
    $dataFile = Join-Path $fixture 'mesh-data.db'
    Set-Content -LiteralPath $dataFile -Value 'preserve this data'
    $bundle = [pscustomobject]@{
        Hive = 'LocalMachine'; KeyName = '{00000000-0000-0000-0000-000000000001}'
        DisplayName = 'SyncDows'; WindowsInstaller = 0
        BundleUpgradeCode = @('{43D9DC72-284C-4270-BC4B-F6C6DDA16ED1}')
        UninstallString = ('"{0}" /uninstall' -f $fakeSetup)
    }
    $msi = [pscustomobject]@{
        Hive = 'LocalMachine'; KeyName = '{00000000-0000-0000-0000-000000000002}'
        DisplayName = 'SyncDows'; WindowsInstaller = 1; BundleUpgradeCode = $null
        UninstallString = 'MsiExec.exe /I{00000000-0000-0000-0000-000000000002}'
    }
    # Replace only platform boundaries. No test reads or changes real installed products.
    function Get-SyncDowsMsiExecPath { return $script:fakeSetup }
    function Get-SyncDowsRegistrations { return $script:registrations }
    function Protect-SyncDowsLegacyData { $script:preserved = $true }
    function Start-Process {
        param($FilePath, $ArgumentList, [switch]$Wait, [switch]$PassThru, $Verb)
        $script:calls += [pscustomobject]@{ FilePath=$FilePath; Arguments=$ArgumentList; Wait=$Wait; PassThru=$PassThru; Verb=$Verb }
        return [pscustomobject]@{ ExitCode=$script:exitCode }
    }

    $plan = @(Get-SyncDowsUninstallPlan @($bundle, $bundle, $msi))
    Assert-True ($plan.Count -eq 1 -and $plan[0].Kind -eq 'Bundle') 'Bundle must own removal of its chained MSIs'
    Assert-True ($plan[0].Executable -eq $fakeSetup) 'Quoted executable path was changed'
    Assert-Fails { Get-SyncDowsUninstallPlan @() } 'No registered'
    $bad = $bundle.PSObject.Copy()
    $bad.UninstallString = 'powershell -Command do-something'
    Assert-Fails { Get-SyncDowsUninstallPlan @($bad) } 'invalid'
    $other = $bundle.PSObject.Copy()
    $other.KeyName = '{00000000-0000-0000-0000-000000000003}'
    Assert-Fails { Get-SyncDowsUninstallPlan @($bundle, $other) } 'Multiple'
    $other.BundleUpgradeCode = @('{00000000-0000-0000-0000-000000000004}')
    Assert-Fails { Get-SyncDowsUninstallPlan @($other) } 'No registered'

    $script:registrations = @($bundle)
    $script:calls = @()
    $script:exitCode = 0
    $logs = Join-Path $fixture "logs with spaces '"
    $result = Invoke-SyncDowsUninstall -Quiet -LogDirectory $logs
    Assert-True ($result -eq 0 -and $calls.Count -eq 1) 'Bundle uninstall was not started exactly once'
    Assert-True ($calls[0].Arguments -eq ('/uninstall /norestart /log "{0}" /quiet' -f (Join-Path $logs 'uninstall-0.log'))) 'Incorrectly quoted uninstall arguments'
    Assert-True ($calls[0].Wait -and $calls[0].PassThru -and -not $calls[0].Verb) 'Burn must handle its own elevation and return its exit code'

    $script:calls = @()
    $whatIfLogs = Join-Path $fixture 'what-if'
    $null = Invoke-SyncDowsUninstall -LogDirectory $whatIfLogs -WhatIf
    Assert-True ($calls.Count -eq 0 -and -not (Test-Path -LiteralPath $whatIfLogs)) 'WhatIf performed an operation'
    $script:exitCode = 1603
    Assert-Fails { Invoke-SyncDowsUninstall -LogDirectory $logs } '1603'
    $script:exitCode = 3010
    Assert-True ((Invoke-SyncDowsUninstall -LogDirectory $logs) -eq 3010) 'Required restart was not reported'
    $script:exitCode = 0

    $companion = $msi.PSObject.Copy()
    $companion.KeyName = '{00000000-0000-0000-0000-000000000005}'
    $companion.DisplayName = 'SyncDows Uninstaller'
    $script:registrations = @($msi, $companion)
    $script:calls = @()
    $null = Invoke-SyncDowsUninstall -Quiet -LogDirectory $logs
    Assert-True ($calls.Count -eq 2) 'Standalone MSI removal left the companion package installed'
    foreach ($call in $calls) {
        Assert-True ($call.Arguments -match '^/x \{[\w-]+\} /norestart /L\*v "' -and $call.Verb -eq 'RunAs') 'Machine MSI needs /x and elevation'
    }
    $msi.Hive = 'CurrentUser'
    $script:preserved = $false
    $script:registrations = @($msi)
    $script:calls = @()
    $null = Invoke-SyncDowsUninstall -LogDirectory $logs
    Assert-True (-not $calls[0].Verb) 'Legacy per-user uninstall must keep the original user context'
    Assert-True $preserved 'Legacy data was not preserved before uninstall'

    $legacy = Join-Path $fixture 'legacy-data'
    $current = Join-Path $fixture 'current-data'
    New-Item -ItemType Directory -Path $legacy, $current | Out-Null
    Set-Content -LiteralPath (Join-Path $legacy 'syncdows.db') -Value 'legacy mesh'
    Set-Content -LiteralPath (Join-Path $legacy 'identity.p12') -Value 'old identity'
    Set-Content -LiteralPath (Join-Path $current 'identity.p12') -Value 'current identity'
    Copy-SyncDowsLegacyFiles -Source $legacy -Destination $current
    Assert-True ((Get-Content -LiteralPath (Join-Path $current 'syncdows.db')) -eq 'legacy mesh') 'Legacy database was not preserved'
    Assert-True ((Get-Content -LiteralPath (Join-Path $current 'identity.p12')) -eq 'current identity') 'Existing identity was overwritten'
    Assert-True (Test-Path -LiteralPath (Join-Path $legacy 'syncdows.db')) 'Legacy source was deleted'
    Set-Content -LiteralPath (Join-Path $legacy 'syncdows.db-wal') -Value 'old WAL'
    Copy-SyncDowsLegacyFiles -Source $legacy -Destination $current
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $current 'syncdows.db-wal'))) 'Old WAL was mixed with an existing database'
    $orphaned = Join-Path $fixture 'orphaned-data'
    New-Item -ItemType Directory -Path $orphaned | Out-Null
    Set-Content -LiteralPath (Join-Path $orphaned 'syncdows.db-wal') -Value 'orphan WAL'
    Assert-Fails { Copy-SyncDowsLegacyFiles -Source $legacy -Destination $orphaned } 'sidecars without a database'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $orphaned 'syncdows.db'))) 'Migration wrote into a conflicting data directory'

    $script:registrations = @($bundle)
    Remove-Item -LiteralPath $fakeSetup
    Assert-Fails { Invoke-SyncDowsUninstall -LogDirectory $logs } 'installer is missing'
    Assert-True ((Get-Content -LiteralPath $dataFile) -eq 'preserve this data') 'Uninstall touched user data'
    Write-Host 'Uninstall script regression checks passed.'
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
