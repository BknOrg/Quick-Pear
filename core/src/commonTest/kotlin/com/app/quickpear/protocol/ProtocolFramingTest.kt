package com.app.quickpear.protocol

import com.app.quickpear.domain.ChunkAck
import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.ChunkHeader
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.MetadataRequest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ProtocolFramingTest {

    private val encoder = BinaryFrameEncoder()
    private val decoder = BinaryFrameDecoder()

    @Test
    fun testMetadataRequestEncodingAndDecoding() {
        val buffer = Buffer()
        val fileMetadata = FileMetadata.create(
            fileId = 1,
            fileName = "test_video.mp4",
            fileSizeBytes = 10_485_760, // 10 MB
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val request = MetadataRequest(
            sessionId = "test-session-uuid",
            files = listOf(fileMetadata)
        )

        encoder.writeMetadataRequest(buffer, request)

        val header = decoder.readHeader(buffer)
        assertEquals(ProtocolConstants.MSG_METADATA_REQ, header.messageType)

        val decodedRequest = decoder.readMetadataRequest(buffer, header.payloadLength)
        assertEquals(request.sessionId, decodedRequest.sessionId)
        assertEquals(1, decodedRequest.files.size)
        assertEquals("test_video.mp4", decodedRequest.files[0].fileName)
        assertEquals(10_485_760L, decodedRequest.files[0].fileSizeBytes)
    }

    @Test
    fun testMetadataResponseEncodingAndDecoding() {
        val buffer = Buffer()
        val responseCode = ProtocolConstants.METADATA_RESP_ACCEPTED
        val startChunkOffset = 42L

        encoder.writeMetadataResponse(buffer, responseCode, startChunkOffset)

        val header = decoder.readHeader(buffer)
        assertEquals(ProtocolConstants.MSG_METADATA_RESP, header.messageType)
        assertEquals(9L, header.payloadLength)

        val (decodedCode, decodedOffset) = decoder.readMetadataResponse(buffer)
        assertEquals(responseCode, decodedCode)
        assertEquals(startChunkOffset, decodedOffset)
    }

    @Test
    fun testChunkDataEncodingAndDecoding() {
        val buffer = Buffer()
        val payload = "Hello QuickPear Chunk Payload Data!".encodeToByteArray()
        val chunkHeader = ChunkHeader(
            chunkMarker = ProtocolConstants.CHUNK_MARKER_PAYLOAD,
            chunkId = 100L,
            chunkLength = payload.size,
            checksum = 123456789L
        )
        val chunkData = ChunkData(chunkHeader, payload)

        encoder.writeChunkData(buffer, chunkData)

        val header = decoder.readHeader(buffer)
        assertEquals(ProtocolConstants.MSG_CHUNK_DATA, header.messageType)

        val decodedChunkData = decoder.readChunkData(buffer, header.payloadLength)
        assertEquals(chunkData.header.chunkId, decodedChunkData.header.chunkId)
        assertEquals(chunkData.header.checksum, decodedChunkData.header.checksum)
        assertContentEquals(chunkData.payloadBytes, decodedChunkData.payloadBytes)
    }

    @Test
    fun testChunkAckEncodingAndDecoding() {
        val buffer = Buffer()
        val ack = ChunkAck(chunkId = 55L, status = ChunkAck.STATUS_SUCCESS)

        encoder.writeChunkAck(buffer, ack)

        val header = decoder.readHeader(buffer)
        assertEquals(ProtocolConstants.MSG_CHUNK_ACK, header.messageType)

        val decodedAck = decoder.readChunkAck(buffer)
        assertEquals(ack.chunkId, decodedAck.chunkId)
        assertEquals(ack.status, decodedAck.status)
    }

    @Test
    fun testTransferCompleteEncodingAndDecoding() {
        val buffer = Buffer()
        val fileId = 7
        val dummySha256 = ByteArray(32) { it.toByte() }

        encoder.writeTransferComplete(buffer, fileId, dummySha256)

        val header = decoder.readHeader(buffer)
        assertEquals(ProtocolConstants.MSG_TRANSFER_COMPLETE, header.messageType)

        val (decodedFileId, decodedSha) = decoder.readTransferComplete(buffer)
        assertEquals(fileId, decodedFileId)
        assertContentEquals(dummySha256, decodedSha)
    }

    @Test
    fun testTransferCompleteAckEncodingAndDecoding() {
        val buffer = Buffer()
        val fileId = 7
        val status: Byte = 0x00

        encoder.writeTransferCompleteAck(buffer, fileId, status)

        val header = decoder.readHeader(buffer)
        assertEquals(ProtocolConstants.MSG_TRANSFER_COMPLETE_ACK, header.messageType)

        val (decodedFileId, decodedStatus) = decoder.readTransferCompleteAck(buffer)
        assertEquals(fileId, decodedFileId)
        assertEquals(status, decodedStatus)
    }
}
