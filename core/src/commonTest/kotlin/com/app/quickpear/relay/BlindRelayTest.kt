package com.app.quickpear.relay

import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.engine.TransferEngine
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.io.PartFileManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import io.ktor.utils.io.writeFully
import okio.Buffer
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlindRelayTest {

    private val fs = FileSystem.SYSTEM

    private fun tempDir(prefix: String): Path {
        val p = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "quickpear_${prefix}_${Random.nextLong()}"
        fs.createDirectories(p)
        return p
    }

    @Test
    fun testBlindRelayPipesRawBinaryStream() = runTest {
        val testPort = 59921
        val relayServer = StandaloneRelayServer(port = testPort, host = "127.0.0.1")
        relayServer.start()

        val token = ByteArray(16) { (it + 1).toByte() }
        val testData = Random.nextBytes(256 * 1024) // 256 KB binary

        try {
            val receiverAsync = async {
                val client = BlindRelayClient()
                val conn = client.connectAndPair("127.0.0.1", testPort, token, BlindRelayClient.ROLE_RECEIVER)
                val buffer = Buffer()
                conn.readBufferExactly(buffer, testData.size.toLong())
                conn.close()
                buffer.readByteArray()
            }

            val senderAsync = async {
                val client = BlindRelayClient()
                val conn = client.connectAndPair("127.0.0.1", testPort, token, BlindRelayClient.ROLE_SENDER)
                conn.writeChannel.writeFully(testData)
                conn.writeChannel.flush()
                conn.close()
            }

            val receivedBytes = receiverAsync.await()
            senderAsync.await()

            assertTrue(testData.contentEquals(receivedBytes), "Relay stream must preserve exact raw bytes")
        } finally {
            relayServer.stop()
        }
    }

    @Test
    fun testTransferEngineWorksOverBlindRelay() = runTest {
        val testPort = 59922
        val relayServer = StandaloneRelayServer(port = testPort, host = "127.0.0.1")
        relayServer.start()

        val srcDir = tempDir("relay_src")
        val dstDir = tempDir("relay_dst")
        val filePath = srcDir / "large_relay_test.bin"
        val data = Random.nextBytes(512 * 1024) // 512 KB
        fs.write(filePath) { write(data) }

        val sha256 = ChecksumUtil.calculateFileSha256(filePath, fs)
        val meta = FileMetadata(
            fileId = 101,
            fileName = "large_relay_test.bin",
            fileSizeBytes = data.size.toLong(),
            chunkSizeBytes = 65536,
            totalChunks = (data.size.toLong() + 65535) / 65536,
            expectedSha256 = sha256
        )

        val token = Random.nextBytes(16)
        val senderEngine = TransferEngine(PartFileManager(tempDir("sender_unused")))
        val receiverEngine = TransferEngine(PartFileManager(dstDir))

        try {
            val receiverAsync = async {
                val client = BlindRelayClient()
                val conn = client.connectAndPair("127.0.0.1", testPort, token, BlindRelayClient.ROLE_RECEIVER)
                try {
                    receiverEngine.receiveFiles(conn) { true }
                } finally {
                    conn.close()
                }
            }

            val senderAsync = async {
                val client = BlindRelayClient()
                val conn = client.connectAndPair("127.0.0.1", testPort, token, BlindRelayClient.ROLE_SENDER)
                try {
                    senderEngine.sendFiles(conn, "relay-session-101", mapOf(meta to filePath))
                } finally {
                    conn.close()
                }
            }

            listOf(receiverAsync, senderAsync).awaitAll()

            assertEquals(TransferStatus.COMPLETED, senderEngine.progressState.value?.status)
            assertEquals(TransferStatus.COMPLETED, receiverEngine.progressState.value?.status)

            val destFile = dstDir / "large_relay_test.bin"
            assertTrue(fs.exists(destFile))
            assertEquals(sha256, ChecksumUtil.calculateFileSha256(destFile, fs))
        } finally {
            relayServer.stop()
            if (fs.exists(srcDir)) fs.deleteRecursively(srcDir)
            if (fs.exists(dstDir)) fs.deleteRecursively(dstDir)
        }
    }
}
