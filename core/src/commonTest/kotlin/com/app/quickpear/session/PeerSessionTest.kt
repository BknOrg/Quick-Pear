package com.app.quickpear.session

import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.engine.TransferEngine
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.network.KtorSocketClient
import com.app.quickpear.network.KtorSocketServer
import com.app.quickpear.protocol.ProtocolConstants
import com.app.quickpear.security.DeviceIdentity
import com.app.quickpear.security.HandshakeException
import com.app.quickpear.security.HelloMessage
import com.app.quickpear.security.PeerIdentity
import com.app.quickpear.security.TrustStore
import com.app.quickpear.security.toBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerSessionTest {
    private val fs = FileSystem.SYSTEM

    private fun tempDir(name: String): Path {
        val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-sess-$name-${(0..Int.MAX_VALUE).random()}"
        fs.createDirectories(dir)
        return dir
    }

    private inner class Device(val name: String) {
        val root = tempDir(name)
        val identity = DeviceIdentity.loadOrCreate(root / "id")
        val trust = TrustStore(root / "trust")
        val downloads = root / "downloads"
        val engine = TransferEngine(PartFileManager(downloads))
        val client = PeerClient(identity, { name }, engine)
    }

    private class FakeApproval(
        var transfer: ApprovalDecision = ApprovalDecision.ACCEPT,
        var pairAccepts: Boolean = true
    ) : ApprovalHandler {
        var transferPrompts = 0
        var lastSas: String? = null
        override suspend fun onTransferRequest(peer: PeerIdentity, request: MetadataRequest): ApprovalDecision {
            transferPrompts++
            return transfer
        }
        override suspend fun onPairingRequest(peer: PeerIdentity, sasCode: String): Boolean {
            lastSas = sasCode
            return pairAccepts
        }
    }

    private fun createFile(dir: Path, name: String, size: Int): Pair<FileMetadata, Path> {
        val path = dir / name
        fs.write(path) { write(ByteArray(size) { (it * 7).toByte() }) }
        return FileMetadata.create(
            fileId = 1, fileName = name, fileSizeBytes = size.toLong(),
            sha256 = ChecksumUtil.calculateFileSha256(path, fs)
        ) to path
    }

    /** Runs a server for [receiver] and the [clientBlock] concurrently; returns both outcomes. */
    private suspend fun <T> session(
        port: Int,
        receiver: Device,
        approval: ApprovalHandler,
        clientBlock: suspend () -> T
    ): Pair<Result<T>, Result<com.app.quickpear.security.HandshakeResult?>> {
        val server = KtorSocketServer(port = port, host = "127.0.0.1")
        server.start()
        try {
            // Dispatchers.Default => real clock; runTest's virtual time would fire withTimeout instantly
            return withContext(Dispatchers.Default) {
                coroutineScope {
                    val handler = IncomingConnectionHandler(
                        receiver.identity, { receiver.name }, receiver.trust, receiver.engine, approval
                    )
                    val rx = async {
                        val conn = server.acceptConnections().first()
                        try { runCatching { handler.handle(conn) } } finally { conn.close() }
                    }
                    val tx = async { runCatching { clientBlock() } }
                    tx.await() to rx.await()
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingWithMatchingCodesTrustsEachOther() = runTest {
        val a = Device("a1"); val b = Device("b1")
        val approval = FakeApproval(pairAccepts = true)
        var clientSas: String? = null

        val (tx, rx) = session(9911, b, approval) {
            a.client.pair("127.0.0.1", 9911, a.trust) { _, sas -> clientSas = sas; true }
        }

        assertTrue(tx.getOrThrow())
        assertNotNull(rx.getOrThrow())
        assertEquals(clientSas, approval.lastSas, "both sides must see the same code")
        assertTrue(Regex("\\d{6}").matches(clientSas!!))
        assertTrue(a.trust.isTrusted(b.identity.deviceId))
        assertTrue(b.trust.isTrusted(a.identity.deviceId))
        assertEquals("b1", a.trust.get(b.identity.deviceId)?.name)
    }

    @Test
    fun pairingRejectedByOneSideTrustsNobody() = runTest {
        val a = Device("a2"); val b = Device("b2")

        val (tx, _) = session(9912, b, FakeApproval(pairAccepts = false)) {
            a.client.pair("127.0.0.1", 9912, a.trust) { _, _ -> true }
        }

        assertFalse(tx.getOrThrow())
        assertFalse(a.trust.isTrusted(b.identity.deviceId))
        assertFalse(b.trust.isTrusted(a.identity.deviceId))
    }

    @Test
    fun untrustedSenderAcceptAlwaysThenNextTransferIsSilent() = runTest {
        val a = Device("a3"); val b = Device("b3")
        val approval = FakeApproval(transfer = ApprovalDecision.ACCEPT_ALWAYS)
        val (meta, path) = createFile(a.root, "photo.bin", 4096)

        val (tx1, _) = session(9913, b, approval) {
            a.client.sendFiles("127.0.0.1", 9913, "s1", mapOf(meta to path))
        }
        tx1.getOrThrow()
        assertEquals(1, approval.transferPrompts)
        assertTrue(b.trust.isTrusted(a.identity.deviceId), "ACCEPT_ALWAYS must add sender to trust list")
        assertEquals(TransferStatus.COMPLETED, b.engine.progressState.value?.status)
        assertTrue(fs.exists(b.downloads / "photo.bin"))

        // Second send from now-trusted device: no prompt even if the handler would reject.
        approval.transfer = ApprovalDecision.REJECT
        val (tx2, _) = session(9914, b, approval) {
            a.client.sendFiles("127.0.0.1", 9914, "s2", mapOf(meta to path))
        }
        tx2.getOrThrow()
        assertEquals(1, approval.transferPrompts, "trusted sender must not be prompted")
        assertEquals(TransferStatus.COMPLETED, a.engine.progressState.value?.status)
    }

    @Test
    fun removingFromTrustListRestoresPrompt() = runTest {
        val a = Device("a4"); val b = Device("b4")
        val approval = FakeApproval(transfer = ApprovalDecision.REJECT)
        b.trust.add(a.identity.deviceId, "a4", a.identity.publicKey)
        val (meta, path) = createFile(a.root, "f.bin", 100)

        b.trust.remove(a.identity.deviceId)
        val (tx, _) = session(9915, b, approval) {
            a.client.sendFiles("127.0.0.1", 9915, "s", mapOf(meta to path))
        }
        tx.getOrThrow()

        assertEquals(1, approval.transferPrompts)
        assertEquals(TransferStatus.FAILED, a.engine.progressState.value?.status)
        assertFalse(fs.exists(b.downloads / "f.bin"))
        assertFalse(b.trust.isTrusted(a.identity.deviceId))
    }

    @Test
    fun rejectedUntrustedTransferDoesNotTrustSender() = runTest {
        val a = Device("a5"); val b = Device("b5")
        val (meta, path) = createFile(a.root, "x.bin", 100)

        session(9916, b, FakeApproval(transfer = ApprovalDecision.REJECT)) {
            a.client.sendFiles("127.0.0.1", 9916, "s", mapOf(meta to path))
        }.first.getOrThrow()

        assertFalse(b.trust.isTrusted(a.identity.deviceId))
    }

    @Test
    fun connectingToWrongDeviceIsDetectedViaExpectedId() = runTest {
        val a = Device("a6"); val b = Device("b6")
        val impostorExpectedId = DeviceIdentity.generate().deviceId
        val (meta, path) = createFile(a.root, "x.bin", 100)

        val (tx, _) = session(9917, b, FakeApproval()) {
            a.client.sendFiles("127.0.0.1", 9917, "s", mapOf(meta to path), expectedPeerId = impostorExpectedId)
        }

        assertTrue(tx.exceptionOrNull() is HandshakeException)
        assertFalse(fs.exists(b.downloads / "x.bin"))
    }

    @Test
    fun malformedHandshakeIsRejectedWithoutCrashing() = runTest {
        val b = Device("b7")
        val (tx, rx) = session(9918, b, FakeApproval()) {
            val conn = KtorSocketClient().connect("127.0.0.1", 9918)
            try { conn.sendFrame(ProtocolConstants.MSG_HANDSHAKE_REQ, "not json".encodeToByteArray()) } finally { conn.close() }
        }
        tx.getOrThrow()
        assertNull(rx.getOrThrow())
    }

    @Test
    fun forgedAuthSignatureIsRejected() = runTest {
        val b = Device("b8")
        val attacker = DeviceIdentity.generate()
        val victim = DeviceIdentity.generate()
        val json = Json { ignoreUnknownKeys = true }

        val (tx, rx) = session(9919, b, FakeApproval()) {
            val conn = KtorSocketClient().connect("127.0.0.1", 9919)
            try {
                // Claim to be the attacker key but send a signature made by a different key.
                val hello = HelloMessage(
                    version = ProtocolConstants.PROTOCOL_VERSION, name = "evil",
                    publicKey = attacker.publicKey.toBase64(),
                    nonce = ByteArray(32) { 1 }.toBase64(), purpose = "TRANSFER"
                )
                conn.sendFrame(ProtocolConstants.MSG_HANDSHAKE_REQ, json.encodeToString(HelloMessage.serializer(), hello).encodeToByteArray())
                val header = conn.readHeader()
                conn.readFramePayload(header.payloadLength, 8192)
                val forged = victim.publicKey.toBase64() // not a valid signature at all
                conn.sendFrame(ProtocolConstants.MSG_HANDSHAKE_AUTH, "{\"signature\":\"$forged\"}".encodeToByteArray())
            } finally { conn.close() }
        }
        tx.getOrThrow()
        assertNull(rx.getOrThrow(), "forged handshake must not authenticate")
        assertFalse(b.trust.isTrusted(attacker.deviceId))
    }
}
