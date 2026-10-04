package com.app.quickpear.network

import com.app.quickpear.domain.ChunkAck
import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.protocol.BinaryFrameDecoder
import com.app.quickpear.protocol.BinaryFrameEncoder
import com.app.quickpear.protocol.FrameHeader
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer

class SocketConnection(
    private val socket: Socket,
    private val encoder: BinaryFrameEncoder = BinaryFrameEncoder(),
    private val decoder: BinaryFrameDecoder = BinaryFrameDecoder()
) {
    val readChannel: ByteReadChannel = socket.openReadChannel()
    val writeChannel: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)

    val remoteAddress: String
        get() = socket.remoteAddress.toString()

    /**
     * Reads exactly N bytes into an Okio Buffer from ByteReadChannel.
     */
    suspend fun readBufferExactly(buffer: Buffer, byteCount: Long) = withContext(Dispatchers.IO) {
        var bytesLeft = byteCount
        val tempArray = ByteArray(8192)
        while (bytesLeft > 0) {
            val toRead = bytesLeft.coerceAtMost(tempArray.size.toLong()).toInt()
            val read = readChannel.readAvailable(tempArray, 0, toRead)
            if (read == -1) throw IllegalStateException("Socket closed unexpectedly before reading $byteCount bytes")
            buffer.write(tempArray, 0, read)
            bytesLeft -= read
        }
    }

    /**
     * Reads 13-byte standard frame header.
     */
    suspend fun readHeader(): FrameHeader = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, 13L)
        decoder.readHeader(buffer)
    }

    /**
     * Reads 0x03 METADATA_REQ payload.
     */
    suspend fun readMetadataRequest(payloadLength: Long): MetadataRequest = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, payloadLength)
        decoder.readMetadataRequest(buffer, payloadLength)
    }

    /**
     * Reads 0x04 METADATA_RESP payload.
     */
    suspend fun readMetadataResponse(): Pair<Byte, Long> = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, 9L)
        decoder.readMetadataResponse(buffer)
    }

    /**
     * Reads 0x05 CHUNK_DATA payload.
     */
    suspend fun readChunkData(payloadLength: Long): ChunkData = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, payloadLength)
        decoder.readChunkData(buffer, payloadLength)
    }

    /**
     * Reads 0x06 CHUNK_ACK payload.
     */
    suspend fun readChunkAck(): ChunkAck = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, 9L)
        decoder.readChunkAck(buffer)
    }

    /**
     * Reads 0x07 TRANSFER_COMPLETE payload.
     */
    suspend fun readTransferComplete(): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, 36L)
        decoder.readTransferComplete(buffer)
    }

    /**
     * Reads 0x08 TRANSFER_COMPLETE_ACK payload.
     */
    suspend fun readTransferCompleteAck(): Pair<Int, Byte> = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        readBufferExactly(buffer, 5L)
        decoder.readTransferCompleteAck(buffer)
    }

    /**
     * Flushes Okio Buffer bytes to ByteWriteChannel.
     */
    private suspend fun sendBuffer(buffer: Buffer) = withContext(Dispatchers.IO) {
        val bytes = buffer.readByteArray()
        writeChannel.writeFully(bytes)
    }

    suspend fun sendMetadataRequest(request: MetadataRequest) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeMetadataRequest(buffer, request)
        sendBuffer(buffer)
    }

    suspend fun sendMetadataResponse(responseCode: Byte, startChunkOffset: Long) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeMetadataResponse(buffer, responseCode, startChunkOffset)
        sendBuffer(buffer)
    }

    suspend fun sendChunkData(chunkData: ChunkData) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeChunkData(buffer, chunkData)
        sendBuffer(buffer)
    }

    suspend fun sendChunkAck(ack: ChunkAck) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeChunkAck(buffer, ack)
        sendBuffer(buffer)
    }

    suspend fun sendTransferComplete(fileId: Int, sha256Bytes: ByteArray) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeTransferComplete(buffer, fileId, sha256Bytes)
        sendBuffer(buffer)
    }

    suspend fun sendTransferCompleteAck(fileId: Int, finalStatus: Byte) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeTransferCompleteAck(buffer, fileId, finalStatus)
        sendBuffer(buffer)
    }

    /**
     * Sends a frame with an arbitrary payload (used for handshake / pairing control messages).
     */
    suspend fun sendFrame(messageType: Byte, payload: ByteArray) = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        encoder.writeHeader(buffer, messageType, payload.size.toLong())
        buffer.write(payload)
        sendBuffer(buffer)
    }

    /**
     * Reads a raw payload of [payloadLength] bytes, rejecting anything larger than [maxBytes]
     * so an unauthenticated peer cannot make us allocate arbitrary memory.
     */
    suspend fun readFramePayload(payloadLength: Long, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(payloadLength in 0..maxBytes) { "Payload length $payloadLength exceeds limit $maxBytes" }
        val buffer = Buffer()
        readBufferExactly(buffer, payloadLength)
        buffer.readByteArray()
    }

    fun close() {
        socket.close()
    }
}
