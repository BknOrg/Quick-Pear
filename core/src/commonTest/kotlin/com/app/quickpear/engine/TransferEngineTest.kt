package com.app.quickpear.engine

import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.network.KtorSocketClient
import com.app.quickpear.network.KtorSocketServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferEngineTest {

    private val fs = FileSystem.SYSTEM

    private fun tempDir(name: String): Path {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-engine-$name-${(0..Int.MAX_VALUE).random()}"
        fs.createDirectories(dir)
        return dir
    }

    private fun createFile(dir: Path, name: String, size: Int): Pair<FileMetadata, Path> {
        val path = dir / name
        fs.write(path) {
            val block = ByteArray(8192) { (it * 31 + size).toByte() }
            var remaining = size
            while (remaining > 0) {
                val n = minOf(remaining, block.size)
                write(block, 0, n)
                remaining -= n
            }
        }
        val meta = FileMetadata.create(
            fileId = name.hashCode(),
            fileName = name,
            fileSizeBytes = size.toLong(),
            sha256 = ChecksumUtil.calculateFileSha256(path, fs)
        )
        return meta to path
    }

    private suspend fun transfer(
        port: Int,
        senderFiles: Map<FileMetadata, Path>,
        receiverDir: Path,
        approve: Boolean = true
    ): Pair<TransferEngine, TransferEngine> {
        val server = KtorSocketServer(port = port, host = "127.0.0.1")
        val sender = TransferEngine(PartFileManager(tempDir("sender-unused")))
        val receiver = TransferEngine(PartFileManager(receiverDir))
        server.start()
        try {
            kotlinx.coroutines.coroutineScope {
                val rx = async {
                    val conn = server.acceptConnections().first()
                    try {
                        receiver.receiveFiles(conn) { approve }
                    } finally {
                        conn.close()
                    }
                }
                val tx = async {
                    val conn = KtorSocketClient().connect("127.0.0.1", port)
                    try {
                        sender.sendFiles(conn, "test-session", senderFiles)
                    } finally {
                        conn.close()
                    }
                }
                listOf(rx, tx).awaitAll()
            }
        } finally {
            server.stop()
        }
        return sender to receiver
    }

    @Test
    fun singleMultiChunkFileTransfersAndIsFinalized() = runTest {
        val srcDir = tempDir("src1")
        val dstDir = tempDir("dst1")
        val (meta, path) = createFile(srcDir, "big.bin", 5 * 1024 * 1024 + 123)

        val (sender, receiver) = transfer(9901, mapOf(meta to path), dstDir)

        assertEquals(TransferStatus.COMPLETED, sender.progressState.value?.status)
        assertEquals(TransferStatus.COMPLETED, receiver.progressState.value?.status)
        assertTrue(fs.exists(dstDir / "big.bin"))
        assertFalse(fs.exists(dstDir / "big.bin.part"))
        assertFalse(fs.exists(dstDir / "big.bin.part.meta"))
        assertEquals(meta.expectedSha256, ChecksumUtil.calculateFileSha256(dstDir / "big.bin", fs))
    }

    @Test
    fun multipleFilesIncludingZeroByteFile() = runTest {
        val srcDir = tempDir("src2")
        val dstDir = tempDir("dst2")
        val files = listOf(
            createFile(srcDir, "a.txt", 1000),
            createFile(srcDir, "empty.dat", 0),
            createFile(srcDir, "b.bin", 3 * 1024 * 1024)
        )

        val (sender, receiver) = transfer(9902, files.toMap(), dstDir)

        assertEquals(TransferStatus.COMPLETED, sender.progressState.value?.status)
        assertEquals(TransferStatus.COMPLETED, receiver.progressState.value?.status)
        for ((meta, _) in files) {
            val out = dstDir / meta.fileName
            assertTrue(fs.exists(out), "${meta.fileName} should exist")
            assertEquals(meta.fileSizeBytes, fs.metadata(out).size)
            assertEquals(meta.expectedSha256, ChecksumUtil.calculateFileSha256(out, fs))
        }
    }

    @Test
    fun rejectedTransferFailsOnBothSidesAndWritesNothing() = runTest {
        val srcDir = tempDir("src3")
        val dstDir = tempDir("dst3")
        val (meta, path) = createFile(srcDir, "no.txt", 500)

        val (sender, receiver) = transfer(9903, mapOf(meta to path), dstDir, approve = false)

        assertEquals(TransferStatus.FAILED, sender.progressState.value?.status)
        assertEquals(TransferStatus.FAILED, receiver.progressState.value?.status)
        assertFalse(fs.exists(dstDir / "no.txt"))
    }

    @Test
    fun wrongExpectedChecksumIsReportedAsFailure() = runTest {
        val srcDir = tempDir("src4")
        val dstDir = tempDir("dst4")
        val (goodMeta, path) = createFile(srcDir, "corrupt.bin", 2000)
        val badMeta = goodMeta.copy(expectedSha256 = "0".repeat(64))

        val (sender, receiver) = transfer(9904, mapOf(badMeta to path), dstDir)

        assertEquals(TransferStatus.FAILED, sender.progressState.value?.status)
        assertEquals(TransferStatus.FAILED, receiver.progressState.value?.status)
        assertFalse(fs.exists(dstDir / "corrupt.bin"))
    }

    @Test
    fun maliciousFileNameCannotEscapeDownloadDirectory() = runTest {
        val srcDir = tempDir("src5")
        val dstDir = tempDir("dst5")
        val (meta, path) = createFile(srcDir, "evil.txt", 300)
        val evilMeta = meta.copy(fileName = "../../escaped.txt")

        val (_, receiver) = transfer(9905, mapOf(evilMeta to path), dstDir)

        assertEquals(TransferStatus.COMPLETED, receiver.progressState.value?.status)
        assertTrue(fs.exists(dstDir / "escaped.txt"))
        assertFalse(fs.exists(dstDir.parent!!.parent!! / "escaped.txt"))
    }

    @Test
    fun cancelTransferStopsTransferAndCleansUpPartialFiles() = runTest {
        val srcDir = tempDir("src-cancel")
        val dstDir = tempDir("dst-cancel")
        val (meta, path) = createFile(srcDir, "cancel-test.bin", 6 * 1024 * 1024)

        val port = 9906
        val server = KtorSocketServer(port = port, host = "127.0.0.1")
        val sender = TransferEngine(PartFileManager(tempDir("sender-unused-cancel")))
        val receiver = TransferEngine(PartFileManager(dstDir))
        server.start()

        try {
            kotlinx.coroutines.coroutineScope {
                val rx = async {
                    val conn = server.acceptConnections().first()
                    try {
                        receiver.receiveFiles(conn) { true }
                    } catch (_: Exception) {
                    } finally {
                        conn.close()
                    }
                }
                val tx = async {
                    val conn = KtorSocketClient().connect("127.0.0.1", port)
                    try {
                        sender.sendFiles(conn, "test-cancel-session", mapOf(meta to path))
                    } catch (_: Exception) {
                    } finally {
                        conn.close()
                    }
                }

                // Wait until transfer starts
                while (sender.progressState.value?.status != TransferStatus.TRANSFERRING &&
                    receiver.progressState.value?.status != TransferStatus.TRANSFERRING) {
                    kotlinx.coroutines.delay(10)
                }

                // Cancel from sender side
                sender.cancelTransfer("Transfer cancelled by user")

                listOf(rx, tx).awaitAll()
            }
        } finally {
            server.stop()
        }

        assertEquals(TransferStatus.FAILED, sender.progressState.value?.status)
        assertEquals(TransferStatus.FAILED, receiver.progressState.value?.status)
        assertFalse(fs.exists(dstDir / "cancel-test.bin"))
        assertFalse(fs.exists(dstDir / "cancel-test.bin.part"))
        assertFalse(fs.exists(dstDir / "cancel-test.bin.part.meta"))
    }
}
