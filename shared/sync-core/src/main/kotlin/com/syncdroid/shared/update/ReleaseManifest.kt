package com.syncdroid.shared.update

import java.io.StringReader
import java.util.Properties

enum class UpdatePlatform(val id: String) {
    Android("android"),
    MacOsArm64("macos-arm64"),
    WindowsX64("windows-x64"),
    LinuxX64("linux-x64"),
}

internal val REQUIRED_RELEASE_PLATFORMS = setOf(UpdatePlatform.Android, UpdatePlatform.MacOsArm64, UpdatePlatform.WindowsX64)

data class ReleaseAsset(
    val platform: UpdatePlatform,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class ReleaseManifest(
    val version: String,
    val publishedAt: String,
    val notesUrl: String,
    val assets: List<ReleaseAsset>,
) {
    fun assetFor(platform: UpdatePlatform): ReleaseAsset =
        assets.singleOrNull { it.platform == platform }
            ?: error("Release $version does not include ${platform.id}")

    fun encode(): String = buildString {
        appendLine("schema=1")
        appendLine("version=$version")
        appendLine("publishedAt=$publishedAt")
        appendLine("notesUrl=$notesUrl")
        assets.sortedBy { it.platform.id }.forEach { asset ->
            val prefix = "asset.${asset.platform.id}"
            appendLine("$prefix.file=${asset.fileName}")
            appendLine("$prefix.url=${asset.downloadUrl}")
            appendLine("$prefix.sha256=${asset.sha256.lowercase()}")
            appendLine("$prefix.size=${asset.sizeBytes}")
        }
    }

    companion object {
        fun parse(text: String): ReleaseManifest {
            val properties = Properties().apply { load(StringReader(text)) }
            require(properties.required("schema") == "1") { "Unsupported release manifest schema" }
            val assets = UpdatePlatform.entries.mapNotNull { platform ->
                val prefix = "asset.${platform.id}"
                properties.getProperty("$prefix.file")?.let { fileName ->
                    require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && fileName != "..") {
                        "Invalid update asset file name"
                    }
                    ReleaseAsset(
                        platform = platform,
                        fileName = fileName,
                        downloadUrl = properties.required("$prefix.url").also(::requireHttps),
                        sha256 = properties.required("$prefix.sha256").lowercase().also {
                            require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid update SHA-256" }
                        },
                        sizeBytes = properties.required("$prefix.size").toLong().also {
                            require(it > 0L) { "Invalid update asset size" }
                        },
                    )
                }
            }
            require(assets.isNotEmpty()) { "Release manifest contains no assets" }
            return ReleaseManifest(
                version = properties.required("version").also { require(SemanticVersion.parse(it) != null) },
                publishedAt = properties.required("publishedAt"),
                notesUrl = properties.required("notesUrl").also(::requireHttps),
                assets = assets,
            )
        }

        private fun Properties.required(key: String): String =
            getProperty(key)?.trim()?.takeIf(String::isNotEmpty) ?: error("Release manifest is missing $key")

        private fun requireHttps(url: String) {
            require(url.startsWith("https://")) { "Update URLs must use HTTPS" }
        }
    }
}

data class SemanticVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String?) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)
            .takeIf { it != 0 }
            ?: when {
                preRelease == null && other.preRelease != null -> 1
                preRelease != null && other.preRelease == null -> -1
                else -> compareValues(preRelease, other.preRelease)
            }

    companion object {
        private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(value: String): SemanticVersion? = pattern.matchEntire(value.trim())?.destructured?.let {
            val (major, minor, patch, preRelease) = it
            SemanticVersion(major.toInt(), minor.toInt(), patch.toInt(), preRelease.ifBlank { null })
        }
    }
}

fun isNewerVersion(candidate: String, current: String): Boolean {
    val candidateVersion = SemanticVersion.parse(candidate) ?: return false
    val currentVersion = SemanticVersion.parse(current) ?: return false
    return candidateVersion > currentVersion
}

internal fun offlineBundleDownloadUrl(manifest: ReleaseManifest): String {
    val releaseDirectory = manifest.assets.first().downloadUrl.substringBeforeLast('/')
    return "$releaseDirectory/SyncDroid-Mesh-${manifest.version}-offline.sdu"
}
