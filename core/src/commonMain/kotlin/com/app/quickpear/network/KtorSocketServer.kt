package com.app.quickpear.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class KtorSocketServer(
    private val port: Int = 8888,
    private val host: String = "0.0.0.0"
) {
    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null

    /**
     * Binds the TCP server socket. Re-binds cleanly if previously closed.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val currentServer = serverSocket
        if (currentServer == null || currentServer.isClosed) {
            selectorManager?.close()
            val selector = SelectorManager(Dispatchers.IO)
            selectorManager = selector
            serverSocket = aSocket(selector).tcp().bind(host, port)
        }
    }

    /**
     * Emits accepted SocketConnections as they arrive.
     * Resilient against individual socket errors so the server listener never dies unexpectedly.
     */
    fun acceptConnections(): Flow<SocketConnection> = flow {
        val server = serverSocket ?: throw IllegalStateException("Server not started")
        while (currentCoroutineContext().isActive && !server.isClosed) {
            try {
                val clientSocket = server.accept()
                emit(SocketConnection(clientSocket))
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive || server.isClosed) break
                // Delay briefly to prevent tight spinning if the interface changes, then continue accepting
                delay(50)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Closes server socket and selector manager.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        try {
            selectorManager?.close()
        } catch (_: Exception) {}
        selectorManager = null
    }
}
