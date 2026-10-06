using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Uninstall SyncDows")]
[assembly: System.Reflection.AssemblyProduct("SyncDows")]
[assembly: System.Reflection.AssemblyCompany("Fullm3t41")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]

internal static class UninstallSyncDows
{
    [STAThread]
    private static int Main()
    {
        try
        {
            string script = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "Uninstall-SyncDows.ps1");
            if (!File.Exists(script))
                throw new FileNotFoundException("The uninstall script is missing. You can also remove SyncDows from Windows Settings > Apps.", script);
            Process.Start(new ProcessStartInfo
            {
                FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                    @"WindowsPowerShell\v1.0\powershell.exe"),
                Arguments = "-NoProfile -ExecutionPolicy Bypass -File \"" + script + "\"",
                UseShellExecute = true
            });
            // Release this EXE before Windows Installer removes it.
            return 0;
        }
        catch (Exception error)
        {
            MessageBox.Show("The SyncDows uninstaller could not be started.\n\n" + error.Message,
                "Uninstall SyncDows", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
