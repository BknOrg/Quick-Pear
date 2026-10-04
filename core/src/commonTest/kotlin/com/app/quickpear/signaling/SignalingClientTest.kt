package com.app.quickpear.signaling

import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.security.DeviceIdentity
import com.app.quickpear.security.TrustStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalingClientTest {

    @Test
    fun testLiveCloudPairing() = runBlocking(Dispatchers.IO) {
        val fs = FileSystem.SYSTEM
        val tempBase = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp_test_${System.currentTimeMillis()}"
        val dir1 = tempBase / "data1"
        val dir2 = tempBase / "data2"
        fs.createDirectories(dir1)
        fs.createDirectories(dir2)

        val id1 = DeviceIdentity.loadOrCreate(dir1, fs)
        val id2 = DeviceIdentity.loadOrCreate(dir2, fs)

        val trust1 = TrustStore(dir1, fs)
        val trust2 = TrustStore(dir2, fs)

        val client1 = SignalingClient(
            identity = id1,
            deviceNameProvider = { "Laptop Windows" },
            deviceType = DeviceType.WINDOWS,
            trustStore = trust1,
            fileSystem = fs
        )
        val client2 = SignalingClient(
            identity = id2,
            deviceNameProvider = { "HP Android" },
            deviceType = DeviceType.ANDROID,
            trustStore = trust2,
            fileSystem = fs
        )

        client1.start()
        client2.start()

        try {
            // Wait for both to connect to broker
            withTimeout(10000L) {
                while (!client1.isSignalingActive.value || !client2.isSignalingActive.value) {
                    kotlinx.coroutines.delay(100)
                }
            }

            val pairingCode = "948210"
            var sas1 = ""
            var sas2 = ""

            val job1 = async {
                client1.startCloudPairing(pairingCode) { peerName, sas ->
                    sas1 = sas
                    true
                }
            }

            kotlinx.coroutines.delay(1000)

            val job2 = async {
                client2.joinCloudPairing(pairingCode) { peerName, sas ->
                    sas2 = sas
                    true
                }
            }

            val (res1, res2) = withTimeout(20000L) {
                Pair(job1.await(), job2.await())
            }

            assertTrue(res1, "Host pairing should succeed")
            assertTrue(res2, "Client pairing should succeed")
            assertEquals(sas1, sas2, "SAS codes on both sides must match")
            assertTrue(trust1.isTrusted(id2.deviceId), "Device 1 must trust Device 2")
            assertTrue(trust2.isTrusted(id1.deviceId), "Device 2 must trust Device 1")
        } finally {
            client1.stop()
            client2.stop()
            try { fs.deleteRecursively(tempBase) } catch (_: Exception) {}
        }
    }

    @Test
    fun testLiveCloudFileTransfer() = runBlocking(Dispatchers.IO) {
        val fs = FileSystem.SYSTEM
        val tempBase = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp_transfer_${System.currentTimeMillis()}"
        val dir1 = tempBase / "data1"
        val dir2 = tempBase / "data2"
        val downloads2 = tempBase / "downloads2"
        fs.createDirectories(dir1)
        fs.createDirectories(dir2)
        fs.createDirectories(downloads2)

        val id1 = DeviceIdentity.loadOrCreate(dir1, fs)
        val id2 = DeviceIdentity.loadOrCreate(dir2, fs)

        val trust1 = TrustStore(dir1, fs)
        val trust2 = TrustStore(dir2, fs)

        // Pre-trust each other
        trust1.add(id2.deviceId, "Receiver Phone", id2.publicKey)
        trust2.add(id1.deviceId, "Sender PC", id1.publicKey)

        val partManager2 = PartFileManager(downloads2, fs)

        val client1 = SignalingClient(
            identity = id1,
            deviceNameProvider = { "Sender PC" },
            deviceType = DeviceType.WINDOWS,
            trustStore = trust1,
            fileSystem = fs
        )
        val client2 = SignalingClient(
            identity = id2,
            deviceNameProvider = { "Receiver Phone" },
            deviceType = DeviceType.ANDROID,
            trustStore = trust2,
            partFileManager = partManager2,
            fileSystem = fs
        )

        client1.start()
        client2.start()

        try {
            withTimeout(10000L) {
                while (!client1.isSignalingActive.value || !client2.isSignalingActive.value) {
                    kotlinx.coroutines.delay(100)
                }
            }

            // Create a test file of 150 KB (spans multiple 64KB chunks)
            val testFilePath = dir1 / "test_doc.txt"
            val testContent = "QuickPear Cloud File Transfer Test\n".repeat(4500)
            fs.write(testFilePath) { writeUtf8(testContent) }

            val fileSize = fs.metadataOrNull(testFilePath)?.size ?: 0L
            val fileSha256 = ChecksumUtil.calculateFileSha256(testFilePath, fs)
            val meta = FileMetadata.create(
                fileId = 1,
                fileName = "test_doc.txt",
                fileSizeBytes = fileSize,
                chunkSizeBytes = 65536,
                sha256 = fileSha256
            )

            val targetPeer = PeerDevice(
                id = id2.deviceId,
                name = "Receiver Phone",
                ipAddress = "127.0.0.1",
                port = 8888,
                deviceType = DeviceType.ANDROID,
                connectionType = ConnectionType.CLOUD_P2P
            )

            val success = withTimeout(30000L) {
                client1.sendFiles(targetPeer, mapOf(meta to testFilePath))
            }

            assertTrue(success, "sendFiles should return true")

            val downloadedFile = downloads2 / "test_doc.txt"
            assertTrue(fs.exists(downloadedFile), "Downloaded file must exist")
            val receivedContent = fs.read(downloadedFile) { readUtf8() }
            assertEquals(testContent, receivedContent, "Downloaded content must match source exactly")
        } finally {
            client1.stop()
            client2.stop()
            try { fs.deleteRecursively(tempBase) } catch (_: Exception) {}
        }
    }
}
