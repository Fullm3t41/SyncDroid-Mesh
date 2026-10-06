using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("SyncDows data migration")]
[assembly: AssemblyProduct("SyncDows")]
[assembly: AssemblyCompany("Fullm3t41")]
[assembly: AssemblyVersion("1.0.0.0")]

internal static class PreserveUserData
{
    private static readonly string[] PersistentFiles =
    {
        "syncdows.db",
        "syncdows.db-wal",
        "syncdows.db-shm",
        "syncdows.db-journal",
        "identity.p12"
    };

    [STAThread]
    private static int Main(string[] arguments)
    {
        string logPath = Path.Combine(Path.GetTempPath(), "SyncDows-preflight-" + Guid.NewGuid().ToString("N") + ".log");
        try
        {
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string source = Path.Combine(localAppData, "SyncDows");
            string destination = Path.Combine(localAppData, "Fullm3t41", "SyncDows");
            string installPath = null;
            bool skipProcessWait = false;
            bool machineInstall = false;

            for (int index = 0; index < arguments.Length; index++)
            {
                if (arguments[index] == "--source" && index + 1 < arguments.Length)
                    source = Path.GetFullPath(arguments[++index]);
                else if (arguments[index] == "--destination" && index + 1 < arguments.Length)
                    destination = Path.GetFullPath(arguments[++index]);
                else if (arguments[index] == "--install-path" && index + 1 < arguments.Length)
                    installPath = arguments[++index];
                else if (arguments[index] == "--skip-process-wait")
                    skipProcessWait = true;
                else if (arguments[index] == "--machine-install")
                    machineInstall = true;
                else if (arguments[index] == "--log" && index + 1 < arguments.Length)
                    logPath = Path.GetFullPath(arguments[++index]);
            }

            Directory.CreateDirectory(Path.GetDirectoryName(logPath));
            File.AppendAllText(logPath, "SyncDows install preflight\r\nWindows: " + Environment.OSVersion +
                "\r\n64-bit OS: " + Environment.Is64BitOperatingSystem + "\r\nInstall path: " + installPath + "\r\n");
            if (!string.IsNullOrWhiteSpace(installPath))
                ValidateInstallPath(installPath, localAppData, source, destination);
            if (!skipProcessWait && !WaitForSyncDowsToExit(TimeSpan.FromSeconds(120)))
                throw new TimeoutException("SyncDows did not close in time. Quit it from the notification area and run the installer again.");
            if (!Directory.Exists(source) || PathsEqual(source, destination))
            {
                File.AppendAllText(logPath, "No legacy data migration needed.\r\n");
                if (machineInstall) CheckForPerUserInstallation();
                File.AppendAllText(logPath, "Preflight passed.\r\n");
                return 0;
            }

            Directory.CreateDirectory(destination);
            bool currentDatabaseExists = File.Exists(Path.Combine(destination, "syncdows.db"));
            if (!currentDatabaseExists)
            {
                foreach (string sidecar in new[] { "syncdows.db-wal", "syncdows.db-shm", "syncdows.db-journal" })
                    if (File.Exists(Path.Combine(destination, sidecar)))
                        throw new IOException("The current data folder contains SQLite sidecars without a database. Resolve these files before migrating legacy data.");
            }
            foreach (string fileName in PersistentFiles)
            {
                // Never attach an old database's WAL/journal to an existing current database.
                if (currentDatabaseExists && fileName.StartsWith("syncdows.db", StringComparison.Ordinal)) continue;
                CopyMissingFile(source, destination, fileName);
            }
            File.AppendAllText(logPath, "Legacy data preserved.\r\n");
            if (machineInstall) CheckForPerUserInstallation();
            File.AppendAllText(logPath, "Preflight passed.\r\n");
            return 0;
        }
        catch (Exception error)
        {
            try { File.AppendAllText(logPath, error.ToString() + "\r\n"); } catch { }
            if (Array.IndexOf(arguments, "--quiet") < 0)
            {
                MessageBox.Show(
                    "Setup stopped before changing application files.\n\n" + error.Message + "\n\nLog: " + logPath,
                    "SyncDows setup",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
            }
            return 20;
        }
    }

    private static void CheckForPerUserInstallation()
    {
        // Windows Installer cannot major-upgrade a per-user product into a per-machine product.
        // Run as the original user so both this check and data migration use the correct profile.
        foreach (RegistryView view in new[] { RegistryView.Registry64, RegistryView.Registry32 })
        {
            using (RegistryKey user = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, view))
            using (RegistryKey uninstall = user.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall"))
            {
                if (uninstall == null) continue;
                foreach (string name in uninstall.GetSubKeyNames())
                {
                    using (RegistryKey product = uninstall.OpenSubKey(name))
                    {
                        if (product == null) continue;
                        string displayName = product.GetValue("DisplayName") as string;
                        string publisher = product.GetValue("Publisher") as string;
                        if (string.Equals(publisher, "Fullm3t41", StringComparison.OrdinalIgnoreCase) &&
                            (string.Equals(displayName, "SyncDows", StringComparison.OrdinalIgnoreCase) ||
                             string.Equals(displayName, "SyncDows Uninstaller", StringComparison.OrdinalIgnoreCase)))
                        {
                            throw new IOException("A per-user SyncDows installation is already registered. " +
                                "Uninstall it from Windows Settings > Apps (or run Uninstall-SyncDows.ps1), " +
                                "then run this installer again. Your mesh data and synced folders are retained.");
                        }
                    }
                }
            }
        }
    }

    private static void ValidateInstallPath(string requestedPath, string localAppData, string legacyData, string persistentData)
    {
        if (!Path.IsPathRooted(requestedPath))
            throw new IOException("Choose a complete, absolute installation path.");
        string installPath = Path.GetFullPath(requestedPath)
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        string root = Path.GetPathRoot(installPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (string.Equals(installPath, root, StringComparison.OrdinalIgnoreCase) ||
            PathsEqual(installPath, localAppData) ||
            IsSameOrChild(installPath, legacyData) || IsSameOrChild(legacyData, installPath) ||
            IsSameOrChild(installPath, persistentData) || IsSameOrChild(persistentData, installPath))
        {
            throw new IOException("The selected installation path overlaps a protected Windows or SyncDows data folder.");
        }
        if (File.Exists(installPath))
            throw new IOException("The selected installation path is an existing file.");
        if (Directory.Exists(installPath) && Directory.GetFileSystemEntries(installPath).Length > 0 &&
            !File.Exists(Path.Combine(installPath, "SyncDows.exe")))
        {
            throw new IOException("Choose an empty folder or the folder containing an existing SyncDows installation.");
        }

        // This helper deliberately stays unelevated to preserve the initiating user's data.
        // The per-machine MSI checks write access after UAC elevation; probing Program Files
        // here would reject a valid installation before Windows can request administrator access.
    }

    private static bool IsSameOrChild(string candidate, string protectedPath)
    {
        string value = Path.GetFullPath(candidate).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        string boundary = Path.GetFullPath(protectedPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return string.Equals(value, boundary, StringComparison.OrdinalIgnoreCase) ||
            value.StartsWith(boundary + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    }

    private static bool WaitForSyncDowsToExit(TimeSpan timeout)
    {
        Stopwatch timer = Stopwatch.StartNew();
        while (timer.Elapsed < timeout)
        {
            Process[] running = Process.GetProcessesByName("SyncDows");
            if (running.Length == 0)
                return true;
            foreach (Process process in running)
                process.Dispose();
            Thread.Sleep(250);
        }
        return Process.GetProcessesByName("SyncDows").Length == 0;
    }

    private static void CopyMissingFile(string sourceDirectory, string destinationDirectory, string fileName)
    {
        string source = Path.Combine(sourceDirectory, fileName);
        string destination = Path.Combine(destinationDirectory, fileName);
        if (!File.Exists(source) || File.Exists(destination))
            return;

        string temporary = destination + ".migrating";
        Exception lastError = null;
        for (int attempt = 0; attempt < 20; attempt++)
        {
            try
            {
                File.Copy(source, temporary, true);
                if (new FileInfo(source).Length != new FileInfo(temporary).Length)
                    throw new IOException("The preserved file has the wrong size.");
                File.Move(temporary, destination);
                return;
            }
            catch (Exception error)
            {
                lastError = error;
                try { if (File.Exists(temporary)) File.Delete(temporary); } catch { }
                Thread.Sleep(250);
            }
        }
        throw new IOException("Could not preserve " + fileName + ".", lastError);
    }

    private static bool PathsEqual(string first, string second)
    {
        string left = Path.GetFullPath(first).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        string right = Path.GetFullPath(second).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return string.Equals(left, right, StringComparison.OrdinalIgnoreCase);
    }
}
