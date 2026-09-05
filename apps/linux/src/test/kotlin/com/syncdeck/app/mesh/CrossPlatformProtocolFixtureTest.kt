package com.syncdeck.app.mesh

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossPlatformProtocolFixtureTest {
    private val fixture = loadCrossPlatformFixture()
    private val version = VersionVector.fromJson(fixture.required("versionVector.json"))

    @Test
    fun macDomainObjectsMatchGoldenPayloads() {
        val time = fixture.required("chat.createdAtMillis").toLong()
        val chat = MeshChatMessage(
            fixture.required("chat.eventId"), fixture.required("chat.groupId"),
            fixture.required("chat.authorDeviceId"), fixture.required("chat.body"), time, "",
        )
        assertEquals(fixture.required("chat.payloadHex"), chat.canonicalPayload().fixtureHex())
        assertTrue(chat.hasValidMessageId())

        val membership = MembershipEvent(
            fixture.required("membership.eventId"), fixture.required("chat.groupId"),
            MembershipEventType.valueOf(fixture.required("membership.eventType")),
            fixture.required("membership.subjectDeviceId"), fixture.required("membership.subjectDisplayName"),
            fixture.required("membership.subjectPublicKeyBase64"), fixture.required("chat.authorDeviceId"),
            fixture.csv("membership.parents"), version, time, "",
        )
        assertEquals(fixture.required("membership.payloadHex"), membership.canonicalPayload().fixtureHex())

        val folder = FolderAnnouncement(
            fixture.required("folder.eventId"), fixture.required("chat.groupId"),
            fixture.required("folder.folderId"), fixture.required("folder.displayName"),
            fixture.csv("folder.includes"), fixture.csv("folder.excludes"),
            fixture.required("chat.authorDeviceId"), version, time, "",
        )
        assertEquals(fixture.required("folder.payloadHex"), folder.canonicalPayload().fixtureHex())
        assertTrue(folder.hasValidEventId())
    }
}

private fun loadCrossPlatformFixture(): Properties {
    val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("protocol/fixtures/shared-core-v1.properties") }
        .first(Files::isRegularFile)
    return Properties().apply { Files.newInputStream(file).use(::load) }
}

private fun Properties.required(key: String): String = requireNotNull(getProperty(key))
private fun Properties.csv(key: String): List<String> = required(key).split(',').filter(String::isNotEmpty)
private fun ByteArray.fixtureHex(): String = joinToString("") { "%02x".format(it) }
