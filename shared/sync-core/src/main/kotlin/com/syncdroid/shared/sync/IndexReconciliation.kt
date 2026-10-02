package com.syncdroid.shared.sync

import com.syncdroid.shared.protocol.FolderIndexUpdate
import com.syncdroid.shared.protocol.IndexedFileRecord

data class IndexStateSnapshot(
    val indexEpoch: Long,
    val maxSequence: Long,
    val metadataReceivedSequence: Long,
    val contentAppliedSequence: Long,
)

sealed interface IndexReceiveDecision {
    data class Accepted(val next: IndexStateSnapshot) : IndexReceiveDecision
    data object RequiresFullIndex : IndexReceiveDecision
}

data class IndexExportRange(
    val fullIndex: Boolean,
    val previousSequence: Long,
    val lastSequence: Long,
)

fun reconcileReceivedIndex(
    current: IndexStateSnapshot?,
    incomingEpoch: Long,
    previousSequence: Long,
    lastSequence: Long,
    fullIndex: Boolean,
): IndexReceiveDecision {
    require(incomingEpoch != 0L) { "Index epoch cannot be zero" }
    require(previousSequence >= 0 && lastSequence >= previousSequence) { "Invalid index sequence range" }
    val epochChanged = current != null && current.indexEpoch != incomingEpoch
    if ((current == null || epochChanged) && !fullIndex) return IndexReceiveDecision.RequiresFullIndex
    val expectedPrevious = if (fullIndex || epochChanged) 0L else current?.maxSequence ?: 0L
    if (previousSequence != expectedPrevious) return IndexReceiveDecision.RequiresFullIndex
    return IndexReceiveDecision.Accepted(
        IndexStateSnapshot(
            incomingEpoch,
            lastSequence,
            lastSequence,
            if (epochChanged || fullIndex) 0L else current?.contentAppliedSequence ?: 0L,
        ),
    )
}

fun planIndexExport(
    localEpoch: Long,
    localMaxSequence: Long,
    peerKnownEpoch: Long?,
    peerReceivedSequence: Long?,
): IndexExportRange? {
    require(localEpoch != 0L && localMaxSequence >= 0L)
    val full = peerKnownEpoch == null || peerReceivedSequence == null ||
        peerKnownEpoch != localEpoch || peerReceivedSequence > localMaxSequence
    val previous = if (full) 0L else peerReceivedSequence
    if (!full && previous == localMaxSequence) return null
    return IndexExportRange(full, previous, localMaxSequence)
}

fun acknowledgeIndexContent(
    current: IndexStateSnapshot,
    indexEpoch: Long,
    sequence: Long,
): IndexStateSnapshot {
    require(current.indexEpoch == indexEpoch) { "Acknowledgement belongs to a stale index epoch" }
    require(sequence in current.contentAppliedSequence..current.metadataReceivedSequence) {
        "Applied acknowledgement is outside the received metadata range"
    }
    return current.copy(contentAppliedSequence = sequence)
}

/**
 * Trims index updates so their batch fits one session message, which the transport caps at
 * 16 MiB. A trimmed update keeps a prefix of its records and ends its range at the last one kept;
 * peers, including older versions, accept it and receive the remainder in their next session.
 * Folders after the budget runs out wait for a later session too.
 */
fun fitIndexUpdates(
    updates: List<FolderIndexUpdate>,
    budgetBytes: Long = INDEX_BATCH_BUDGET_BYTES,
): List<FolderIndexUpdate> {
    var remaining = budgetBytes
    val fitted = mutableListOf<FolderIndexUpdate>()
    for (update in updates) {
        var used = UPDATE_OVERHEAD_BYTES + 3L * update.folderId.length
        var kept = 0
        for (record in update.files) {
            val size = estimatedRecordBytes(record)
            // Always make progress: the first folder sends at least one record.
            if (used + size > remaining && (kept > 0 || fitted.isNotEmpty())) break
            used += size
            kept++
        }
        if (kept == update.files.size && used <= remaining) {
            fitted += update
            remaining -= used
            continue
        }
        if (kept > 0) fitted += update.copy(files = update.files.take(kept), lastSequence = update.files[kept - 1].sequence)
        break
    }
    return fitted
}

/** An upper bound on a record's encoded size: strings take at most three bytes per UTF-16 unit. */
private fun estimatedRecordBytes(record: IndexedFileRecord): Long =
    RECORD_OVERHEAD_BYTES +
        3L * (record.relativePath.length + record.fileId.length + record.originDeviceId.length) +
        record.version.counters.keys.sumOf { 3L * it.length + VERSION_ENTRY_OVERHEAD_BYTES } +
        record.blocks.sumOf { BLOCK_OVERHEAD_BYTES + 3L * it.sha256.length }

/** Half the transport limit, leaving room for the other fields of the session message. */
const val INDEX_BATCH_BUDGET_BYTES = 8L * 1024 * 1024
private const val UPDATE_OVERHEAD_BYTES = 64L
private const val RECORD_OVERHEAD_BYTES = 512L
private const val VERSION_ENTRY_OVERHEAD_BYTES = 32L
private const val BLOCK_OVERHEAD_BYTES = 32L

fun validateFolderIndexUpdate(update: FolderIndexUpdate) {
    require(update.indexEpoch != 0L && update.previousSequence >= 0 && update.lastSequence >= update.previousSequence)
    var sequence = update.previousSequence
    val paths = mutableSetOf<String>()
    update.files.forEach { file ->
        require(file.sequence > sequence && file.sequence <= update.lastSequence) {
            "Index sequences are not increasing"
        }
        sequence = file.sequence
        require(paths.add(normalizeRelativePath(file.relativePath))) { "Index contains a duplicate path" }
        require(file.fileId.isNotBlank())
        require(file.originDeviceId.isNotBlank())
        require(file.sizeBytes >= 0 && file.modifiedAtMillis >= 0)
        require(file.deleted || file.contentSha256.matches(HASH_PATTERN)) { "Invalid content hash" }
        require(file.previousContentSha256?.matches(HASH_PATTERN) != false) { "Invalid parent content hash" }
        var expectedOffset = 0L
        file.blocks.forEachIndexed { index, block ->
            require(block.index == index && block.offsetBytes == expectedOffset && block.sizeBytes >= 0)
            require(block.sha256.matches(HASH_PATTERN)) { "Invalid block hash" }
            expectedOffset += block.sizeBytes
        }
        if (file.blocks.isNotEmpty()) require(file.blockSizeBytes > 0)
        if (!file.deleted && file.blocks.isNotEmpty()) require(expectedOffset == file.sizeBytes)
    }
    if (update.files.isNotEmpty()) require(sequence == update.lastSequence) {
        "Index last sequence does not match its records"
    }
}

private val HASH_PATTERN = Regex("[a-fA-F0-9]{64}")
