package com.syncdroid.app.model

data class PeerDevice(
    val deviceId: String,
    val name: String,
    val detail: String,
    val online: Boolean,
    val initials: String,
    val lastOnlineAtMillis: Long? = null,
    val syncing: Boolean = false,
    val syncProgress: Float? = null,
)

data class SaveFolder(
    val game: String,
    val path: String,
    val level: Int,
    val updatedOn: String,
    val copies: Int,
    val status: SaveStatus,
    val filterSummary: String = "All files",
    val meshFolderId: String = path,
    val overwriteOnly: Boolean = false,
    val exceptionCount: Int = 0,
    val supportsFolderSettings: Boolean = false,
)

enum class SaveStatus { Synced, Syncing, Conflict, Configure, Declined }
