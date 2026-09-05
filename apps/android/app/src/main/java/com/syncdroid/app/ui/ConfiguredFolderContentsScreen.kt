package com.syncdroid.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.syncdroid.app.data.FileVersionEntity
import com.syncdroid.app.model.SaveFolder
import com.syncdroid.app.storage.ConfiguredFolderEntry
import com.syncdroid.app.storage.configuredFolderSource
import com.syncdroid.app.storage.parentFolderPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConfiguredFolderContentsScreen(
    folder: SaveFolder,
    deviceNames: Map<String, String>,
    loadVersions: suspend () -> List<FileVersionEntity>,
    onExclude: suspend (List<String>) -> Unit,
    onDelete: suspend (List<String>) -> Unit,
    onDeleteEverywhere: suspend (List<String>, Boolean) -> Unit,
    onFilesChanged: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sourceResult = remember(folder.path) { runCatching { configuredFolderSource(context, folder.path) } }
    val source = sourceResult.getOrNull()
    val scope = rememberCoroutineScope()
    var currentDirectory by remember(folder.meshFolderId) { mutableStateOf("") }
    var entries by remember(folder.meshFolderId) { mutableStateOf<List<ConfiguredFolderEntry>?>(null) }
    var versions by remember(folder.meshFolderId) { mutableStateOf<Map<String, FileVersionEntity>>(emptyMap()) }
    var selectedPaths by remember(folder.meshFolderId) { mutableStateOf<Set<String>>(emptySet()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(sourceResult.exceptionOrNull()?.message) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPermanentDeleteDialog by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    LaunchedEffect(currentDirectory, refreshKey, source) {
        if (source == null) return@LaunchedEffect
        entries = null
        runCatching {
            withContext(Dispatchers.IO) { source.list(currentDirectory) } to loadVersions()
        }.onSuccess { (listed, indexed) ->
            entries = listed
            versions = indexed.associateBy(FileVersionEntity::relativePath)
            error = null
        }.onFailure {
            entries = emptyList()
            error = it.message ?: "Could not open this folder"
        }
    }

    fun navigateBack() {
        when {
            selectedPaths.isNotEmpty() -> selectedPaths = emptySet()
            currentDirectory.isNotBlank() -> currentDirectory = parentFolderPath(currentDirectory)
            else -> onBack()
        }
    }

    fun removeSelected(exclude: Boolean, everywhere: Boolean = false, permanent: Boolean = false) {
        val paths = selectedPaths.toList()
        if (paths.isEmpty() || source == null) return
        scope.launch {
            removing = true
            runCatching {
                if (exclude) onExclude(paths)
                if (everywhere) onDeleteEverywhere(paths, permanent) else onDelete(paths)
            }.onSuccess {
                selectedPaths = emptySet()
                showDeleteDialog = false
                showPermanentDeleteDialog = false
                refreshKey++
                onFilesChanged()
            }.onFailure {
                error = it.message ?: "Could not remove the selected files"
                showDeleteDialog = false
                showPermanentDeleteDialog = false
            }
            removing = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectedPaths.isEmpty()) folder.game else "${selectedPaths.size} selected")
                },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectedPaths.isNotEmpty()) {
                Surface(
                    modifier = Modifier.size(56.dp).combinedClickable(
                        enabled = !removing,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Delete selected files",
                        onLongClickLabel = "Permanently delete from all devices",
                        onClick = { showDeleteDialog = true },
                        onLongClick = { showPermanentDeleteDialog = true },
                    ),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shadowElevation = 6.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete selected files")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        if (currentDirectory.isBlank()) "Folder contents" else currentDirectory,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Hold a file to select it. Hold the bin to permanently delete the selection across all devices. Synced from identifies the device that made the latest edit.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            when (val visibleEntries = entries) {
                null -> item { Text("Loading…", modifier = Modifier.padding(20.dp)) }
                else -> {
                    if (visibleEntries.isEmpty() && error == null) {
                        item {
                            Text(
                                "This folder is empty.",
                                modifier = Modifier.padding(20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visibleEntries, key = ConfiguredFolderEntry::relativePath) { entry ->
                        val selected = entry.relativePath in selectedPaths
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (entry.isDirectory && selectedPaths.isEmpty()) {
                                            currentDirectory = entry.relativePath
                                        } else if (!entry.isDirectory && selectedPaths.isNotEmpty()) {
                                            selectedPaths = if (selected) {
                                                selectedPaths - entry.relativePath
                                            } else {
                                                selectedPaths + entry.relativePath
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!entry.isDirectory) selectedPaths = selectedPaths + entry.relativePath
                                    },
                                ),
                        ) {
                            FolderContentRow(entry, versions[entry.relativePath], deviceNames)
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
    }

    if (showPermanentDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!removing) showPermanentDeleteDialog = false },
            title = { Text("Permanently delete from all devices?") },
            text = { Text("Delete ${selectedPaths.size} selected file(s) and their SyncDroid recovery copies on every device’s next sync? This cannot be undone through File history. Independently edited copies require conflict review. All devices need this app version; cloud-provider backups or trash may remain.") },
            confirmButton = {
                TextButton(enabled = !removing, onClick = { removeSelected(exclude = false, everywhere = true, permanent = true) }) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !removing, onClick = { showPermanentDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!removing) showDeleteDialog = false },
            title = { Text("Remove ${selectedPaths.size} ${if (selectedPaths.size == 1) "file" else "files"}?") },
            text = {
                Column {
                    Text("Delete from all devices applies on each device’s next sync, including cloud sync, even in overwrite-only folders. Independently edited copies need conflict review. Recovery copies are kept for 30 days.")
                    Text("Delete using folder rule follows the folder setting: normal folders share the deletion; overwrite-only folders keep the other copies.", modifier = Modifier.padding(top = 10.dp))
                    if (folder.supportsFolderSettings) {
                        Text(
                            "Delete & exclude also records exceptions so these paths are not restored by future syncs. You can undo them in Folder settings.",
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { removeSelected(exclude = false, everywhere = true) }, enabled = !removing) {
                        Text("Delete from all devices", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { removeSelected(exclude = false) }, enabled = !removing) {
                        Text("Delete using folder rule", color = MaterialTheme.colorScheme.error)
                    }
                    if (folder.supportsFolderSettings) {
                        TextButton(onClick = { removeSelected(exclude = true) }, enabled = !removing) {
                            Text("Delete & exclude", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, enabled = !removing) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FolderContentRow(
    entry: ConfiguredFolderEntry,
    version: FileVersionEntity?,
    deviceNames: Map<String, String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            if (entry.isDirectory) {
                Text("Folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${formatSize(entry.sizeBytes)} · Last edited ${formatDate(entry.modifiedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Synced from: ${editorName(version, deviceNames)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun editorName(version: FileVersionEntity?, deviceNames: Map<String, String>): String {
    val deviceId = version?.originDeviceId?.takeIf(String::isNotBlank) ?: return "Not indexed yet"
    return deviceNames[deviceId] ?: "Unknown device · ${deviceId.take(8)}"
}

private fun formatDate(millis: Long): String = if (millis <= 0) {
    "Unknown"
} else {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
