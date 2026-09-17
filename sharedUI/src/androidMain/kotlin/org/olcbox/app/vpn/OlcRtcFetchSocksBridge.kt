package org.olcbox.app.vpn

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Local SOCKS5 front for UI OkHttp (no auth) → olcRTC Mobile SOCKS (user/pass).
 *
 * OkHttp cannot authenticate SOCKS5 reliably; Clash `:route` on 7890 was flaky
 * (ECONNREFUSED). This bridge runs in `:vpn` next to Mobile.
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

    val isRunning: Boolean
        get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

    fun start() {
        stopped = false
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", listenPort))
        }
        serverSocket = server
        acceptThread = thread(name = "OlcRtcFetchSocks", isDaemon = true) {
            acceptLoop(server)
        }
        log("olcRTC fetch SOCKS bridge on 127.0.0.1:$listenPort → $backendHost:$backendPort")
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
                .onFailure { if (!stopped) log("fetch SOCKS accept failed: ${it.message}") }
                .getOrNull() ?: continue
            synchronized(sockets) { sockets.add(client) }
            thread(name = "OlcRtcFetchSocksClient", isDaemon = true) {
                try {
                    handleClient(client)
                } finally {
                    synchronized(sockets) { sockets.remove(client) }
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        val clientIn = DataInputStream(client.getInputStream())
        val clientOut = DataOutputStream(client.getOutputStream())
        if (!handshakeClientNoAuth(clientIn, clientOut)) return

        // Peek CONNECT request from client — forward after backend auth.
        val connectHdr = ByteArray(4)
        clientIn.readFully(connectHdr)
        if (connectHdr[0] != SOCKS_VERSION || connectHdr[1] != CMD_CONNECT) {
            replyClientFailure(clientOut)
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
                replyClientFailure(clientOut)
                return
            }
        }
        val portBytes = ByteArray(2).also { clientIn.readFully(it) }

        Socket().use { backend ->
            backend.connect(InetSocketAddress(backendHost, backendPort), CONNECT_TIMEOUT_MS)
            val backendIn = DataInputStream(backend.getInputStream())
            val backendOut = DataOutputStream(backend.getOutputStream())
            if (!handshakeBackendAuth(backendIn, backendOut)) {
                replyClientFailure(clientOut)
                return
            }

            backendOut.write(connectHdr)
            backendOut.write(addrBytes)
            backendOut.write(portBytes)
            backendOut.flush()

            // Forward CONNECT reply (variable length) then pipe.
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
                    replyClientFailure(clientOut)
                    return
                }
            }
            val repPort = ByteArray(2).also { backendIn.readFully(it) }
            clientOut.write(repAddr)
            clientOut.write(repPort)
            clientOut.flush()

            if (repHdr[1] != REP_SUCCEEDED) return

            val c2b = relay(client, backend, "c2b")
            val b2c = relay(backend, client, "b2c")
            c2b.join()
            runCatching { backend.close() }
            runCatching { client.close() }
            b2c.join(RELAY_JOIN_MS)
        }
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

    private fun replyClientFailure(output: DataOutputStream) {
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
            }
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
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
        const val CONNECT_TIMEOUT_MS = 3_000
        const val RELAY_BUF = 16 * 1024
        const val RELAY_JOIN_MS = 500L
    }
}
