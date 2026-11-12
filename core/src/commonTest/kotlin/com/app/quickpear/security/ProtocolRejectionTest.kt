package com.app.quickpear.security

import com.app.quickpear.domain.*
import com.app.quickpear.network.FrameConnection
import com.app.quickpear.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Buffer
import kotlin.test.*

class ProtocolRejectionTest {
    private class Packet(vararg bytes: ByteArray) : FrameConnection() {
        private val packets = bytes.toMutableList()
        override val remoteAddress = "test"
        override suspend fun receivePacket() = packets.removeAt(0)
        override suspend fun sendPacket(packet: ByteArray) { }
        override fun close() { }
    }
    @Test fun oversizedAndNegativePayloadsAreRejectedWithoutReadingBody() = runBlocking<Unit>(Dispatchers.IO) {
        for (length in listOf(-1L, Long.MAX_VALUE, 8193L)) {
            val header = Buffer().also { BinaryFrameEncoder().writeHeader(it, ProtocolConstants.MSG_HANDSHAKE_REQ, length) }.readByteArray()
            assertFailsWith<IllegalArgumentException> { Packet(header).readHeader() }
        }
        val body = Buffer().also { BinaryFrameEncoder().writeHeader(it, ProtocolConstants.MSG_TEXT_SHARE, 0) }.readByteArray()
        assertFailsWith<IllegalArgumentException> { Packet(body).readHeader() }
    }
    @Test fun versionOneCannotAuthenticate() = runBlocking<Unit>(Dispatchers.IO) {
        val peer = DeviceIdentity.generate()
        val hello = HelloMessage(1, "old client", peer.publicKey.toBase64(), "", ByteArray(32).toBase64(), "TRANSFER")
        val payload = Json.encodeToString(HelloMessage.serializer(), hello).encodeToByteArray()
        val packet = Buffer().also { BinaryFrameEncoder().writeHeader(it, ProtocolConstants.MSG_HANDSHAKE_REQ, payload.size.toLong()); it.write(payload) }.readByteArray()
        val error = assertFailsWith<HandshakeException> { Handshake.respond(Packet(packet), DeviceIdentity.generate(), "new client") }
        assertTrue(error.message!!.contains("update both devices"))
    }
    @Test fun changingACommittedContributionIsRejected() = runBlocking<Unit>(Dispatchers.IO) {
        fun frame(type: Byte, bytes: ByteArray) = Buffer().also {
            BinaryFrameEncoder().writeHeader(it, type, bytes.size.toLong()); it.write(bytes)
        }.readByteArray()
        val original = "original contribution".encodeToByteArray()
        val commit = HandshakeCommit(2, com.app.quickpear.io.ChecksumUtil.calculateSha256(original))
        val connection = Packet(
            frame(ProtocolConstants.MSG_HANDSHAKE_REQ, Json.encodeToString(HandshakeCommit.serializer(), commit).encodeToByteArray()),
            frame(ProtocolConstants.MSG_HANDSHAKE_REVEAL, "changed contribution".encodeToByteArray())
        )
        assertFailsWith<HandshakeException> { Handshake.respond(connection, DeviceIdentity.generate(), "receiver") }
        assertNull(connection.authenticatedPeerId)
    }
    @Test fun invalidMetadataAndArithmeticOverflowAreRejected() {
        val valid = FileMetadata.create(1, "a", 1, 1, "a".repeat(64))
        assertFails { valid.copy(chunkSizeBytes = 0).validate() }
        assertFails { valid.copy(totalChunks = Long.MAX_VALUE).validate() }
        assertFails { valid.copy(fileSizeBytes = -1).validate() }
        assertFails { MetadataRequest("s", List(1001) { valid }).validate() }
        assertFails { MetadataRequest("s", listOf(valid, valid)).validate() }
        assertFails { FileMetadata.create(2, "huge", Long.MAX_VALUE, 1, "a".repeat(64)).validate() }
    }
}
