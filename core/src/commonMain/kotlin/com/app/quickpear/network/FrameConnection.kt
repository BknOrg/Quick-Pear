package com.app.quickpear.network

import com.app.quickpear.domain.ChunkAck
import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.protocol.BinaryFrameDecoder
import com.app.quickpear.protocol.BinaryFrameEncoder
import com.app.quickpear.protocol.FrameHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlinx.coroutines.sync.withLock

/** Frame protocol shared by socket and MQTT transports. */
abstract class FrameConnection {
    private val encoder = BinaryFrameEncoder()
    private val decoder = BinaryFrameDecoder()
    private val sendMutex = kotlinx.coroutines.sync.Mutex()
    private var incoming = Buffer()
    private var secure: com.app.quickpear.security.SecureSession? = null
    var authenticatedPeerId: String? = null
        private set
    open val binding: String = ""
    abstract val remoteAddress: String
    open val remoteHost: String get() = remoteAddress
    protected abstract suspend fun receivePacket(): ByteArray
    protected abstract suspend fun sendPacket(packet: ByteArray)
    abstract fun close()

    internal fun activate(session: com.app.quickpear.security.SecureSession, peerId: String) {
        check(secure == null)
        secure = session
        authenticatedPeerId = peerId
    }

    suspend fun readHeader(timeoutMillis: Long = com.app.quickpear.protocol.ProtocolConstants.IO_TIMEOUT_MS): FrameHeader = withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeout(timeoutMillis) {
        check(incoming.size == 0L) { "Previous frame not consumed" }
        val wire = Buffer().write(receivePacket())
        val outer = decoder.readHeader(wire)
        com.app.quickpear.protocol.FrameLimits.validate(outer, secure != null)
        require(wire.size == outer.payloadLength) { "Frame length mismatch" }
        val session = secure
        if (session != null) {
            val plain = session.decrypt(wire.readByteArray())
            incoming = Buffer().write(plain)
            val header = decoder.readHeader(incoming)
            com.app.quickpear.protocol.FrameLimits.validatePlain(header)
            require(incoming.size == header.payloadLength)
            header
        } else {
            incoming = wire
            outer
        }
    }

    }

    private fun readBufferExactly(buffer: Buffer, byteCount: Long) {
        require(byteCount >= 0 && byteCount == incoming.size) { "Invalid payload length" }
        buffer.write(incoming, byteCount)
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
    private suspend fun sendBuffer(buffer: Buffer) = withContext(Dispatchers.IO) { sendMutex.withLock {
        val bytes = buffer.readByteArray()
        com.app.quickpear.protocol.FrameLimits.validatePlain(decoder.readHeader(Buffer().write(bytes)))
        val session = secure
        val packet = if (session == null) {
            com.app.quickpear.protocol.FrameLimits.validate(decoder.readHeader(Buffer().write(bytes)), false)
            bytes
        } else {
            val record = session.encrypt(bytes)
            val outer = Buffer()
            encoder.writeHeader(outer, com.app.quickpear.protocol.ProtocolConstants.MSG_SECURE, record.size.toLong())
            outer.write(record).readByteArray()
        }
        kotlinx.coroutines.withTimeout(com.app.quickpear.protocol.ProtocolConstants.IO_TIMEOUT_MS) { sendPacket(packet) }
    } }

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

}
