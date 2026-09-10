package org.olcbox.app.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.identity.DeviceIdentityProvider
import org.olcbox.app.data.repository.SubscriptionFetchProxy

@Serializable
enum class ReleaseChannel {
    Stable,
    Nightly
}

data class ReleaseMirror(
    val name: String,
    val repositoryUrl: String
) {
    val ownerRepo: String
        get() = repositoryUrl
            .removePrefix("https://github.com/")
            .removeSuffix("/")

    companion object {
        val GitHub = ReleaseMirror(
            name = "GitHub",
            repositoryUrl = "https://github.com/sweetbat/PTRK-KVN-app"
        )
    }
}

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val updatedAt: String? = null
)

data class AppUpdateInfo(
    val channel: ReleaseChannel,
    val version: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val asset: AppUpdateAsset,
    val isUpdateAvailable: Boolean
)

class AppUpdateService(
    private val httpClient: HttpClient = createUpdateHttpClient(),
    private val deviceIdentityProvider: DeviceIdentityProvider,
    private val mirror: ReleaseMirror = ReleaseMirror.GitHub,
    private val currentVersion: String = CurrentAppInfo.value.version,
    private val platform: UpdatePlatform = UpdatePlatform.current()
) {
    suspend fun check(
        channel: ReleaseChannel,
        proxy: SubscriptionFetchProxy? = null
    ): Result<AppUpdateInfo> = runCatching {
        val release = fetchRelease(channel, proxy)
        val asset = selectAsset(release.assets, platform)
            ?: error(
                "No ${platform.assetToken.joinToString(" + ")} update asset in ${release.tagName}. " +
                        "Expected asset name containing ${platform.assetToken.joinToString(", ")}" +
                        platform.preferredExtensions.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " and ending with one of: ")
                            .orEmpty()
            )

        AppUpdateInfo(
            channel = channel,
            version = updateVersion(channel, release.tagName, asset),
            htmlUrl = release.htmlUrl,
            publishedAt = release.publishedAt,
            asset = asset,
            isUpdateAvailable = isUpdateAvailable(
                channel = channel,
                releaseTag = updateVersion(channel, release.tagName, asset),
                currentVersion = currentVersion
            )
        )
    }

    suspend fun fetchRelease(
        channel: ReleaseChannel,
        proxy: SubscriptionFetchProxy? = null
    ): GithubRelease {
        val client = if (proxy == null) {
            httpClient
        } else {
            createUpdateHttpClient(proxy)
        }

        return try {
            withProxyAuthentication(proxy) {
                fetchRelease(client, channel)
            }
        } finally {
            if (client !== httpClient) {
                client.close()
            }
        }
    }

    private suspend fun fetchRelease(client: HttpClient, channel: ReleaseChannel): GithubRelease {
        return when (channel) {
            ReleaseChannel.Stable -> fetchEndpoint(
                client,
                "https://api.github.com/repos/${mirror.ownerRepo}/releases/latest",
            )
            ReleaseChannel.Nightly -> fetchBetaRelease(client)
        }
    }

    private suspend fun fetchBetaRelease(client: HttpClient): GithubRelease {
        // Prefer an explicit `beta` tag release; otherwise newest prerelease.
        runCatching {
            return fetchEndpoint(
                client,
                "https://api.github.com/repos/${mirror.ownerRepo}/releases/tags/beta",
            )
        }
        val listUrl = "https://api.github.com/repos/${mirror.ownerRepo}/releases?per_page=20"
        val hwid = deviceIdentityProvider.hwid()
        val response = client.get(listUrl) {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github+json")
                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                append("x-hwid", hwid)
            }
        }
        if (response.status.value !in 200..299) {
            error("GitHub beta release request failed with HTTP ${response.status.value}")
        }
        val releases = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GithubReleaseListed.serializer()),
            response.bodyAsText(),
        )
        val beta = releases.firstOrNull { it.prerelease && !it.draft }
            ?: releases.firstOrNull { !it.draft }
            ?: error("No beta releases found for ${mirror.ownerRepo}")
        return GithubRelease(
            tagName = beta.tagName,
            htmlUrl = beta.htmlUrl,
            publishedAt = beta.publishedAt,
            assets = beta.assets,
        )
    }

    private suspend fun fetchEndpoint(client: HttpClient, endpoint: String): GithubRelease {
        val hwid = deviceIdentityProvider.hwid()
        val response = client.get(endpoint) {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github+json")
                append(HttpHeaders.UserAgent, CurrentAppInfo.userAgent)
                append("x-hwid", hwid)
            }
        }

        if (response.status.value !in 200..299) {
            error("GitHub release request failed with HTTP ${response.status.value}")
        }

        return json.decodeFromString(GithubRelease.serializer(), response.bodyAsText())
    }

    companion object {
        fun selectAsset(assets: List<GithubReleaseAsset>, platform: UpdatePlatform): AppUpdateAsset? {
            val asset = when (platform.os) {
                "android" -> selectAndroidAsset(assets, platform)
                else -> selectAssetByTokens(assets, platform.assetToken, platform.preferredExtensions)
            }

            return asset?.let {
                AppUpdateAsset(
                    name = it.name,
                    downloadUrl = it.browserDownloadUrl,
                    sizeBytes = it.size,
                    updatedAt = it.updatedAt
                )
            }
        }

        private fun selectAndroidAsset(
            assets: List<GithubReleaseAsset>,
            platform: UpdatePlatform
        ): GithubReleaseAsset? {
            val preferredExtensions = platform.preferredExtensions
            val exactAbiAsset = platform.androidArchTokens.firstNotNullOfOrNull { archToken ->
                selectAssetByTokens(assets, listOf("android", archToken), preferredExtensions)
            }
            if (exactAbiAsset != null) return exactAbiAsset

            val universalCandidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                "android" in name && knownAndroidArchTokens.none { it in name }
            }

            return selectPreferredAsset(universalCandidates, preferredExtensions)
        }

        private fun selectAssetByTokens(
            assets: List<GithubReleaseAsset>,
            tokens: List<String>,
            preferredExtensions: List<String>
        ): GithubReleaseAsset? {
            val candidates = assets.filter { asset ->
                val name = asset.name.lowercase()
                tokens.all { it in name }
            }

            return selectPreferredAsset(candidates, preferredExtensions)
        }

        private fun selectPreferredAsset(
            candidates: List<GithubReleaseAsset>,
            preferredExtensions: List<String>
        ): GithubReleaseAsset? {
            fun score(asset: GithubReleaseAsset): Long {
                val version = asset.name.versionToken()
                    ?.split('.', '-', '_')
                    ?.mapNotNull { it.toLongOrNull() }
                    .orEmpty()
                // Prefer higher semver; fall back to updated timestamp.
                val versionScore = version.getOrNull(0)?.times(1_000_000L)?.plus(
                    (version.getOrNull(1) ?: 0L) * 1_000L + (version.getOrNull(2) ?: 0L)
                ) ?: 0L
                return versionScore
            }

            val byExt = preferredExtensions.flatMap { extension ->
                candidates.filter { it.name.lowercase().endsWith(extension) }
            }.ifEmpty { candidates }

            return byExt.maxWithOrNull(compareBy<GithubReleaseAsset> { score(it) }
                .thenBy { it.updatedAt.orEmpty() })
        }

        fun isUpdateAvailable(
            channel: ReleaseChannel,
            releaseTag: String,
            currentVersion: String
        ): Boolean {
            val release = releaseTag.removePrefix("v")
            if (channel == ReleaseChannel.Nightly && (release == "nightly" || release == "beta")) return true

            return compareVersions(release, currentVersion) > 0
        }

        fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val size = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until size) {
                val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }

        private fun updateVersion(
            channel: ReleaseChannel,
            releaseTag: String,
            asset: AppUpdateAsset
        ): String {
            return when (channel) {
                ReleaseChannel.Stable -> releaseTag.removePrefix("v")
                ReleaseChannel.Nightly -> asset.name.versionToken() ?: releaseTag.removePrefix("v")
            }
        }

        private fun String.versionToken(): String? {
            return Regex("""(?:^|[-_])v?(\d+\.\d+\.\d+)(?:[-_.]|$)""")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
        }
    }
}

