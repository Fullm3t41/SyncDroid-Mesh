package com.syncdroid.shared.update

import com.syncdroid.shared.protocol.UpdateAssetDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val DEFAULT_RELEASE_MANIFEST_URL =
    "https://api.github.com/repos/Fullm3t41/SyncDroid-Mesh/contents/syncdroid-update.properties?ref=updates"
const val UPDATE_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L

internal fun releaseSignatureUrl(manifestUrl: String): String {
    val suffixIndex = sequenceOf(manifestUrl.indexOf('?'), manifestUrl.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull()
        ?: manifestUrl.length
    return manifestUrl.substring(0, suffixIndex) + ".sig" + manifestUrl.substring(suffixIndex)
}

fun interface LastUpdateCheckStore {
    fun save(timeMillis: Long)
}

sealed interface UpdateState {
    val currentVersion: String

    data class Idle(override val currentVersion: String) : UpdateState
    data class Checking(override val currentVersion: String) : UpdateState
    data class UpToDate(override val currentVersion: String, val checkedAtMillis: Long) : UpdateState
    data class Available(
        override val currentVersion: String,
        val manifest: ReleaseManifest,
        val asset: ReleaseAsset,
    ) : UpdateState
    data class Downloading(
        override val currentVersion: String,
        val manifest: ReleaseManifest,
        val asset: ReleaseAsset,
        val bytesDownloaded: Long,
        val source: UpdateSource,
    ) : UpdateState {
        val progress: Float get() = (bytesDownloaded.toDouble() / asset.sizeBytes).toFloat().coerceIn(0f, 1f)
    }
    data class Ready(
        override val currentVersion: String,
        val manifest: ReleaseManifest,
        val asset: ReleaseAsset,
        val installer: Path,
        val source: UpdateSource,
    ) : UpdateState
    data class Failed(
        override val currentVersion: String,
        val message: String,
        val updateStillAvailable: Boolean,
    ) : UpdateState
}

enum class UpdateSource { GitHub, Mesh, OfflineBundle, Cache }

class OutdatedOfflineBundleException(
    val bundleVersion: String,
    val minimumVersion: String,
    val sourceDeleted: Boolean = false,
) : IllegalArgumentException(
    "Offline update $bundleVersion is older than $minimumVersion" +
        if (sourceDeleted) " and was deleted." else ".",
)

data class OfflineSeedState(val version: String? = null, val preparing: Boolean = false, val error: String? = null) {
    val description: String get() = when {
        preparing -> "Downloading and verifying installers for all platforms…"
        error != null && version != null -> "Preparation failed · version $version is still ready to share"
        error != null -> "Preparation failed · select to retry"
        version != null -> "Version $version ready to share · select to prepare the latest release"
        else -> "Download Windows, Mac and Android installers for offline devices on your mesh"
    }
}

interface MeshUpdateCache {
    /** Pin the advertised files for the lifetime of this exchange. */
    fun openExchange(): MeshUpdateCache = this
    fun closeExchange() {}
    fun availableAssets(): List<UpdateAssetDescriptor>
    fun desiredAsset(): UpdateAssetDescriptor?
    fun desiredAsset(remoteAssets: List<UpdateAssetDescriptor>): UpdateAssetDescriptor? =
        desiredAsset()?.takeIf(remoteAssets::contains)
    fun partialSize(sha256: String): Long
    suspend fun readChunk(sha256: String, offset: Long, maxBytes: Int): ByteArray?
    suspend fun writeChunk(asset: UpdateAssetDescriptor, offset: Long, bytes: ByteArray)
}

class ReleaseUpdateService(
    private val currentVersion: String,
    private val platform: UpdatePlatform,
    private val cacheDirectory: Path,
    private val lastCheck: () -> Long,
    private val lastCheckStore: LastUpdateCheckStore,
    private val manifestUrl: String = DEFAULT_RELEASE_MANIFEST_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val trustedPublicKeyBase64: String = RELEASE_SIGNING_PUBLIC_KEY_BASE64,
) : MeshUpdateCache {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle(currentVersion))
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private val verifiedAssetHashes = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var signedManifest: SignedReleaseManifest? = loadCachedManifest()

    private val cacheLock = Any()
    private val pinnedVersions = mutableMapOf<String, Int>()
    @Volatile private var seedManifest: SignedReleaseManifest? = runCatching {
        SignedReleaseManifest.decodeEnvelope(Files.readAllBytes(cacheDirectory.resolve("seed-manifest.signed")), trustedPublicKeyBase64)
    }.getOrNull()?.takeIf { signed -> signed.manifest.assets.all { isComplete(signed.manifest.version, it) } }
    private val mutableSeedState = MutableStateFlow(OfflineSeedState(seedManifest?.manifest?.version))
    val seedState: StateFlow<OfflineSeedState> = mutableSeedState.asStateFlow()

    @Volatile
    private var pendingManifestDescriptor: UpdateAssetDescriptor? = null

    init {
        signedManifest?.let(::promoteCompleteSeed)
        pruneCachedReleases()
        refreshStateFromCache(UpdateSource.Cache)
    }

    suspend fun runDailyChecks() {
        while (true) {
            checkForUpdate(force = false)
            val elapsed = now() - lastCheck()
            delay((UPDATE_CHECK_INTERVAL_MILLIS - elapsed).coerceIn(60_000L, UPDATE_CHECK_INTERVAL_MILLIS))
        }
    }

    suspend fun checkForUpdate(force: Boolean = true) = operationMutex.withLock {
        if (!force && now() - lastCheck() < UPDATE_CHECK_INTERVAL_MILLIS) {
            refreshStateFromCache(UpdateSource.Cache)
            return@withLock
        }
        mutableState.value = UpdateState.Checking(currentVersion)
        val checkedAt = now()
        lastCheckStore.save(checkedAt)
        runCatching {
            val candidate = withContext(Dispatchers.IO) { fetchSignedManifest() }
            acceptSignedManifest(candidate)
            if (isNewerVersion(candidate.manifest.version, currentVersion)) {
                refreshStateFromCache(UpdateSource.Cache)
            } else {
                mutableState.value = UpdateState.UpToDate(currentVersion, checkedAt)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            val cached = signedManifest?.manifest
            val stillAvailable = cached?.let { isNewerVersion(it.version, currentVersion) } == true
            if (stillAvailable) refreshStateFromCache(UpdateSource.Cache)
            else mutableState.value = UpdateState.Failed(
                currentVersion,
                error.message ?: "Could not check for updates",
                updateStillAvailable = false,
            )
        }
    }

    suspend fun downloadUpdate() = operationMutex.withLock {
        val selectedManifest = signedManifest?.manifest ?: return@withLock
        if (!isNewerVersion(selectedManifest.version, currentVersion)) return@withLock
        val asset = selectedManifest.assetFor(platform)
        if (isComplete(selectedManifest.version, asset)) {
            mutableState.value = UpdateState.Ready(
                currentVersion, selectedManifest, asset, assetPath(selectedManifest.version, asset), UpdateSource.Cache,
            )
            return@withLock
        }
        runCatching { downloadAsset(selectedManifest, asset) }.onFailure { error ->
            if (error is CancellationException) throw error
            mutableState.value = UpdateState.Failed(
                currentVersion,
                error.message ?: "Could not download the update",
                updateStillAvailable = true,
            )
        }
    }

    suspend fun importOfflineBundle(path: Path): String = try {
        Files.newInputStream(path).use { importOfflineBundle(it) }
    } catch (error: OutdatedOfflineBundleException) {
        val deleted = withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(path) }.getOrDefault(false) }
        throw OutdatedOfflineBundleException(error.bundleVersion, error.minimumVersion, deleted)
    }

    suspend fun importOfflineBundle(input: InputStream): String = operationMutex.withLock {
        mutableSeedState.value = mutableSeedState.value.copy(preparing = true, error = null)
        try {
            runCatching { withContext(Dispatchers.IO) { importBundleLocked(input) } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    val stillAvailable = signedManifest?.manifest?.let { isNewerVersion(it.version, currentVersion) } == true
                    mutableState.value = UpdateState.Failed(
                        currentVersion,
                        error.message ?: "Could not import the offline update bundle",
                        updateStillAvailable = stillAvailable,
                    )
                }
                .onFailure { mutableSeedState.value = mutableSeedState.value.copy(error = it.message ?: "Could not prepare offline updates") }
                .getOrThrow()
        } finally {
            mutableSeedState.value = mutableSeedState.value.copy(preparing = false)
            pruneCachedReleases()
        }
    }

    suspend fun downloadAndImportLatestOfflineBundle(): String = operationMutex.withLock {
        mutableSeedState.value = mutableSeedState.value.copy(preparing = true, error = null)
        try {
            runCatching {
                withContext(Dispatchers.IO) {
                    val candidate = fetchSignedManifest()
                    rejectOutdatedOfflineBundle(candidate)
                    acceptSignedManifest(candidate)
                    val connection = openConnection(offlineBundleDownloadUrl(candidate.manifest))
                    try {
                        connection.connect()
                        require(connection.responseCode in 200..299) { githubHttpError(connection.responseCode) }
                        connection.inputStream.buffered().use(::importBundleLocked)
                    } finally {
                        connection.disconnect()
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val stillAvailable = signedManifest?.manifest?.let { isNewerVersion(it.version, currentVersion) } == true
                mutableState.value = UpdateState.Failed(
                    currentVersion,
                    error.message ?: "Could not download the offline update bundle",
                    updateStillAvailable = stillAvailable,
                )
            }.onFailure { mutableSeedState.value = mutableSeedState.value.copy(error = it.message ?: "Could not prepare offline updates") }.getOrThrow()
        } finally {
            mutableSeedState.value = mutableSeedState.value.copy(preparing = false)
            pruneCachedReleases()
        }
    }

    fun installerPath(): Path? = (state.value as? UpdateState.Ready)?.installer

    // A complete cross-platform seed stays available until its replacement is complete.
    private fun servingManifest(): SignedReleaseManifest? = seedManifest ?: signedManifest

    private fun inventory(signed: SignedReleaseManifest): List<UpdateAssetDescriptor> = buildList {
        add(signed.descriptor())
        signed.manifest.assets.filter { isComplete(signed.manifest.version, it) }
            .forEach { add(it.descriptor(signed.manifest.version)) }
    }

    override fun availableAssets(): List<UpdateAssetDescriptor> = synchronized(cacheLock) {
        servingManifest()?.let(::inventory).orEmpty()
    }

    override fun openExchange(): MeshUpdateCache = synchronized(cacheLock) {
        val signed = servingManifest()
        val advertised = signed?.let(::inventory).orEmpty()
        val version = signed?.manifest?.version
        if (version != null) pinnedVersions[version] = (pinnedVersions[version] ?: 0) + 1
        object : MeshUpdateCache by this@ReleaseUpdateService {
            private var closed = false
            override fun availableAssets() = advertised
            override suspend fun readChunk(sha256: String, offset: Long, maxBytes: Int): ByteArray? =
                signed?.let { readCachedChunk(it, sha256, offset, maxBytes) }
            override fun closeExchange() = synchronized(cacheLock) {
                if (!closed) {
                    closed = true
                    if (version != null) {
                        val remaining = (pinnedVersions[version] ?: 1) - 1
                        if (remaining == 0) pinnedVersions.remove(version) else pinnedVersions[version] = remaining
                    }
                    pruneCachedReleases()
                }
            }
        }
    }

    override fun desiredAsset(): UpdateAssetDescriptor? {
        val current = signedManifest?.manifest ?: return null
        if (!isNewerVersion(current.version, currentVersion)) return null
        val asset = current.assetFor(platform)
        return asset.descriptor(current.version).takeUnless { isComplete(current.version, asset) }
    }

    override fun desiredAsset(remoteAssets: List<UpdateAssetDescriptor>): UpdateAssetDescriptor? {
        val baseline = signedManifest?.manifest?.version ?: currentVersion
        val remoteManifest = remoteAssets.asSequence()
            .filter { it.platformId == SIGNED_MANIFEST_PLATFORM_ID }
            .filter { it.sizeBytes in 1..SignedReleaseManifest.MAX_ENVELOPE_BYTES.toLong() }
            .filter { isNewerVersion(it.releaseVersion, baseline) }
            .maxWithOrNull(compareBy { SemanticVersion.parse(it.releaseVersion) })
        if (remoteManifest != null) {
            pendingManifestDescriptor = remoteManifest
            return remoteManifest
        }
        return desiredAsset()?.takeIf(remoteAssets::contains)
    }

    override fun partialSize(sha256: String): Long {
        val pendingManifest = pendingManifestDescriptor?.takeIf { it.sha256 == sha256 }
        if (pendingManifest != null) return partialManifestSize(pendingManifest)

        val selected = signedManifest ?: return 0L
        val asset = selected.manifest.assets.firstOrNull { it.sha256 == sha256 } ?: return 0L
        val partial = partialPath(selected.manifest.version, asset)
        if (!partial.exists()) return 0L
        return when (val size = partial.fileSize()) {
            in 0 until asset.sizeBytes -> size
            asset.sizeBytes -> {
                runCatching { finishDownload(selected.manifest, asset, UpdateSource.Mesh) }
                    .fold(onSuccess = { asset.sizeBytes }, onFailure = { 0L })
            }
            else -> {
                Files.deleteIfExists(partial)
                0L
            }
        }
    }

    override suspend fun readChunk(sha256: String, offset: Long, maxBytes: Int): ByteArray? =
        servingManifest()?.let { readCachedChunk(it, sha256, offset, maxBytes) }

    private suspend fun readCachedChunk(
        signed: SignedReleaseManifest, sha256: String, offset: Long, maxBytes: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        require(maxBytes in 1..MeshUpdateExchange.UPDATE_CHUNK_BYTES && offset >= 0)
        val envelope = signed.envelopeBytes()
        if (SignedReleaseManifest.sha256(envelope) == sha256) {
            if (offset !in 0..envelope.size.toLong()) return@withContext null
            return@withContext envelope.copyOfRange(offset.toInt(), (offset + maxBytes).coerceAtMost(envelope.size.toLong()).toInt())
        }
        val asset = signed.manifest.assets.firstOrNull { it.sha256 == sha256 } ?: return@withContext null
        val path = assetPath(signed.manifest.version, asset)
        if (!isComplete(signed.manifest.version, asset) || offset !in 0..asset.sizeBytes) return@withContext null
        Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            val remaining = (asset.sizeBytes - offset).coerceAtMost(maxBytes.toLong()).toInt()
            val buffer = java.nio.ByteBuffer.allocate(remaining)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.flip()
            ByteArray(buffer.remaining()).also(buffer::get)
        }
    }

    override suspend fun writeChunk(asset: UpdateAssetDescriptor, offset: Long, bytes: ByteArray) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (asset.platformId == SIGNED_MANIFEST_PLATFORM_ID) {
                writeManifestChunk(asset, offset, bytes)
                return@withContext
            }
            val selectedManifest = signedManifest?.manifest ?: error("No verified release manifest is available")
            val expected = selectedManifest.assets.singleOrNull { it.sha256 == asset.sha256 }
                ?: error("Peer offered an unknown update asset")
            require(expected.descriptor(selectedManifest.version) == asset) {
                "Peer update metadata does not match the signed release manifest"
            }
            val partial = partialPath(selectedManifest.version, expected)
            Files.createDirectories(partial.parent)
            val currentSize = partial.takeIf(Path::exists)?.fileSize() ?: 0L
            require(offset == currentSize) { "Update chunk is not contiguous" }
            require(offset + bytes.size <= expected.sizeBytes) { "Update chunk exceeds the expected asset size" }
            Files.write(partial, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            val downloaded = offset + bytes.size
            mutableState.value = UpdateState.Downloading(
                currentVersion, selectedManifest, expected, downloaded, UpdateSource.Mesh,
            )
            if (downloaded == expected.sizeBytes) {
                runCatching { finishDownload(selectedManifest, expected, UpdateSource.Mesh) }
                    .onFailure { error ->
                        mutableState.value = UpdateState.Failed(
                            currentVersion,
                            error.message ?: "Mesh update failed verification",
                            updateStillAvailable = true,
                        )
                        throw error
                    }
            }
        }
    }

    private fun importBundleLocked(input: InputStream): String {
        Files.createDirectories(cacheDirectory)
        val temporaryBundle = cacheDirectory.resolve(".offline-import-${UUID.randomUUID()}.sdu")
        try {
            Files.newOutputStream(temporaryBundle, StandardOpenOption.CREATE_NEW).buffered().use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_OFFLINE_BUNDLE_BYTES) { "Offline update bundle is too large" }
                    output.write(buffer, 0, count)
                }
            }
            ZipFile(temporaryBundle.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size in 3..MAX_BUNDLE_ENTRIES) { "Offline update bundle has an invalid file count" }
                require(entries.none { it.isDirectory || '/' in it.name || '\\' in it.name || it.name == ".." }) {
                    "Offline update bundle contains an unsafe path"
                }
                require(entries.map { it.name }.distinct().size == entries.size) { "Offline update bundle contains duplicate files" }
                val manifestEntry = zip.getEntry(MANIFEST_FILE) ?: error("Offline update bundle is missing $MANIFEST_FILE")
                val signatureEntry = zip.getEntry(RELEASE_SIGNATURE_FILE) ?: error("Offline update bundle is missing $RELEASE_SIGNATURE_FILE")
                val manifestText = String(zip.readSmallEntry(manifestEntry, SignedReleaseManifest.MAX_MANIFEST_BYTES), StandardCharsets.UTF_8)
                val signatureText = String(zip.readSmallEntry(signatureEntry, MAX_SIGNATURE_FILE_BYTES), StandardCharsets.UTF_8)
                val candidate = SignedReleaseManifest.verify(manifestText, signatureText, trustedPublicKeyBase64)
                rejectOutdatedOfflineBundle(candidate)
                validateCandidateVersion(candidate)
                require(candidate.manifest.assets.map { it.platform }.toSet().containsAll(REQUIRED_RELEASE_PLATFORMS)) {
                    "Offline update bundle must contain Android, macOS and Windows releases"
                }
                val allowedNames = candidate.manifest.assets.mapTo(mutableSetOf(MANIFEST_FILE, RELEASE_SIGNATURE_FILE)) { it.fileName }
                require(entries.all { it.name in allowedNames } && entries.size == allowedNames.size) {
                    "Offline update bundle does not exactly match its signed manifest"
                }
                candidate.manifest.assets.forEach { asset ->
                    val entry = zip.getEntry(asset.fileName) ?: error("Offline update bundle is missing ${asset.fileName}")
                    require(entry.size == -1L || entry.size == asset.sizeBytes) { "${asset.fileName} has the wrong size" }
                    val destination = assetPath(candidate.manifest.version, asset)
                    val partial = destination.resolveSibling("${asset.fileName}.import")
                    Files.createDirectories(destination.parent)
                    Files.deleteIfExists(partial)
                    try {
                        zip.getInputStream(entry).use { source ->
                            Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW).buffered().use { output ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                var copied = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    require(copied <= asset.sizeBytes) { "${asset.fileName} is larger than its signed size" }
                                    output.write(buffer, 0, count)
                                }
                                require(copied == asset.sizeBytes) { "${asset.fileName} has the wrong size" }
                            }
                        }
                        if (sha256(partial) != asset.sha256) error("${asset.fileName} failed its SHA-256 check")
                    } catch (error: Throwable) {
                        Files.deleteIfExists(partial)
                        throw error
                    }
                    moveReplacing(partial, destination)
                    verifiedAssetHashes += asset.sha256
                }
                acceptSignedManifest(candidate)
                refreshStateFromCache(UpdateSource.OfflineBundle)
                return candidate.manifest.version
            }
        } finally {
            Files.deleteIfExists(temporaryBundle)
        }
    }

    private suspend fun downloadAsset(selectedManifest: ReleaseManifest, asset: ReleaseAsset) = withContext(Dispatchers.IO) {
        Files.createDirectories(cacheDirectory.resolve(selectedManifest.version))
        val partial = partialPath(selectedManifest.version, asset)
        var offset = partial.takeIf(Path::exists)?.fileSize() ?: 0L
        if (offset == asset.sizeBytes) {
            finishDownload(selectedManifest, asset, UpdateSource.Cache)
            return@withContext
        }
        if (offset > asset.sizeBytes) {
            Files.deleteIfExists(partial)
            offset = 0L
        }
        var connection = openConnection(asset.downloadUrl).apply {
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        connection.connect()
        if (offset > 0L && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            Files.deleteIfExists(partial)
            offset = 0L
            connection = openConnection(asset.downloadUrl).also { it.connect() }
        }
        require(connection.responseCode in 200..299) { githubHttpError(connection.responseCode) }
        BufferedInputStream(connection.inputStream).use { source ->
            BufferedOutputStream(Files.newOutputStream(
                partial,
                StandardOpenOption.CREATE,
                if (offset == 0L) StandardOpenOption.TRUNCATE_EXISTING else StandardOpenOption.APPEND,
            )).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var downloaded = offset
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    mutableState.value = UpdateState.Downloading(
                        currentVersion, selectedManifest, asset, downloaded, UpdateSource.GitHub,
                    )
                }
            }
        }
        connection.disconnect()
        require(partial.fileSize() == asset.sizeBytes) { "Downloaded update size does not match the signed manifest" }
        finishDownload(selectedManifest, asset, UpdateSource.GitHub)
    }

    private fun writeManifestChunk(asset: UpdateAssetDescriptor, offset: Long, bytes: ByteArray) {
        require(asset == pendingManifestDescriptor) { "Peer offered an unexpected signed manifest" }
        require(asset.sizeBytes in 1..SignedReleaseManifest.MAX_ENVELOPE_BYTES.toLong()) { "Signed manifest is too large" }
        val partial = manifestPartialPath(asset.sha256)
        Files.createDirectories(partial.parent)
        val currentSize = partial.takeIf(Path::exists)?.fileSize() ?: 0L
        require(offset == currentSize) { "Signed manifest chunk is not contiguous" }
        require(offset + bytes.size <= asset.sizeBytes) { "Signed manifest chunk exceeds its expected size" }
        Files.write(partial, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if (offset + bytes.size == asset.sizeBytes) {
            try {
                finishManifestDownload(asset, partial)
            } catch (error: Throwable) {
                Files.deleteIfExists(partial)
                throw error
            }
        }
    }

    private fun partialManifestSize(asset: UpdateAssetDescriptor): Long {
        val partial = manifestPartialPath(asset.sha256)
        if (!partial.exists()) return 0L
        return when (val size = partial.fileSize()) {
            in 0 until asset.sizeBytes -> size
            asset.sizeBytes -> runCatching {
                finishManifestDownload(asset, partial)
                asset.sizeBytes
            }.getOrElse {
                Files.deleteIfExists(partial)
                0L
            }
            else -> {
                Files.deleteIfExists(partial)
                0L
            }
        }
    }

    private fun finishManifestDownload(asset: UpdateAssetDescriptor, partial: Path) {
        val bytes = Files.readAllBytes(partial)
        if (SignedReleaseManifest.sha256(bytes) != asset.sha256) {
            Files.deleteIfExists(partial)
            error("Peer signed manifest failed its transport hash check")
        }
        val candidate = SignedReleaseManifest.decodeEnvelope(bytes, trustedPublicKeyBase64)
        require(candidate.manifest.version == asset.releaseVersion) { "Peer manifest version does not match its inventory" }
        acceptSignedManifest(candidate)
        pendingManifestDescriptor = null
        Files.deleteIfExists(partial)
        refreshStateFromCache(UpdateSource.Mesh)
    }

    private fun finishDownload(selectedManifest: ReleaseManifest, asset: ReleaseAsset, source: UpdateSource) {
        val partial = partialPath(selectedManifest.version, asset)
        if (sha256(partial) != asset.sha256) {
            Files.deleteIfExists(partial)
            error("Downloaded update failed its SHA-256 check")
        }
        val destination = assetPath(selectedManifest.version, asset)
        moveReplacing(partial, destination)
        verifiedAssetHashes += asset.sha256
        mutableState.value = UpdateState.Ready(currentVersion, selectedManifest, asset, destination, source)
    }

    private fun acceptSignedManifest(candidate: SignedReleaseManifest) {
        validateCandidateVersion(candidate)
        signedManifest?.let(::promoteCompleteSeed)
        Files.createDirectories(cacheDirectory)
        writeAtomically(cacheDirectory.resolve(RELEASE_SIGNATURE_FILE), candidate.signatureBase64.toByteArray(StandardCharsets.UTF_8))
        writeAtomically(cacheDirectory.resolve(MANIFEST_FILE), candidate.manifestText.toByteArray(StandardCharsets.UTF_8))
        signedManifest = candidate
        promoteCompleteSeed(candidate)
        pruneCachedReleases()
    }

    private fun promoteCompleteSeed(candidate: SignedReleaseManifest) = synchronized(cacheLock) {
        val manifest = candidate.manifest
        val previous = seedManifest?.manifest?.version
        if (previous != null && !isNewerVersion(manifest.version, previous)) return@synchronized
        if (!manifest.assets.map { it.platform }.toSet().containsAll(REQUIRED_RELEASE_PLATFORMS) ||
            !manifest.assets.all { isComplete(manifest.version, it) }) return@synchronized
        Files.createDirectories(cacheDirectory)
        writeAtomically(cacheDirectory.resolve("seed-manifest.signed"), candidate.envelopeBytes())
        seedManifest = candidate
        mutableSeedState.value = mutableSeedState.value.copy(version = manifest.version, error = null)
    }

    private fun pruneCachedReleases() = synchronized(cacheLock) {
        if (!Files.isDirectory(cacheDirectory)) return@synchronized
        val directories = Files.list(cacheDirectory).use { entries ->
            entries.filter { path -> SemanticVersion.parse(path.fileName.toString()) != null &&
                Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.iterator().asSequence().toList()
        }
        // Retain two recent releases as well as the installed version, complete seed and active transfers.
        val recent = directories.map { it.fileName.toString() }
            .sortedWith(compareByDescending { SemanticVersion.parse(it) }).take(2)
        val keep = setOfNotNull(currentVersion, signedManifest?.manifest?.version, seedManifest?.manifest?.version) + pinnedVersions.keys + recent
        directories.filter { it.fileName.toString() !in keep }.forEach { directory ->
            // These are app-owned release directories, never user-selected bundle paths.
            runCatching {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    private fun validateCandidateVersion(candidate: SignedReleaseManifest) {
        val existing = signedManifest ?: return
        val comparison = requireNotNull(SemanticVersion.parse(candidate.manifest.version))
            .compareTo(requireNotNull(SemanticVersion.parse(existing.manifest.version)))
        require(comparison >= 0) { "Release manifest rollback was rejected" }
        if (comparison == 0) {
            require(candidate.manifestText == existing.manifestText && candidate.signatureBase64 == existing.signatureBase64) {
                "A different manifest already exists for version ${candidate.manifest.version}"
            }
        }
    }

    private fun rejectOutdatedOfflineBundle(candidate: SignedReleaseManifest) {
        val minimum = sequenceOf(currentVersion, signedManifest?.manifest?.version)
            .filterNotNull()
            .maxWithOrNull(compareBy { requireNotNull(SemanticVersion.parse(it)) })
            ?: currentVersion
        val candidateVersion = requireNotNull(SemanticVersion.parse(candidate.manifest.version))
        if (candidateVersion < requireNotNull(SemanticVersion.parse(minimum))) {
            throw OutdatedOfflineBundleException(candidate.manifest.version, minimum)
        }
    }

    private fun refreshStateFromCache(source: UpdateSource) {
        val cached = signedManifest?.manifest ?: return
        if (!isNewerVersion(cached.version, currentVersion)) return
        val asset = runCatching { cached.assetFor(platform) }.getOrNull() ?: return
        mutableState.value = if (isComplete(cached.version, asset)) {
            UpdateState.Ready(currentVersion, cached, asset, assetPath(cached.version, asset), source)
        } else {
            UpdateState.Available(currentVersion, cached, asset)
        }
    }

    private fun loadCachedManifest(): SignedReleaseManifest? = runCatching {
        SignedReleaseManifest.verify(
            Files.readString(cacheDirectory.resolve(MANIFEST_FILE)),
            Files.readString(cacheDirectory.resolve(RELEASE_SIGNATURE_FILE)),
            trustedPublicKeyBase64,
        )
    }.getOrNull()

    private fun isComplete(version: String, asset: ReleaseAsset): Boolean {
        val path = assetPath(version, asset)
        if (!path.exists() || path.fileSize() != asset.sizeBytes) return false
        if (asset.sha256 in verifiedAssetHashes) return true
        return if (sha256(path) == asset.sha256) {
            verifiedAssetHashes += asset.sha256
            true
        } else {
            false
        }
    }

    private fun assetPath(version: String, asset: ReleaseAsset): Path = cacheDirectory.resolve(version).resolve(asset.fileName)
    private fun partialPath(version: String, asset: ReleaseAsset): Path =
        assetPath(version, asset).resolveSibling("${asset.fileName}.part")
    private fun manifestPartialPath(sha256: String): Path = cacheDirectory.resolve("incoming-manifests").resolve("$sha256.part")

    private fun ReleaseAsset.descriptor(version: String) = UpdateAssetDescriptor(
        releaseVersion = version,
        platformId = platform.id,
        fileName = fileName,
        sha256 = sha256,
        sizeBytes = sizeBytes,
    )

    private fun SignedReleaseManifest.descriptor(): UpdateAssetDescriptor {
        val envelope = envelopeBytes()
        return UpdateAssetDescriptor(
            releaseVersion = manifest.version,
            platformId = SIGNED_MANIFEST_PLATFORM_ID,
            fileName = "syncdroid-update-${manifest.version}.signed",
            sha256 = SignedReleaseManifest.sha256(envelope),
            sizeBytes = envelope.size.toLong(),
        )
    }

    private fun httpGetText(url: String): String {
        val connection = openConnection(url)
        return try {
            connection.connect()
            require(connection.responseCode in 200..299) { githubHttpError(connection.responseCode) }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchSignedManifest(): SignedReleaseManifest {
        val manifestText = httpGetText(manifestUrl)
        val signatureText = httpGetText(releaseSignatureUrl(manifestUrl))
        return SignedReleaseManifest.verify(manifestText, signatureText, trustedPublicKeyBase64)
    }

    private fun openConnection(url: String): HttpURLConnection {
        val uri = URI(url)
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            if (uri.host.equals("api.github.com", ignoreCase = true) && "/contents/" in uri.path) {
                setRequestProperty("Accept", "application/vnd.github.raw+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            } else {
                setRequestProperty("Accept", "application/octet-stream")
            }
            setRequestProperty("User-Agent", "SyncDroid-Mesh-Updater/$currentVersion")
        }
    }

    private fun githubHttpError(responseCode: Int): String = when (responseCode) {
        429 -> "GitHub temporarily rate-limited update checks (HTTP 429). Try again shortly."
        else -> "GitHub returned HTTP $responseCode"
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveReplacing(source: Path, destination: Path) {
        runCatching {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeAtomically(destination: Path, bytes: ByteArray) {
        val temporary = destination.resolveSibling("${destination.fileName}.tmp")
        Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        moveReplacing(temporary, destination)
    }

    private fun ZipFile.readSmallEntry(entry: java.util.zip.ZipEntry, maxBytes: Int): ByteArray =
        getInputStream(entry).use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "${entry.name} is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

    private companion object {
        const val MANIFEST_FILE = "syncdroid-update.properties"
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val MAX_SIGNATURE_FILE_BYTES = 16 * 1024
        const val MAX_BUNDLE_ENTRIES = 16
        const val MAX_OFFLINE_BUNDLE_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
