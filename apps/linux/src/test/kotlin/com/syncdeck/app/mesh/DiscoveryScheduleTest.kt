package com.syncdeck.app.mesh

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryScheduleTest {
    @Test
    fun fiveMinuteWindowStartsAtNextClockBoundary() {
        val window = currentOrNextDiscoveryWindow(
            LocalDateTime.of(2026, 8, 16, 10, 2, 12),
            intervalMinutes = 5,
            windowSeconds = 30,
        )
        assertEquals(LocalDateTime.of(2026, 8, 16, 10, 5), window.start)
        assertEquals(LocalDateTime.of(2026, 8, 16, 10, 5, 30), window.end)
    }

    @Test
    fun closingDuringAWindowUsesItsRemainingTime() {
        val window = currentOrNextDiscoveryWindow(
            LocalDateTime.of(2026, 8, 16, 10, 5, 12),
            intervalMinutes = 5,
            windowSeconds = 30,
        )
        assertEquals(LocalDateTime.of(2026, 8, 16, 10, 5), window.start)
        assertEquals(LocalDateTime.of(2026, 8, 16, 10, 5, 30), window.end)
    }

    @Test
    fun longIntervalsRemainMidnightAnchored() {
        val daily = currentOrNextDiscoveryWindow(
            LocalDateTime.of(2026, 8, 16, 18, 0),
            intervalMinutes = 24 * 60,
            windowSeconds = 300,
        )
        val twoDay = currentOrNextDiscoveryWindow(
            LocalDateTime.of(2026, 8, 16, 18, 0),
            intervalMinutes = 48 * 60,
            windowSeconds = 300,
        )
        val weekly = currentOrNextDiscoveryWindow(
            LocalDateTime.of(2026, 8, 16, 18, 0),
            intervalMinutes = 7 * 24 * 60,
            windowSeconds = 300,
        )
        assertEquals(0, daily.start.hour)
        assertEquals(0, daily.start.minute)
        assertEquals(0, twoDay.start.hour)
        assertEquals(0, twoDay.start.minute)
        assertEquals(0, weekly.start.hour)
        assertEquals(0, weekly.start.minute)
    }

    @Test
    fun settingsPreviewAlwaysShowsUpcomingWindows() {
        val upcoming = upcomingDiscoveryWindows(
            LocalDateTime.of(2026, 8, 16, 10, 5, 12),
            intervalMinutes = 5,
            windowSeconds = 30,
            count = 3,
        )
        assertEquals(
            listOf(10 to 10, 10 to 15, 10 to 20),
            upcoming.map { it.start.hour to it.start.minute },
        )
    }

    @Test
    fun menuAndSettingsUseTheSameSupportedDiscoveryChoices() {
        assertEquals(listOf(15, 30, 60, 180, 360, 1_440, 2_880, 10_080), SUPPORTED_DISCOVERY_INTERVALS)
        assertEquals(listOf(300L, 600L, 900L), SUPPORTED_DISCOVERY_WINDOWS)
        assertEquals("48 hours", discoveryIntervalLabel(48 * 60))
        assertEquals("48 hr", discoveryIntervalLabel(48 * 60, compact = true))
        assertEquals("1 week", discoveryIntervalLabel(7 * 24 * 60))
        assertEquals("5 minutes", discoveryWindowLabel(300))
        assertEquals(180, normalizeDiscoveryInterval(null))
        assertEquals(15, normalizeDiscoveryInterval(5))
        assertEquals(300L, normalizeDiscoveryWindow(30))
    }
}
