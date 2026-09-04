param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$Executable,
    [Parameter(Mandatory=$true)][int]$UiPid,
    [Parameter(Mandatory=$true)][int]$WorkerPid
)
$ErrorActionPreference = 'Stop'
try {
    foreach ($processId in @($UiPid, $WorkerPid)) {
        if ($processId -gt 0) {
            $running = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($running -and -not $running.WaitForExit(120000)) {
                throw 'SyncDows is still closing. Wait for synchronization to finish, then try again.'
            }
        }
    }
    $installFolder = Split-Path -Parent $Executable
    # Burn consumes one quoted argument string; Windows paths cannot contain a quote.
    $arguments = '/install /passive /norestart /log "{0}" InstallFolder="{1}"' -f (
        (Join-Path $PSScriptRoot 'installer.log'), $installFolder)
    $setup = Start-Process -FilePath $Installer -ArgumentList $arguments -Wait -PassThru
    if ($setup.ExitCode -eq 3010) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show('The update is installed. Restart Windows to finish.', 'SyncDows update') | Out-Null
    } elseif ($setup.ExitCode -ne 0) {
        throw "Setup returned $($setup.ExitCode). See $(Join-Path $PSScriptRoot 'installer.log')."
    } else {
        Start-Process -FilePath $Executable
    }
} catch {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'SyncDows update could not finish') | Out-Null
    exit 1
}
