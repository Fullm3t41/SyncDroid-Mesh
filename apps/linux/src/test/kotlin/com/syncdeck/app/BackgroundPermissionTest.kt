package com.syncdeck.app

import kotlin.test.*

class BackgroundPermissionTest {
    @Test fun gamingModeRequiresLivePluginEvenWithDesktopEnvironment() {
        var now = 0L
        val permission = BackgroundPermission { now }
        assertFalse(permission.allowed(true, true, false))
        permission.renew()
        assertTrue(permission.allowed(true, true, true))
        now = 45_000_000_000L
        assertFalse(permission.allowed(true, true, false))
        permission.renew()
        assertTrue(permission.allowed(true, false, false))
        permission.revoke()
        assertFalse(permission.allowed(true, true, false))
    }
    @Test fun desktopTrayNeedsNoPluginButRespectsDisableSetting() {
        val permission = BackgroundPermission()
        assertTrue(permission.allowed(false, true, false))
        assertFalse(permission.allowed(false, true, true))
        assertFalse(permission.allowed(false, false, false))
    }
}
