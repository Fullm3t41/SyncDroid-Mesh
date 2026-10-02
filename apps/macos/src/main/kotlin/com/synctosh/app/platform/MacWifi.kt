package com.synctosh.app.platform

object MacWifi {
    /**
     * Since macOS 14.4, networksetup often reports no network while connected, so fall back to
     * ipconfig and then the slower system_profiler. Each returns null when macOS hides the name.
     */
    fun currentSsid(): String? {
        if (!System.getProperty("os.name", "").contains("Mac", ignoreCase = true)) return null
        return runCatching {
            val interfaceName = wifiInterface() ?: return@runCatching null
            command("/usr/sbin/networksetup", "-getairportnetwork", interfaceName)?.let(::parseCurrentNetwork)
                ?: command("/usr/sbin/ipconfig", "getsummary", interfaceName)?.let(::parseIpconfigSummary)
                ?: command("/usr/sbin/system_profiler", "SPAirPortDataType")?.let(::parseSystemProfiler)
        }.getOrNull()
    }

    private fun wifiInterface(): String? {
        val output = command("/usr/sbin/networksetup", "-listallhardwareports") ?: return null
        val lines = output.lineSequence().map(String::trim).toList()
        return lines.indices.firstNotNullOfOrNull { index ->
            if (lines[index].equals("Hardware Port: Wi-Fi", ignoreCase = true)) {
                lines.getOrNull(index + 1)?.substringAfter("Device:", "")?.trim()?.takeIf(String::isNotBlank)
            } else null
        }
    }

    private fun command(vararg arguments: String): String? {
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return output.takeIf { process.waitFor() == 0 }
    }

    internal fun parseCurrentNetwork(output: String): String? = output
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("Current Wi-Fi Network:", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    internal fun parseIpconfigSummary(output: String): String? = output
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("SSID :") }
        ?.substringAfter(':')
        ?.trim()
        ?.takeUnless(::isHidden)

    internal fun parseSystemProfiler(output: String): String? {
        val lines = output.lines()
        val heading = lines.indexOfFirst { it.trim() == "Current Network Information:" }
        if (heading < 0) return null
        return lines.drop(heading + 1)
            .firstOrNull(String::isNotBlank)
            ?.trim()
            ?.takeIf { it.endsWith(":") }
            ?.removeSuffix(":")
            ?.takeUnless(::isHidden)
    }

    private fun isHidden(name: String) = name.isBlank() || name.equals("<redacted>", ignoreCase = true)
}
