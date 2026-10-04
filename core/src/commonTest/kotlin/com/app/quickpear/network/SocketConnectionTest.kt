package com.app.quickpear.network

import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.ChunkHeader
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.protocol.ProtocolConstants
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SocketConnectionTest {

    @Test
    fun testSocketServerClientCommunication() = runTest {
        val testPort = 9876
        val server = KtorSocketServer(port = testPort, host = "127.0.0.1")
        val client = KtorSocketClient()

        server.start()

        val serverJob = async {
            server.acceptConnections().first()
        }

        // Connect client
        val clientConnection = client.connect("127.0.0.1", testPort)
        val serverConnection = serverJob.await()

        try {
            // Client sends MetadataRequest
            val request = MetadataRequest(
                sessionId = "loopback-session",
                files = listOf(
                    FileMetadata.create(
                        fileId = 1,
                        fileName = "socket_test.dat",
                        fileSizeBytes = 1000,
                        sha256 = "abc123sha256"
                    )
                )
            )

            val clientSendJob = async {
                clientConnection.sendMetadataRequest(request)
            }

            val header = serverConnection.readHeader()
            assertEquals(ProtocolConstants.MSG_METADATA_REQ, header.messageType)

            val receivedRequest = serverConnection.readMetadataRequest(header.payloadLength)
            clientSendJob.await()

            assertEquals("loopback-session", receivedRequest.sessionId)
            assertEquals("socket_test.dat", receivedRequest.files[0].fileName)

            // Server responds with MetadataResponse ACCEPTED
            val serverRespJob = async {
                serverConnection.sendMetadataResponse(ProtocolConstants.METADATA_RESP_ACCEPTED, startChunkOffset = 0L)
            }

            val clientHeader = clientConnection.readHeader()
            assertEquals(ProtocolConstants.MSG_METADATA_RESP, clientHeader.messageType)

            val (code, offset) = clientConnection.readMetadataResponse()
            serverRespJob.await()

            assertEquals(ProtocolConstants.METADATA_RESP_ACCEPTED, code)
            assertEquals(0L, offset)

            // Client sends CHUNK_DATA
            val samplePayload = "Socket Test Chunk Bytes".encodeToByteArray()
            val chunkData = ChunkData(
                header = ChunkHeader(
                    chunkMarker = ProtocolConstants.CHUNK_MARKER_PAYLOAD,
                    chunkId = 0L,
                    chunkLength = samplePayload.size,
                    checksum = 9999L
                ),
                payloadBytes = samplePayload
            )

            val clientChunkJob = async {
                clientConnection.sendChunkData(chunkData)
            }

            val chunkMsgHeader = serverConnection.readHeader()
            assertEquals(ProtocolConstants.MSG_CHUNK_DATA, chunkMsgHeader.messageType)

            val receivedChunk = serverConnection.readChunkData(chunkMsgHeader.payloadLength)
            clientChunkJob.await()

            assertEquals(0L, receivedChunk.header.chunkId)
            assertContentEquals(samplePayload, receivedChunk.payloadBytes)

        } finally {
            clientConnection.close()
            serverConnection.close()
            server.stop()
        }
    }
}
