package com.app.quickpear.io

import com.app.quickpear.domain.ChunkData
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileChunkerWriterTest {

    private val fileSystem = FileSystem.SYSTEM

    @Test
    fun testChecksumCalculation() {
        val sampleBytes = "QuickPear Local Transfer Test".encodeToByteArray()
        val checksum1 = ChecksumUtil.calculateChunkChecksum(sampleBytes)
        val checksum2 = ChecksumUtil.calculateChunkChecksum(sampleBytes)
        assertEquals(checksum1, checksum2)

        val sha256Hex = ChecksumUtil.calculateSha256(sampleBytes)
        assertTrue(sha256Hex.isNotEmpty())
        assertEquals(64, sha256Hex.length)
    }

    @Test
    fun testChunkingAndWritingFlow() = runTest {
        val tempDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val sourceFile = tempDir / "quickpear_test_source.bin"
        val destFile = tempDir / "quickpear_test_dest.bin"

        try {
            // Create a 5 MB dummy test file
            val testDataSize = 5 * 1024 * 1024
            val testBuffer = ByteArray(testDataSize) { (it % 256).toByte() }

            fileSystem.write(sourceFile) {
                write(testBuffer)
            }

            // Chunk with 2 MB chunks
            val chunker = FileChunker(fileSystem = fileSystem, chunkSizeBytes = 2 * 1024 * 1024)
            val chunks: List<ChunkData> = chunker.readChunksFlow(sourceFile).toList()

            // 5 MB divided by 2 MB chunks = 3 chunks (2MB, 2MB, 1MB)
            assertEquals(3, chunks.size)
            assertEquals(2 * 1024 * 1024, chunks[0].payloadBytes.size)
            assertEquals(2 * 1024 * 1024, chunks[1].payloadBytes.size)
            assertEquals(1 * 1024 * 1024, chunks[2].payloadBytes.size)

            // Write chunks to destination file
            val writer = ChunkWriter(fileSystem = fileSystem, chunkSizeBytes = 2 * 1024 * 1024)
            for (chunk in chunks) {
                val success = writer.writeChunk(destFile, chunk)
                assertTrue(success)
            }

            // Verify original SHA-256 matches destination SHA-256
            val sourceSha = ChecksumUtil.calculateFileSha256(sourceFile, fileSystem)
            val destSha = ChecksumUtil.calculateFileSha256(destFile, fileSystem)
            assertEquals(sourceSha, destSha)

        } finally {
            if (fileSystem.exists(sourceFile)) fileSystem.delete(sourceFile)
            if (fileSystem.exists(destFile)) fileSystem.delete(destFile)
        }
    }
}
