package com.app.quickpear.protocol

import com.app.quickpear.domain.ChunkAck
import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.MetadataRequest
import kotlinx.serialization.json.Json
import okio.BufferedSink

class BinaryFrameEncoder(private val json: Json = Json { ignoreUnknownKeys = true }) {

    /**
     * Writes 13-byte header: Magic Bytes (4B) + Message Type (1B) + Payload Length (8B).
     */
    fun writeHeader(sink: BufferedSink, messageType: Byte, payloadLength: Long) {
        sink.write(ProtocolConstants.MAGIC_BYTES)
        sink.writeByte(messageType.toInt())
        sink.writeLong(payloadLength) // Big-Endian by default in Okio
        sink.flush()
    }

    /**
     * Encodes 0x03 METADATA_REQ.
     */
    fun writeMetadataRequest(sink: BufferedSink, request: MetadataRequest) {
        val jsonString = json.encodeToString(MetadataRequest.serializer(), request)
        val jsonBytes = jsonString.encodeToByteArray()
        writeHeader(sink, ProtocolConstants.MSG_METADATA_REQ, jsonBytes.size.toLong())
        sink.write(jsonBytes)
        sink.flush()
    }

    /**
     * Encodes 0x04 METADATA_RESP (9 Bytes payload: 1 Byte response code + 8 Bytes start chunk offset).
     */
    fun writeMetadataResponse(sink: BufferedSink, responseCode: Byte, startChunkOffset: Long) {
        val payloadLength = 9L
        writeHeader(sink, ProtocolConstants.MSG_METADATA_RESP, payloadLength)
        sink.writeByte(responseCode.toInt())
        sink.writeLong(startChunkOffset)
        sink.flush()
    }

    /**
     * Encodes 0x05 CHUNK_DATA.
     * Body layout: Marker (1B) + Chunk ID (8B) + Chunk Length (4B) + Checksum (8B) + Raw Bytes (N B).
     */
    fun writeChunkData(sink: BufferedSink, chunkData: ChunkData) {
        val headerLength = 1 + 8 + 4 + 8 // 21 bytes
        val totalPayloadLength = headerLength + chunkData.payloadBytes.size
        writeHeader(sink, ProtocolConstants.MSG_CHUNK_DATA, totalPayloadLength.toLong())

        sink.writeByte(chunkData.header.chunkMarker.toInt())
        sink.writeLong(chunkData.header.chunkId)
        sink.writeInt(chunkData.header.chunkLength)
        sink.writeLong(chunkData.header.checksum)
        sink.write(chunkData.payloadBytes)
        sink.flush()
    }

    /**
     * Encodes 0x06 CHUNK_ACK (9 Bytes payload: 8 Bytes Chunk ID + 1 Byte Status).
     */
    fun writeChunkAck(sink: BufferedSink, ack: ChunkAck) {
        writeHeader(sink, ProtocolConstants.MSG_CHUNK_ACK, 9L)
        sink.writeLong(ack.chunkId)
        sink.writeByte(ack.status.toInt())
        sink.flush()
    }

    /**
     * Encodes 0x07 TRANSFER_COMPLETE (36 Bytes payload: 4 Bytes File ID + 32 Bytes SHA-256).
     */
    fun writeTransferComplete(sink: BufferedSink, fileId: Int, sha256Bytes: ByteArray) {
        require(sha256Bytes.size == 32) { "SHA-256 bytes must be exactly 32 bytes" }
        writeHeader(sink, ProtocolConstants.MSG_TRANSFER_COMPLETE, 36L)
        sink.writeInt(fileId)
        sink.write(sha256Bytes)
        sink.flush()
    }

    /**
     * Encodes 0x08 TRANSFER_COMPLETE_ACK (5 Bytes payload: 4 Bytes File ID + 1 Byte Final Status).
     */
    fun writeTransferCompleteAck(sink: BufferedSink, fileId: Int, finalStatus: Byte) {
        writeHeader(sink, ProtocolConstants.MSG_TRANSFER_COMPLETE_ACK, 5L)
        sink.writeInt(fileId)
        sink.writeByte(finalStatus.toInt())
        sink.flush()
    }
}
