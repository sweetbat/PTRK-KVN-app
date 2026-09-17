package org.olcbox.app.vpn

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Local HTTP CONNECT front (no auth) → olcRTC Mobile SOCKS5 (user/pass).
 *
 * Why HTTP not SOCKS for OkHttp: Java [Proxy.Type.SOCKS] resolves DNS locally first.
 * The app is excluded from the VPN TUN, so local DNS on MTS whitelist cannot resolve
 * GitHub / subscription hosts. HTTP CONNECT sends the hostname to us unresolved;
 * Mobile resolves it inside the tunnel.
 *
 * Port 7890 matches Mihomo mixed-port so UI always uses the same fetch proxy.
 */
class OlcRtcFetchSocksBridge(
    private val listenPort: Int,
    private val backendHost: String,
    private val backendPort: Int,
    private val username: String,
    private val password: String,
    private val log: (String) -> Unit,
) {
    @Volatile
    private var stopped = false
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sockets = mutableSetOf<Socket>()
    private val sessionPermit = java.util.concurrent.Semaphore(1)

    val isRunning: Boolean
        get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

    fun start() {
        stopped = false
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", listenPort))
        }
        serverSocket = server
        acceptThread = thread(name = "OlcRtcFetchHttp", isDaemon = true) {
            acceptLoop(server)
        }
        log("olcRTC fetch HTTP CONNECT on 127.0.0.1:$listenPort → SOCKS $backendHost:$backendPort")
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        acceptThread?.interrupt()
        acceptThread = null
        serverSocket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val client = runCatching { server.accept() }
                .onFailure { if (!stopped) log("fetch accept failed: ${it.message}") }
                .getOrNull() ?: continue
            synchronized(sockets) { sockets.add(client) }
            thread(name = "OlcRtcFetchHttpClient", isDaemon = true) {
                try {
                    handleClient(client)
                } catch (_: EOFException) {
                } catch (_: IOException) {
                } catch (t: Throwable) {
                    log("fetch client error: ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    synchronized(sockets) { sockets.remove(client) }
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val rawIn = BufferedInputStream(client.getInputStream())
        val rawOut = BufferedOutputStream(client.getOutputStream())
        // Peek first byte: SOCKS5 (0x05) vs HTTP ('C' for CONNECT / 'G' GET…)
        rawIn.mark(1)
        val first = rawIn.read()
        if (first < 0) return
        rawIn.reset()
        if (first == SOCKS_VERSION.toInt()) {
            handleSocksClient(client, rawIn, rawOut)
        } else {
            handleHttpConnect(client, rawIn, rawOut)
        }
    }

    private fun handleHttpConnect(client: Socket, clientIn: InputStream, clientOut: OutputStream) {
        // Handshake only — clear idle timeout before long TLS body relay (APK / YAML).
        client.soTimeout = CONNECT_TIMEOUT_MS
        val header = readHttpHeaderBlock(clientIn) ?: return
        val requestLine = header.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
            clientOut.write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }
        val target = parts[1]
        val host = target.substringBeforeLast(':').trim().trim('[', ']')
        val port = target.substringAfterLast(':', "443").toIntOrNull() ?: 443
        if (host.isBlank()) {
            clientOut.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }

        // Serialize fetches so one hung CONNECT cannot pile onto Mobile SOCKS.
        if (!sessionPermit.tryAcquire()) {
            clientOut.write("HTTP/1.1 429 Too Many Requests\r\nConnection: close\r\n\r\n".toByteArray())
            clientOut.flush()
            return
        }
        try {
            Socket().use { backend ->
                backend.soTimeout = CONNECT_TIMEOUT_MS
                backend.connect(InetSocketAddress(backendHost, backendPort), CONNECT_TIMEOUT_MS)
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())
                if (!handshakeBackendAuth(backendIn, backendOut)) {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                    clientOut.flush()
                    return
                }
                if (!socksConnectDomain(backendIn, backendOut, host, port)) {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                    clientOut.flush()
                    return
                }
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()

                // Unlimited idle during body transfer — WebRTC is slow; a 14s soTimeout
                // or join+close after c2b EOF truncates TLS → BAD_RECORD_MAC.
                client.soTimeout = 0
                backend.soTimeout = 0
                pipeBidirectional(client, backend)
            }
        } finally {
            sessionPermit.release()
        }
    }

    private fun handleSocksClient(client: Socket, rawIn: InputStream, rawOut: OutputStream) {
        val clientIn = DataInputStream(rawIn)
        val clientOut = DataOutputStream(rawOut)
        if (!handshakeClientNoAuth(clientIn, clientOut)) return

        val connectHdr = ByteArray(4)
        clientIn.readFully(connectHdr)
        if (connectHdr[0] != SOCKS_VERSION || connectHdr[1] != CMD_CONNECT) {
            replySocksFailure(clientOut)
            return
        }
        val atyp = connectHdr[3]
        val addrBytes = when (atyp) {
            ATYP_IPV4 -> ByteArray(4).also { clientIn.readFully(it) }
            ATYP_DOMAIN -> {
                val len = clientIn.readUnsignedByte()
                ByteArray(1 + len).also {
                    it[0] = len.toByte()
                    clientIn.readFully(it, 1, len)
                }
            }
            ATYP_IPV6 -> ByteArray(16).also { clientIn.readFully(it) }
            else -> {
                replySocksFailure(clientOut)
                return
            }
        }
        val portBytes = ByteArray(2).also { clientIn.readFully(it) }

        if (!sessionPermit.tryAcquire()) {
            replySocksFailure(clientOut)
            return
        }
        try {
            Socket().use { backend ->
                backend.connect(InetSocketAddress(backendHost, backendPort), CONNECT_TIMEOUT_MS)
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())
                if (!handshakeBackendAuth(backendIn, backendOut)) {
                    replySocksFailure(clientOut)
                    return
                }
                backendOut.write(connectHdr)
                backendOut.write(addrBytes)
                backendOut.write(portBytes)
                backendOut.flush()

                val repHdr = ByteArray(4)
                backendIn.readFully(repHdr)
                clientOut.write(repHdr)
                val repAtyp = repHdr[3]
                val repAddr = when (repAtyp) {
                    ATYP_IPV4 -> ByteArray(4).also { backendIn.readFully(it) }
                    ATYP_DOMAIN -> {
                        val len = backendIn.readUnsignedByte()
                        ByteArray(1 + len).also {
                            it[0] = len.toByte()
                            backendIn.readFully(it, 1, len)
                        }
                    }
                    ATYP_IPV6 -> ByteArray(16).also { backendIn.readFully(it) }
                    else -> {
                        replySocksFailure(clientOut)
                        return
                    }
                }
                val repPort = ByteArray(2).also { backendIn.readFully(it) }
                clientOut.write(repAddr)
                clientOut.write(repPort)
                clientOut.flush()
                if (repHdr[1] != REP_SUCCEEDED) return

                client.soTimeout = 0
                backend.soTimeout = 0
                pipeBidirectional(client, backend)
            }
        } finally {
            sessionPermit.release()
        }
    }

    /**
     * Full-duplex pipe. Half-close on EOF only — never close the peer socket when the
     * client→server direction finishes (HTTPS clients go idle after the request while
     * the response body is still flowing). Premature close caused BAD_RECORD_MAC.
     */
    private fun pipeBidirectional(client: Socket, backend: Socket) {
        val c2b = relay(client, backend, "c2b")
        val b2c = relay(backend, client, "b2c")
        c2b.join()
        b2c.join()
    }

    private fun socksConnectDomain(
        input: DataInputStream,
        output: DataOutputStream,
        host: String,
        port: Int,
    ): Boolean {
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) return false
        output.write(byteArrayOf(SOCKS_VERSION, CMD_CONNECT, 0x00, ATYP_DOMAIN))
        output.write(hostBytes.size)
        output.write(hostBytes)
        output.write((port ushr 8) and 0xff)
        output.write(port and 0xff)
        output.flush()

        val repHdr = ByteArray(4)
        input.readFully(repHdr)
        val repAtyp = repHdr[3]
        when (repAtyp) {
            ATYP_IPV4 -> ByteArray(4).also { input.readFully(it) }
            ATYP_DOMAIN -> {
                val len = input.readUnsignedByte()
                ByteArray(len).also { input.readFully(it) }
            }
            ATYP_IPV6 -> ByteArray(16).also { input.readFully(it) }
            else -> return false
        }
        ByteArray(2).also { input.readFully(it) }
        return repHdr[0] == SOCKS_VERSION && repHdr[1] == REP_SUCCEEDED
    }

    private fun readHttpHeaderBlock(input: InputStream): String? {
        val buf = ByteArray(8192)
        var n = 0
        while (n < buf.size) {
            val b = input.read()
            if (b < 0) break
            buf[n++] = b.toByte()
            if (n >= 4 &&
                buf[n - 4] == '\r'.code.toByte() &&
                buf[n - 3] == '\n'.code.toByte() &&
                buf[n - 2] == '\r'.code.toByte() &&
                buf[n - 1] == '\n'.code.toByte()
            ) {
                return buf.decodeToString(0, n)
            }
        }
        return if (n > 0) buf.decodeToString(0, n) else null
    }

    private fun handshakeClientNoAuth(input: DataInputStream, output: DataOutputStream): Boolean {
        if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
        val nMethods = input.readUnsignedByte()
        var noAuth = false
        repeat(nMethods) {
            if (input.readUnsignedByte() == SOCKS_METHOD_NO_AUTH.toInt()) noAuth = true
        }
        if (!noAuth) {
            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
            output.flush()
            return false
        }
        output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_AUTH))
        output.flush()
        return true
    }

    private fun handshakeBackendAuth(input: DataInputStream, output: DataOutputStream): Boolean {
        output.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
        output.flush()
        if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
        if (input.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return false

        val user = username.toByteArray()
        val pass = password.toByteArray()
        output.write(SOCKS_AUTH_VERSION.toInt())
        output.write(user.size)
        output.write(user)
        output.write(pass.size)
        output.write(pass)
        output.flush()

        if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
        return input.readUnsignedByte() == 0x00
    }

    private fun replySocksFailure(output: DataOutputStream) {
        runCatching {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION, REP_GENERAL_FAILURE, 0x00, ATYP_IPV4,
                    0, 0, 0, 0, 0, 0,
                ),
            )
            output.flush()
        }
    }

    private fun relay(from: Socket, to: Socket, name: String): Thread {
        return thread(name = "OlcRtcFetchRelay-$name", isDaemon = true) {
            runCatching {
                from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUF)
                to.getOutputStream().flush()
            }
            // Half-close only — peer may still be sending the HTTP/TLS response body.
            runCatching { to.shutdownOutput() }
        }
    }

    private companion object {
        const val SOCKS_VERSION: Byte = 0x05
        const val SOCKS_AUTH_VERSION: Byte = 0x01
        const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
        const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
        const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
        const val CMD_CONNECT: Byte = 0x01
        const val REP_SUCCEEDED: Byte = 0x00
        const val REP_GENERAL_FAILURE: Byte = 0x01
        const val ATYP_IPV4: Byte = 0x01
        const val ATYP_DOMAIN: Byte = 0x03
        const val ATYP_IPV6: Byte = 0x04
        const val CONNECT_TIMEOUT_MS = 8_000
        const val RELAY_BUF = 32 * 1024
    }
}
