package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.SecureRandom
import java.security.cert.X509Certificate
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

    return HttpClient(OkHttp) {
        expectSuccess = false

        engine {
            if (subscriptionProxy != null) {
                proxy = ProxyBuilder.socks(subscriptionProxy.host, subscriptionProxy.port)
            }
            if (insecureTrustManager != null) {
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(insecureTrustManager), SecureRandom())
                }
                config {
                    sslSocketFactory(sslContext.socketFactory, insecureTrustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }

        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

internal actual suspend fun downloadSubscriptionBodyDirect(
    url: String,
    hwid: String?,
    allowInsecureRequests: Boolean,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
): DirectSubscriptionDownload {
    val client = createProxyHttpClient(
        connectTimeoutMs = connectTimeoutMs,
        requestTimeoutMs = requestTimeoutMs,
        socketTimeoutMs = socketTimeoutMs,
        allowInsecureRequests = allowInsecureRequests,
    )
    return try {
        val agents = listOf("ClashMeta/1.19.0", "clash.meta/v1.19.0", "mihomo/1.19.0", "Clash")
        val joiner = if ('?' in url) "&" else "?"
        val urls = listOf(url, "$url${joiner}flag=clash", "$url${joiner}flag=meta").distinct()
        fun usable(text: String): Boolean {
            val lower = text.lowercase()
            return lower.contains("proxies:") ||
                lower.contains("proxy-groups:") ||
                lower.contains("mixed-port:") ||
                text.contains("olcrtc://", ignoreCase = true)
        }
        for (candidate in urls) {
            for (agent in agents) {
                val body = runCatching {
                    client.get(candidate) {
                        headers {
                            append(io.ktor.http.HttpHeaders.Accept, "text/yaml, */*")
                            remove(io.ktor.http.HttpHeaders.UserAgent)
                            append(io.ktor.http.HttpHeaders.UserAgent, agent)
                            if (!hwid.isNullOrBlank()) append("x-hwid", hwid)
                        }
                    }.bodyAsText()
                }.getOrNull()
                if (body != null && usable(body)) return DirectSubscriptionDownload(content = body)
            }
        }
        DirectSubscriptionDownload(
            content = client.get(url) {
                headers {
                    append(io.ktor.http.HttpHeaders.Accept, "text/yaml, */*")
                    remove(io.ktor.http.HttpHeaders.UserAgent)
                    append(io.ktor.http.HttpHeaders.UserAgent, "ClashMeta/1.19.0")
                }
            }.bodyAsText()
        )
    } finally {
        client.close()
    }
}

internal actual suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T {
    if (subscriptionProxy == null || subscriptionProxy.username.isBlank()) {
        return block()
    }

    return proxyAuthenticatorMutex.withLock {
        val previous = Authenticator.getDefault()
        Authenticator.setDefault(subscriptionProxy.authenticator())
        try {
            block()
        } finally {
            Authenticator.setDefault(previous)
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
