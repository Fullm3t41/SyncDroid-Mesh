package com.syncdows.app.mesh

import com.syncdroid.shared.protocol.MeshBundleWireCodec
import com.syncdroid.shared.protocol.MeshStateBundleWire
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class FutureEventTypeTest {
    @Test
    fun membershipEventOfAnUnknownTypeIsSkippedNotFatal() {
        val creator = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }
        val key = Base64.getEncoder().encodeToString(creator.public.encoded)
        val id = deviceIdFor(creator.public)
        val known = MeshWireCodec.decode(MeshWireCodec.encode(MeshStateBundle("Mesh", listOf(
            MembershipEvent("e1", "group", MembershipEventType.AddDevice, id, "Creator", key, id, emptyList(),
                VersionVector().increment(id), 1L, "c2ln"),
        )))).membershipEvents.single()
        val wire = MeshBundleWireCodec.decode(MeshWireCodec.encode(MeshStateBundle("Mesh", listOf(known))))
        val future = wire.membershipEvents.single().copy(eventId = "e2", eventType = "TransferOwnership")
        val bytes = MeshBundleWireCodec.encode(MeshStateBundleWire(wire.groupName, wire.membershipEvents + future,
            wire.folderAnnouncements, wire.syncExceptionEvents, wire.chatMessages))

        assertEquals(listOf("e1"), MeshWireCodec.decode(bytes).membershipEvents.map { it.eventId })
    }
}
