package com.app.quickpear.io

import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.use

object ChecksumUtil {

    /**
     * Fast 64-bit checksum calculation (CRC-64 / FNV-1a hybrid) for ByteArray chunks.
     */
    fun calculateChunkChecksum(bytes: ByteArray): Long {
        var hash = -3750763025362895273L // FNV offset basis
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= 1099511628211L // FNV prime
        }
        return hash
    }

    /**
     * Calculates SHA-256 hash string (hex encoded) for an entire file using Okio HashingSource.
     */
    fun calculateFileSha256(filePath: Path, fileSystem: FileSystem = FileSystem.SYSTEM): String {
        fileSystem.source(filePath).use { rawSource ->
            HashingSource.sha256(rawSource).use { hashingSource ->
                val buffer = Buffer()
                while (hashingSource.read(buffer, 8192L) != -1L) {
                    buffer.clear()
                }
                return hashingSource.hash.hex()
            }
        }
    }

    /**
     * Calculates SHA-256 raw bytes for a file.
     */
    fun calculateFileSha256Bytes(filePath: Path, fileSystem: FileSystem = FileSystem.SYSTEM): ByteArray {
        fileSystem.source(filePath).use { rawSource ->
            HashingSource.sha256(rawSource).use { hashingSource ->
                val buffer = Buffer()
                while (hashingSource.read(buffer, 8192L) != -1L) {
                    buffer.clear()
                }
                return hashingSource.hash.toByteArray()
            }
        }
    }

    /**
     * Calculates SHA-256 hex string for a ByteArray.
     */
    fun calculateSha256(bytes: ByteArray): String {
        return bytes.toByteString().sha256().hex()
    }
}
