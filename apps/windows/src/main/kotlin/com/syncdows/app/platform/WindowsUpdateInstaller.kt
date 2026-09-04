package com.syncdows.app.platform

import java.nio.file.Files
import java.nio.file.Path

object WindowsUpdateInstaller {
    fun launch(installer: Path) {
        require(Files.isRegularFile(installer)) { "The downloaded update is unavailable" }
        val executable = Path.of(requireNotNull(System.getProperty("jpackage.app-path")) {
            "Install updates from the installed application"
        }).toAbsolutePath().normalize()
        require(executable.fileName.toString().equals("SyncDows.exe", ignoreCase = true)) {
            "Install updates from the installed SyncDows application"
        }
        val work = Files.createTempDirectory("syncdows-update-")
        val script = work.resolve("install-update.ps1")
        WindowsUpdateInstaller::class.java.getResourceAsStream("/updates/install-update.ps1")!!.use {
            Files.copy(it, script)
        }
        val current = ProcessHandle.current()
        val parent = current.parent().orElse(null)
        val workerPid = parent?.takeIf { it.info().command() == current.info().command() }?.pid() ?: 0L
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.toString(),
            "-Installer", installer.toAbsolutePath().toString(), "-Executable", executable.toString(),
            "-UiPid", current.pid().toString(), "-WorkerPid", workerPid.toString())
            .redirectOutput(work.resolve("update.log").toFile())
            .redirectErrorStream(true)
            .start()
    }
}
