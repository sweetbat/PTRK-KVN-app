package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.olcbox.app.data.identity.RemnawaveDeviceIdentity
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy?,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
    allowInsecureRequests: Boolean
): HttpClient {
    val insecureTrustManager = if (allowInsecureRequests) trustAllCertificatesManager() else null
    val okHttp = buildSubscriptionOkHttpClient(
        connectTimeoutMs = connectTimeoutMs,
        requestTimeoutMs = requestTimeoutMs,
        socketTimeoutMs = socketTimeoutMs,
        insecureTrustManager = insecureTrustManager,
        pinSubscriptionHeaders = true,
        subscriptionProxy = subscriptionProxy,
    )

    return HttpClient(OkHttp) {
        expectSuccess = false

        engine {
            // Proxy is already on the preconfigured OkHttp client when VPN is up.
            preconfigured = okHttp
        }

        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

/**
 * Direct OkHttp download that identifies as PTRK-KVN-app (panel / Telegram bot)
 * and requests Clash YAML via ?flag=meta / ?flag=clash.
 */
internal actual suspend fun downloadSubscriptionBodyDirect(
    url: String,
    hwid: String?,
    allowInsecureRequests: Boolean,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
    subscriptionProxy: SubscriptionFetchProxy?,
): DirectSubscriptionDownload = withContext(Dispatchers.IO) {
    val client = buildSubscriptionOkHttpClient(
        connectTimeoutMs = connectTimeoutMs,
        requestTimeoutMs = requestTimeoutMs,
        socketTimeoutMs = socketTimeoutMs,
        insecureTrustManager = if (allowInsecureRequests) trustAllCertificatesManager() else null,
        pinSubscriptionHeaders = true,
        subscriptionProxy = subscriptionProxy,
    )
    try {
        fun usable(text: String): Boolean = isUsableSubscriptionBody(text)

        val appAgent = RemnawaveDeviceIdentity.userAgent()
        val deviceModel = RemnawaveDeviceIdentity.deviceModel()
        val osVersion = RemnawaveDeviceIdentity.osVersion()
        // Through VPN: one meta + one ClashMeta fallback. Extra UA/flag spam starves olcRTC.
        val viaVpn = subscriptionProxy != null
        val yamlAgents = if (viaVpn) {
            listOf(appAgent, "ClashMeta/1.19.0")
        } else {
            listOf(appAgent, "ClashMeta/1.19.0", "mihomo/1.19.0", "clash-verge")
        }
        val joiner = if ('?' in url) "&" else "?"
        val urls = if (viaVpn) {
            listOf("$url${joiner}flag=meta", url).distinct()
        } else {
            listOf(
                "$url${joiner}flag=meta",
                "$url${joiner}flag=clash",
                url,
            ).distinct()
        }
        val maxAttempts = if (viaVpn) 2 else 6
        var attempts = 0

        suspend fun fetch(
            target: String,
            agent: String,
            includeHwid: Boolean,
        ): DirectSubscriptionDownload? {
            val builder = Request.Builder()
                .url(target)
                .header("User-Agent", agent)
                .header("Accept", "text/yaml, text/plain, application/octet-stream, */*")
                .header("x-device-os", "Android")
                .header("x-ver-os", osVersion)
                .header("x-device-model", deviceModel)
            if (includeHwid && !hwid.isNullOrBlank()) {
                builder.header("x-hwid", hwid)
            }
            val response = client.newCall(builder.build()).await()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                android.util.Log.i(
                    "SubDownload",
                    "ua=$agent model=$deviceModel os=$osVersion hwid=${includeHwid && !hwid.isNullOrBlank()} " +
                        "code=${resp.code} len=${body.length} clash=${usable(body)} " +
                        "rejected=${isRemnawaveClientRejectedBody(body)}"
                )
                return DirectSubscriptionDownload(
                    content = body,
                    profileTitle = resp.profileTitle(),
                    trafficUsed = resp.subscriptionTraffic()?.first,
                    trafficAvailable = resp.subscriptionTraffic()?.second,
                    expireLabel = resp.subscriptionExpireLabel(),
                    updateIntervalMs = resp.profileUpdateIntervalMs(),
                    announce = resp.subscriptionAnnounce(),
                    supportUrl = resp.subscriptionSupportUrl(),
                    webPageUrl = resp.subscriptionWebPageUrl(),
                )
            }
        }

        suspend fun firstYaml(): Pair<DirectSubscriptionDownload, String>? {
            for (candidate in urls) {
                for (agent in yamlAgents) {
                    if (attempts >= maxAttempts) return null
                    attempts++
                    val withHwid = !hwid.isNullOrBlank()
                    val downloaded = runCatching {
                        fetch(candidate, agent, includeHwid = withHwid)
                    }.getOrNull()
                    if (downloaded != null && usable(downloaded.content)) {
                        return downloaded to agent
                    }
                }
            }
            return null
        }

        val resolved = firstYaml()
            ?: error("Subscription server returned an empty response")

        val (yaml, usedAgent) = resolved

        // Panel HWID row — only when offline (via VPN every extra CONNECT hurts).
        if (!viaVpn && !hwid.isNullOrBlank() && usedAgent != appAgent) {
            runCatching {
                fetch(urls.first(), appAgent, includeHwid = true)
            }
        }

        yaml
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/** Cancel OkHttp when the coroutine times out — blocking execute() ignored cancellation. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

private fun Response.profileTitle(): String? {
    val raw = header("profile-title")?.trim()?.ifBlank { null } ?: return null
    if (raw.startsWith("base64:", ignoreCase = true)) {
        val encoded = raw.substringAfter(':').filterNot { it.isWhitespace() }
        return runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                .toString(Charsets.UTF_8)
                .trim()
                .ifBlank { null }
        }.getOrNull() ?: raw.removePrefix("base64:").trim()
    }
    return raw
}

private fun Response.subscriptionTraffic(): Pair<String, String>? {
    val raw = header("subscription-userinfo")?.trim()?.ifBlank { null } ?: return null
    val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    fun part(name: String): Long? =
        parts.firstOrNull { it.startsWith("$name=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.toLongOrNull()
    val upload = part("upload") ?: 0L
    val download = part("download") ?: 0L
    val total = part("total") ?: return null
    val used = (upload + download).coerceAtLeast(0L)
    val availableLabel = if (total <= 0L) "∞" else formatSubscriptionBytes(total)
    return formatSubscriptionBytes(used) to availableLabel
}

private fun Response.subscriptionExpireLabel(): String? {
    val raw = header("subscription-userinfo")?.trim()?.ifBlank { null } ?: return null
    val expireRaw = raw.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("expire=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.toLongOrNull()
        ?: return null
    if (expireRaw < 0L) return null
    // Remnawave sends expire=0 for non-expiring plans — show that explicitly.
    if (expireRaw == 0L) return "\u221e"
    // Clash panels send seconds; some send milliseconds.
    val millis = if (expireRaw > 10_000_000_000L) expireRaw else expireRaw * 1000L
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val dd = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    val mm = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
    val yyyy = cal.get(java.util.Calendar.YEAR)
    return "$dd.$mm.$yyyy"
}

private fun Response.profileUpdateIntervalMs(): Long? {
    val hours = header("profile-update-interval")?.trim()?.toIntOrNull() ?: return null
    return hours.coerceIn(1, 168).toLong() * 60L * 60L * 1000L
}

private fun Response.subscriptionAnnounce(): String? {
    val raw = header("announce")?.trim()?.ifBlank { null }
        ?: header("profile-announce")?.trim()?.ifBlank { null }
        ?: return null
    if (raw.startsWith("base64:", ignoreCase = true)) {
        val encoded = raw.substringAfter(':').filterNot { it.isWhitespace() }
        return runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                .toString(Charsets.UTF_8)
                .trim()
                .ifBlank { null }
        }.getOrNull() ?: raw.removePrefix("base64:").trim()
    }
    return raw
}

private fun Response.subscriptionSupportUrl(): String? {
    val raw = header("support-url")?.trim()?.ifBlank { null }
        ?: header("profile-support-url")?.trim()?.ifBlank { null }
        ?: return null
    return decodePossiblyBase64Header(raw)?.takeIf { isOpenableUrl(it) }
}

private fun Response.subscriptionWebPageUrl(): String? {
    val raw = header("profile-web-page-url")?.trim()?.ifBlank { null }
        ?: header("website")?.trim()?.ifBlank { null }
        ?: header("web-page-url")?.trim()?.ifBlank { null }
        ?: return null
    return decodePossiblyBase64Header(raw)?.takeIf { isOpenableUrl(it) }
}

private fun decodePossiblyBase64Header(raw: String): String? {
    if (raw.startsWith("base64:", ignoreCase = true)) {
        val encoded = raw.substringAfter(':').filterNot { it.isWhitespace() }
        return runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                .toString(Charsets.UTF_8)
                .trim()
                .ifBlank { null }
        }.getOrNull() ?: raw.removePrefix("base64:").trim()
    }
    return raw
}

private fun isOpenableUrl(value: String): Boolean {
    val v = value.trim()
    return v.startsWith("http://", ignoreCase = true) ||
        v.startsWith("https://", ignoreCase = true) ||
        v.startsWith("tg://", ignoreCase = true) ||
        v.startsWith("ton://", ignoreCase = true)
}

private fun formatSubscriptionBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toInt() / 10.0}KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).toInt() / 10.0}MB"
    val gb = mb / 1024.0
    return "${(gb * 100).toInt() / 100.0}GB"
}

private fun buildSubscriptionOkHttpClient(
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
    insecureTrustManager: X509TrustManager?,
    pinSubscriptionHeaders: Boolean,
    subscriptionProxy: SubscriptionFetchProxy? = null,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)

    if (subscriptionProxy != null) {
        val proxyType = if (subscriptionProxy.useHttpProxy) Proxy.Type.HTTP else Proxy.Type.SOCKS
        builder.proxy(
            Proxy(
                proxyType,
                InetSocketAddress(subscriptionProxy.host, subscriptionProxy.port),
            )
        )
        android.util.Log.i(
            "SubDownload",
            "OkHttp via $proxyType ${subscriptionProxy.host}:${subscriptionProxy.port}",
        )
    }

    if (pinSubscriptionHeaders) {
        builder.addNetworkInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host.lowercase()
            val next = original.newBuilder()
            val existingUa = original.header("User-Agent")
            if (existingUa.isNullOrBlank()) {
                next.header("User-Agent", RemnawaveDeviceIdentity.userAgent())
            }
            if ("api.github.com" !in host && "github.com" !in host) {
                next.header("Accept", "text/yaml, text/plain, */*")
                if (original.header("x-device-os").isNullOrBlank()) {
                    next.header("x-device-os", "Android")
                }
                if (original.header("x-ver-os").isNullOrBlank()) {
                    next.header("x-ver-os", RemnawaveDeviceIdentity.osVersion())
                }
                if (original.header("x-device-model").isNullOrBlank()) {
                    next.header("x-device-model", RemnawaveDeviceIdentity.deviceModel())
                }
            }
            chain.proceed(next.build())
        }
    }

    if (insecureTrustManager != null) {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(insecureTrustManager), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, insecureTrustManager)
        builder.hostnameVerifier { _, _ -> true }
    }

    return builder.build()
}

internal actual suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T {
    if (subscriptionProxy == null || subscriptionProxy.username.isBlank()) {
        return block()
    }

    return proxyAuthenticatorMutex.withLock {
        Authenticator.setDefault(subscriptionProxy.authenticator())
        try {
            block()
        } finally {
            Authenticator.setDefault(null)
        }
    }
}

private val proxyAuthenticatorMutex = Mutex()

private fun trustAllCertificatesManager(): X509TrustManager {
    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

private fun SubscriptionFetchProxy.authenticator(): Authenticator {
    val proxy = this
    return object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val matchesProxyHost = requestingHost == null ||
                requestingHost == proxy.host ||
                requestingSite?.hostAddress == proxy.host
            if (!matchesProxyHost || requestingPort != proxy.port) {
                return null
            }
            return PasswordAuthentication(proxy.username, proxy.password.toCharArray())
        }
    }
}
