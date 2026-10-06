package com.syncdroid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncdroid.app.storage.ManagedFileEntry
import com.syncdroid.app.storage.ManagedFileRepository
import com.syncdroid.app.storage.ManagedStorageRoot
import com.syncdroid.app.storage.SyncFilterRules
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    storageRoots: List<ManagedStorageRoot>,
    onBack: () -> Unit,
    onFolderSelected: (path: String, name: String, rules: SyncFilterRules) -> Unit,
    modifier: Modifier = Modifier,
    openCreateFolderInitially: Boolean = false,
) {
    require(storageRoots.isNotEmpty()) { "At least one storage location is required" }
    var selectedRootId by rememberSaveable { mutableStateOf(storageRoots.first().id) }
    val selectedRoot = storageRoots.firstOrNull { it.id == selectedRootId } ?: storageRoots.first()
    val repository = remember(selectedRoot.directory) { ManagedFileRepository(selectedRoot.directory) }
    val root = remember(repository) { repository.root }
    var currentPath by rememberSaveable { mutableStateOf(root.path) }
    var entries by remember { mutableStateOf<List<ManagedFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCreateFolder by rememberSaveable { mutableStateOf(false) }
    var choosingNewFolderLocation by rememberSaveable { mutableStateOf(openCreateFolderInitially) }
    var editFilters by rememberSaveable { mutableStateOf(false) }
    var rules by remember { mutableStateOf(SyncFilterRules()) }
    val currentDirectory = remember(currentPath) { File(currentPath) }

    fun navigateBack() {
        if (editFilters) {
            editFilters = false
        } else {
            val parent = repository.parentInsideRoot(currentDirectory)
            if (parent != null) currentPath = parent.path else onBack()
        }
    }
    BackHandler(onBack = ::navigateBack)

    LaunchedEffect(currentPath, refreshKey) {
        loading = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { repository.list(currentDirectory) }
        }.onSuccess { entries = it }
            .onFailure { loadError = it.message ?: "Could not read this folder" }
        loading = false
    }

    if (editFilters) {
        FilterEditorScreen(
            folderName = if (currentDirectory == root) selectedRoot.label else currentDirectory.name,
            initialRules = rules,
            onBack = { editFilters = false },
            onSave = {
                rules = it
                editFilters = false
            },
            modifier = modifier,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Choose a folder") },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        choosingNewFolderLocation = false
                        showCreateFolder = true
                    }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("New folder")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(
                        friendlyStoragePath(selectedRoot, repository, currentDirectory),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { editFilters = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.FilterAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(if (rules.includes.isEmpty() && rules.excludes.isEmpty()) "Filters" else rules.summary(), maxLines = 1)
                        }
                        Button(
                            onClick = {
                                if (choosingNewFolderLocation) {
                                    choosingNewFolderLocation = false
                                    showCreateFolder = true
                                } else {
                                    onFolderSelected(
                                        currentDirectory.path,
                                        if (currentDirectory == root) selectedRoot.label else currentDirectory.name,
                                        rules,
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (choosingNewFolderLocation) "Create here" else "Use this folder")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (storageRoots.size > 1) {
                item(key = "storage_roots") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "STORAGE LOCATION",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            storageRoots.forEach { storageRoot ->
                                if (storageRoot.id == selectedRoot.id) {
                                    Button(onClick = {}) {
                                        Icon(
                                            if (storageRoot.removable) Icons.Rounded.SdStorage else Icons.Rounded.Smartphone,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(storageRoot.label)
                                    }
                                } else {
                                    OutlinedButton(onClick = {
                                        selectedRootId = storageRoot.id
                                        currentPath = storageRoot.directory.path
                                    }) {
                                        Icon(
                                            if (storageRoot.removable) Icons.Rounded.SdStorage else Icons.Rounded.Smartphone,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(storageRoot.label)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (choosingNewFolderLocation) {
                item(key = "new_folder_location_prompt") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            "Choose the storage and parent folder first, then tap Create here.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            item {
                Text(
                    friendlyStoragePath(selectedRoot, repository, currentDirectory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            repository.parentInsideRoot(currentDirectory)?.let { parent ->
                item(key = "parent") {
                    FileEntryRow(
                        name = "Up one folder",
                        detail = if (parent == root) selectedRoot.label else parent.name,
                        isDirectory = true,
                        onClick = { currentPath = parent.path },
                    )
                }
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            } else if (loadError != null) {
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Text(
                            loadError.orEmpty(),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else if (entries.isEmpty()) {
                item {
                    EmptyFolder(onCreateFolder = {
                        choosingNewFolderLocation = false
                        showCreateFolder = true
                    })
                }
            } else {
                items(entries, key = { it.file.path }) { entry ->
                    FileEntryRow(
                        name = entry.file.name,
                        detail = if (entry.isDirectory) "Folder" else formatFileSize(entry.sizeBytes),
                        isDirectory = entry.isDirectory,
                        onClick = if (entry.isDirectory) ({ currentPath = entry.file.path }) else null,
                    )
                }
            }
        }
    }

    if (showCreateFolder) {
        CreateFolderDialog(
            repository = repository,
            parent = currentDirectory,
            onDismiss = { showCreateFolder = false },
            onCreated = { created ->
                showCreateFolder = false
                choosingNewFolderLocation = false
                currentPath = created.path
                refreshKey++
            },
        )
    }
}

private fun friendlyStoragePath(
    storageRoot: ManagedStorageRoot,
    repository: ManagedFileRepository,
    directory: File,
): String {
    val relative = repository.relativePath(directory)
    return if (directory == repository.root) storageRoot.label else "${storageRoot.label}/$relative"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterEditorScreen(
    folderName: String,
    initialRules: SyncFilterRules,
    onBack: () -> Unit,
    onSave: (SyncFilterRules) -> Unit,
    modifier: Modifier = Modifier,
) {
    var includes by remember(initialRules) { mutableStateOf(initialRules.includes) }
    var excludes by remember(initialRules) { mutableStateOf(initialRules.excludes) }
    var includeInput by rememberSaveable { mutableStateOf("") }
    var excludeInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("File filters") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
                Button(
                    onClick = { onSave(SyncFilterRules(includes, excludes)) },
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                ) {
                    Text("Save filters")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(folderName, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Include only the files you want. Exclude rules always win.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("QUICK PRESETS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { includes = emptyList(); excludes = emptyList() }) { Text("All files") }
                    OutlinedButton(onClick = { includes = listOf("*.sav"); excludes = emptyList() }) { Text("Files · *.sav") }
                    OutlinedButton(onClick = { includes = listOf("*.sav", "*.bak"); excludes = listOf("*.tmp") }) { Text(".sav + backups") }
                }
            }
            item {
                PatternEditor(
                    title = "Include patterns",
                    helper = if (includes.isEmpty()) "Empty means every file is included." else "Only matching files will sync.",
                    patterns = includes,
                    input = includeInput,
                    onInputChange = { includeInput = it },
                    onAdd = {
                        normalizedPattern(includeInput)?.let { pattern ->
                            if (pattern !in includes) includes = includes + pattern
                            includeInput = ""
                        }
                    },
                    onRemove = { includes = includes - it },
                )
            }
            item {
                PatternEditor(
                    title = "Exclude patterns",
                    helper = "Useful for temporary files such as *.tmp.",
                    patterns = excludes,
                    input = excludeInput,
                    onInputChange = { excludeInput = it },
                    onAdd = {
                        normalizedPattern(excludeInput)?.let { pattern ->
                            if (pattern !in excludes) excludes = excludes + pattern
                            excludeInput = ""
                        }
                    },
                    onRemove = { excludes = excludes - it },
                )
            }
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Text(
                        SyncFilterRules(includes, excludes).summary(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternEditor(
    title: String,
    helper: String,
    patterns: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(helper, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("Pattern, e.g. *.sav") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onAdd, enabled = input.isNotBlank()) {
                Icon(Icons.Rounded.Add, contentDescription = "Add pattern")
            }
        }
        patterns.forEach { pattern ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pattern, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { onRemove(pattern) }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove $pattern")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(name: String, detail: String, isDirectory: Boolean, onClick: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(17.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Icon(
                    if (isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isDirectory) Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyFolder(onCreateFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.padding(18.dp).size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("This folder is empty", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onCreateFolder) { Text("Create a folder") }
    }
}

@Composable
private fun CreateFolderDialog(
    repository: ManagedFileRepository,
    parent: File,
    onDismiss: () -> Unit,
    onCreated: (File) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Folder name") },
                    singleLine = true,
                    isError = error != null,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !creating,
                onClick = {
                    creating = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { repository.createFolder(parent, name) }
                        result.onSuccess(onCreated).onFailure {
                            error = it.message ?: "Could not create the folder"
                            creating = false
                        }
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun normalizedPattern(value: String): String? {
    val trimmed = value.trim()
    return trimmed.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
    else -> "%.1f GB".format(bytes / 1_073_741_824.0)
}
