package com.syncdroid.shared.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {
    @Test
    fun preReleaseNumbersCompareNumerically() {
        assertTrue(isNewerVersion("1.3.0-rc.10", "1.3.0-rc.9"))
        assertTrue(isNewerVersion("1.3.0-rc.1", "1.3.0-beta.12"))
        assertTrue(isNewerVersion("1.3.0-alpha.beta", "1.3.0-alpha.1"))
        assertTrue(isNewerVersion("1.3.0-alpha.1", "1.3.0-alpha"))
        assertTrue(isNewerVersion("1.3.0", "1.3.0-rc.10"))
        assertFalse(isNewerVersion("1.3.0-rc.9", "1.3.0-rc.10"))
    }

    @Test
    fun oversizedNumbersAreNotVersions() {
        assertNull(SemanticVersion.parse("99999999999.0.0"))
        assertFalse(isNewerVersion("99999999999.0.0", "1.2.13"))
    }

    @Test
    fun releasesWithoutThisPlatformHaveNoAsset() {
        val manifest = ReleaseManifest(
            "1.2.14", "2026-10-02T00:00:00Z", "https://example.com/notes",
            listOf(ReleaseAsset(UpdatePlatform.Android, "app.apk", "https://example.com/app.apk", "a".repeat(64), 1)),
        )
        assertNull(manifest.assetOrNull(UpdatePlatform.WindowsX64))
    }
}
