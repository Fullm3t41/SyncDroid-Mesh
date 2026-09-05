package com.syncdeck.app.platform

object LinuxDeviceName {
    fun current(): String = runCatching { java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname")).trim() }
        .getOrNull()?.takeIf(String::isNotBlank) ?: "Steam Deck"
}
