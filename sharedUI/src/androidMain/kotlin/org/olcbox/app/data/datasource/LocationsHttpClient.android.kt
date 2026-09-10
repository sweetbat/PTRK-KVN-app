package org.olcbox.app.data.datasource

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
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val CLASH_USER_AGENT = "ClashMeta/1.19.0"

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
        forceClashUserAgent = true,
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
 * Direct OkHttp download that always sends ClashMeta UA (Remnawave returns YAML only then).
 * Bypasses Ktor header quirks that were causing JSON / base64 dumps.
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
        forceClashUserAgent = true,
    )
    try {
        fun usable(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("proxies:") ||
                lower.contains("proxy-groups:") ||
                lower.contains("mixed-port:") ||
                text.contains("olcrtc://", ignoreCase = true)
        }

        fun fetch(target: String, agent: String, includeHwid: Boolean): DirectSubscriptionDownload? {
            val builder = Request.Builder()
                .url(target)
                .header("User-Agent", agent)
                .header("Accept", "text/yaml, text/plain, application/octet-stream, */*")
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
                )
            }
        }

        val agents = listOf(
            CLASH_USER_AGENT,
            "clash.meta/v1.19.0",
            "mihomo/1.19.0",
            "Clash",
        )
        val joiner = if ('?' in url) "&" else "?"
        val urls = listOf(url, "$url${joiner}flag=clash", "$url${joiner}flag=meta").distinct()

        for (candidate in urls) {
            for (agent in agents) {
                val downloaded = runCatching { fetch(candidate, agent, includeHwid = true) }.getOrNull()
                if (downloaded != null && usable(downloaded.content)) return@withContext downloaded
            }
        }
        if (!hwid.isNullOrBlank()) {
            for (candidate in urls) {
                val downloaded = runCatching {
                    fetch(candidate, CLASH_USER_AGENT, includeHwid = false)
                }.getOrNull()
                if (downloaded != null && usable(downloaded.content)) return@withContext downloaded
            }
        }

        // Last resort: return whatever ClashMeta gives so the caller can show a precise error.
        fetch(url, CLASH_USER_AGENT, includeHwid = !hwid.isNullOrBlank())
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
    forceClashUserAgent: Boolean,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)

    if (forceClashUserAgent) {
        builder.addNetworkInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host.lowercase()
            val next = original.newBuilder()
                .header("User-Agent", CLASH_USER_AGENT)
            if ("api.github.com" !in host && "github.com" !in host) {
                next.header("Accept", "text/yaml, text/plain, */*")
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
