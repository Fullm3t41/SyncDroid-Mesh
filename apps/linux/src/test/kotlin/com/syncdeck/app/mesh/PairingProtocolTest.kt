package com.syncdeck.app.mesh

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PairingProtocolTest {
    @Test
    fun matchingCodeAuthenticatesTranscriptAndIdentity() {
        val inviterSigner = signer()
        val joinerSigner = signer()
        val inviter = PairingHandshake(
            PairingRole.Inviter, "invite-1", "123456", PairingIdentity.from(inviterSigner, "Mac"),
        )
        val joiner = PairingHandshake(
            PairingRole.Joiner, "invite-1", "123456", PairingIdentity.from(joinerSigner, "Android"),
        )

        val inviterRound1 = roundTrip<PairingRound1>(inviter.createRound1())
        val joinerRound1 = roundTrip<PairingRound1>(joiner.createRound1())
        inviter.receiveRound1(joinerRound1); joiner.receiveRound1(inviterRound1)
        val inviterRound2 = roundTrip<PairingRound2>(inviter.createRound2())
        val joinerRound2 = roundTrip<PairingRound2>(joiner.createRound2())
        inviter.receiveRound2(joinerRound2); joiner.receiveRound2(inviterRound2)
        val inviterRound3 = roundTrip<PairingRound3>(inviter.createRound3())
        val joinerRound3 = roundTrip<PairingRound3>(joiner.createRound3())
        inviter.receiveRound3(joinerRound3); joiner.receiveRound3(inviterRound3)

        val inviterResult = inviter.finish(roundTrip(joiner.createConfirmation()))
        val joinerResult = joiner.finish(roundTrip(inviter.createConfirmation()))
        assertEquals(joinerSigner.deviceId, inviterResult.remoteIdentity.deviceId)
        assertEquals(inviterSigner.deviceId, joinerResult.remoteIdentity.deviceId)
        assertContentEquals(inviterResult.sessionKey, joinerResult.sessionKey)
    }

    @Test
    fun differentCodesDoNotAuthenticate() {
        val inviter = PairingHandshake(PairingRole.Inviter, "invite-2", "111111", PairingIdentity.from(signer(), "Mac"))
        val joiner = PairingHandshake(PairingRole.Joiner, "invite-2", "222222", PairingIdentity.from(signer(), "Android"))
        val inviterRound1 = inviter.createRound1(); val joinerRound1 = joiner.createRound1()
        inviter.receiveRound1(joinerRound1); joiner.receiveRound1(inviterRound1)
        val inviterRound2 = inviter.createRound2(); val joinerRound2 = joiner.createRound2()
        assertFails {
            inviter.receiveRound2(joinerRound2); joiner.receiveRound2(inviterRound2)
            inviter.receiveRound3(joiner.createRound3())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: Any): T = PairingWireCodec.decode(PairingWireCodec.encode(value)) as T

    private fun signer(): DeviceSigner {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        return object : DeviceSigner {
            override val deviceId = deviceIdFor(pair.public)
            override val publicKey = pair.public
            override fun sign(payload: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private); update(payload); sign()
            }
        }
    }
}
