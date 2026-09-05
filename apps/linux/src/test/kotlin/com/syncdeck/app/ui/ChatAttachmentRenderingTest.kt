package com.syncdeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.syncdeck.app.mesh.MeshChatMessage
import com.syncdroid.shared.protocol.WireChatAttachment
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class ChatAttachmentRenderingTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun rendersInlineImageAndCompactDropPrompt() {
        val file = Files.createTempFile("chat-preview", ".png")
        val photo = BufferedImage(600, 320, BufferedImage.TYPE_INT_RGB)
        photo.createGraphics().apply {
            color = Color(33, 110, 130); fillRect(0, 0, 600, 320)
            color = Color(255, 220, 100); fillOval(410, 40, 100, 100)
            color = Color(30, 160, 100); fillPolygon(intArrayOf(0, 230, 420, 600), intArrayOf(320, 120, 280, 320), 4)
            dispose()
        }
        ImageIO.write(photo, "png", file.toFile()); photo.flush()
        val created = System.currentTimeMillis()
        val message = MeshChatMessage("preview", "group", "peer", "A picture from my device", created, "",
            WireChatAttachment("Holiday picture.png", "image/png", Files.size(file), "0".repeat(64), created + 86_400_000))
        var scene: ImageComposeScene? = null
        var promptSize = IntSize.Zero
        try {
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(width = 800, height = 650) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            ChatScreen(listOf(message), "local", mapOf("peer" to "Other device"), true,
                                {}, {}, {}, {}, { file })
                            ChatDropPrompt(Modifier.align(Alignment.BottomCenter).padding(bottom = 94.dp)
                                .onGloballyPositioned { promptSize = it.size })
                        }
                    }
                }
                scene!!.render().close()
            }
            // Allow the real asynchronous local preview decoder to complete between UI frames.
            repeat(20) {
                Thread.sleep(50)
                SwingUtilities.invokeAndWait { scene!!.render().close() }
            }
            SwingUtilities.invokeAndWait {
                scene!!.render().use { image ->
                    image.encodeToData()!!.use { File("build/chat-attachment-preview.png").writeBytes(it.bytes) }
                }
                assertTrue(promptSize.width in 1..340 && promptSize.height in 1..180, promptSize.toString())
            }
        } finally {
            SwingUtilities.invokeAndWait { scene?.close() }
            Files.delete(file)
        }
    }
}
