using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

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
        try
        {
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string source = Path.Combine(localAppData, "SyncDows");
            string destination = Path.Combine(localAppData, "Fullm3t41", "SyncDows");
            string installPath = null;
            bool skipProcessWait = false;

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
            }

            if (!string.IsNullOrWhiteSpace(installPath))
                ValidateInstallPath(installPath, localAppData, source, destination);
            if (!skipProcessWait && !WaitForSyncDowsToExit(TimeSpan.FromSeconds(120)))
                throw new TimeoutException("SyncDows did not close in time. Quit it from the notification area and run the installer again.");
            if (!Directory.Exists(source) || PathsEqual(source, destination))
                return 0;

            Directory.CreateDirectory(destination);
            foreach (string fileName in PersistentFiles)
                CopyMissingFile(source, destination, fileName);
            return 0;
        }
        catch (Exception error)
        {
            if (Array.IndexOf(arguments, "--quiet") < 0)
            {
                MessageBox.Show(
                    "Setup stopped before making changes.\n\n" + error.Message,
                    "SyncDows setup",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
            }
            return 20;
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

        string writableParent = installPath;
        while (!Directory.Exists(writableParent))
        {
            writableParent = Path.GetDirectoryName(writableParent);
            if (string.IsNullOrEmpty(writableParent))
                throw new IOException("The selected installation path has no accessible parent folder.");
        }
        string probe = Path.Combine(writableParent, ".syncdows-install-check-" + Guid.NewGuid().ToString("N") + ".tmp");
        try { File.WriteAllBytes(probe, new byte[] { 1 }); }
        finally { if (File.Exists(probe)) File.Delete(probe); }
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
