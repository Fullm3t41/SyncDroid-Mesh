package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.protocol.MeshSessionMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class CrossPlatformWireCodecFixtureTest {
    private val fixture = loadWireFixture()

    @Test
    fun macAdaptersPreservePairingSessionAndTransferBytes() {
        assertGolden(
            "pairing.confirmation",
            PairingWireCodec.encode(PairingConfirmation("invite-1", PairingRole.Joiner, byteArrayOf(0, 1, 2, 0xff.toByte()))),
        )
        assertGolden("session.error", MeshSessionCodec.encode(MeshSessionMessage.Error("retry")))
        assertGolden("transfer.error", FileTransferWireCodec.encode(FileTransferMessage.Error("missing")))

        assertGolden(
            "pairing.confirmation",
            PairingWireCodec.encode(PairingWireCodec.decode(fixture.required("pairing.confirmation").hexToBytes())),
        )
        assertGolden(
            "session.error",
            MeshSessionCodec.encode(MeshSessionCodec.decode(fixture.required("session.error").hexToBytes())),
        )
        assertGolden(
            "transfer.error",
            FileTransferWireCodec.encode(FileTransferWireCodec.decode(fixture.required("transfer.error").hexToBytes())),
        )
        assertGolden(
            "mesh.bundle",
            MeshWireCodec.encode(MeshWireCodec.decode(fixture.required("mesh.bundle").hexToBytes())),
        )
        assertGolden(
            "pairing.completion",
            PairingCompletionCodec.encode(
                PairingCompletionCodec.decode(fixture.required("pairing.completion").hexToBytes()),
            ),
        )
        assertGolden(
            "session.peerProof",
            StablePeerProofCodec.encode(
                StablePeerProofCodec.decode(fixture.required("session.peerProof").hexToBytes()),
            ),
        )
    }

    private fun assertGolden(key: String, bytes: ByteArray) = assertEquals(fixture.required(key), bytes.fixtureHex())
}

private fun loadWireFixture(): Properties {
    val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("protocol/fixtures/wire-codecs-v1.properties") }
        .first(Files::isRegularFile)
    return Properties().apply { Files.newInputStream(file).use(::load) }
}

private fun Properties.required(key: String): String = requireNotNull(getProperty(key))
private fun ByteArray.fixtureHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
