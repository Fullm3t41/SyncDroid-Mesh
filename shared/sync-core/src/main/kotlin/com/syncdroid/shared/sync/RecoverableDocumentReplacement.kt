package com.syncdroid.shared.sync

/** Keep the original document until the provider has installed the replacement. */
fun <T> replaceDocumentRecoverably(
    original: T?,
    replacement: T,
    name: String,
    backupName: String,
    rename: (T, String) -> Boolean,
    delete: (T) -> Boolean,
) {
    if (original != null) check(rename(original, backupName)) {
        "The document provider could not preserve the original file; replacement was cancelled."
    }
    try {
        check(rename(replacement, name)) { "The document provider could not install the replacement." }
    } catch (failure: Throwable) {
        if (original != null && !runCatching { rename(original, name) }.getOrDefault(false)) {
            throw IllegalStateException("Replacement failed. The original file is preserved as $backupName.", failure)
        }
        throw failure
    }
    // A cleanup failure must not undo a successful replacement or remove the new document.
    if (original != null) runCatching { delete(original) }
}
