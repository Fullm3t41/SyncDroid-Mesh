package com.synctosh.app.platform

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MacUpdateInstallerTest {
    @Test fun resolvesInstalledBundleWithSpaces() {
        assertEquals(Path.of("/Users/Test User/Applications/SyncTosh.app"),
            MacUpdateInstaller.applicationBundle(Path.of(
                "/Users/Test User/Applications/SyncTosh.app/Contents/MacOS/SyncTosh")))
    }

    @Test fun rejectsDevelopmentLauncherAndUnrelatedLayouts() {
        for (path in listOf("/usr/bin/java", "/tmp/SyncTosh",
            "/tmp/Other/Contents/MacOS/SyncTosh", "/tmp/App.app/Contents/bin/SyncTosh")) {
            assertFailsWith<IllegalArgumentException> {
                MacUpdateInstaller.applicationBundle(Path.of(path))
            }
        }
    }
}
