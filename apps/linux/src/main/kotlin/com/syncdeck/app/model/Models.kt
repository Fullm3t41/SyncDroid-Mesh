package com.syncdeck.app.model

enum class MainSection(val label: String) {
    Sync("Sync"),
    Folders("Folders"),
    Devices("Devices"),
    Chat("Chat"),
    Settings("Settings"),
}

enum class ThemeMode { System, Light, Dark }

data class MeshPeer(
    val deviceId: String,
    val name: String,
    val online: Boolean,
    val lastOnlineAtMillis: Long? = null,
    val syncing: Boolean = false,
    val syncProgress: Float? = null,
) {
    val initials: String = name
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }
}

data class LocalFolder(
    val folderId: String,
    val displayName: String,
    val localPath: String,
    val expanded: Boolean = false,
)
