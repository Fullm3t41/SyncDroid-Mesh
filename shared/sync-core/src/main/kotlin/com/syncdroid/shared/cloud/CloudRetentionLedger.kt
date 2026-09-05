package com.syncdroid.shared.cloud

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** The grace period starts when an object stops being referenced, not when it was uploaded. */
class CloudRetentionLedger(private val path: Path) {
    fun expiredObjects(
        items: Collection<CloudRemoteItem>, liveNames: Set<String>, ownedPrefixes: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<CloudRemoteItem> {
        // An unreadable ledger delays cleanup; it must never cause early deletion.
        val previous = runCatching { JSONObject(String(Files.readAllBytes(path), Charsets.UTF_8)) }.getOrElse { JSONObject() }
        val next = JSONObject()
        val expired = mutableListOf<CloudRemoteItem>()
        items.filter { !it.folder && it.name.endsWith(".sdenc") && it.name !in liveNames &&
            ownedPrefixes.any(it.name::startsWith)
        }.forEach { item ->
            val firstUnused = previous.optLong(item.id, nowMillis).takeIf { it in 1..nowMillis } ?: nowMillis
            next.put(item.id, firstUnused)
            if (nowMillis - firstUnused >= RETENTION_MILLIS) expired += item
        }
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "cloud-retention-", ".tmp")
        try {
            Files.write(temporary, next.toString().toByteArray(Charsets.UTF_8))
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        return expired
    }

    companion object { const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000 }
}
