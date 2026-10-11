package com.app.quickpear.relay

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lightweight, zero-knowledge blind relay server.
 * Connects paired sender and receiver clients by matching their 16-byte session token,
 * then blindly pipes raw binary data between them at line speed with 0% Base64 overhead.
 */
class StandaloneRelayServer(
    private val port: Int = RelayProtocol.DEFAULT_RELAY_PORT,
    private val host: String = "0.0.0.0"
) {
    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    private data class PeerSlot(
        val socket: Socket,
        val readChannel: ByteReadChannel,
        val writeChannel: ByteWriteChannel,
        val role: Byte
    )

    private data class RelaySession(
        val tokenHex: String,
        var peer1: PeerSlot? = null,
        var peer2: PeerSlot? = null,
        val pairedDeferred: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private val sessions = mutableMapOf<String, RelaySession>()
    private val sessionsMutex = Mutex()

    suspend fun start() = withContext(Dispatchers.IO) {
        val selector = SelectorManager(Dispatchers.IO)
        selectorManager = selector
        val server = aSocket(selector).tcp().bind(host, port)
        serverSocket = server
        val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = serverScope

        serverScope.launch {
            while (isActive && !server.isClosed) {
                try {
                    val clientSocket = server.accept()
                    launch { handleClient(clientSocket) }
                } catch (e: Exception) {
                    if (!isActive || server.isClosed) break
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val readChannel = socket.openReadChannel()
        val writeChannel = socket.openWriteChannel(autoFlush = true)

        try {
            // Read registration header:
            // [MAGIC: 4B][MSG_REGISTER: 1B][ROLE: 1B][TOKEN: 16B]
            val magic = readChannel.readInt()
            if (magic != RelayProtocol.MAGIC) {
                socket.close()
                return@withContext
            }
            val headerBytes = ByteArray(2)
            readChannel.readFully(headerBytes)
            val msgType = headerBytes[0]
            if (msgType != RelayProtocol.MSG_REGISTER) {
                socket.close()
                return@withContext
            }
            val role = headerBytes[1]
            val tokenBytes = ByteArray(RelayProtocol.TOKEN_LENGTH)
            readChannel.readFully(tokenBytes)

            val tokenHex = tokenBytes.joinToString("") { "%02x".format(it) }
            val currentSlot = PeerSlot(socket, readChannel, writeChannel, role)

            val session: RelaySession
            val isSecondPeer: Boolean
            sessionsMutex.withLock {
                val existing = sessions[tokenHex]
                if (existing == null) {
                    session = RelaySession(tokenHex, peer1 = currentSlot)
                    sessions[tokenHex] = session
                    isSecondPeer = false
                } else {
                    existing.peer2 = currentSlot
                    session = existing
                    isSecondPeer = true
                }
            }

            if (isSecondPeer) {
                // Both peers are now present! Notify peer1 and peer2
                val peer1 = session.peer1 ?: return@withContext
                val peer2 = session.peer2 ?: return@withContext

                // Send ACK to peer1
                peer1.writeChannel.writeInt(RelayProtocol.MAGIC)
                peer1.writeChannel.writeFully(byteArrayOf(RelayProtocol.MSG_REGISTER_ACK, RelayProtocol.STATUS_PEER_CONNECTED))
                peer1.writeChannel.flush()

                // Send ACK to peer2
                peer2.writeChannel.writeInt(RelayProtocol.MAGIC)
                peer2.writeChannel.writeFully(byteArrayOf(RelayProtocol.MSG_REGISTER_ACK, RelayProtocol.STATUS_PEER_CONNECTED))
                peer2.writeChannel.flush()

                session.pairedDeferred.complete(Unit)

                // Start bidirectional streaming pipe
                val jobA = launch {
                    try {
                        peer1.readChannel.copyTo(peer2.writeChannel)
                    } catch (_: Exception) {}
                }
                val jobB = launch {
                    try {
                        peer2.readChannel.copyTo(peer1.writeChannel)
                    } catch (_: Exception) {}
                }

                // Wait for either stream to finish/terminate
                jobA.join()
                jobB.join()

                // Cleanup session
                sessionsMutex.withLock {
                    sessions.remove(tokenHex)
                }
                try { peer1.socket.close() } catch (_: Exception) {}
                try { peer2.socket.close() } catch (_: Exception) {}
            } else {
                // Wait until second peer connects or session is cancelled
                session.pairedDeferred.await()
            }
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        scope?.cancel()
        scope = null
        try { serverSocket?.close() } catch (_: Exception) {}
        try { selectorManager?.close() } catch (_: Exception) {}
        serverSocket = null
        selectorManager = null
        sessionsMutex.withLock {
            sessions.clear()
        }
    }
}
