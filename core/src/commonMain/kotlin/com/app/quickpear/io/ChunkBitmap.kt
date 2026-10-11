package com.app.quickpear.io

import kotlin.math.max

/**
 * High-performance, compact bit array for tracking file chunks.
 * Each chunk is represented by a single bit (1 = received, 0 = missing).
 * 
 * Automatically expands its underlying byte buffer as chunks arrive.
 */
class ChunkBitmap(initialCapacityChunks: Long = 64L) {

    private var bytes: ByteArray = ByteArray(max(1, ((initialCapacityChunks + 7) / 8).toInt()))

    val byteCount: Int
        get() = bytes.size

    /**
     * Ensures the internal byte array can hold up to [chunkIndex] + 1 chunks.
     */
    private fun ensureCapacity(chunkIndex: Long) {
        val requiredBytes = ((chunkIndex / 8) + 1).toInt()
        if (requiredBytes > bytes.size) {
            val newCapacity = max(requiredBytes, bytes.size * 2)
            val newBytes = ByteArray(newCapacity)
            bytes.copyInto(newBytes)
            bytes = newBytes
        }
    }

    /**
     * Marks the chunk at [chunkIndex] as received (sets bit to 1).
     */
    @Synchronized
    fun set(chunkIndex: Long) {
        if (chunkIndex < 0) return
        ensureCapacity(chunkIndex)
        val byteIndex = (chunkIndex / 8).toInt()
        val bitOffset = (chunkIndex % 8).toInt()
        bytes[byteIndex] = (bytes[byteIndex].toInt() or (1 shl bitOffset)).toByte()
    }

    /**
     * Clears the bit at [chunkIndex] (sets bit to 0).
     */
    @Synchronized
    fun clear(chunkIndex: Long) {
        if (chunkIndex < 0) return
        val byteIndex = (chunkIndex / 8).toInt()
        if (byteIndex >= bytes.size) return
        val bitOffset = (chunkIndex % 8).toInt()
        bytes[byteIndex] = (bytes[byteIndex].toInt() and (1 shl bitOffset).inv()).toByte()
    }

    /**
     * Checks whether the chunk at [chunkIndex] has been received.
     */
    @Synchronized
    fun isSet(chunkIndex: Long): Boolean {
        if (chunkIndex < 0) return false
        val byteIndex = (chunkIndex / 8).toInt()
        if (byteIndex >= bytes.size) return false
        val bitOffset = (chunkIndex % 8).toInt()
        return (bytes[byteIndex].toInt() and (1 shl bitOffset)) != 0
    }

    /**
     * Finds the first chunk index in `0 until totalChunks` that has NOT been received.
     * Returns [totalChunks] if all chunks are received.
     */
    @Synchronized
    fun firstMissingChunk(totalChunks: Long): Long {
        if (totalChunks <= 0L) return 0L
        val maxByte = ((totalChunks + 7) / 8).toInt().coerceAtMost(bytes.size)
        var chunkIndex = 0L

        for (byteIdx in 0 until maxByte) {
            val b = bytes[byteIdx].toInt() and 0xFF
            if (b == 0xFF) {
                chunkIndex += 8
                if (chunkIndex >= totalChunks) return totalChunks
                continue
            }
            // Byte has at least one 0 bit
            for (bit in 0 until 8) {
                val current = byteIdx.toLong() * 8 + bit
                if (current >= totalChunks) return totalChunks
                if ((b and (1 shl bit)) == 0) {
                    return current
                }
            }
            chunkIndex += 8
        }

        // If bytes array was smaller than required for totalChunks
        val checkedChunks = (maxByte.toLong() * 8).coerceAtMost(totalChunks)
        return if (checkedChunks < totalChunks) checkedChunks else totalChunks
    }

    /**
     * Returns the total number of chunks that have been set (bit count / popcount).
     */
    @Synchronized
    fun cardinality(): Long {
        var count = 0L
        for (b in bytes) {
            count += Integer.bitCount(b.toInt() and 0xFF)
        }
        return count
    }

    /**
     * Returns a copy of the raw byte buffer trimmed to [totalChunks] length.
     */
    @Synchronized
    fun toByteArray(totalChunks: Long? = null): ByteArray {
        val requiredLength = if (totalChunks != null && totalChunks > 0L) {
            ((totalChunks + 7) / 8).toInt()
        } else {
            // Trim trailing zero bytes
            var lastNonZero = bytes.size - 1
            while (lastNonZero >= 0 && bytes[lastNonZero] == 0.toByte()) {
                lastNonZero--
            }
            (lastNonZero + 1).coerceAtLeast(1)
        }

        val result = ByteArray(requiredLength)
        val copyCount = bytes.size.coerceAtMost(requiredLength)
        bytes.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = copyCount)
        return result
    }

    companion object {
        fun fromByteArray(data: ByteArray): ChunkBitmap {
            val bitmap = ChunkBitmap()
            bitmap.bytes = data.copyOf()
            return bitmap
        }
    }
}
