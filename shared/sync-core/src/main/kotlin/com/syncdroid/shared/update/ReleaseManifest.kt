package com.syncdroid.shared.update

import java.io.StringReader
import java.util.Properties

enum class UpdatePlatform(val id: String) {
    Android("android"),
    MacOsArm64("macos-arm64"),
    WindowsX64("windows-x64"),
}

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
        assetOrNull(platform) ?: error("Release $version does not include ${platform.id}")

    fun assetOrNull(platform: UpdatePlatform): ReleaseAsset? = assets.singleOrNull { it.platform == platform }

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
                preRelease == null || other.preRelease == null -> 0
                else -> comparePreRelease(preRelease, other.preRelease)
            }

    companion object {
        private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        /** Null for anything that is not a version, including numbers too large to compare safely. */
        fun parse(value: String): SemanticVersion? = pattern.matchEntire(value.trim())?.destructured?.let {
            val (major, minor, patch, preRelease) = it
            SemanticVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                preRelease.ifBlank { null },
            )
        }

        /** Semantic Versioning precedence: rc.10 follows rc.9, and numeric identifiers precede text. */
        private fun comparePreRelease(left: String, right: String): Int {
            val leftParts = left.split('.')
            val rightParts = right.split('.')
            for (index in 0 until minOf(leftParts.size, rightParts.size)) {
                val a = leftParts[index]
                val b = rightParts[index]
                val aNumber = a.toBigIntegerOrNull()
                val bNumber = b.toBigIntegerOrNull()
                val result = when {
                    aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                    aNumber != null -> -1
                    bNumber != null -> 1
                    else -> a.compareTo(b)
                }
                if (result != 0) return result
            }
            return leftParts.size.compareTo(rightParts.size)
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
