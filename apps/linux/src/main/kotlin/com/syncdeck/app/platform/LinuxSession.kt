package com.syncdeck.app.platform

import java.nio.file.Files
import java.nio.file.Path

/** Gaming Mode wins over inherited desktop environment variables during a session switch. */
object LinuxSession {
    fun gamingMode(): Boolean {
        if (System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("gamescope", true) ||
            System.getenv("DESKTOP_SESSION").orEmpty().contains("gamescope", true)) return true
        return runCatching {
            Files.list(Path.of("/proc")).use { entries -> entries.anyMatch { path ->
                path.fileName.toString().all(Char::isDigit) && runCatching {
                    Files.getOwner(path) == Files.getOwner(Path.of("/proc/self")) &&
                        Files.readString(path.resolve("comm")).trim() == "gamescope" &&
                        Files.readString(path.resolve("cmdline")).split('\u0000').any { it == "--steam" || it == "-e" }
                }.getOrDefault(false)
            } }
        }.getOrDefault(false)
    }
    fun desktopAvailable(): Boolean = !gamingMode() &&
        (!System.getenv("DISPLAY").isNullOrBlank() || !System.getenv("WAYLAND_DISPLAY").isNullOrBlank())
}
