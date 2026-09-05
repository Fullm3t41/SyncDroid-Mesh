package com.syncdeck.app.platform

import kotlin.test.*

class LinuxWifiTest {
    @Test fun handlesColonInActiveSsidAndIgnoresNeighbours() {
        assertEquals("Home: 5GHz", LinuxWifi.parseCurrentNetwork(":Neighbours\n*:Home: 5GHz\n"))
        assertNull(LinuxWifi.parseCurrentNetwork(":Neighbours\n"))
    }
}
