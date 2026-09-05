package com.syncdows.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.syncdows.app.mesh.MeshFolder
import com.syncdroid.shared.cloud.CloudSyncPolicy
import com.syncdroid.shared.cloud.CloudSyncScope
import com.syncdroid.shared.cloud.CloudProvider
import com.syncdows.app.mesh.CloudAccountStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncSettingsScreen(
    policy: CloudSyncPolicy,
    folders: List<MeshFolder>,
    onScopeChanged: (CloudSyncScope) -> Unit,
    onFolderChanged: (String, Boolean) -> Unit,
    accounts: List<CloudAccountStatus>,
    busy: Boolean,
    onSyncNow: () -> Unit,
    status: String,
    onConnect: (CloudProvider) -> Unit,
    onDisconnect: (CloudProvider) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsDetailTopBar("Cloud sync", onBack)
        WindowsTouchLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Choose whether encrypted cloud storage is disabled, selected folder by folder, or inherited by every mesh folder.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SectionLabel("CLOUD COVERAGE")
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    CloudScopeRow("Off", "Keep all files on the local mesh", policy.scope == CloudSyncScope.DISABLED) {
                        onScopeChanged(CloudSyncScope.DISABLED)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CloudScopeRow(
                        "Folder by folder",
                        "Choose individual folders below or from their expanded cards",
                        policy.scope == CloudSyncScope.SELECTED_FOLDERS,
                    ) { onScopeChanged(CloudSyncScope.SELECTED_FOLDERS) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CloudScopeRow(
                        "All folders",
                        "Include every current and future mesh folder",
                        policy.scope == CloudSyncScope.ALL_FOLDERS,
                    ) { onScopeChanged(CloudSyncScope.ALL_FOLDERS) }
                }
            }
            if (policy.scope == CloudSyncScope.SELECTED_FOLDERS) {
                item { SectionLabel("FOLDERS") }
                if (folders.isEmpty()) {
                    item { EmptyStateCard("No mesh folders", "Folders can be enabled here after they are added to the mesh.") }
                } else {
                    items(folders, key = MeshFolder::folderId) { folder ->
                        SettingsCard {
                            SettingsActionRow(
                                icon = Icons.Rounded.Cloud,
                                title = folder.displayName,
                                detail = if (policy.isEnabledFor(folder.folderId)) "Included in cloud sync" else "Local mesh only",
                                onClick = { onFolderChanged(folder.folderId, !policy.isEnabledFor(folder.folderId)) },
                                trailing = {
                                    Switch(
                                        checked = policy.isEnabledFor(folder.folderId),
                                        onCheckedChange = { onFolderChanged(folder.folderId, it) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                SectionLabel("ACCOUNTS")
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    accounts.forEachIndexed { index, account ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CloudProviderRow(
                            account = account,
                            enabled = !busy,
                            onClick = {
                                if (account.connected) onDisconnect(account.provider) else onConnect(account.provider)
                            },
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    SettingsActionRow(Icons.Rounded.Cloud, "Sync cloud now",
                        "Pull remote changes and push local changes · $status", onSyncNow,
                        enabled = !busy && policy.scope != CloudSyncScope.DISABLED && accounts.any { it.connected })
                }
            }
            item {
                SettingsInformationCard(
                    "Already-paired devices can exchange encrypted files through the same cloud account, even on different networks. On unregistered Wi-Fi or mobile internet, use Sync cloud now. Automatic cloud sync runs only on registered Wi-Fi. Superseded uploads are retained for 30 days.",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundOperationScreen(
    launchAtLogin: Boolean,
    noBackgroundService: Boolean,
    onLaunchAtLoginChanged: (Boolean) -> Unit,
    onNoBackgroundServiceChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsDetailTopBar("Background operation", onBack)
        WindowsTouchLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Keep SyncDows available for scheduled synchronization without leaving its main window open.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SectionLabel("STARTUP")
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Rounded.Computer,
                        title = "Launch at login",
                        detail = when {
                            !launchAtLogin -> "SyncDows starts only when you open it"
                            noBackgroundService -> "Starts with its window open when you sign in"
                            else -> "Starts hidden in the system tray"
                        },
                        onClick = { onLaunchAtLoginChanged(!launchAtLogin) },
                        trailing = {
                            Switch(checked = launchAtLogin, onCheckedChange = onLaunchAtLoginChanged)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsActionRow(
                        icon = Icons.Rounded.Computer,
                        title = "No background service",
                        detail = if (noBackgroundService) "Closing the window fully quits SyncDows" else "Closing keeps SyncDows in the system tray",
                        onClick = { onNoBackgroundServiceChanged(!noBackgroundService) },
                        trailing = {
                            Switch(checked = noBackgroundService, onCheckedChange = onNoBackgroundServiceChanged)
                        },
                    )
                }
            }
            item {
                SettingsInformationCard(
                    if (noBackgroundService) {
                        "SyncDows will stop discovery and synchronization when its window closes. Open the app again whenever you want to resume."
                    } else {
                        "Closing the SyncDows window keeps synchronization running from the system tray. Use Quit from the tray menu when you want to stop the app completely. Background discovery follows your registered Wi-Fi, interval and duration settings."
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun CloudScopeRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (selected) Text("✓", modifier = Modifier.padding(start = 6.dp, top = 1.dp), color = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CloudProviderRow(account: CloudAccountStatus, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && account.configured, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Cloud, contentDescription = null)
        Column {
            Text(account.provider.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !account.configured -> "Sign-in is not available in this build yet"
                    account.connected -> "Connected · select to disconnect"
                    else -> "Select to connect securely in your browser"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsInformationCard(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Info, contentDescription = null)
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
