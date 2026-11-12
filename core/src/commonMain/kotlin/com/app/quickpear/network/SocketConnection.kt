package com.app.quickpear.network

import com.app.quickpear.protocol.*
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import okio.Buffer

class SocketConnection(private val socket: Socket, private val onClose: () -> Unit = {}) : FrameConnection() {
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel(autoFlush = true)
    override val remoteAddress get() = socket.remoteAddress.toString()
    override val remoteHost get() = (socket.remoteAddress as? InetSocketAddress)?.hostname ?: ""
    override suspend fun receivePacket(): ByteArray {
        val headerBytes = ByteArray(ProtocolConstants.HEADER_SIZE)
        readChannel.readFully(headerBytes)
        val header = BinaryFrameDecoder().readHeader(Buffer().write(headerBytes))
        FrameLimits.validate(header, authenticatedPeerId != null)
        val payload = ByteArray(header.payloadLength.toInt())
        readChannel.readFully(payload)
        return headerBytes + payload
    }
    override suspend fun sendPacket(packet: ByteArray) { writeChannel.writeFully(packet) }
    suspend fun readBufferExactly(buffer: Buffer, byteCount: Long) {
        require(byteCount in 0..ProtocolConstants.MAX_FRAME_PAYLOAD)
        val bytes = ByteArray(byteCount.toInt())
        readChannel.readFully(bytes)
        buffer.write(bytes)
    }
    override fun close() { try { socket.close() } finally { onClose() } }
}
