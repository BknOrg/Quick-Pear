package com.app.quickpear.domain

/**
 * Header structure for chunk data frame (0x05 CHUNK_DATA).
 */
data class ChunkHeader(
    val chunkMarker: Byte = 0xAA.toByte(), // Marker 0xAA
    val chunkId: Long,                     // Index of chunk (0-based)
    val chunkLength: Int,                  // Length of raw bytes
    val checksum: Long                     // CRC32C / xxHash64 checksum
)

/**
 * Container for chunk payload including header and raw byte buffer.
 */
data class ChunkData(
    val header: ChunkHeader,
    val payloadBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkData) return false
        if (header != other.header) return false
        return payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        return result
    }
}

/**
 * Chunk acknowledgment response (0x06 CHUNK_ACK).
 */
data class ChunkAck(
    val chunkId: Long,
    val status: Byte // 0x00 = SUCCESS, 0x01 = CHECKSUM_MISMATCH
) {
    companion object {
        const val STATUS_SUCCESS: Byte = 0x00
        const val STATUS_CHECKSUM_MISMATCH: Byte = 0x01
    }
}
