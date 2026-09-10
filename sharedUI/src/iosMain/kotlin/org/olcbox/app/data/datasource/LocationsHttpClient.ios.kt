package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.create
import platform.Foundation.serverTrust

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal actual fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy?,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long,
    allowInsecureRequests: Boolean
): HttpClient {
    return HttpClient(Darwin) {
        expectSuccess = false

        engine {
            if (allowInsecureRequests) {
                handleChallenge { _, _, challenge, completionHandler ->
                    val serverTrust = challenge.protectionSpace.serverTrust
                    if (serverTrust != null) {
                        completionHandler(
                            NSURLSessionAuthChallengeUseCredential,
                            NSURLCredential.create(serverTrust)
                        )
                    } else {
                        completionHandler(
                            NSURLSessionAuthChallengePerformDefaultHandling,
                            null
                        )
                    }
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

internal actual suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T = block()

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
        val response = client.get(url) {
            headers {
                append(io.ktor.http.HttpHeaders.Accept, "text/yaml, */*")
                remove(io.ktor.http.HttpHeaders.UserAgent)
                append(io.ktor.http.HttpHeaders.UserAgent, "ClashMeta/1.19.0")
                if (!hwid.isNullOrBlank()) append("x-hwid", hwid)
            }
        }
        DirectSubscriptionDownload(content = response.bodyAsText())
    } finally {
        client.close()
    }
}
