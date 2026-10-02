package com.syncdroid.shared.protocol

/**
 * The events in [events] that no other event causally follows. Device clocks differ, so
 * membership state is derived from version vectors, which record what the signer had seen.
 */
fun <T> causallyLatest(events: Collection<T>, version: (T) -> VersionVector): List<T> =
    events.filter { event -> events.none { other -> version(event).relationTo(version(other)) == CausalRelation.Before } }

/**
 * Whether a device is a member after these additions and removals of it. A removal that is
 * concurrent with an addition wins.
 */
fun isTrustedAfter(additionVersions: Collection<VersionVector>, removalVersions: Collection<VersionVector>): Boolean {
    val events = additionVersions.map { it to true } + removalVersions.map { it to false }
    val latest = causallyLatest(events) { it.first }
    return latest.isEmpty() || latest.all { it.second }
}

/**
 * Peers keep relaying a removed device's chat messages, folders and exceptions, so these stay
 * valid when created before the removal. They carry no membership version, only their author's
 * clock time.
 */
fun acceptsRemovedSignerItem(createdAtMillis: Long, removedAtMillis: Long?): Boolean =
    removedAtMillis != null && createdAtMillis < removedAtMillis

/** A removed device's membership event stays valid unless it was signed after seeing that removal. */
fun acceptsRemovedSignerMembership(eventVersion: VersionVector, removalVersions: Collection<VersionVector>): Boolean =
    removalVersions.isNotEmpty() && removalVersions.none {
        eventVersion.relationTo(it) == CausalRelation.After || eventVersion.relationTo(it) == CausalRelation.Equal
    }