data class UpdatePlatform(
    val os: String,
    val arch: String
) {
    val assetToken: List<String>
        get() = when (os) {
            "windows" -> listOf("windows", "amd64")
            "macos" -> listOf("macos", arch)
            "linux" -> listOf("linux", arch)
            "android" -> listOf("android")
            else -> listOf(os, arch)
        }

    val androidArchTokens: List<String>
        get() = when (os) {
            "android" -> when (arch.lowercase()) {
                "armeabi-v7a", "armeabi" -> listOf("armeabi-v7a", "armeabi")
                "arm64", "arm64-v8a" -> listOf("arm64", "arm64-v8a")
                "amd64", "x86_64" -> listOf("amd64", "x86_64")
                "x86" -> listOf("x86")
                else -> emptyList()
            }
            else -> emptyList()
        }

    val preferredExtensions: List<String>
        get() = when (os) {
            "windows" -> listOf(".msi", ".exe", ".zip")
            "macos" -> listOf(".dmg")
            "linux" -> listOf(".appimage")
            "android" -> listOf(".apk")
            else -> emptyList()
        }

    companion object {
        fun current(): UpdatePlatform = currentUpdatePlatform()
    }
}

expect fun currentUpdatePlatform(): UpdatePlatform

private val knownAndroidArchTokens = setOf(
    "armeabi-v7a",
    "armeabi",
    "arm64-v8a",
    "arm64",
    "x86_64",
    "amd64",
    "x86"
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GithubReleaseAsset> = emptyList()
)

@Serializable
private data class GithubReleaseListed(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubReleaseAsset> = emptyList()
)

@Serializable
data class GithubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

private val json = Json {
    ignoreUnknownKeys = true
}

private fun createUpdateHttpClient(proxy: SubscriptionFetchProxy? = null): HttpClient =
    createProxyHttpClient(
        subscriptionProxy = proxy,
        connectTimeoutMs = 5_000,
        requestTimeoutMs = 15_000,
        socketTimeoutMs = 15_000
    )
