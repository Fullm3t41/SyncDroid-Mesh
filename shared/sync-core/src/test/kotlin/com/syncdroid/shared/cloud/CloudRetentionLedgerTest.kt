package com.syncdroid.shared.cloud

import java.nio.file.Files
import kotlin.test.*

class CloudRetentionLedgerTest {
    @Test fun waitsThirtyDaysAndProtectsLiveAndOtherPublishersFiles() {
        val root = Files.createTempDirectory("retention-")
        try {
            val path = root.resolve("ledger.json")
            val orphan = CloudRemoteItem("old", "owned-old.sdenc", 1, false)
            val live = CloudRemoteItem("live", "owned-live.sdenc", 1, false)
            val other = CloudRemoteItem("other", "other-old.sdenc", 1, false)
            val items = listOf(orphan, live, other)
            val start = 1000L
            val age = CloudRetentionLedger.RETENTION_MILLIS
            assertTrue(CloudRetentionLedger(path).expiredObjects(items, setOf(live.name), listOf("owned-"), start).isEmpty())
            assertTrue(CloudRetentionLedger(path).expiredObjects(items, setOf(live.name), listOf("owned-"), start + age - 1).isEmpty())
            assertEquals(listOf(orphan), CloudRetentionLedger(path).expiredObjects(items, setOf(live.name), listOf("owned-"), start + age))
            CloudRetentionLedger(path).expiredObjects(items, setOf(live.name, orphan.name), listOf("owned-"), start + age)
            assertTrue(CloudRetentionLedger(path).expiredObjects(items, setOf(live.name), listOf("owned-"), start + age + 1).isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }
}
