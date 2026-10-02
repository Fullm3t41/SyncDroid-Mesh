package com.syncdows.app.mesh

import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.protocol.WireChatAttachment
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class AttachmentTransferTest {
    @Test
    fun anOversizedAttachmentIsRejectedWithoutDesynchronizingTheSession() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("syncdows-attachment-test")
        val sender = WindowsDeviceIdentity("sender", directory.resolve("sender.p12"), legacyKeyStoreFactory = null)
        val receiver = WindowsDeviceIdentity("receiver", directory.resolve("receiver.p12"), legacyKeyStoreFactory = null)
        MeshStore(directory.resolve("mesh.db")).use { store ->
            val profile = store.createMesh("Home mesh", "Receiver", receiver)
            val announced = "four".toByteArray()
            val createdAt = System.currentTimeMillis()
            val attachment = WireChatAttachment(
                "note.txt", "text/plain", announced.size.toLong(), sha256Hex(announced.inputStream()),
                createdAt + CHAT_ATTACHMENT_RETENTION_MILLIS,
            )
            val message = MeshChatMessage.create(profile.groupId, "", sender, createdAt, attachment)
            // An older sender streams the cached file after it grew, then continues the session.
            val server = MeshPeerServer(DeviceTlsContext(sender, allowUnknownPeer = true)) { connection ->
                FileTransferWireCodec.decode(connection.receive())
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileStart(4, message.createdAtMillis)))
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileChunk(0, "four and more".toByteArray())))
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileEnd(attachment.contentSha256)))
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("next session message")))
            }
            try {
                val port = server.start()
                MeshPeerClient(DeviceTlsContext(receiver, allowUnknownPeer = true))
                    .connect(InetAddress.getLoopbackAddress(), port)
                    .use { connection ->
                        assertFailsWith<IllegalStateException> {
                            ChatAttachmentStore(store, directory.resolve("attachments")).receive(connection, message)
                        }
                        val next = FileTransferWireCodec.decode(connection.receive())
                        assertEquals(FileTransferMessage.Error("next session message"), next)
                    }
            } finally {
                server.close()
            }
        }
    }
}
