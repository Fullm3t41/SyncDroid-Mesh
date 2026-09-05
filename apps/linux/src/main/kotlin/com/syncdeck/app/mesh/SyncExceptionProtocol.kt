package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.createSignedSyncExceptionEvent
import com.syncdroid.shared.protocol.verifyEcdsaSha256
import java.security.PublicKey

fun SyncExceptionEvent.verifySignature(publicKey: PublicKey): Boolean =
    verifyEcdsaSha256(publicKey, canonicalPayload(), signatureBase64)

fun com.syncdroid.shared.protocol.SyncExceptionEvent.Companion.create(
    groupId: String,
    folderId: String,
    relativePath: String,
    active: Boolean,
    signer: DeviceSigner,
    version: VersionVector,
    createdAtMillis: Long = System.currentTimeMillis(),
): SyncExceptionEvent = createSignedSyncExceptionEvent(
    groupId, folderId, relativePath, active, signer.deviceId, version, createdAtMillis, signer::sign,
)
