package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.MeshBundleWireCodec
import com.syncdroid.shared.protocol.MeshStateBundleWire
import com.syncdroid.shared.protocol.WireChatMessage
import com.syncdroid.shared.protocol.WireFolderAnnouncement
import com.syncdroid.shared.protocol.WireMembershipEvent

data class MeshStateBundle(
    val groupName: String,
    val membershipEvents: List<MembershipEvent>,
    val folderAnnouncements: List<FolderAnnouncement> = emptyList(),
    val syncExceptionEvents: List<SyncExceptionEvent> = emptyList(),
    val chatMessages: List<MeshChatMessage> = emptyList(),
)

object MeshWireCodec {
    fun encode(bundle: MeshStateBundle): ByteArray = MeshBundleWireCodec.encode(bundle.toWire())
    fun decode(bytes: ByteArray): MeshStateBundle = MeshBundleWireCodec.decode(bytes).toDomain()
}

private fun MeshStateBundle.toWire() = MeshStateBundleWire(
    groupName,
    membershipEvents.map { event ->
        WireMembershipEvent(
            event.eventId, event.groupId, event.eventType.name, event.subjectDeviceId,
            event.subjectDisplayName, event.subjectPublicKeyBase64, event.signerDeviceId,
            event.parentEventIds, event.version, event.createdAtMillis, event.signatureBase64,
        )
    },
    folderAnnouncements.map { event ->
        WireFolderAnnouncement(
            event.eventId, event.groupId, event.folderId, event.displayName,
            event.includePatterns, event.excludePatterns, event.signerDeviceId,
            event.version, event.createdAtMillis, event.signatureBase64,
        )
    },
    syncExceptionEvents,
    chatMessages.map { message ->
        WireChatMessage(
            message.messageId, message.groupId, message.authorDeviceId,
            message.body, message.createdAtMillis, message.signatureBase64, message.attachment,
        )
    },
)

private fun MeshStateBundleWire.toDomain() = MeshStateBundle(
    groupName,
    membershipEvents.map { event ->
        MembershipEvent(
            event.eventId, event.groupId, MembershipEventType.valueOf(event.eventType), event.subjectDeviceId,
            event.subjectDisplayName, event.subjectPublicKeyBase64, event.signerDeviceId,
            event.parentEventIds, event.version, event.createdAtMillis, event.signatureBase64,
        )
    },
    folderAnnouncements.map { event ->
        FolderAnnouncement(
            event.eventId, event.groupId, event.folderId, event.displayName,
            event.includePatterns, event.excludePatterns, event.signerDeviceId,
            event.version, event.createdAtMillis, event.signatureBase64,
        )
    },
    syncExceptionEvents,
    chatMessages.map { message ->
        MeshChatMessage(
            message.messageId, message.groupId, message.authorDeviceId,
            message.body, message.createdAtMillis, message.signatureBase64, message.attachment,
        )
    },
)
