package com.syncdroid.shared.update

import com.syncdroid.shared.protocol.MeshSessionMessage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedOfflineUpdateTest {
    @Test fun newerOnlineManifestDoesNotDisplaceCompleteSeedAcrossRestart() = runBlocking {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        val root = Files.createTempDirectory("retained-offline-seed")
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val old = signedBundle(root, "1.2.1", keys)
            val newer = signedBundle(root, "1.2.2", keys)
            java.util.zip.ZipFile(newer.toFile()).use { zip ->
                for ((endpoint, entry) in listOf("/manifest" to "syncdroid-update.properties", "/manifest.sig" to RELEASE_SIGNATURE_FILE)) {
                    val bytes = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
                    server.createContext(endpoint) { request ->
                        request.sendResponseHeaders(200, bytes.size.toLong())
                        request.responseBody.use { it.write(bytes) }
                    }
                }
            }
            server.start()
            val cache = root.resolve("cache")
            val url = "http://127.0.0.1:${server.address.port}/manifest"
            val seed = service(cache, UpdatePlatform.WindowsX64, publicKey, manifestUrl = url)
            seed.importOfflineBundle(old)
            seed.checkForUpdate()
            assertEquals("1.2.2", assertIs<UpdateState.Available>(seed.state.value).manifest.version)
            assertEquals(setOf("1.2.1"), seed.availableAssets().map { it.releaseVersion }.toSet())
            val restarted = service(cache, UpdatePlatform.WindowsX64, publicKey, manifestUrl = url)
            assertEquals("1.2.1", restarted.seedState.value.version)
            val offlinePeer = service(root.resolve("peer"), UpdatePlatform.Android, publicKey)
            val toPeer = Channel<MeshSessionMessage>(Channel.UNLIMITED)
            val toSeed = Channel<MeshSessionMessage>(Channel.UNLIMITED)
            val sending = async { MeshUpdateExchange(restarted).run("b", "a", toPeer::send, toSeed::receive) }
            val receiving = async { MeshUpdateExchange(offlinePeer).run("a", "b", toSeed::send, toPeer::receive) }
            sending.await(); receiving.await()
            assertEquals("1.2.1", assertIs<UpdateState.Ready>(offlinePeer.state.value).manifest.version)
            restarted.importOfflineBundle(newer)
            assertEquals("1.2.2", restarted.seedState.value.version)
            assertEquals(setOf("1.2.2"), restarted.availableAssets().map { it.releaseVersion }.toSet())
        } finally { server.stop(0); root.toFile().deleteRecursively() }
    }

    @Test fun failedPreparationKeepsSeedAndReportsFailure() = runBlocking {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        val root = Files.createTempDirectory("failed-seed-refresh")
        try {
            val seed = service(root.resolve("cache"), UpdatePlatform.Android, publicKey)
            seed.importOfflineBundle(signedBundle(root, "1.2.1", keys))
            val newer = signedBundle(root, "1.2.2", keys)
            val entries = java.util.zip.ZipFile(newer.toFile()).use { zip ->
                zip.entries().asSequence().map { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }.toList()
            }
            ZipOutputStream(Files.newOutputStream(newer)).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(if (name.endsWith(".exe")) byteArrayOf(99) else bytes)
                    zip.closeEntry()
                }
            }
            assertFailsWith<IllegalStateException> { seed.importOfflineBundle(newer) }
            assertEquals("1.2.1", seed.seedState.value.version)
            assertFalse(seed.seedState.value.preparing)
            assertTrue(seed.seedState.value.error != null)
            assertEquals(setOf("1.2.1"), seed.availableAssets().map { it.releaseVersion }.toSet())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cleanupRetainsInFlightSeedAndTwoRecentReleases() = runBlocking {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        val root = Files.createTempDirectory("seed-cache-retention")
        try {
            val cache = root.resolve("cache")
            val seed = service(cache, UpdatePlatform.Android, publicKey)
            val original = signedBundle(root, "1.2.1", keys)
            seed.importOfflineBundle(original)
            val inFlight = seed.openExchange()
            try {
                val asset = inFlight.availableAssets().first { it.platformId == UpdatePlatform.Android.id }
                seed.importOfflineBundle(signedBundle(root, "1.2.2", keys))
                seed.importOfflineBundle(signedBundle(root, "1.2.3", keys))
                assertTrue(Files.isDirectory(cache.resolve("1.2.1")))
                assertContentEquals(byteArrayOf(1), inFlight.readChunk(asset.sha256, 0, 1))
                assertEquals(setOf("1.2.1"), inFlight.availableAssets().map { it.releaseVersion }.toSet())
            } finally { inFlight.closeExchange() }
            assertFalse(Files.exists(cache.resolve("1.2.1")))
            assertTrue(Files.isDirectory(cache.resolve("1.2.2")))
            assertTrue(Files.isDirectory(cache.resolve("1.2.3")))
            assertTrue(Files.exists(original))
            assertEquals("1.2.3", service(cache, UpdatePlatform.Android, publicKey).seedState.value.version)
        } finally { root.toFile().deleteRecursively() }
    }


    @Test
    fun importedBundleSeedsManifestAndPlatformAssetWithoutGitHub() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val files = mapOf(
            UpdatePlatform.Android to ("SyncDroid-Mesh-0.2.1-Android.apk" to byteArrayOf(1, 2, 3)),
            UpdatePlatform.MacOsArm64 to ("SyncTosh-0.2.1-macOS-arm64.dmg" to byteArrayOf(4, 5, 6, 7)),
            UpdatePlatform.WindowsX64 to ("SyncDows-0.2.1-Windows-x64.exe" to byteArrayOf(8, 9)),
        )
        val manifest = ReleaseManifest(
            version = "0.2.1",
            publishedAt = "2026-08-16T00:00:00Z",
            notesUrl = "https://example.test/releases/0.2.1",
            assets = files.map { (platform, namedBytes) ->
                ReleaseAsset(
                    platform,
                    namedBytes.first,
                    "https://example.test/${namedBytes.first}",
                    sha256(namedBytes.second),
                    namedBytes.second.size.toLong(),
                )
            },
        ).encode()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(manifest.toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val root = Files.createTempDirectory("signed-offline-update")
        try {
            val bundle = root.resolve("SyncDroid-Mesh-0.2.1-offline.sdu")
            ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
                fun add(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                add("syncdroid-update.properties", manifest.toByteArray(StandardCharsets.UTF_8))
                add(RELEASE_SIGNATURE_FILE, signature.toByteArray(StandardCharsets.UTF_8))
                files.values.forEach { (name, bytes) -> add(name, bytes) }
            }

            val source = service(root.resolve("source"), UpdatePlatform.WindowsX64, publicKey)
            val target = service(root.resolve("target"), UpdatePlatform.Android, publicKey)
            source.importOfflineBundle(bundle)

            val sourceToTarget = Channel<MeshSessionMessage>(Channel.UNLIMITED)
            val targetToSource = Channel<MeshSessionMessage>(Channel.UNLIMITED)
            val sourceJob = async {
                MeshUpdateExchange(source).run("device-b", "device-a", sourceToTarget::send, targetToSource::receive)
            }
            val targetJob = async {
                MeshUpdateExchange(target).run("device-a", "device-b", targetToSource::send, sourceToTarget::receive)
            }
            sourceJob.await()
            targetJob.await()

            val ready = assertIs<UpdateState.Ready>(target.state.value)
            assertEquals("0.2.1", ready.manifest.version)
            assertEquals(UpdateSource.Mesh, ready.source)
            assertContentEquals(files.getValue(UpdatePlatform.Android).second, ready.installer.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun alteredManifestIsRejected() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val original = "signed content"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(original.toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        assertFailsWith<IllegalArgumentException> {
            SignedReleaseManifest.verify("altered content", signature, publicKey)
        }
    }

    @Test
    fun importedSignedBundleOlderThanTheInstalledAppIsDeleted() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val root = Files.createTempDirectory("outdated-offline-update")
        try {
            val bundle = signedBundle(root, "1.2.3", keyPair)
            val error = assertFailsWith<OutdatedOfflineBundleException> {
                service(root.resolve("cache"), UpdatePlatform.Android, publicKey, currentVersion = "1.2.4")
                    .importOfflineBundle(bundle)
            }
            assertTrue(error.sourceDeleted)
            assertFalse(Files.exists(bundle))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundleMatchingTheInstalledVersionRemainsAvailableForSeeding() = runBlocking {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val root = Files.createTempDirectory("same-version-offline-update")
        try {
            val bundle = signedBundle(root, "1.2.5", keyPair)
            val source = service(root.resolve("cache"), UpdatePlatform.Android, publicKey, currentVersion = "1.2.5")
            assertEquals("1.2.5", source.importOfflineBundle(bundle))
            assertTrue(Files.exists(bundle))
            assertEquals(4, source.availableAssets().size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun service(
        directory: java.nio.file.Path,
        platform: UpdatePlatform,
        publicKey: String,
        currentVersion: String = "0.2.0",
        manifestUrl: String = "https://127.0.0.1/unavailable",
    ) = ReleaseUpdateService(
        currentVersion = currentVersion,
        platform = platform,
        cacheDirectory = directory,
        lastCheck = { 0L },
        lastCheckStore = LastUpdateCheckStore {},
        manifestUrl = manifestUrl,
        trustedPublicKeyBase64 = publicKey,
    )

    private fun signedBundle(
        root: java.nio.file.Path,
        version: String,
        keyPair: java.security.KeyPair,
    ): java.nio.file.Path {
        val files = mapOf(
            UpdatePlatform.Android to ("SyncDroid-Mesh-$version-Android.apk" to byteArrayOf(1)),
            UpdatePlatform.MacOsArm64 to ("SyncTosh-$version-macOS-arm64.dmg" to byteArrayOf(2)),
            UpdatePlatform.WindowsX64 to ("SyncDows-$version-Windows-x64.exe" to byteArrayOf(3)),
        )
        val manifest = ReleaseManifest(
            version = version,
            publishedAt = "2026-08-20T00:00:00Z",
            notesUrl = "https://example.test/releases/tag/v$version",
            assets = files.map { (platform, namedBytes) ->
                ReleaseAsset(
                    platform,
                    namedBytes.first,
                    "https://example.test/releases/download/v$version/${namedBytes.first}",
                    sha256(namedBytes.second),
                    namedBytes.second.size.toLong(),
                )
            },
        ).encode()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(manifest.toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        return root.resolve("SyncDroid-Mesh-$version-offline.sdu").also { bundle ->
            ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
                fun add(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                add("syncdroid-update.properties", manifest.toByteArray(StandardCharsets.UTF_8))
                add(RELEASE_SIGNATURE_FILE, signature.toByteArray(StandardCharsets.UTF_8))
                files.values.forEach { (name, bytes) -> add(name, bytes) }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
