package com.syncdroid.app.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.syncdroid.app.storage.SyncFilterRules

class DocumentTreeScanner(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun listRelativeFilePaths(treeUri: Uri): Set<String> {
        val root = readableRoot(treeUri)
        return buildSet { collectRelativeFilePaths(root, parentPath = "", destination = this) }
    }

    fun scan(
        treeUri: Uri,
        rules: SyncFilterRules,
        excludedRelativePaths: Set<String> = emptySet(),
    ): List<FileManifestEntry> {
        val root = readableRoot(treeUri)

        val files = mutableListOf<FileManifestEntry>()
        scanChildren(root, parentPath = "", rules = rules, excludedRelativePaths = excludedRelativePaths, destination = files)
        return files.sortedBy(FileManifestEntry::relativePath)
    }

    private fun readableRoot(treeUri: Uri): DocumentFile =
        requireNotNull(DocumentFile.fromTreeUri(appContext, treeUri)) {
            "The selected folder is no longer available"
        }.also { root ->
            require(root.exists() && root.isDirectory && root.canRead()) { "The selected folder is not readable" }
        }

    private fun collectRelativeFilePaths(
        directory: DocumentFile,
        parentPath: String,
        destination: MutableSet<String>,
    ) {
        appContext.readSyncChildren(directory).forEach { child ->
            val name = requireNotNull(child.name?.takeIf(String::isNotBlank)) { "Could not read a document name" }
            val relativePath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            when {
                child.isDirectory -> collectRelativeFilePaths(child, relativePath, destination)
                child.isFile -> destination += relativePath
            }
        }
    }

    private fun scanChildren(
        directory: DocumentFile,
        parentPath: String,
        rules: SyncFilterRules,
        excludedRelativePaths: Set<String>,
        destination: MutableList<FileManifestEntry>,
    ) {
        appContext.readSyncChildren(directory)
            .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name.orEmpty().lowercase() }))
            .forEach { child ->
                val name = requireNotNull(child.name?.takeIf(String::isNotBlank)) { "Could not read a document name" }
                val relativePath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                when {
                    child.isDirectory -> scanChildren(child, relativePath, rules, excludedRelativePaths, destination)
                    child.isFile && relativePath !in excludedRelativePaths && rules.shouldSync(relativePath) -> {
                        destination += stableEntry(relativePath, child)
                    }
                }
            }
    }

    private fun stableEntry(relativePath: String, document: DocumentFile): FileManifestEntry {
        repeat(MAX_HASH_ATTEMPTS) {
            val sizeBefore = document.length()
            val modifiedBefore = document.lastModified()
            val hash = requireNotNull(resolver.openInputStream(document.uri)) {
                "Could not open $relativePath"
            }.buffered().use(FileHasher::sha256)
            if (sizeBefore == document.length() && modifiedBefore == document.lastModified()) {
                return FileManifestEntry(
                    relativePath = relativePath,
                    sizeBytes = sizeBefore,
                    modifiedAtMillis = modifiedBefore,
                    sha256 = hash,
                )
            }
        }
        error("File changed repeatedly while it was being scanned: $relativePath")
    }

    private companion object {
        const val MAX_HASH_ATTEMPTS = 2
    }
}
