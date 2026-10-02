package com.syncdroid.app.mesh

import com.syncdroid.app.data.FolderAnnouncementEntity
import com.syncdroid.app.data.MembershipEventEntity
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.data.SyncExceptionEventEntity
import com.syncdroid.app.sync.FolderExceptionRepository
import com.syncdroid.app.sync.VersionVector
import android.util.Log
import com.syncdroid.shared.protocol.newestChatWithinBudget
import kotlinx.coroutines.CancellationException
import org.json.JSONArray

class MeshReplicationRepository(
    database: SyncDroidDatabase,
    signer: DeviceSigner,
) {
    private val meshDao = database.meshDao()
    private val syncDao = database.syncDao()
    private val memberships = MeshMembershipRepository(meshDao)
    private val folders = MeshFolderRepository(database, signer.deviceId)
    private val exceptions = FolderExceptionRepository(database, signer)
    private val chat = MeshChatRepository(database, signer)
    private val chatDao = database.chatDao()

    suspend fun export(groupId: String, groupName: String): MeshStateBundle = MeshStateBundle(
        groupName = groupName,
        membershipEvents = meshDao.membershipEvents(groupId).map(MembershipEventEntity::toDomain),
        folderAnnouncements = syncDao.folderAnnouncements(groupId).map(FolderAnnouncementEntity::toDomain),
        syncExceptionEvents = syncDao.syncExceptionEvents(groupId).map(SyncExceptionEventEntity::toDomain),
        chatMessages = newestChatWithinBudget(
            chatDao.recentMessages(groupId, MAX_REPLICATED_CHAT_MESSAGES).asReversed().map { it.toDomain() },
        ) { it.body.length },
    )

    suspend fun receive(bundle: MeshStateBundle): MeshReceiveResult {
        require(bundle.membershipEvents.isNotEmpty()) { "A mesh bundle must include its membership proof" }
        val groupIds = bundle.membershipEvents.map(MembershipEvent::groupId).toSet() +
            bundle.folderAnnouncements.map(FolderAnnouncement::groupId) +
            bundle.syncExceptionEvents.map(SyncExceptionEvent::groupId) +
            bundle.chatMessages.map(MeshChatMessage::groupId)
        require(groupIds.size == 1) { "A mesh bundle cannot mix groups" }
        val groupId = groupIds.single()
        val replicatedItemCountBefore = replicatedItemCount(groupId)

        memberships.rebuildProjection(groupId, bundle.groupName)

        val remaining = bundle.membershipEvents.sortedBy(MembershipEvent::createdAtMillis).toMutableList()
        var madeProgress: Boolean
        do {
            madeProgress = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                val result = memberships.apply(bundle.groupName, event)
                if (result.isSuccess) {
                    iterator.remove()
                    madeProgress = true
                }
            }
        } while (madeProgress && remaining.isNotEmpty())
        // An event that never validates, such as one a removed device signed after its removal,
        // is left out instead of failing every exchange with the peer that relays it.
        if (remaining.isNotEmpty()) {
            Log.w(TAG, "Skipped ${remaining.size} membership events without a trusted signer")
        }

        bundle.folderAnnouncements
            .sortedWith(compareBy(FolderAnnouncement::createdAtMillis, FolderAnnouncement::eventId))
            .forEach { skipInvalid("folder announcement") { folders.receive(it) } }
        bundle.syncExceptionEvents
            .sortedWith(compareBy(SyncExceptionEvent::createdAtMillis, SyncExceptionEvent::eventId))
            .forEach { skipInvalid("sync exception") { exceptions.receive(it) } }
        val newChatMessages = bundle.chatMessages
            .sortedWith(compareBy(MeshChatMessage::createdAtMillis, MeshChatMessage::messageId))
            .filter { skipInvalid("chat message") { chat.receive(it) } == true }
        return MeshReceiveResult(
            newChatMessages = newChatMessages,
            replicatedStateChanged = replicatedItemCount(groupId) > replicatedItemCountBefore,
        )
    }

    private suspend fun replicatedItemCount(groupId: String): Int =
        meshDao.membershipEvents(groupId).size +
            syncDao.folderAnnouncements(groupId).size +
            syncDao.syncExceptionEvents(groupId).size +
            chatDao.recentMessages(groupId, MAX_REPLICATED_CHAT_MESSAGES).size
}

data class MeshReceiveResult(
    val newChatMessages: List<MeshChatMessage> = emptyList(),
    val replicatedStateChanged: Boolean = false,
)

private const val MAX_REPLICATED_CHAT_MESSAGES = 5_000
private const val TAG = "SyncDroidMesh"

/** Skips one invalid replicated item; database and cancellation errors still end the exchange. */
private inline fun <T> skipInvalid(kind: String, receive: () -> T): T? = try {
    receive()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (invalid: IllegalArgumentException) {
    Log.w(TAG, "Skipped an invalid $kind: ${invalid.message}")
    null
} catch (invalid: IllegalStateException) {
    Log.w(TAG, "Skipped an invalid $kind: ${invalid.message}")
    null
}

private fun MembershipEventEntity.toDomain() = MembershipEvent(
    eventId = eventId,
    groupId = groupId,
    eventType = MembershipEventType.valueOf(eventType),
    subjectDeviceId = subjectDeviceId,
    subjectDisplayName = subjectDisplayName,
    subjectPublicKeyBase64 = subjectPublicKeyBase64,
    signerDeviceId = signerDeviceId,
    parentEventIds = JSONArray(parentEventIdsJson).strings(),
    version = VersionVector.fromJson(versionVectorJson),
    createdAtMillis = createdAtMillis,
    signatureBase64 = signatureBase64,
)

private fun FolderAnnouncementEntity.toDomain() = FolderAnnouncement(
    eventId = eventId,
    groupId = groupId,
    folderId = folderId,
    displayName = displayName,
    includePatterns = JSONArray(includePatternsJson).strings(),
    excludePatterns = JSONArray(excludePatternsJson).strings(),
    signerDeviceId = signerDeviceId,
    version = VersionVector.fromJson(versionVectorJson),
    createdAtMillis = createdAtMillis,
    signatureBase64 = signatureBase64,
)

private fun SyncExceptionEventEntity.toDomain() = SyncExceptionEvent(
    eventId = eventId,
    groupId = groupId,
    folderId = folderId,
    relativePath = relativePath,
    active = active,
    signerDeviceId = signerDeviceId,
    version = VersionVector.fromJson(versionVectorJson),
    createdAtMillis = createdAtMillis,
    signatureBase64 = signatureBase64,
)

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
