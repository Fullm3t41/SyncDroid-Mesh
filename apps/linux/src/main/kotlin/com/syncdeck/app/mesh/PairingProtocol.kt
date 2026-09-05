package com.syncdeck.app.mesh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest

fun PairingIdentity.decodePublicKey(): PublicKey = decodePublicKey(publicKeySpkiBase64)

fun com.syncdroid.shared.protocol.PairingIdentity.Companion.from(signer: DeviceSigner, displayName: String) = PairingIdentity(
    signer.deviceId,
    Base64.getEncoder().encodeToString(signer.publicKey.encoded),
    displayName,
)

data class PairingResult(val remoteIdentity: PairingIdentity, val sessionKey: ByteArray)

class PairingHandshake(
    private val role: PairingRole,
    private val invitationId: String,
    pairingCode: String,
    private val localIdentity: PairingIdentity,
    random: SecureRandom = SecureRandom(),
) {
    private val participantId = participantId(role, localIdentity.deviceId, invitationId)
    private val participant = JPAKEParticipant(
        participantId,
        pairingCode.also { require(it.matches(Regex("\\d{6}"))) }.toCharArray(),
        JPAKEPrimeOrderGroups.NIST_3072,
        SHA256Digest(),
        random,
    )
    private var localRound1: PairingRound1? = null
    private var remoteRound1: PairingRound1? = null
    private var localRound2: PairingRound2? = null
    private var remoteRound2: PairingRound2? = null
    private var keyMaterial: BigInteger? = null
    private var sessionKey: ByteArray? = null

    fun createRound1(): PairingRound1 {
        check(localRound1 == null)
        val value = participant.createRound1PayloadToSend()
        return PairingRound1(
            invitationId, role, localIdentity, value.participantId, value.gx1, value.gx2,
            value.knowledgeProofForX1.toList(), value.knowledgeProofForX2.toList(),
        ).also { localRound1 = it }
    }

    fun receiveRound1(value: PairingRound1) {
        check(remoteRound1 == null)
        require(value.invitationId == invitationId && value.role != role)
        require(value.identity.deviceId == deviceIdFor(value.identity.decodePublicKey()))
        require(value.participantId == participantId(value.role, value.identity.deviceId, invitationId))
        participant.validateRound1PayloadReceived(
            JPAKERound1Payload(
                value.participantId, value.gx1, value.gx2,
                value.proofX1.toTypedArray(), value.proofX2.toTypedArray(),
            ),
        )
        remoteRound1 = value
    }

    fun createRound2(): PairingRound2 {
        check(remoteRound1 != null && localRound2 == null)
        val value = participant.createRound2PayloadToSend()
        return PairingRound2(invitationId, role, value.participantId, value.a, value.knowledgeProofForX2s.toList())
            .also { localRound2 = it }
    }

    fun receiveRound2(value: PairingRound2) {
        val remote = requireNotNull(remoteRound1)
        check(remoteRound2 == null)
        require(value.invitationId == invitationId && value.role == remote.role && value.participantId == remote.participantId)
        participant.validateRound2PayloadReceived(
            JPAKERound2Payload(value.participantId, value.a, value.proofX2s.toTypedArray()),
        )
        remoteRound2 = value
    }

    fun createRound3(): PairingRound3 {
        val material = ensureSessionKey()
        val value = participant.createRound3PayloadToSend(material)
        return PairingRound3(invitationId, role, value.participantId, value.macTag)
    }

    fun receiveRound3(value: PairingRound3) {
        val remote = requireNotNull(remoteRound1)
        require(value.invitationId == invitationId && value.role == remote.role && value.participantId == remote.participantId)
        participant.validateRound3PayloadReceived(
            JPAKERound3Payload(value.participantId, value.macTag),
            requireNotNull(keyMaterial),
        )
    }

    fun createConfirmation() = PairingConfirmation(
        invitationId,
        role,
        transcriptMac(requireNotNull(sessionKey), role),
    )

    fun finish(remoteConfirmation: PairingConfirmation): PairingResult {
        val remote = requireNotNull(remoteRound1)
        require(remoteConfirmation.invitationId == invitationId && remoteConfirmation.role == remote.role)
        require(MessageDigest.isEqual(transcriptMac(requireNotNull(sessionKey), remote.role), remoteConfirmation.hmacSha256)) {
            "Pairing transcript authentication failed"
        }
        return PairingResult(remote.identity, requireNotNull(sessionKey).copyOf())
    }

    private fun ensureSessionKey(): BigInteger {
        keyMaterial?.let { return it }
        check(localRound2 != null && remoteRound2 != null)
        val material = participant.calculateKeyingMaterial()
        keyMaterial = material
        sessionKey = hkdfSha256(
            material.unsignedBytes(),
            sha256("syncdroid-jpake-v1:$invitationId".toByteArray(StandardCharsets.UTF_8)),
            sha256(transcript()),
            32,
        )
        return material
    }

    private fun transcriptMac(key: ByteArray, confirmingRole: PairingRole) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        update("syncdroid-pairing-confirm-v1".toByteArray(StandardCharsets.UTF_8))
        update(confirmingRole.name.toByteArray(StandardCharsets.UTF_8))
        doFinal(transcript())
    }

    private fun transcript(): ByteArray {
        val rounds1 = listOf(requireNotNull(localRound1), requireNotNull(remoteRound1)).sortedBy { it.role.ordinal }
        val rounds2 = listOf(requireNotNull(localRound2), requireNotNull(remoteRound2)).sortedBy { it.role.ordinal }
        return canonicalBytes {
            string("syncdroid-pairing-transcript-v1")
            string(invitationId)
            rounds1.forEach {
                string(it.role.name); string(it.identity.deviceId); string(it.identity.publicKeySpkiBase64)
                string(it.identity.displayName); string(it.participantId); string(it.gx1.toString(16)); string(it.gx2.toString(16))
                strings(it.proofX1.map { number -> number.toString(16) })
                strings(it.proofX2.map { number -> number.toString(16) })
            }
            rounds2.forEach {
                string(it.role.name); string(it.participantId); string(it.a.toString(16))
                strings(it.proofX2s.map { number -> number.toString(16) })
            }
        }
    }
}

object PairingWireCodec {
    fun encode(value: Any): ByteArray = com.syncdroid.shared.protocol.PairingWireCodec.encode(value)
    fun decode(bytes: ByteArray): Any = com.syncdroid.shared.protocol.PairingWireCodec.decode(bytes)
}

private fun participantId(role: PairingRole, deviceId: String, invitationId: String) =
    "${role.name.lowercase()}:$deviceId:$invitationId"

private fun BigInteger.unsignedBytes() = toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

private fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val extracted = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(input) }
    val output = ByteArrayOutputStream()
    var previous = ByteArray(0)
    var counter = 1
    while (output.size() < length) {
        previous = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extracted, "HmacSHA256")); update(previous); update(info); update(counter.toByte()); doFinal()
        }
        output.write(previous); counter++
    }
    return output.toByteArray().copyOf(length)
}
