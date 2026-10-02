package com.syncdroid.shared.sync

import com.syncdroid.shared.protocol.FileBlock
import com.syncdroid.shared.protocol.FolderIndexUpdate
import com.syncdroid.shared.protocol.IndexedFileRecord
import com.syncdroid.shared.protocol.MeshSessionMessage
import com.syncdroid.shared.protocol.MeshSessionWireCodec
import com.syncdroid.shared.protocol.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexFittingTest {
    private val device = "d".repeat(64)
    private val hash = "a".repeat(64)

    @Test
    fun smallBatchesAreSentUnchanged() {
        val update = update("photos", fullIndex = true, files = records(10, blocksEach = 0))

        assertEquals(listOf(update), fitIndexUpdates(listOf(update)))
    }

    @Test
    fun largeFolderIsSentInValidPartsThatEachFitOneMessage() {
        // 300 large videos with 1,000 blocks each would encode to about 25 MiB in one batch.
        val full = update("videos", fullIndex = true, files = records(300, blocksEach = 1_000))
        var receiver: IndexStateSnapshot? = null
        var sent = 0
        while (sent < full.files.size) {
            val remaining = if (receiver == null) full else full.copy(
                previousSequence = receiver.maxSequence,
                fullIndex = false,
                files = full.files.filter { it.sequence > receiver!!.maxSequence },
            )
            val part = fitIndexUpdates(listOf(remaining)).single()
            assertTrue(MeshSessionWireCodec.encode(MeshSessionMessage.IndexBatch(listOf(part))).size <= INDEX_BATCH_BUDGET_BYTES)
            validateFolderIndexUpdate(part)
            val decision = reconcileReceivedIndex(receiver, part.indexEpoch, part.previousSequence, part.lastSequence, part.fullIndex)
            receiver = (decision as IndexReceiveDecision.Accepted).next
            assertTrue(part.files.isNotEmpty())
            sent += part.files.size
        }
        assertEquals(full.lastSequence, receiver!!.maxSequence)
    }

    @Test
    fun foldersBeyondTheBudgetWaitForTheNextSession() {
        val first = update("first", fullIndex = true, files = records(200, blocksEach = 500))
        val second = update("second", fullIndex = true, files = records(5, blocksEach = 0))

        val fitted = fitIndexUpdates(listOf(first, second), budgetBytes = 4L * 1024 * 1024)

        assertEquals(listOf("first"), fitted.map(FolderIndexUpdate::folderId))
        assertTrue(fitted.single().files.size < first.files.size)
    }

    private fun update(folderId: String, fullIndex: Boolean, files: List<IndexedFileRecord>) =
        FolderIndexUpdate(folderId, 7L, 0L, files.last().sequence, fullIndex, files)

    private fun records(count: Int, blocksEach: Int) = List(count) { index ->
        IndexedFileRecord(
            relativePath = "media/clip-$index.mp4",
            fileId = "file-$index",
            sizeBytes = blocksEach * 131_072L,
            modifiedAtMillis = 1_000L,
            contentSha256 = hash,
            previousContentSha256 = null,
            originDeviceId = device,
            deleted = false,
            version = VersionVector(mapOf(device to index + 1L)),
            sequence = index + 1L,
            blockSizeBytes = if (blocksEach > 0) 131_072 else 0,
            blocks = List(blocksEach) { FileBlock(it, it * 131_072L, 131_072, hash) },
        )
    }
}
