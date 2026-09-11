package org.olcbox.app.data.olcsub

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.PtrkSubscriptionCompanion
import org.olcbox.app.data.repository.SubscriptionFetchProxy

data class OlcSubExitResult(
    val exit: String,
    val display: String? = null,
)

/**
 * Switches SOCKS exit on a shared olcsub container (`POST /{uuid}/exit`).
 * Room/key stay the same; the app shows DE/PL/FI as separate servers.
 */
class OlcSubExitClient(
    private val httpClientFactory: (SubscriptionFetchProxy?) -> HttpClient =
        { proxy -> createProxyHttpClient(subscriptionProxy = proxy) },
    private val settleDelayMs: Long = 1_500L,
) {
    /**
     * @return true if exit was applied (or no exit needed); false if request failed
     *         and the caller should retry after the tunnel is up (whitelist).
     */
    suspend fun prepareExitBeforeConnect(
        entry: LocationEntry,
        proxy: SubscriptionFetchProxy? = null,
        settle: Boolean = true,
    ): Boolean {
        val exit = PtrkSubscriptionCompanion.normalizeExitCountry(entry.metadata?.exit)
            ?: return true
        val url = PtrkSubscriptionCompanion.exitRequestUrl(entry.subscriptionUrl)
            ?: return true

        val applied = setExitCountry(url, exit, proxy).isSuccess
        if (applied && settle) {
            delay(settleDelayMs)
        }
        return applied
    }

    suspend fun setExitCountry(
        exitRequestUrl: String,
        exitCountry: String,
        proxy: SubscriptionFetchProxy? = null,
    ): Result<OlcSubExitResult> {
        val exit = PtrkSubscriptionCompanion.normalizeExitCountry(exitCountry)
            ?: return Result.failure(IllegalArgumentException("Unsupported exit: $exitCountry"))
        val client = httpClientFactory(proxy)
        return try {
            val response = client.post(exitRequestUrl) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Accept, "application/json, */*")
                }
                setBody("""{"exit_country":"$exit"}""")
            }
            if (response.status.value !in 200..299) {
                return Result.failure(
                    IllegalStateException("olcsub exit HTTP ${response.status.value}")
                )
            }
            val body = response.bodyAsText()
            val resolvedExit = Regex(""""exit"\s*:\s*"([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { PtrkSubscriptionCompanion.normalizeExitCountry(it) }
                ?: exit
            val display = Regex(""""display"\s*:\s*"([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
            Result.success(OlcSubExitResult(exit = resolvedExit, display = display))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(error)
        } finally {
            client.close()
        }
    }
}
