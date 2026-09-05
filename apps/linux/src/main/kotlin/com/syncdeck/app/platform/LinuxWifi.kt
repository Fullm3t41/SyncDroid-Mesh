package com.syncdeck.app.platform

import java.util.concurrent.TimeUnit

object LinuxWifi {
    fun currentSsid(): String? = runCatching {
        val process = ProcessBuilder("nmcli", "-t", "--escape", "no", "-f", "IN-USE,SSID", "device", "wifi", "list", "--rescan", "no")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return@runCatching null }
        if (process.exitValue() != 0) return@runCatching null
        parseCurrentNetwork(process.inputStream.bufferedReader().readText())
    }.getOrNull()
    internal fun parseCurrentNetwork(output: String): String? = output.lineSequence()
        .firstOrNull { it.startsWith("*:") }?.removePrefix("*:")?.takeIf(String::isNotBlank)
}
