package com.synctosh.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacWifiTest {
    @Test
    fun `parses current network name`() {
        assertEquals("Home Mesh", MacWifi.parseCurrentNetwork("Current Wi-Fi Network: Home Mesh\n"))
    }

    @Test
    fun `does not mistake disconnected output for an ssid`() {
        assertNull(MacWifi.parseCurrentNetwork("You are not associated with an AirPort network.\n"))
    }

    @Test
    fun `reads the network name from ipconfig when networksetup has none`() {
        val summary = """
            <dictionary> {
              BSSID : 12:34:56:78:9a:bc
              InterfaceType : WiFi
              SSID : Home Mesh
            }
        """.trimIndent()
        assertEquals("Home Mesh", MacWifi.parseIpconfigSummary(summary))
        assertNull(MacWifi.parseIpconfigSummary("  SSID : <redacted>\n"))
    }

    @Test
    fun `reads the network name from system_profiler`() {
        val report = """
            Wi-Fi:

                  Interfaces:
                    en0:
                      Status: Connected
                      Current Network Information:
                        Home Mesh:
                          PHY Mode: 802.11ax
                      Other Local Wi-Fi Networks:
        """.trimIndent()
        assertEquals("Home Mesh", MacWifi.parseSystemProfiler(report))
        assertNull(MacWifi.parseSystemProfiler("Current Network Information:\n  <redacted>:\n"))
        assertNull(MacWifi.parseSystemProfiler("Status: Off\n"))
    }
}
