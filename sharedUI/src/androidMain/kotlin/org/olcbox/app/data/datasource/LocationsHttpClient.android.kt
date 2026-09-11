package org.olcbox.app.data.datasource

import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.olcbox.app.data.identity.RemnawaveDeviceIdentity
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

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
    )

    return HttpClient(OkHttp) {
        expectSuccess = false

        engine {
            // Ktor's engine.config { addNetworkInterceptor } is unreliable — pin via preconfigured.
            preconfigured = okHttp
            if (subscriptionProxy != null) {
                proxy = ProxyBuilder.socks(subscriptionProxy.host, subscriptionProxy.port)
            }
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
): DirectSubscriptionDownload = withContext(Dispatchers.IO) {
    val client = buildSubscriptionOkHttpClient(
        connectTimeoutMs = connectTimeoutMs,
        requestTimeoutMs = requestTimeoutMs,
        socketTimeoutMs = socketTimeoutMs,
        insecureTrustManager = if (allowInsecureRequests) trustAllCertificatesManager() else null,
        pinSubscriptionHeaders = true,
    )
    try {
        fun usable(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("proxies:") ||
                lower.contains("proxy-groups:") ||
                lower.contains("mixed-port:") ||
                text.contains("olcrtc://", ignoreCase = true)
        }

        val appAgent = RemnawaveDeviceIdentity.userAgent()

        fun fetch(target: String, agent: String, includeHwid: Boolean): DirectSubscriptionDownload? {
            val builder = Request.Builder()
                .url(target)
                .header("User-Agent", agent)
                .header("Accept", "text/yaml, text/plain, application/octet-stream, */*")
                .header("x-device-os", "Android")
                .header("x-ver-os", Build.VERSION.RELEASE ?: "unknown")
                .header("x-device-model", RemnawaveDeviceIdentity.MODEL)
            if (includeHwid && !hwid.isNullOrBlank()) {
                builder.header("x-hwid", hwid)
            }
            val response = client.newCall(builder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                android.util.Log.i(
                    "SubDownload",
                    "ua=$agent code=${resp.code} len=${body.length} " +
                        "ctype=${resp.header("Content-Type")} " +
                        "title=${resp.header("profile-title")} " +
                        "clash=${usable(body)}"
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

        // Remnawave: PTRK-KVN-app UA → base64 URI dump; ClashMeta/mihomo → Clash YAML.
        // Keep x-device-model=PTRK-KVN-app for HWID labeling; fall back UA for YAML body.
        val yamlAgents = listOf(
            appAgent,
            "ClashMeta/1.19.0",
            "clash.meta/v1.19.0",
            "mihomo/1.19.0",
            "clash-verge",
            "Clash",
        )
        val joiner = if ('?' in url) "&" else "?"
        val urls = listOf(
            "$url${joiner}flag=meta",
            "$url${joiner}flag=clash",
            url,
        ).distinct()

        fun firstUsable(includeHwid: Boolean): DirectSubscriptionDownload? {
            for (candidate in urls) {
                for (agent in yamlAgents) {
                    val downloaded = runCatching {
                        fetch(candidate, agent, includeHwid = includeHwid)
                    }.getOrNull()
                    if (downloaded != null && usable(downloaded.content)) return downloaded
                }
            }
            return null
        }

        firstUsable(includeHwid = true)?.let { return@withContext it }
        if (!hwid.isNullOrBlank()) {
            firstUsable(includeHwid = false)?.let { return@withContext it }
        }

        // Last resort: return whatever we got (caller will show a clear error).
        fetch(urls.first(), "ClashMeta/1.19.0", includeHwid = !hwid.isNullOrBlank())
            ?: fetch(urls.first(), appAgent, includeHwid = !hwid.isNullOrBlank())
            ?: error("Subscription server returned an empty response")
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
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
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)

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
                    next.header("x-ver-os", Build.VERSION.RELEASE ?: "unknown")
                }
                if (original.header("x-device-model").isNullOrBlank()) {
                    next.header("x-device-model", RemnawaveDeviceIdentity.MODEL)
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
