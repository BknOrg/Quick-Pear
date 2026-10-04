package com.app.quickpear.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class KtorSocketClient {

    /**
     * Connects to target host and port via Ktor TCP socket with a bounded timeout.
     */
    suspend fun connect(host: String, port: Int = 8888, timeoutMillis: Long = 5000L): SocketConnection = withContext(Dispatchers.IO) {
        val selector = SelectorManager(Dispatchers.IO)
        try {
            val socket = withTimeout(timeoutMillis) {
                aSocket(selector).tcp().connect(host, port)
            }
            SocketConnection(socket)
        } catch (e: Exception) {
            try { selector.close() } catch (_: Exception) {}
            throw e
        }
    }
}
