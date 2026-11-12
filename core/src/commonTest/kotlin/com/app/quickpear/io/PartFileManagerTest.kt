package com.app.quickpear.io

import com.app.quickpear.domain.FileMetadata
import okio.FileSystem
import kotlin.test.*

class PartFileManagerTest {
    private val fs = FileSystem.SYSTEM
    private fun directory() = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-parts-${java.util.UUID.randomUUID()}").also { fs.createDirectories(it) }
    private fun meta(id: Int = 1, name: String = "same.bin", contents: ByteArray = byteArrayOf(1, 2, 3, 4)): FileMetadata =
        FileMetadata.create(id, name, contents.size.toLong(), 2, okio.ByteString.of(*contents).sha256().hex())
    @Test fun sameNamesAreIsolatedAndResumeSurvivesRestart() {
        val dir = directory()
        try {
            val first = TransferFileKey("peer:session-a", 1)
            val second = TransferFileKey("peer:session-b", 1)
            val manager = PartFileManager(dir)
            manager.prepare(first, "peer", meta()); manager.prepare(second, "peer", meta())
            val bytes = byteArrayOf(1, 2)
            assertTrue(manager.writeChunk(first, 0, bytes, ChecksumUtil.calculateChunkChecksum(bytes)))
            assertFalse(manager.writeChunk(first, 0, bytes, ChecksumUtil.calculateChunkChecksum(bytes)))
            assertEquals(2, manager.receivedBytes(first))
            assertNotEquals(manager.getPartPath(first), manager.getPartPath(second))
            val restarted = PartFileManager(dir)
            restarted.prepare(first, "peer", meta())
            assertEquals(1, restarted.getStartChunkOffset(first))
            val end = byteArrayOf(3, 4)
            restarted.writeChunk(first, 1, end, ChecksumUtil.calculateChunkChecksum(end))
            assertTrue(fs.exists(restarted.finalizeTransfer(first)))
            assertTrue(fs.exists(manager.getPartPath(second)))
            assertFails { manager.finalizeTransfer(second) }
        } finally { fs.deleteRecursively(dir) }
    }
    @Test fun changedMetadataResetsResumeAndInvalidChunksAreRejected() {
        val dir = directory()
        try {
            val manager = PartFileManager(dir); val key = TransferFileKey("session", 1)
            manager.prepare(key, "peer", meta())
            manager.writeChunk(key, 0, byteArrayOf(1, 2), ChecksumUtil.calculateChunkChecksum(byteArrayOf(1, 2)))
            manager.prepare(key, "peer", meta(contents = byteArrayOf(9, 8, 7, 6)))
            assertEquals(0, manager.getStartChunkOffset(key))
            assertFails { manager.writeChunk(key, Long.MAX_VALUE, byteArrayOf(1), 0) }
            assertFails { manager.writeChunk(key, 0, byteArrayOf(1), 0) }
        } finally { fs.deleteRecursively(dir) }
    }
    @Test fun wrongHashIsDeletedAndExistingDestinationIsPreserved() {
        val dir = directory()
        try {
            val manager = PartFileManager(dir); val key = TransferFileKey("session", 1)
            manager.prepare(key, "peer", meta())
            for (i in 0..1) { val bytes = byteArrayOf(9, 9); manager.writeChunk(key, i.toLong(), bytes, ChecksumUtil.calculateChunkChecksum(bytes)) }
            assertFails { manager.finalizeTransfer(key) }
            assertFalse(fs.exists(manager.getPartPath(key)))
            manager.prepare(key, "peer", meta())
            for (i in 0..1) { val bytes = if (i == 0) byteArrayOf(1, 2) else byteArrayOf(3, 4); manager.writeChunk(key, i.toLong(), bytes, ChecksumUtil.calculateChunkChecksum(bytes)) }
            fs.write(dir / "same.bin") { writeUtf8("existing") }
            assertEquals("same (1).bin", manager.finalizeTransfer(key).name)
            assertEquals("existing", fs.read(dir / "same.bin") { readUtf8() })
        } finally { fs.deleteRecursively(dir) }
    }
    @Test fun storageFailureDoesNotDefaultToSuccess() {
        val dir = directory()
        try {
            assertFalse(PartFileManager(dir, freeSpace = { 0 }).hasSufficientStorage(1))
            assertFalse(PartFileManager(dir, freeSpace = { error("storage unavailable") }).hasSufficientStorage(1))
        } finally { fs.deleteRecursively(dir) }
    }
}
