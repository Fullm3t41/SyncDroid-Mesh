package com.syncdroid.app.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

interface SyncFileApplier {
    fun apply(relativePath: String, input: InputStream, expectedSha256: String, sourceModifiedAtMillis: Long? = null)
    fun delete(relativePath: String)

    /** Why this storage cannot keep [relativePath] under its exact name, or null when it can. */
    fun storageProblem(relativePath: String): String? = null
}

/**
 * Android's shared storage ignores capitalization, and its document providers replace characters
 * FAT disallows. A file written under such a name would be stored under another one, which the next
 * scan reports as a new file and spreads to every device. [childNames] lists the entries of the
 * directory at the given path components, or returns null when it does not exist yet.
 */
internal fun storageNameProblem(relativePath: String, childNames: (List<String>) -> List<String>?): String? {
    val parts = relativePath.split('/')
    if (parts.any { part -> part.any { it < ' ' || it in FAT_INVALID_CHARACTERS } }) return UNSUPPORTED_NAME_REASON
    var parent = emptyList<String>()
    for (part in parts) {
        val names = childNames(parent) ?: return null
        if (part in names) {
            parent = parent + part
            continue
        }
        // Anything missing is created with the exact spelling.
        return if (names.any { it.equals(part, ignoreCase = true) }) CASE_CLASH_REASON else null
    }
    return null
}

internal const val CASE_CLASH_REASON =
    "File names differ only by capitalization. Rename one so every device uses the same spelling."
internal const val UNSUPPORTED_NAME_REASON =
    "This device's storage does not allow this file name. Rename it on the device that created it."
private val FAT_INVALID_CHARACTERS = setOf('"', '*', ':', '<', '>', '?', '|', '\\')

class DocumentTreeFileApplier(
    private val context: Context,
    treeUri: Uri,
    private val expectedContent: com.syncdroid.shared.sync.ExpectedFileContent? = null,
) : SyncFileApplier {
    private val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Folder permission is unavailable" }

    override fun apply(
        relativePath: String,
        input: InputStream,
        expectedSha256: String,
        sourceModifiedAtMillis: Long?,
    ) {
        val parts = safeParts(relativePath)
        val parent = ensureDirectory(parts.dropLast(1))
        val name = parts.last()
        val temporaryName = ".syncdroid-${UUID.randomUUID()}.tmp"
        val temporary = requireNotNull(parent.createFile("application/octet-stream", temporaryName)) {
            "Could not create a temporary document"
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(context.contentResolver.openOutputStream(temporary.uri, "w")).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
                output.flush()
            }
            val actual = digest.digest().toHex()
            require(actual.equals(expectedSha256, true)) { "Received file hash does not match its manifest" }
            val original = findChild(parent, name)
            require(original == null || original.isFile) { "The destination is no longer a file" }
            expectedContent?.verify(original?.let {
                requireNotNull(context.contentResolver.openInputStream(it.uri)) {
                    "Could not verify the local file before replacement"
                }.buffered().use(FileHasher::sha256)
            })
            com.syncdroid.shared.sync.replaceDocumentRecoverably(
                original, temporary, name, ".syncdroid-backup-${UUID.randomUUID()}",
                // A provider may store a different name than requested; treat that as a failed rename.
                rename = { document, destinationName -> document.renameTo(destinationName) && document.name == destinationName },
                delete = DocumentFile::delete,
            )
        } finally {
            // Remove the replacement unless it was installed under the exact name.
            if (temporary.exists() && temporary.name != name) temporary.delete()
        }
    }

    override fun storageProblem(relativePath: String): String? = storageNameProblem(normalizedRelativePath(relativePath)) { parts ->
        find(parts)?.takeIf(DocumentFile::isDirectory)?.let { directory ->
            context.readSyncChildren(directory).mapNotNull(DocumentFile::getName)
        }
    }

    override fun delete(relativePath: String) {
        find(safeParts(relativePath))?.let { document ->
            require(document.isFile)
            expectedContent?.verify(requireNotNull(context.contentResolver.openInputStream(document.uri)).use(FileHasher::sha256))
            require(document.delete()) { "Could not delete synced document" }
        }
    }

    fun open(relativePath: String): InputStream? = find(safeParts(relativePath))?.takeIf(DocumentFile::isFile)?.let {
        context.contentResolver.openInputStream(it.uri)
    }

    private fun findChild(parent: DocumentFile, name: String): DocumentFile? {
        val matches = context.readSyncChildren(parent).filter { it.name == name }
        require(matches.size <= 1) { "The document provider returned duplicate file names" }
        return matches.singleOrNull()
    }

    private fun ensureDirectory(parts: List<String>): DocumentFile {
        var current = root
        parts.forEach { name ->
            current = findChild(current, name)?.also { require(it.isDirectory) { "$name is not a folder" } }
                ?: requireNotNull(current.createDirectory(name)) { "Could not create folder $name" }.also { created ->
                    if (created.name != name) {
                        created.delete()
                        error("This device's storage renamed the folder $name")
                    }
                }
        }
        return current
    }

    private fun find(parts: List<String>): DocumentFile? {
        var current = root
        parts.forEach { name -> current = findChild(current, name) ?: return null }
        return current
    }

    private fun safeParts(path: String): List<String> = normalizedRelativePath(path).split('/')
}
