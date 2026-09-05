package com.syncdeck.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

/** Read only local completed attachments, with limits before allocating decoded pixels. */
internal fun loadChatImage(path: Path, name: String, mediaType: String): ImageBitmap? {
    if (!mediaType.startsWith("image/", ignoreCase = true) &&
        name.substringAfterLast('.', "").lowercase() !in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")) return null
    return runCatching {
        require(Files.size(path) <= 32L * 1024 * 1024)
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            requireNotNull(input)
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return loadSkiaChatImage(path)
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= 50_000_000L)
                val sample = ceil(max(width, height) / 1024.0).toInt().coerceAtLeast(1)
                val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val bitmap = reader.read(0, params)
                try { bitmap.toComposeImageBitmap() } finally { bitmap.flush() }
            } finally { reader.dispose() }
        }
    }.getOrNull()
}

/** Skia supplies WebP support when no JVM ImageIO reader is installed. */
private fun loadSkiaChatImage(path: Path): ImageBitmap? = runCatching {
    org.jetbrains.skia.Data.makeFromBytes(Files.readAllBytes(path)).use { data ->
        org.jetbrains.skia.Codec.makeFromData(data).use { codec ->
            val width = codec.width
            val height = codec.height
            require(width > 0 && height > 0 && width.toLong() * height <= 50_000_000L)
            val scale = minOf(1.0, 1024.0 / max(width, height))
            org.jetbrains.skia.Bitmap().use { bitmap ->
                check(bitmap.allocPixels(org.jetbrains.skia.ImageInfo.makeN32Premul(
                    (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))))
                codec.readPixels(bitmap)
                org.jetbrains.skia.Image.makeFromBitmap(bitmap).toComposeImageBitmap()
            }
        }
    }
}.getOrNull()
