package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/** Only complete downloads are made available to the package installer. */
internal class UpdateDownloadCache(private val directory: File) {
    private val downloadMutex = Mutex()

    fun downloadedFile(asset: AppUpdateAsset): File? = targetFile(asset).takeIf { file ->
        file.isFile && file.length() > 0L &&
            (asset.sizeBytes == null || file.length() == asset.sizeBytes)
    }

    suspend fun download(
        asset: AppUpdateAsset,
        proxy: Proxy = Proxy.NO_PROXY,
        onRetry: suspend () -> Unit = {},
        onProgress: suspend (Float) -> Unit = {}
    ): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            downloadedFile(asset)?.let { return@withContext it }
            check(directory.isDirectory || directory.mkdirs()) { "Could not create update directory" }
            val target = targetFile(asset)
            val partial = File(directory, "${target.name}.part")

            var expectedTotal = asset.sizeBytes
            var copied = partial.takeIf { it.isFile }?.length() ?: 0L
            if (copied > 0L && expectedTotal != null && copied >= expectedTotal) {
                if (!partial.renameTo(target)) throw IOException("Could not save downloaded update")
                onProgress(1f)
                return@withContext target
            }
            if (copied > 0L) {
                onProgress(
                    if (expectedTotal != null && expectedTotal > 0L) {
                        (copied.toDouble() / expectedTotal).toFloat().coerceIn(0f, 0.99f)
                    } else {
                        0f
                    }
                )
            }

            var attempt = 0
            while (true) {
                coroutineContext.ensureActive()
                attempt++
                try {
                    val rangeStart = partial.takeIf { it.isFile }?.length() ?: 0L
                    val result = downloadAttempt(
                        asset = asset,
                        proxy = proxy,
                        partial = partial,
                        rangeStart = rangeStart,
                        expectedTotal = expectedTotal,
                        onProgress = onProgress,
                    )
                    expectedTotal = result.total ?: expectedTotal
                    copied = result.copied
                    break
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val retryable = error is SSLException ||
                        error is IOException ||
                        error.message?.contains("SSL", ignoreCase = true) == true ||
                        error.message?.contains("BAD_DECRYPT", ignoreCase = true) == true ||
                        error.message?.contains("BAD_RECORD_MAC", ignoreCase = true) == true
                    if (!retryable || attempt >= MAX_ATTEMPTS) throw error
                    runCatching { onRetry() }
                    delay(800L * attempt)
                }
            }

            if (copied == 0L ||
                (expectedTotal != null && copied != expectedTotal) ||
                (asset.sizeBytes != null && copied != asset.sizeBytes)
            ) {
                throw IOException("Update download is incomplete ($copied / ${expectedTotal ?: asset.sizeBytes})")
            }
            coroutineContext.ensureActive()
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not save downloaded update")
            onProgress(1f)
            target
        }
    }

    private data class AttemptResult(val copied: Long, val total: Long?)

    private suspend fun downloadAttempt(
        asset: AppUpdateAsset,
        proxy: Proxy,
        partial: File,
        rangeStart: Long,
        expectedTotal: Long?,
        onProgress: suspend (Float) -> Unit,
    ): AttemptResult {
        val connection = URL(asset.downloadUrl).openConnection(proxy) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "PTRK-KVN-app")
            connection.setRequestProperty("Accept", "*/*")
            if (rangeStart > 0L) {
                connection.setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = connection.responseCode
            val acceptingResume = rangeStart > 0L && code == HttpURLConnection.HTTP_PARTIAL
            val freshOk = code == HttpURLConnection.HTTP_OK
            if (!acceptingResume && !freshOk) {
                throw IOException("Update download returned HTTP $code")
            }
            if (rangeStart > 0L && freshOk) {
                // Server ignored Range — restart from scratch.
                partial.delete()
            }
            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            val total = when {
                acceptingResume && contentLength != null -> rangeStart + contentLength
                asset.sizeBytes != null -> asset.sizeBytes
                contentLength != null && freshOk -> contentLength
                expectedTotal != null -> expectedTotal
                else -> null
            }
            val append = acceptingResume && partial.exists()
            var copied = if (append) rangeStart else 0L
            if (total != null && total > 0L) {
                onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 0.99f))
            } else {
                onProgress(0f)
            }
            connection.inputStream.use { input ->
                RandomAccessFile(partial, "rw").use { raf ->
                    if (append) raf.seek(rangeStart) else raf.setLength(0)
                    // Small buffer + pacing so olcRTC WebRTC is not starved by APK bulk.
                    val buffer = ByteArray(8 * 1024)
                    var sincePace = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        copied += read
                        sincePace += read
                        if (sincePace >= PACE_EVERY_BYTES) {
                            sincePace = 0L
                            delay(PACE_DELAY_MS)
                        }
                        if (total != null && total > 0L) {
                            onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 0.99f))
                        }
                    }
                }
            }
            return AttemptResult(copied = copied, total = total)
        } finally {
            connection.disconnect()
        }
    }

    private fun targetFile(asset: AppUpdateAsset): File {
        val identity = listOf(asset.downloadUrl, asset.updatedAt, asset.sizeBytes).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val name = asset.name.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "olcbox-update.apk" }
        return File(directory, "$hash-$name")
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
        const val PACE_EVERY_BYTES = 256L * 1024L
        const val PACE_DELAY_MS = 40L
    }
}
