package com.syncdroid.app.sync

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

internal fun Context.readSyncChildren(directory: DocumentFile): List<DocumentFile> {
    require(directory.exists() && directory.isDirectory && directory.canRead()) {
        "A sync directory is unavailable; the scan was cancelled"
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        directory.uri, DocumentsContract.getDocumentId(directory.uri),
    )
    // DocumentFile.listFiles swallows provider errors and can return a partial/empty listing.
    return requireNotNull(contentResolver.query(
        childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
    )) { "The document provider did not return a complete directory listing" }.use { cursor ->
        val listingError = cursor.extras?.getString(DocumentsContract.EXTRA_ERROR)
        check(listingError.isNullOrBlank()) { "The document provider could not list this directory: $listingError" }
        check(cursor.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) != true) {
            "The document provider is still loading this directory; try syncing again"
        }
        buildList {
            while (cursor.moveToNext()) {
                val id = requireNotNull(cursor.getString(0)) { "Missing document ID" }
                val uri = DocumentsContract.buildDocumentUriUsingTree(directory.uri, id)
                val child = requireNotNull(DocumentFile.fromTreeUri(this@readSyncChildren, uri))
                require(DocumentsContract.getDocumentId(child.uri) == id) { "The provider returned a different document" }
                require(child.exists() && child.canRead() && (child.isFile || child.isDirectory)) {
                    "A sync document could not be read; the scan was cancelled"
                }
                val name = requireNotNull(child.name?.takeIf(String::isNotBlank)) { "Could not read a document name" }
                check(!name.matches(Regex("\\.syncdroid-backup-[0-9a-fA-F-]{36}"))) {
                    "A previous replacement left a recovery copy ($name). Restore or move that copy before syncing this folder."
                }
                add(child)
            }
        }
    }
}
