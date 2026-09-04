package com.syncdroid.shared.sync

import kotlin.test.*

class RecoverableDocumentReplacementTest {
    private class Document(var name: String, var deleted: Boolean = false)

    @Test fun renameFailureRestoresOriginalWithoutDeletingIt() {
        val old = Document("save")
        val replacement = Document("temporary")
        assertFailsWith<IllegalStateException> {
            replaceDocumentRecoverably(old, replacement, "save", "backup",
                rename = { doc, name -> if (doc === replacement) false else { doc.name = name; true } },
                delete = { it.deleted = true; true })
        }
        assertEquals("save", old.name)
        assertFalse(old.deleted)
    }

    @Test fun failedRollbackLeavesRecoverableBackup() {
        val old = Document("save")
        val replacement = Document("temporary")
        val failure = assertFailsWith<IllegalStateException> {
            replaceDocumentRecoverably(old, replacement, "save", "backup",
                rename = { doc, name -> if (name == "backup") { doc.name = name; true } else false },
                delete = { it.deleted = true; true })
        }
        assertTrue(failure.message!!.contains("backup"))
        assertEquals("backup", old.name)
        assertFalse(old.deleted)
    }

    @Test fun originalIsRemovedOnlyAfterReplacementSucceeds() {
        val old = Document("save")
        val replacement = Document("temporary")
        replaceDocumentRecoverably(old, replacement, "save", "backup",
            rename = { doc, name -> assertFalse(old.deleted); doc.name = name; true },
            delete = { assertEquals("save", replacement.name); it.deleted = true; true })
        assertTrue(old.deleted)
        assertEquals("save", replacement.name)
    }

    @Test fun unsupportedBackupRenameLeavesOriginalUntouched() {
        val old = Document("save")
        assertFailsWith<IllegalStateException> {
            replaceDocumentRecoverably(old, Document("temporary"), "save", "backup",
                rename = { _, _ -> false }, delete = { fail("Must not delete the original") })
        }
        assertEquals("save", old.name)
        assertFalse(old.deleted)
    }
}
