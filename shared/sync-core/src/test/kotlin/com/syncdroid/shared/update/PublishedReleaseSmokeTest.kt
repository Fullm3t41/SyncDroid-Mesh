package com.syncdroid.shared.update

import com.syncdroid.shared.protocol.MeshSessionMessage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Assume.assumeTrue

/** Opt-in checks against real published assets, without installing apps or using live app data. */
class PublishedReleaseSmokeTest {
    @Test fun publishedReleaseSupportsGitHubOfflineImportAndMeshDelivery() = runBlocking {
        val bundlePath = System.getenv("SYNCDROID_SMOKE_BUNDLE")
        val version = System.getenv("SYNCDROID_SMOKE_VERSION")
        assumeTrue("Published release smoke test requires a bundle and version", bundlePath != null && version != null)
        val root = Files.createTempDirectory("published-release-smoke-")
        fun service(name: String, platform: UpdatePlatform, online: Boolean) = ReleaseUpdateService(
            currentVersion = "1.2.8",
            platform = platform,
            cacheDirectory = root.resolve(name),
            lastCheck = { 0L },
            lastCheckStore = LastUpdateCheckStore {},
            manifestUrl = if (online) DEFAULT_RELEASE_MANIFEST_URL else "http://127.0.0.1:1/github-disabled",
        )
        try {
            val online = service("github", UpdatePlatform.Android, true)
            online.checkForUpdate()
            assertEquals(version, assertIs<UpdateState.Available>(online.state.value).manifest.version)
            online.downloadUpdate()
            val githubReady = assertIs<UpdateState.Ready>(online.state.value)
            assertEquals(version, githubReady.manifest.version)
            assertEquals(UpdateSource.GitHub, githubReady.source)

            val seed = service("offline-seed", UpdatePlatform.WindowsX64, false)
            seed.importOfflineBundle(Path.of(requireNotNull(bundlePath)))
            assertEquals(version, assertIs<UpdateState.Ready>(seed.state.value).manifest.version)

            val peer = service("mesh-peer", UpdatePlatform.Android, false)
            val toPeer = Channel<MeshSessionMessage>(Channel.UNLIMITED)
            val toSeed = Channel<MeshSessionMessage>(Channel.UNLIMITED)
            val seedJob = async {
                MeshUpdateExchange(seed).run("device-b", "device-a", toPeer::send, toSeed::receive)
            }
            val peerJob = async {
                MeshUpdateExchange(peer).run("device-a", "device-b", toSeed::send, toPeer::receive)
            }
            seedJob.await()
            peerJob.await()
            val meshReady = assertIs<UpdateState.Ready>(peer.state.value)
            assertEquals(version, meshReady.manifest.version)
            assertEquals(UpdateSource.Mesh, meshReady.source)
            assertEquals(githubReady.asset.sha256, meshReady.asset.sha256)
            assertEquals(-1L, Files.mismatch(githubReady.installer, meshReady.installer))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
