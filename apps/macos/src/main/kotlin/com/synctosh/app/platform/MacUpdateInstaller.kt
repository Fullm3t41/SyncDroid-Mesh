package com.synctosh.app.platform

import java.nio.file.Files
import java.nio.file.Path

object MacUpdateInstaller {
    fun open(installer: Path) {
        require(Files.isRegularFile(installer)) { "The downloaded update is unavailable" }
        val executable = Path.of(requireNotNull(System.getProperty("jpackage.app-path")) {
            "Install updates from the installed application"
        }).toAbsolutePath().normalize()
        val application = applicationBundle(executable)
        require(Files.isWritable(application) && Files.isWritable(application.parent) &&
            !application.startsWith(Path.of("/Volumes"))) {
            "Move SyncTosh to a writable Applications folder before updating"
        }
        val work = Files.createTempDirectory("synctosh-update-")
        val script = work.resolve("install-update.sh")
        MacUpdateInstaller::class.java.getResourceAsStream("/updates/install-update.sh")!!.use {
            Files.copy(it, script)
        }
        val current = ProcessHandle.current()
        val parent = current.parent().orElse(null)
        val workerPid = parent?.takeIf { it.info().command() == current.info().command() }?.pid() ?: 0L
        ProcessBuilder("/bin/bash", script.toString(), installer.toAbsolutePath().toString(),
            application.toString(), current.pid().toString(), workerPid.toString())
            .redirectOutput(work.resolve("update.log").toFile())
            .redirectErrorStream(true)
            .start()
    }

    internal fun applicationBundle(executable: Path): Path {
        require(executable.fileName.toString() == "SyncTosh" &&
            executable.parent?.fileName.toString() == "MacOS" &&
            executable.parent?.parent?.fileName.toString() == "Contents" &&
            executable.parent?.parent?.parent?.fileName.toString().endsWith(".app")) {
            "Install updates from the installed SyncTosh application"
        }
        return executable.parent.parent.parent
    }
}
