package com.syncdroid.shared.sync

/** The destination observed by the scan that authorized a transfer; null means absent. */
data class ExpectedFileContent(val sha256: String?) {
    fun verify(actualSha256: String?) {
        check(if (sha256 == null) actualSha256 == null else sha256.equals(actualSha256, true)) {
            "The local file changed during sync. Its contents were preserved; sync again to resolve the conflict."
        }
    }
}
