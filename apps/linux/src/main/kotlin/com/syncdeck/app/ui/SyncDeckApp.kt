package com.syncdeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.syncdeck.app.model.MainSection
import com.syncdeck.app.model.ThemeMode
import com.syncdeck.app.mesh.MeshRuntime
import com.syncdeck.app.mesh.MeshFolder
import com.syncdeck.app.platform.AppPreferences
import com.syncdeck.app.platform.LinuxDeviceName
import com.syncdeck.app.platform.LinuxFolderPicker
import com.syncdeck.app.platform.LinuxStartupManager
import com.syncdeck.app.platform.UpdateConfig
import java.nio.file.Path
import com.syncdroid.shared.update.ReleaseUpdateService
import com.syncdroid.shared.update.UpdateState
import kotlinx.coroutines.launch

private enum class SecondaryScreen { CloudSync, BackgroundOperation, PowerDiscovery, FileHistory }

@Composable
fun SyncDeckApp(
    preferences: AppPreferences,
    runtime: MeshRuntime,
    discoveryInterval: Int,
    discoveryWindow: Long,
    alwaysOnDiscovery: Boolean,
    onDiscoveryIntervalChanged: (Int) -> Unit,
    onDiscoveryWindowChanged: (Long) -> Unit,
    onAlwaysOnDiscoveryChanged: (Boolean) -> Unit,
    onCloseToNotificationBar: () -> Unit,
    updateService: ReleaseUpdateService,
) {
    val meshState by runtime.state.collectAsState()
    val updateState by updateService.state.collectAsState()
    val offlineSeedState by updateService.seedState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedSection by remember { mutableStateOf(preferences.selectedSection) }
    var themeMode by remember { mutableStateOf(preferences.themeMode) }
    var deviceName by remember { mutableStateOf(preferences.deviceName ?: LinuxDeviceName.current()) }
    var cloudPolicy by remember { mutableStateOf(preferences.cloudSyncPolicy) }
    var launchAtLogin by remember {
        mutableStateOf(LinuxStartupManager.isEnabled().also { preferences.launchAtLogin = it })
    }
    var noBackgroundService by remember { mutableStateOf(preferences.noBackgroundService) }
    var offlineUpdateImportUnlocked by remember { mutableStateOf(preferences.offlineUpdateImportUnlocked) }
    var secondaryScreen by remember { mutableStateOf<SecondaryScreen?>(null) }
    var featureNotice by remember { mutableStateOf<String?>(null) }
    var updateBundleNotice by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf(deviceName) }
    var showCreateMesh by remember { mutableStateOf(false) }
    var showJoinMesh by remember { mutableStateOf(false) }
    var showPairingOffer by remember { mutableStateOf(false) }
    var folderToConfigure by remember { mutableStateOf<MeshFolder?>(null) }
    var folderConfigurationError by remember { mutableStateOf<String?>(null) }
    var platformError by remember { mutableStateOf<String?>(null) }
    var dismissedWifiSuggestion by remember { mutableStateOf<String?>(null) }
    val suggestedWifi = meshState.currentWifiName?.takeIf { current ->
        meshState.profile != null && current !in meshState.registeredWifiNames && current != dismissedWifiSuggestion
    }

    LaunchedEffect(meshState.pairingOffer) {
        if (meshState.pairingOffer != null) showPairingOffer = true
    }

    LaunchedEffect(showJoinMesh, meshState.profile?.groupId) {
        // The runtime publishes a profile only after the remote completion was
        // authenticated, imported, acknowledged, and saved successfully.
        if (showJoinMesh && meshState.profile != null) {
            showJoinMesh = false
            runtime.dismissError()
        }
    }

    fun selectSection(section: MainSection) {
        selectedSection = section
        preferences.selectedSection = section
        secondaryScreen = null
    }

    fun requestRename() {
        renameDraft = deviceName
        showRenameDialog = true
    }

    SyncDeckTheme(themeMode) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (secondaryScreen == null) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        MainSection.entries.forEach { section ->
                            NavigationBarItem(
                                selected = selectedSection == section,
                                onClick = { selectSection(section) },
                                icon = { Icon(section.icon(), contentDescription = null) },
                                label = { Text(section.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.fillMaxSize().widthIn(max = 1_080.dp)) {
                    when (secondaryScreen) {
                        SecondaryScreen.CloudSync -> CloudSyncSettingsScreen(
                            policy = cloudPolicy,
                            folders = meshState.folders,
                            onScopeChanged = { scope ->
                                cloudPolicy = cloudPolicy.copy(scope = scope)
                                preferences.cloudSyncPolicy = cloudPolicy
                            },
                            onFolderChanged = { folderId, enabled ->
                                cloudPolicy = cloudPolicy.withFolderEnabled(folderId, enabled)
                                preferences.cloudSyncPolicy = cloudPolicy
                            },
                            accounts = meshState.cloudAccounts,
                            busy = meshState.busy,
                            onSyncNow = { runtime.syncCloudNow() },
                            status = meshState.status,
                            onConnect = runtime::connectCloud,
                            onDisconnect = runtime::disconnectCloud,
                            onBack = { secondaryScreen = null },
                        )
                        SecondaryScreen.BackgroundOperation -> BackgroundOperationScreen(
                            launchAtLogin = launchAtLogin,
                            noBackgroundService = noBackgroundService,
                            onLaunchAtLoginChanged = { enabled ->
                                runCatching { LinuxStartupManager.setEnabled(enabled) }
                                    .onSuccess {
                                        launchAtLogin = enabled
                                        preferences.launchAtLogin = enabled
                                    }
                                    .onFailure {
                                        platformError = it.message ?: "Could not update launch at login"
                                    }
                            },
                            onNoBackgroundServiceChanged = { enabled ->
                                noBackgroundService = enabled
                                preferences.noBackgroundService = enabled
                            },
                            onBack = { secondaryScreen = null },
                        )
                        SecondaryScreen.PowerDiscovery -> PowerDiscoveryScreen(
                            intervalMinutes = discoveryInterval,
                            windowSeconds = discoveryWindow,
                            alwaysOnDiscovery = alwaysOnDiscovery,
                            currentWifiName = meshState.currentWifiName,
                            registeredWifiNames = meshState.registeredWifiNames,
                            onIntervalChanged = onDiscoveryIntervalChanged,
                            onWindowChanged = onDiscoveryWindowChanged,
                            onAlwaysOnChanged = onAlwaysOnDiscoveryChanged,
                            onRegisterCurrentWifi = runtime::registerCurrentWifi,
                            onRemoveRegisteredWifi = runtime::removeRegisteredWifi,
                            onBack = { secondaryScreen = null },
                        )
                        SecondaryScreen.FileHistory -> FileHistoryScreen(
                            events = meshState.fileHistory,
                            folders = meshState.folders,
                            deviceNames = buildMap {
                                put(meshState.localDeviceId, deviceName)
                                meshState.peers.forEach { put(it.deviceId, it.name) }
                            },
                            busy = meshState.busy,
                            error = meshState.error,
                            onRecover = { runtime.recoverFile(it.eventId) },
                            onBack = { secondaryScreen = null },
                        )
                        null -> when (selectedSection) {
                            MainSection.Sync -> SyncScreen(
                                deviceName = deviceName,
                                peers = meshState.peers,
                                folders = meshState.folders,
                                meshName = meshState.profile?.groupName,
                                runtimeStatus = meshState.status,
                                busy = meshState.busy,
                                backgroundServiceEnabled = !noBackgroundService,
                                onSyncNow = runtime::syncNow,
                                onRenameDevice = ::requestRename,
                                onCloseToNotificationBar = onCloseToNotificationBar,
                            )
                            MainSection.Folders -> FoldersScreen(
                                folders = meshState.folders,
                                loadManagedFiles = runtime::filesForManagement,
                                onDeleteFile = runtime::deleteManagedFile,
                                onRestoreFile = runtime::restoreManagedFile,
                                cloudPolicy = cloudPolicy,
                                onAddFolder = { featureNotice = "Folder access" },
                                onConfigureFolder = { folderToConfigure = it },
                                onDeclineFolder = { runtime.declineFolder(it.folderId) },
                                onOpenFolder = { folder ->
                                    runCatching {
                                        LinuxFolderPicker.openInFileManager(Path.of(requireNotNull(folder.localPath)))
                                    }.onFailure {
                                        folderConfigurationError = it.message ?: "Could not open this folder in the file manager"
                                    }
                                },
                                onCloudFolderChanged = { folderId, enabled ->
                                    cloudPolicy = cloudPolicy.withFolderEnabled(folderId, enabled)
                                    preferences.cloudSyncPolicy = cloudPolicy
                                },
                            )
                            MainSection.Devices -> DevicesScreen(
                                deviceName = deviceName,
                                peers = meshState.peers,
                                onStartMesh = {
                                    if (meshState.profile == null) showCreateMesh = true
                                    else if (meshState.pairingOffer != null) showPairingOffer = true
                                    else runtime.createPairingOffer()
                                },
                                onJoinMesh = {
                                    runtime.dismissError()
                                    showJoinMesh = true
                                },
                                onRenameDevice = ::requestRename,
                                onRemoveDevice = runtime::removeDevice,
                                hasMesh = meshState.profile != null,
                            )
                            MainSection.Chat -> ChatScreen(
                                messages = meshState.chatMessages,
                                currentDeviceId = meshState.localDeviceId,
                                deviceNames = buildMap {
                                    put(meshState.localDeviceId, deviceName)
                                    meshState.peers.forEach { put(it.deviceId, it.name) }
                                },
                                meshAvailable = meshState.profile != null,
                                onSend = runtime::sendChat,
                                onAttach = {
                                    LinuxFolderPicker.chooseChatAttachment()?.let(runtime::sendChatAttachment)
                                },
                                onDropFiles = runtime::sendChatAttachments,
                                attachmentPath = runtime::chatAttachmentPath,
                                onOpenAttachment = { message ->
                                    runCatching {
                                        val path = requireNotNull(runtime.chatAttachmentPath(message.messageId)) {
                                            "This attachment has not downloaded yet"
                                        }
                                        LinuxFolderPicker.openChatAttachment(path)
                                    }.onFailure { featureNotice = it.message ?: "Could not open this attachment" }
                                },
                            )
                            MainSection.Settings -> SettingsScreen(
                                updateState = updateState,
                                offlineSeedState = offlineSeedState,
                                onUpdateAction = {
                                    featureNotice = "SyncDeck is a preview. Install Linux updates from Desktop Mode after exiting SyncDeck."
                                },
                                onImportUpdateBundle = {
                                    LinuxFolderPicker.chooseOfflineUpdateBundle()?.let { bundle ->
                                        scope.launch {
                                            runCatching { updateService.importOfflineBundle(bundle) }
                                                .onSuccess { version ->
                                                    runtime.syncNow()
                                                    updateBundleNotice = "Update $version was verified and is now available to the mesh."
                                                }
                                                .onFailure { error ->
                                                    updateBundleNotice = error.message ?: "Could not import the offline update bundle."
                                                }
                                        }
                                    }
                                },
                                onDownloadUpdateBundle = {
                                    scope.launch {
                                        runCatching { updateService.downloadAndImportLatestOfflineBundle() }
                                            .onSuccess { version ->
                                                runtime.syncNow()
                                                updateBundleNotice = "Update $version was downloaded and is now available to the mesh."
                                            }
                                            .onFailure { error ->
                                                updateBundleNotice = error.message ?: "Could not download the offline update bundle."
                                            }
                                    }
                                },
                                offlineUpdateImportUnlocked = offlineUpdateImportUnlocked,
                                onOfflineUpdateImportUnlocked = {
                                    offlineUpdateImportUnlocked = true
                                    preferences.offlineUpdateImportUnlocked = true
                                },
                                themeMode = themeMode,
                                onThemeModeChanged = {
                                    themeMode = it
                                    preferences.themeMode = it
                                },
                                onOpenPowerSettings = { secondaryScreen = SecondaryScreen.PowerDiscovery },
                                onOpenFileHistory = { secondaryScreen = SecondaryScreen.FileHistory },
                                cloudScope = cloudPolicy.scope,
                                onOpenCloudSettings = { secondaryScreen = SecondaryScreen.CloudSync },
                                launchAtLogin = launchAtLogin,
                                noBackgroundService = noBackgroundService,
                                onOpenBackgroundSettings = { secondaryScreen = SecondaryScreen.BackgroundOperation },
                            )
                        }
                    }
                }
                suggestedWifi?.let { ssid ->
                    WifiSuggestionBanner(
                        ssid = ssid,
                        onYes = runtime::registerCurrentWifi,
                        onNo = { dismissedWifiSuggestion = ssid },
                        modifier = Modifier.widthIn(max = 560.dp).padding(16.dp),
                    )
                }
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Update device name") },
                text = {
                    Column {
                        Text("This nickname identifies this device throughout your trusted mesh.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = renameDraft,
                            onValueChange = { if (it.length <= 64) renameDraft = it },
                            label = { Text("Device name") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deviceName = renameDraft.trim()
                            preferences.deviceName = deviceName
                            showRenameDialog = false
                        },
                        enabled = renameDraft.isNotBlank() && renameDraft.trim() != deviceName,
                    ) { Text("Update") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showCreateMesh) {
            CreateMeshDialog(
                onDismiss = { showCreateMesh = false },
                onCreate = {
                    showCreateMesh = false
                    runtime.createMesh(it)
                },
            )
        }

        if (showJoinMesh) {
            JoinMeshDialog(
                attemptsRemaining = meshState.attemptsRemaining,
                busy = meshState.busy,
                error = meshState.error,
                onDismiss = {
                    showJoinMesh = false
                    runtime.dismissError()
                },
                onJoin = runtime::joinMesh,
            )
        }

        meshState.pairingOffer?.takeIf { showPairingOffer }?.let { offer ->
            PairingOfferDialog(offer, onDismiss = { showPairingOffer = false })
        }

        folderToConfigure?.let { folder ->
            AlertDialog(
                onDismissRequest = { folderToConfigure = null },
                title = { Text("Configure ${folder.displayName}") },
                text = {
                    Column {
                        Text("Choose where this mesh folder should live on this device.")
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (folder.includePatterns.isEmpty() && folder.excludePatterns.isEmpty()) {
                                "All files will be included."
                            } else {
                                buildString {
                                    if (folder.includePatterns.isNotEmpty()) append("Include: ${folder.includePatterns.joinToString()}\n")
                                    if (folder.excludePatterns.isNotEmpty()) append("Exclude: ${folder.excludePatterns.joinToString()}")
                                }.trim()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            runCatching { LinuxFolderPicker.chooseExisting("Choose an existing folder") }
                                .onSuccess { path ->
                                    if (path != null) {
                                        runtime.configureFolder(folder.folderId, path)
                                        folderToConfigure = null
                                    }
                                }
                                .onFailure {
                                    folderToConfigure = null
                                    folderConfigurationError = it.message ?: "Could not choose that folder"
                                }
                        },
                    ) { Text("Choose existing") }
                    TextButton(
                        onClick = {
                            runCatching {
                                LinuxFolderPicker.chooseParentAndCreate(
                                    "Choose a location for ${folder.displayName}",
                                    folder.displayName,
                                )
                            }.onSuccess { path ->
                                if (path != null) {
                                    runtime.configureFolder(folder.folderId, path)
                                    folderToConfigure = null
                                }
                            }.onFailure {
                                folderToConfigure = null
                                folderConfigurationError = it.message ?: "Could not create that folder"
                            }
                        },
                    ) { Text("Create new") }
                },
                dismissButton = { TextButton(onClick = { folderToConfigure = null }) { Text("Later") } },
            )
        }

        folderConfigurationError?.let { error ->
            AlertDialog(
                onDismissRequest = { folderConfigurationError = null },
                title = { Text("Folder configuration needs attention") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { folderConfigurationError = null }) { Text("Done") } },
            )
        }

        platformError?.let { error ->
            AlertDialog(
                onDismissRequest = { platformError = null },
                title = { Text("Linux setting needs attention") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { platformError = null }) { Text("Done") } },
            )
        }

        if (meshState.error != null && !showJoinMesh) {
            AlertDialog(
                onDismissRequest = runtime::dismissError,
                title = { Text("Mesh action needs attention") },
                text = { Text(requireNotNull(meshState.error)) },
                confirmButton = { TextButton(onClick = runtime::dismissError) { Text("Done") } },
            )
        }

        featureNotice?.let { feature ->
            AlertDialog(
                onDismissRequest = { featureNotice = null },
                title = { Text(feature) },
                text = {
                    Text(
                        when (feature) {
                            "Folder access" ->
                                "The in-app browser will be enabled with persistent Linux folder permissions in the next storage milestone."
                            "Cloud sync" ->
                                "Google Drive and OneDrive will use the shared encrypted cloud format after local peer sync is interoperable."
                            "Background operation" ->
                                "Closing the window keeps SyncDeck in the menu bar. While hidden, it uses the interval and discovery window selected in Power & discovery. Reopen it from the menu-bar icon for continuous discovery."
                            else -> "SyncDeck ${UpdateConfig.CURRENT_VERSION} · Linux preview"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { featureNotice = null }) { Text("Done") }
                },
            )
        }
        updateBundleNotice?.let { notice ->
            AlertDialog(
                onDismissRequest = { updateBundleNotice = null },
                title = { Text("Offline update bundle") },
                text = { Text(notice) },
                confirmButton = {
                    TextButton(onClick = { updateBundleNotice = null }) { Text("Done") }
                },
            )
        }
    }
}

private fun MainSection.icon(): ImageVector = when (this) {
    MainSection.Sync -> Icons.Rounded.Sync
    MainSection.Folders -> Icons.Rounded.Folder
    MainSection.Devices -> Icons.Rounded.Devices
    MainSection.Chat -> Icons.Rounded.ChatBubbleOutline
    MainSection.Settings -> Icons.Rounded.Settings
}
