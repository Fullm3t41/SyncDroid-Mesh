package com.syncdeck.app.platform

import com.syncdeck.app.model.MainSection
import com.syncdeck.app.model.ThemeMode
import com.syncdeck.app.mesh.normalizeDiscoveryInterval
import com.syncdeck.app.mesh.normalizeDiscoveryWindow
import com.syncdroid.shared.cloud.CloudSyncPolicy
import com.syncdroid.shared.cloud.CloudSyncScope
import java.util.prefs.Preferences
import java.util.Base64
import com.syncdroid.shared.cloud.CloudProvider

class AppPreferences {
    fun reload() { preferences.sync() }
    private val preferences = Preferences.userRoot().node("com/syncdeck/app")

    var themeMode: ThemeMode
        get() = preferences.get(KEY_THEME, ThemeMode.System.name)
            .let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System }
        set(value) = preferences.put(KEY_THEME, value.name)

    var selectedSection: MainSection
        get() = preferences.get(KEY_SECTION, MainSection.Sync.name)
            .let { stored -> MainSection.entries.firstOrNull { it.name == stored } ?: MainSection.Sync }
        set(value) = preferences.put(KEY_SECTION, value.name)

    var windowWidth: Float
        get() = preferences.getFloat(KEY_WINDOW_WIDTH, 1_080f).coerceAtLeast(760f)
        set(value) = preferences.putFloat(KEY_WINDOW_WIDTH, value)

    var windowHeight: Float
        get() = preferences.getFloat(KEY_WINDOW_HEIGHT, 700f).coerceAtLeast(540f)
        set(value) = preferences.putFloat(KEY_WINDOW_HEIGHT, value)

    var discoveryIntervalMinutes: Int
        get() = normalizeDiscoveryInterval(preferences.get(KEY_DISCOVERY_INTERVAL, null)?.toIntOrNull())
        set(value) = preferences.putInt(KEY_DISCOVERY_INTERVAL, value)

    var discoveryWindowSeconds: Long
        get() = normalizeDiscoveryWindow(preferences.get(KEY_DISCOVERY_WINDOW, null)?.toLongOrNull())
        set(value) = preferences.putLong(KEY_DISCOVERY_WINDOW, value)

    var alwaysOnDiscovery: Boolean
        get() = preferences.getBoolean(KEY_ALWAYS_ON_DISCOVERY, false)
        set(value) = preferences.putBoolean(KEY_ALWAYS_ON_DISCOVERY, value)

    var registeredWifiNames: Set<String>
        get() = preferences.get(KEY_REGISTERED_WIFI, "")
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { encoded -> runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull() }
            .filter(String::isNotBlank)
            .toSet()
        set(value) {
            val encoded = value.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
                .joinToString("\n") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            if (encoded.isBlank()) preferences.remove(KEY_REGISTERED_WIFI) else preferences.put(KEY_REGISTERED_WIFI, encoded)
        }

    var deviceName: String?
        get() = preferences.get(KEY_DEVICE_NAME, null)?.takeIf(String::isNotBlank)
        set(value) {
            if (value.isNullOrBlank()) preferences.remove(KEY_DEVICE_NAME)
            else preferences.put(KEY_DEVICE_NAME, value.trim())
        }

    var launchAtLogin: Boolean
        get() = preferences.getBoolean(KEY_LAUNCH_AT_LOGIN, false)
        set(value) = preferences.putBoolean(KEY_LAUNCH_AT_LOGIN, value)

    var noBackgroundService: Boolean
        get() = preferences.getBoolean(KEY_NO_BACKGROUND_SERVICE, false)
        set(value) = preferences.putBoolean(KEY_NO_BACKGROUND_SERVICE, value)

    var cloudSyncPolicy: CloudSyncPolicy
        get() = CloudSyncPolicy(
            scope = runCatching {
                CloudSyncScope.valueOf(preferences.get(KEY_CLOUD_SYNC_SCOPE, CloudSyncScope.DISABLED.name))
            }.getOrDefault(CloudSyncScope.DISABLED),
            selectedFolderIds = preferences.get(KEY_CLOUD_SELECTED_FOLDERS, "")
                .lineSequence()
                .filter(String::isNotBlank)
                .toSet(),
        )
        set(value) {
            preferences.put(KEY_CLOUD_SYNC_SCOPE, value.scope.name)
            if (value.selectedFolderIds.isEmpty()) preferences.remove(KEY_CLOUD_SELECTED_FOLDERS)
            else preferences.put(KEY_CLOUD_SELECTED_FOLDERS, value.selectedFolderIds.sorted().joinToString("\n"))
        }

    fun cloudToken(provider: CloudProvider): String? = preferences.get("cloud_token_${provider.name.lowercase()}", null)

    fun setCloudToken(provider: CloudProvider, encrypted: String?) {
        val key = "cloud_token_${provider.name.lowercase()}"
        if (encrypted == null) preferences.remove(key) else preferences.put(key, encrypted)
    }

    var lastUpdateCheckMillis: Long
        get() = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = preferences.putLong(KEY_LAST_UPDATE_CHECK, value)

    var offlineUpdateImportUnlocked: Boolean
        get() = preferences.getBoolean(KEY_OFFLINE_UPDATE_IMPORT_UNLOCKED, false)
        set(value) = preferences.putBoolean(KEY_OFFLINE_UPDATE_IMPORT_UNLOCKED, value)

    fun pairingAttemptState(nowMillis: Long = System.currentTimeMillis()): PairingAttemptState = PairingAttemptState(
        failedAttempts = preferences.getInt(KEY_PAIRING_FAILURES, 0),
        resetAtMillis = preferences.getLong(KEY_PAIRING_RESET_AT, 0),
        nowMillis = nowMillis,
    )

    fun recordPairingFailure(nowMillis: Long = System.currentTimeMillis()): PairingAttemptState {
        val current = pairingAttemptState(nowMillis).normalized()
        val failures = (current.failedAttempts + 1).coerceAtMost(PairingAttemptState.MAX_ATTEMPTS)
        val next = PairingAttemptState(
            failures,
            current.resetAtMillis.takeIf { it > nowMillis } ?: nowMillis + PairingAttemptState.WINDOW_MILLIS,
            nowMillis,
        )
        preferences.putInt(KEY_PAIRING_FAILURES, next.failedAttempts)
        preferences.putLong(KEY_PAIRING_RESET_AT, next.resetAtMillis)
        return next
    }

    fun clearPairingAttempts() {
        preferences.remove(KEY_PAIRING_FAILURES)
        preferences.remove(KEY_PAIRING_RESET_AT)
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_SECTION = "selected_section"
        const val KEY_WINDOW_WIDTH = "window_width"
        const val KEY_WINDOW_HEIGHT = "window_height"
        const val KEY_DISCOVERY_INTERVAL = "discovery_interval_minutes"
        const val KEY_DISCOVERY_WINDOW = "discovery_window_seconds"
        const val KEY_ALWAYS_ON_DISCOVERY = "always_on_discovery"
        const val KEY_REGISTERED_WIFI = "registered_wifi_names"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_LAUNCH_AT_LOGIN = "launch_at_login"
        const val KEY_NO_BACKGROUND_SERVICE = "no_background_service"
        const val KEY_CLOUD_SYNC_SCOPE = "cloud_sync_scope"
        const val KEY_CLOUD_SELECTED_FOLDERS = "cloud_selected_folders"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_millis"
        const val KEY_OFFLINE_UPDATE_IMPORT_UNLOCKED = "offline_update_import_unlocked"
        const val KEY_PAIRING_FAILURES = "pairing_failed_attempts"
        const val KEY_PAIRING_RESET_AT = "pairing_reset_at"
    }
}

data class PairingAttemptState(
    val failedAttempts: Int,
    val resetAtMillis: Long,
    private val nowMillis: Long,
) {
    fun normalized(): PairingAttemptState = if (resetAtMillis == 0L || nowMillis >= resetAtMillis) {
        PairingAttemptState(0, 0, nowMillis)
    } else {
        copy(failedAttempts = failedAttempts.coerceIn(0, MAX_ATTEMPTS))
    }

    val attemptsRemaining get() = (MAX_ATTEMPTS - normalized().failedAttempts).coerceAtLeast(0)
    val locked get() = attemptsRemaining == 0 && resetAtMillis > nowMillis

    companion object {
        const val MAX_ATTEMPTS = 5
        const val WINDOW_MILLIS = 15 * 60 * 1_000L
    }
}
