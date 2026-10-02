package com.syncdroid.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageNameProblemTest {
    private val tree = mapOf(
        "" to listOf("Photos", "notes.txt"),
        "Photos" to listOf("beach.jpg"),
    )
    private val list: (List<String>) -> List<String>? = { parts -> tree[parts.joinToString("/")] }

    @Test fun exactAndNewNamesCanBeStored() {
        assertNull(storageNameProblem("notes.txt", list))
        assertNull(storageNameProblem("Photos/beach.jpg", list))
        assertNull(storageNameProblem("Photos/new.jpg", list))
        assertNull(storageNameProblem("Videos/clip.mp4", list))
    }

    @Test fun capitalizationVariantsClashWithExistingEntries() {
        assertEquals(CASE_CLASH_REASON, storageNameProblem("Notes.txt", list))
        assertEquals(CASE_CLASH_REASON, storageNameProblem("photos/new.jpg", list))
        assertEquals(CASE_CLASH_REASON, storageNameProblem("Photos/Beach.jpg", list))
    }

    @Test fun charactersSharedStorageReplacesAreRejected() {
        assertEquals(UNSUPPORTED_NAME_REASON, storageNameProblem("notes: draft.txt", list))
        assertEquals(UNSUPPORTED_NAME_REASON, storageNameProblem("what?.txt", list))
    }
}
