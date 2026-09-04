package com.syncdroid.app.sync

import com.syncdroid.app.storage.SyncFilterRules
import java.io.File
import java.io.FileInputStream

class DirectFolderScanner {
    fun listRelativeFilePaths(rootDirectory: File): Set<String> {
        val root = rootDirectory.canonicalFile
        require(root.isDirectory) { "Sync root is not a readable directory" }
        return root.walkTopDown().onFail { _, error -> throw error }
            .filter(File::isFile)
            .map { file ->
                val safeFile = file.canonicalFile
                require(safeFile.toPath().startsWith(root.toPath())) { "Folder contains a file outside its root" }
                safeFile.relativeTo(root).invariantSeparatorsPath
            }
            .toSet()
    }

    fun scan(
        rootDirectory: File,
        rules: SyncFilterRules,
        excludedRelativePaths: Set<String> = emptySet(),
    ): List<FileManifestEntry> {
        val root = rootDirectory.canonicalFile
        require(root.isDirectory) { "Sync root is not a readable directory" }

        return root.walkTopDown().onFail { _, error -> throw error }
            .filter { it.isFile }
            .map { file ->
                val safeFile = file.canonicalFile
                require(safeFile.toPath().startsWith(root.toPath())) { "Folder contains a file outside its root" }
                val relativePath = safeFile.relativeTo(root).invariantSeparatorsPath
                relativePath to safeFile
            }
            .filter { (relativePath) -> relativePath !in excludedRelativePaths && rules.shouldSync(relativePath) }
            .map { (relativePath, file) -> stableEntry(relativePath, file) }
            .sortedBy(FileManifestEntry::relativePath)
            .toList()
    }

    private fun stableEntry(relativePath: String, file: File): FileManifestEntry {
        repeat(MAX_HASH_ATTEMPTS) {
            val sizeBefore = file.length()
            val modifiedBefore = file.lastModified()
            val hash = FileInputStream(file).buffered().use(FileHasher::sha256)
            if (sizeBefore == file.length() && modifiedBefore == file.lastModified()) {
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
