package com.app.quickpear.io

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartFileManagerTest {

    private val fileSystem = FileSystem.SYSTEM

    @Test
    fun testPartFileManagerMetadataAndFinalize() {
        val tempDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "quickpear_part_test_${System.currentTimeMillis()}"
        val partManager = PartFileManager(downloadDirectory = tempDir, fileSystem = fileSystem)

        try {
            assertTrue(partManager.hasSufficientStorage(1024L))

            val fileName = "video_test.mp4"
            val meta = partManager.getOrCreateMeta(
                fileId = 1,
                fileName = fileName,
                totalSize = 10_000_000L,
                sha256 = "dummy_sha256_hash"
            )

            assertEquals(1, meta.fileId)
            assertEquals(0L, partManager.getStartChunkOffset(fileName))

            // Mark chunk 0, 1, 2 received
            partManager.markChunkReceived(fileName, 0L)
            partManager.markChunkReceived(fileName, 1L)
            partManager.markChunkReceived(fileName, 2L)

            // Start chunk offset should now be 3
            assertEquals(3L, partManager.getStartChunkOffset(fileName))

            // Create dummy .part file
            val partPath = partManager.getPartPath(fileName)
            fileSystem.write(partPath) {
                writeUtf8("Sample part file content")
            }

            // Finalize transfer
            val finalPath = partManager.finalizeTransfer(fileName)
            assertTrue(fileSystem.exists(finalPath))
            assertFalse(fileSystem.exists(partPath))
            assertFalse(fileSystem.exists(partManager.getMetaPath(fileName)))

            if (fileSystem.exists(finalPath)) fileSystem.delete(finalPath)

        } finally {
            if (fileSystem.exists(tempDir)) fileSystem.deleteRecursively(tempDir)
        }
    }
}
