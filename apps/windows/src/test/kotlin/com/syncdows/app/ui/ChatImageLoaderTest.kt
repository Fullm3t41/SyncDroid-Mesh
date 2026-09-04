package com.syncdows.app.ui

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatImageLoaderTest {
    @Test fun samplesLargePhotosAndHandlesMissingMimeType() {
        val file = Files.createTempFile("chat-image", ".PNG")
        try {
            val source = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
            ImageIO.write(source, "png", file.toFile())
            source.flush()
            val preview = assertNotNull(loadChatImage(file, "photo.PNG", ""))
            assertTrue(preview.width <= 1024 && preview.height <= 1024)
        } finally { Files.delete(file) }
    }

    @Test fun rendersWebpWithTheBundledDecoder() {
        val file = Files.createTempFile("chat-webp", ".webp")
        try {
            val bytes = java.io.ByteArrayOutputStream()
            ImageIO.write(BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png", bytes)
            org.jetbrains.skia.Image.makeFromEncoded(bytes.toByteArray()).use { image ->
                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP)!!.use { data ->
                    Files.write(file, data.bytes)
                }
            }
            assertNotNull(loadChatImage(file, "photo.webp", "image/webp"))
        } finally { Files.delete(file) }
    }

    @Test fun corruptMissingAndNonImageFilesUseAttachmentFallback() {
        val file = Files.createTempFile("chat-document", ".png")
        try {
            Files.writeString(file, "This is not an image")
            assertNull(loadChatImage(file, "corrupt.png", "image/png"))
            assertNull(loadChatImage(file, "report.txt", "text/plain"))
            assertNull(loadChatImage(file.resolveSibling("missing-file.png"), "missing.png", "image/png"))
        } finally { Files.delete(file) }
    }
}
