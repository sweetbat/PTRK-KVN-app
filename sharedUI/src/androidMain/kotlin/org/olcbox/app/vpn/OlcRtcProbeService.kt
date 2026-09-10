package org.olcbox.app.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mobile.Mobile
import org.olcbox.app.data.model.LocationConfig
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs olcRTC ping/check in a dedicated process so libgojni never shares
 * an address space with libclash (dual Go runtimes crash the app).
 */
class OlcRtcProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val receiver = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RECEIVER)
        }
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_PING
        val provider = intent.getStringExtra(EXTRA_PROVIDER).orEmpty()
        val transport = intent.getStringExtra(EXTRA_TRANSPORT).orEmpty()
        val roomId = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE).orEmpty()
        val vp8Fps = intent.getIntExtra(EXTRA_VP8_FPS, LocationConfig.DEFAULT_VP8_FPS)
        val vp8Batch = intent.getIntExtra(EXTRA_VP8_BATCH, LocationConfig.DEFAULT_VP8_BATCH)

        scope.launch {
            val delayMs = runCatching {
                val port = ServerSocket(0).use { it.localPort }
                val mobile = Mobile.new_()
                when (action) {
                    ACTION_CHECK -> mobile.check(
                        provider, transport, roomId, deviceId, key,
                        port.toLong(), 8_000L, vp8Fps.toLong(), vp8Batch.toLong()
                    )
                    else -> mobile.ping(
                        provider, transport, roomId, deviceId, key,
                        port.toLong(), 8_000L, "https://www.gstatic.com/generate_204",
                        vp8Fps.toLong(), vp8Batch.toLong()
                    )
                }
            }.onFailure {
                Log.e(TAG, "probe failed", it)
            }.getOrNull()

            val bundle = Bundle().apply {
                putLong(EXTRA_RESULT_MS, delayMs ?: -1L)
            }
            receiver?.send(if (delayMs != null && delayMs >= 0) RESULT_OK else RESULT_ERROR, bundle)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OlcRtcProbe"
        const val ACTION_PING = "ping"
        const val ACTION_CHECK = "check"
        const val EXTRA_ACTION = "action"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_ROOM = "room"
        const val EXTRA_KEY = "key"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_VP8_FPS = "vp8_fps"
        const val EXTRA_VP8_BATCH = "vp8_batch"
        const val EXTRA_RESULT_MS = "result_ms"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1

        fun probe(
            context: Context,
            locationConfig: LocationConfig,
            deviceId: String,
            action: String = ACTION_PING,
            timeoutMs: Long = 12_000L,
        ): Long? {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return null

            val latch = CountDownLatch(1)
            val resultMs = AtomicLong(-1L)
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    resultMs.set(resultData?.getLong(EXTRA_RESULT_MS, -1L) ?: -1L)
                    latch.countDown()
                }
            }

            val intent = Intent(context, OlcRtcProbeService::class.java).apply {
                putExtra(EXTRA_RECEIVER, receiver)
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_PROVIDER, config.bypassProvider)
                putExtra(EXTRA_TRANSPORT, config.transport)
                putExtra(EXTRA_ROOM, config.id)
                putExtra(EXTRA_KEY, config.key)
                putExtra(EXTRA_DEVICE, deviceId)
                putExtra(EXTRA_VP8_FPS, config.vp8Fps)
                putExtra(EXTRA_VP8_BATCH, config.vp8Batch)
            }
            runCatching { context.startService(intent) }.onFailure {
                Log.e(TAG, "failed to start probe service", it)
                return null
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "probe timed out")
                return null
            }
            return resultMs.get().takeIf { it >= 0L }
        }
    }
}
