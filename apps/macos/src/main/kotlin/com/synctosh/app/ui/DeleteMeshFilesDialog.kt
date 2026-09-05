package com.synctosh.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun DeleteMeshFilesDialog(
    folderName: String,
    loadFiles: suspend () -> List<String>,
    deleteFiles: suspend (List<String>) -> Unit,
    dismiss: () -> Unit,
) {
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { files = loadFiles() } catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text("Delete from all devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$folderName · Select files to delete from every device on its next sync, including through cloud sync. This also applies to overwrite-only folders. Independently edited copies need conflict review. Recovery copies are kept for 30 days.")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!busy && files.isEmpty() && error == null) Text("No indexed files. Sync this folder first.")
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(files, key = { it }) { path ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(path in selected, { checked -> selected = if (checked) selected + path else selected - path }, enabled = !busy)
                            Text(path, Modifier.weight(1f).padding(top = 12.dp))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                scope.launch {
                    busy = true
                    try { deleteFiles(selected.toList()); dismiss() }
                    catch (failure: Exception) { error = failure.message ?: "Could not delete files" }
                    finally { busy = false }
                }
            }) { Text("Delete from all devices", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } },
    )
}
