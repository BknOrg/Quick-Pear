package com.app.quickpear.io

import com.app.quickpear.domain.ChunkData
import okio.FileSystem
import okio.Path

class ChunkWriter(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val chunkSizeBytes: Int = 2_097_152 // 2 MB
) {

    /**
     * Writes a chunk of data to a specific file at the designated chunk index byte offset.
     * Verifies checksum before writing to disk.
     */
    fun writeChunk(destinationPath: Path, chunkData: ChunkData): Boolean {
        // Verify checksum first
        val computedChecksum = ChecksumUtil.calculateChunkChecksum(chunkData.payloadBytes)
        if (computedChecksum != chunkData.header.checksum) {
            return false // Checksum mismatch
        }

        val targetByteOffset = chunkData.header.chunkId * chunkSizeBytes

        // Ensure parent directory exists
        destinationPath.parent?.let { parentDir ->
            if (!fileSystem.exists(parentDir)) {
                fileSystem.createDirectories(parentDir)
            }
        }

        // Open file in ReadWrite mode and write chunk at target byte offset
        fileSystem.openReadWrite(destinationPath).use { fileHandle ->
            fileHandle.write(
                fileOffset = targetByteOffset,
                array = chunkData.payloadBytes,
                arrayOffset = 0,
                byteCount = chunkData.payloadBytes.size
            )
        }
        return true
    }
}
