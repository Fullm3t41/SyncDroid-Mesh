param([Parameter(Mandatory=$true)][string]$Compiler)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ("SyncDows update test ' " + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $source = Join-Path $fixture 'Stub.cs'
    @'
using System;
using System.IO;
class Stub {
    static int Main(string[] args) {
        string folder = AppDomain.CurrentDomain.BaseDirectory;
        if (args.Length > 0) File.WriteAllLines(Path.Combine(folder, "arguments.txt"), args);
        else File.WriteAllText(Path.Combine(folder, "reopened.txt"), "yes");
        return 0;
    }
}
'@ | Set-Content -LiteralPath $source
    $installer = Join-Path $fixture 'Setup.exe'
    & $Compiler /nologo /target:winexe "/out:$installer" $source
    if ($LASTEXITCODE -ne 0) { throw 'Update test stub compilation failed' }
    $application = Join-Path $fixture 'SyncDows.exe'
    Copy-Item -LiteralPath $installer -Destination $application
    $updater = Join-Path $fixture 'install-update.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\..\src\main\resources\updates\install-update.ps1') -Destination $updater
    # A reaped child represents the already-closed UI; this never targets the real app.
    $child = Start-Process -FilePath $env:ComSpec -ArgumentList '/c exit 0' -PassThru -Wait
    & $updater -Installer $installer -Executable $application -UiPid $child.Id -WorkerPid 0
    $arguments = Get-Content -LiteralPath (Join-Path $fixture 'arguments.txt')
    foreach ($expected in @('/install', '/passive', '/norestart', "InstallFolder=$fixture")) {
        if ($arguments -notcontains $expected) { throw "Missing or incorrectly quoted update argument: $expected" }
    }
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if (Test-Path -LiteralPath (Join-Path $fixture 'reopened.txt')) { break }
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath (Join-Path $fixture 'reopened.txt'))) { throw 'Updated app did not reopen' }
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
