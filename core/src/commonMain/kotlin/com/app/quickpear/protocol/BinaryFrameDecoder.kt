package com.app.quickpear.protocol

import com.app.quickpear.domain.ChunkAck
import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.ChunkHeader
import com.app.quickpear.domain.MetadataRequest
import kotlinx.serialization.json.Json
import okio.BufferedSource

class BinaryFrameDecoder(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /**
     * Reads 13-byte standard message header. Validates magic bytes.
     */
    fun readHeader(source: BufferedSource): FrameHeader {
        val magic = source.readByteArray(4)
        if (!magic.contentEquals(ProtocolConstants.MAGIC_BYTES)) {
            throw IllegalStateException("Invalid magic bytes in message header: ${magic.joinToString { it.toString(16) }}")
        }
        val messageType = source.readByte()
        val payloadLength = source.readLong()
        return FrameHeader(messageType, payloadLength)
    }

    /**
     * Decodes 0x03 METADATA_REQ payload.
     */
    fun readMetadataRequest(source: BufferedSource, payloadLength: Long): MetadataRequest {
        val jsonBytes = source.readByteArray(payloadLength)
        val jsonString = jsonBytes.decodeToString()
        return json.decodeFromString(MetadataRequest.serializer(), jsonString)
    }

    /**
     * Decodes 0x04 METADATA_RESP payload. Returns Pair(responseCode, startChunkOffset).
     */
    fun readMetadataResponse(source: BufferedSource): Pair<Byte, Long> {
        val responseCode = source.readByte()
        val startChunkOffset = source.readLong()
        return Pair(responseCode, startChunkOffset)
    }

    /**
     * Decodes 0x05 CHUNK_DATA payload.
     */
    fun readChunkData(source: BufferedSource, payloadLength: Long): ChunkData {
        val marker = source.readByte()
        require(marker == ProtocolConstants.CHUNK_MARKER_PAYLOAD) { "Invalid chunk marker: $marker" }
        val chunkId = source.readLong()
        val chunkLength = source.readInt()
        val checksum = source.readLong()
        val rawBytes = source.readByteArray(chunkLength.toLong())

        val chunkHeader = ChunkHeader(
            chunkMarker = marker,
            chunkId = chunkId,
            chunkLength = chunkLength,
            checksum = checksum
        )
        return ChunkData(header = chunkHeader, payloadBytes = rawBytes)
    }

    /**
     * Decodes 0x06 CHUNK_ACK payload.
     */
    fun readChunkAck(source: BufferedSource): ChunkAck {
        val chunkId = source.readLong()
        val status = source.readByte()
        return ChunkAck(chunkId = chunkId, status = status)
    }

    /**
     * Decodes 0x07 TRANSFER_COMPLETE payload. Returns Pair(fileId, sha256Bytes).
     */
    fun readTransferComplete(source: BufferedSource): Pair<Int, ByteArray> {
        val fileId = source.readInt()
        val sha256Bytes = source.readByteArray(32)
        return Pair(fileId, sha256Bytes)
    }

    /**
     * Decodes 0x08 TRANSFER_COMPLETE_ACK payload. Returns Pair(fileId, finalStatus).
     */
    fun readTransferCompleteAck(source: BufferedSource): Pair<Int, Byte> {
        val fileId = source.readInt()
        val status = source.readByte()
        return Pair(fileId, status)
    }
}
