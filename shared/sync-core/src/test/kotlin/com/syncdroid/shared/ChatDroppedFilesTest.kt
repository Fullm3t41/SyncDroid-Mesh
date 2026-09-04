package com.syncdroid.shared

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatDroppedFilesTest {
    @Test fun acceptsFileUrisWithSpacesUnicodeAndLiteralPercentSigns() {
        val root = Files.createTempDirectory("chat drop ")
        try {
            val path = root.resolve("holiday #1 100% café.png")
            assertEquals(listOf(path), chatDroppedFiles(listOf(path.toUri().toString(), path.toString())))
        } finally { Files.delete(root) }
    }

    @Test fun rejectsUnsupportedAndMalformedEntriesWithoutLosingValidFiles() {
        val root = Files.createTempDirectory("chat-drop")
        try {
            assertEquals(listOf(root), chatDroppedFiles(listOf("https://example.com/a.png", "relative.png",
                "file:%ZZ", "\u0000", root.toUri().toString())))
        } finally { Files.delete(root) }
    }
}
