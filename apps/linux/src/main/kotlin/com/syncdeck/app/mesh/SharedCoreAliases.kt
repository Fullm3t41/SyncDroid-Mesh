package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.canonicalBytes as sharedCanonicalBytes
import com.syncdroid.shared.protocol.eventIdFor as sharedEventIdFor
import com.syncdroid.shared.protocol.sha256 as sharedSha256

typealias CausalRelation = com.syncdroid.shared.protocol.CausalRelation
typealias VersionVector = com.syncdroid.shared.protocol.VersionVector
typealias FileSyncAction = com.syncdroid.shared.sync.FileSyncAction
typealias PairingRole = com.syncdroid.shared.protocol.PairingRole
typealias PairingIdentity = com.syncdroid.shared.protocol.PairingIdentity
typealias PairingRound1 = com.syncdroid.shared.protocol.PairingRound1
typealias PairingRound2 = com.syncdroid.shared.protocol.PairingRound2
typealias PairingRound3 = com.syncdroid.shared.protocol.PairingRound3
typealias PairingConfirmation = com.syncdroid.shared.protocol.PairingConfirmation
typealias StablePeerProof = com.syncdroid.shared.protocol.StablePeerProof
typealias SyncExceptionEvent = com.syncdroid.shared.protocol.SyncExceptionEvent
typealias FolderClock = com.syncdroid.shared.protocol.FolderClock
typealias FileBlock = com.syncdroid.shared.protocol.FileBlock
typealias IndexedFileRecord = com.syncdroid.shared.protocol.IndexedFileRecord
typealias FolderIndexUpdate = com.syncdroid.shared.protocol.FolderIndexUpdate

internal typealias CanonicalOutput = com.syncdroid.shared.protocol.CanonicalOutput

internal fun canonicalBytes(block: CanonicalOutput.() -> Unit): ByteArray = sharedCanonicalBytes(block)
internal fun sha256(value: ByteArray): ByteArray = sharedSha256(value)
internal fun eventIdFor(payload: ByteArray): String = sharedEventIdFor(payload)
