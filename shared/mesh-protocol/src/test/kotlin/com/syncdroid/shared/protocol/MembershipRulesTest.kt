package com.syncdroid.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MembershipRulesTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun removalFollowingAnAdditionWinsWhateverTheClocksSay() {
        val added = VersionVector(mapOf(a to 2))
        val removed = VersionVector(mapOf(a to 2, b to 1))

        assertFalse(isTrustedAfter(listOf(added), listOf(removed)))
    }

    @Test
    fun readdingAfterARemovalRestoresTrust() {
        val added = VersionVector(mapOf(a to 1))
        val removed = VersionVector(mapOf(a to 2))
        val readded = VersionVector(mapOf(a to 3))

        assertTrue(isTrustedAfter(listOf(added, readded), listOf(removed)))
    }

    @Test
    fun concurrentRemovalWins() {
        assertFalse(isTrustedAfter(listOf(VersionVector(mapOf(a to 3))), listOf(VersionVector(mapOf(b to 3)))))
    }

    @Test
    fun causallyLatestKeepsOnlyUnfollowedEvents() {
        val first = VersionVector(mapOf(a to 1))
        val second = VersionVector(mapOf(a to 2))
        val concurrent = VersionVector(mapOf(b to 1))

        assertEquals(listOf(second, concurrent), causallyLatest(listOf(first, second, concurrent)) { it })
    }

    @Test
    fun removedSignerItemsMustPredateTheRemoval() {
        assertTrue(acceptsRemovedSignerItem(createdAtMillis = 1_999, removedAtMillis = 2_000))
        assertFalse(acceptsRemovedSignerItem(createdAtMillis = 2_000, removedAtMillis = 2_000))
        assertFalse(acceptsRemovedSignerItem(createdAtMillis = 1, removedAtMillis = null))
    }

    @Test
    fun removedSignerMembershipMustNotFollowItsRemoval() {
        val removal = VersionVector(mapOf(a to 3, b to 1))

        assertTrue(acceptsRemovedSignerMembership(VersionVector(mapOf(a to 2, b to 1)), listOf(removal)))
        assertTrue(acceptsRemovedSignerMembership(VersionVector(mapOf(a to 2, b to 2)), listOf(removal)))
        assertFalse(acceptsRemovedSignerMembership(VersionVector(mapOf(a to 3, b to 2)), listOf(removal)))
        assertFalse(acceptsRemovedSignerMembership(VersionVector(mapOf(b to 2)), emptyList()))
    }
}
