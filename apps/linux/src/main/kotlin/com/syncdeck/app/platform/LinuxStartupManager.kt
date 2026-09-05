package com.syncdeck.app.platform

import java.nio.file.Files
import java.nio.file.Path

/** KDE autostart runs in Desktop Mode; the worker independently enforces the Gaming Mode gate. */
object LinuxStartupManager {
    private fun target() = LinuxAppPaths.configRoot.parent.resolve("autostart/syncdeck.desktop")
    fun isEnabled(): Boolean = Files.isRegularFile(target())
    fun setEnabled(enabled: Boolean) {
        if (!enabled) { Files.deleteIfExists(target()); return }
        val executable = Path.of(requireNotNull(System.getProperty("jpackage.app-path")) { "Install SyncDeck before enabling launch at login" })
        require(executable.fileName.toString() == "SyncDeck")
        Files.createDirectories(target().parent)
        Files.writeString(target(), desktopEntry(executable))
    }
    internal fun desktopEntry(executable: Path): String {
        val value = executable.toAbsolutePath().toString()
        require(value.none { it == '\n' || it == '\r' })
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$").replace("%", "%%")
        return "[Desktop Entry]\nType=Application\nName=SyncDeck\nExec=\"$escaped\" --background\nTerminal=false\nOnlyShowIn=KDE;GNOME;XFCE;\n"
    }
}
