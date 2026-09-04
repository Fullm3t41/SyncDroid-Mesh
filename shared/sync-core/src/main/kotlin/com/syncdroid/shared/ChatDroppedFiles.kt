package com.syncdroid.shared

import java.net.URI
import java.nio.file.Path

/** Desktop Compose exposes file-list entries as file: URIs, not filesystem strings. */
fun chatDroppedFiles(entries: List<String>): List<Path> = entries.mapNotNull { entry ->
    runCatching {
        val path = if (entry.startsWith("file:", ignoreCase = true)) {
            val uri = URI(entry)
            Path.of(if (uri.authority.equals("localhost", ignoreCase = true))
                URI("file", null, uri.path, null) else uri)
        } else Path.of(entry)
        path.takeIf { it.isAbsolute }?.normalize()
    }.getOrNull()
}.distinct()
