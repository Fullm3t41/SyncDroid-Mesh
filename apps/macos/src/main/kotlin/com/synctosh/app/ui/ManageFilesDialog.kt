package com.synctosh.app.ui

import com.synctosh.app.mesh.ManagedFile
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ManageFilesDialog(
    folderName: String,
    loadFiles: suspend () -> List<ManagedFile>,
    deleteFile: suspend (String, Boolean) -> Unit,
    restoreFile: suspend (String) -> Unit,
    dismiss: () -> Unit,
) {
    var files by remember { mutableStateOf<List<ManagedFile>>(emptyList()) }
    var pending by remember { mutableStateOf<ManagedFile?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun reload() { files = loadFiles() }
    LaunchedEffect(Unit) {
        try { reload() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load files" }
        finally { busy = false }
    }
    fun remove(file: ManagedFile, allDevices: Boolean) {
        scope.launch {
            busy = true; error = null
            try { deleteFile(file.relativePath, allDevices); pending = null; reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { pending = null; error = failure.message ?: "Could not delete file"; runCatching { reload() } }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ManageFilesContent(folderName, files, busy, error, dismiss, { pending = it }, { path -> scope.launch {
            busy = true; error = null
            try { restoreFile(path); reload() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not enable syncing" }
            finally { busy = false }
        } })
    }
    pending?.let { file ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null },
            title = { Text("Delete ${file.relativePath}?") },
            text = { Text("Delete from this device keeps other copies and prevents this path downloading again until you choose Allow syncing again. Delete from all devices applies on their next sync; independently edited copies need review. Recovery copies are kept for 30 days.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { remove(file, false) }, enabled = !busy && file.onThisDevice) { Text("Delete from this device") }
                    TextButton(onClick = { remove(file, true) }, enabled = !busy) { Text("Delete from all devices", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ManageFilesContent(
    folderName: String, files: List<ManagedFile>, busy: Boolean, error: String?,
    onClose: () -> Unit, onDelete: (ManagedFile) -> Unit, onRestore: (String) -> Unit,
) {
        Surface(Modifier.widthIn(max = 1040.dp).fillMaxWidth(0.95f).heightIn(max = 700.dp).fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Manage files", style = MaterialTheme.typography.headlineSmall)
                        Text(folderName, style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
                }
                Text("Device identifies who made this version’s original change. Last synced shows its last recorded successful transfer; older transfers may not have a recorded date.", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Name", Modifier.weight(0.44f), style = MaterialTheme.typography.labelLarge)
                    Text("Last synced", Modifier.weight(0.27f), style = MaterialTheme.typography.labelLarge)
                    Text("Device", Modifier.weight(0.29f), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(48.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (!busy && files.isEmpty()) item { Text("No synced files in this folder.", Modifier.padding(8.dp)) }
                    items(files, key = { it.relativePath }) { file ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(0.44f)) {
                                Text(file.relativePath.substringAfterLast('/'))
                                if ('/' in file.relativePath) Text(file.relativePath.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall)
                                if (!file.onThisDevice) {
                                    Text("Not on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(enabled = !busy, onClick = { onRestore(file.relativePath) }) { Text("Allow syncing again") }
                                }
                            }
                            val date = remember(file.lastSyncedAtMillis) { file.lastSyncedAtMillis?.let {
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                            } ?: "Not recorded" }
                            Text(date, Modifier.weight(0.27f), style = MaterialTheme.typography.bodyMedium)
                            Text(file.sourceDeviceName, Modifier.weight(0.29f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onDelete(file) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete ${file.relativePath}", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
}
