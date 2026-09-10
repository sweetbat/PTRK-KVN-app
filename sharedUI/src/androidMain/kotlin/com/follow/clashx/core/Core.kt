package com.follow.clashx.core

import java.net.InetSocketAddress

data object Core {

    private external fun nativeStartTun(fd: Int, cb: TunInterface): Boolean

    fun startTun(
        fd: Int,
        protect: (Int) -> Boolean,
        resolverProcess: (protocol: Int, source: InetSocketAddress, target: InetSocketAddress, uid: Int) -> String,
    ): Boolean {
        val cb = object : TunInterface {
            override fun protect(fd: Int) {
                protect(fd)
            }

            override fun resolverProcess(protocol: Int, source: String, target: String, uid: Int): String {
                return resolverProcess(
                    protocol,
                    parseInetSocketAddress(source),
                    parseInetSocketAddress(target),
                    uid,
                )
            }
        }
        return nativeStartTun(fd, cb)
    }

    external fun stopTun()

    external fun invokeAction(data: String, cb: InvokeInterface)

    external fun quickStart(
        initParams: String,
        params: String,
        stateParams: String,
        cb: InvokeInterface,
    )

    external fun setEventListener(cb: InvokeInterface?)

    external fun setState(state: String)
    external fun updateDns(dns: String)
    external fun resetConnections()

    external fun getTraffic(): String
    external fun getTotalTraffic(): String
    external fun getRunTime(): String
    external fun getCurrentProfileName(): String
    external fun getAndroidVpnOptions(): String

    external fun startListener()
    external fun stopListener()

    private fun parseInetSocketAddress(address: String): InetSocketAddress {
        val lastColon = address.lastIndexOf(':')
        if (lastColon < 0) return InetSocketAddress.createUnresolved(address, 0)

        val host: String
        val port: Int
        if (address.startsWith("[")) {
            val closeBracket = address.indexOf(']')
            if (closeBracket < 0) return InetSocketAddress.createUnresolved(address, 0)
            host = address.substring(1, closeBracket)
            port = address.drop(closeBracket + 2).toIntOrNull() ?: 0
        } else {
            host = address.substring(0, lastColon)
            port = address.substring(lastColon + 1).toIntOrNull() ?: 0
        }
        return try {
            InetSocketAddress(java.net.InetAddress.getByName(host), port)
        } catch (_: Exception) {
            InetSocketAddress.createUnresolved(host, port)
        }
    }

    init {
        try {
            System.loadLibrary("clash")
            System.loadLibrary("core")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("Core", "Failed to load native library: ${e.message}")
        }
    }
}
