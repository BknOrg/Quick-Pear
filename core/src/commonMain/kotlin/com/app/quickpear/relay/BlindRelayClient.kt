package com.app.quickpear.relay

import com.app.quickpear.network.SocketConnection
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class BlindRelayClient {

    companion object {
        const val ROLE_SENDER: Byte = 0x01
        const val ROLE_RECEIVER: Byte = 0x02
    }

    /**
     * Connects to the blind relay, registers the [channelToken] with [role],
     * and waits until the remote peer has also connected.
     * Returns an active [SocketConnection] that pipes raw binary data to/from the peer.
     */
    suspend fun connectAndPair(
        relayHost: String,
        relayPort: Int = RelayProtocol.DEFAULT_RELAY_PORT,
        channelToken: ByteArray,
        role: Byte,
        timeoutMillis: Long = 15_000L
    ): SocketConnection = withContext(Dispatchers.IO) {
        require(channelToken.size == RelayProtocol.TOKEN_LENGTH) {
            "Channel token must be exactly ${RelayProtocol.TOKEN_LENGTH} bytes"
        }

        val selector = SelectorManager(Dispatchers.IO)
        val socket: Socket
        try {
            socket = withTimeout(timeoutMillis) {
                aSocket(selector).tcp().connect(relayHost, relayPort)
            }
        } catch (e: Exception) {
            try { selector.close() } catch (_: Exception) {}
            throw e
        }

        val connection = SocketConnection(socket)
        val readChannel = connection.readChannel
        val writeChannel = connection.writeChannel

        try {
            withTimeout(timeoutMillis) {
                // 1. Send registration packet:
                // [MAGIC: 4B][MSG_REGISTER: 1B][ROLE: 1B][TOKEN: 16B]
                writeChannel.writeInt(RelayProtocol.MAGIC)
                writeChannel.writeFully(byteArrayOf(RelayProtocol.MSG_REGISTER, role))
                writeChannel.writeFully(channelToken)
                writeChannel.flush()

                // 2. Wait for registration ACK:
                // [MAGIC: 4B][MSG_REGISTER_ACK: 1B][STATUS: 1B]
                val magic = readChannel.readInt()
                if (magic != RelayProtocol.MAGIC) {
                    throw IllegalStateException("Invalid magic from relay: $magic")
                }
                val ackBytes = ByteArray(2)
                readChannel.readFully(ackBytes)
                val msgType = ackBytes[0]
                if (msgType != RelayProtocol.MSG_REGISTER_ACK) {
                    throw IllegalStateException("Expected MSG_REGISTER_ACK but got $msgType")
                }
                val status = ackBytes[1]
                if (status != RelayProtocol.STATUS_PEER_CONNECTED) {
                    throw IllegalStateException("Relay pairing rejected with status: $status")
                }
            }
            connection
        } catch (e: Exception) {
            try { connection.close() } catch (_: Exception) {}
            try { selector.close() } catch (_: Exception) {}
            throw e
        }
    }
}
