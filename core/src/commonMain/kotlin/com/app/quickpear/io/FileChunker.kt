package com.app.quickpear.io

import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.ChunkHeader
import com.app.quickpear.protocol.ProtocolConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.FileSystem
import okio.Path

class FileChunker(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val chunkSizeBytes: Int = 2_097_152 // 2 MB default
) {

    /**
     * Reads a file chunk by chunk starting from startChunkIndex using Okio FileHandle.
     * Yields ChunkData items through a Coroutines Flow without loading the full file into memory.
     */
    fun readChunksFlow(
        filePath: Path,
        startChunkIndex: Long = 0L
    ): Flow<ChunkData> = flow {
        fileSystem.openReadOnly(filePath).use { fileHandle ->
            val fileSize = fileHandle.size()
            val startByteOffset = startChunkIndex * chunkSizeBytes
            var currentChunkIndex = startChunkIndex
            var currentByteOffset = startByteOffset

            val buffer = ByteArray(chunkSizeBytes)

            while (currentByteOffset < fileSize) {
                val bytesToRead = (fileSize - currentByteOffset).coerceAtMost(chunkSizeBytes.toLong()).toInt()
                val bytesRead = fileHandle.read(
                    fileOffset = currentByteOffset,
                    array = buffer,
                    arrayOffset = 0,
                    byteCount = bytesToRead
                )

                if (bytesRead <= 0) break

                val payloadBytes = if (bytesRead == chunkSizeBytes) {
                    buffer.copyOf()
                } else {
                    buffer.copyOf(bytesRead)
                }

                val checksum = ChecksumUtil.calculateChunkChecksum(payloadBytes)
                val header = ChunkHeader(
                    chunkMarker = ProtocolConstants.CHUNK_MARKER_PAYLOAD,
                    chunkId = currentChunkIndex,
                    chunkLength = bytesRead,
                    checksum = checksum
                )

                emit(ChunkData(header = header, payloadBytes = payloadBytes))

                currentByteOffset += bytesRead
                currentChunkIndex++
            }
        }
    }
}
