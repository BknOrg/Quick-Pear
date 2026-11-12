package com.app.quickpear.signaling

import com.app.quickpear.domain.*
import com.app.quickpear.io.*
import com.app.quickpear.network.MessageTransport
import com.app.quickpear.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.FileSystem
import kotlin.test.*

/** A synchronous, in-process MQTT broker. No internet or timer-based message delivery. */
private class MemoryBroker {
    val listeners = java.util.concurrent.ConcurrentHashMap<String, (String, ByteArray) -> Unit>()
    val wire = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun endpoint(): MessageTransport = object : MessageTransport {
        private val connected = MutableStateFlow(false)
        private val topics = mutableSetOf<String>()
        override val isConnected: StateFlow<Boolean> = connected
        override fun start() { connected.value = true }
        override fun stop() { topics.forEach { listeners.remove(it) }; connected.value = false }
        override suspend fun subscribe(topic: String, onMessage: (String, ByteArray) -> Unit) { topics.add(topic); listeners[topic] = onMessage }
        override suspend fun unsubscribe(topic: String) { topics.remove(topic); listeners.remove(topic) }
        override suspend fun publish(topic: String, message: ByteArray) { wire.add(message.decodeToString()); listeners[topic]?.invoke(topic, message) }
    }
    suspend fun ready(topic: String) { withTimeout(5000) { while (!listeners.containsKey(topic)) delay(5) } }
}
class SignalingClientTest {
    private val fs = FileSystem.SYSTEM
    @Test fun cloudPairingTextAndMultiChunkTransferUseAuthenticatedEncryption() = runBlocking(Dispatchers.IO) {
        withTimeout(15_000) {
            val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-cloud-${java.util.UUID.randomUUID()}"
            fs.createDirectories(root)
            val a = DeviceIdentity.generate(); val b = DeviceIdentity.generate()
            val ta = TrustStore(root / "a"); val tb = TrustStore(root / "b")
            val broker = MemoryBroker()
            val text = CompletableDeferred<String>()
            val textCalls = java.util.concurrent.atomic.AtomicInteger()
            val sender = SignalingClient(a, { "A" }, DeviceType.WINDOWS, ta, mqttClient = broker.endpoint())
            val receiver = SignalingClient(b, { "B" }, DeviceType.ANDROID, tb, PartFileManager(root / "downloads"),
                onCloudTextReceived = { peer, content -> assertEquals(a.deviceId, peer.deviceId); textCalls.incrementAndGet(); text.complete(content) }, mqttClient = broker.endpoint())
            sender.start(); receiver.start()
            try {
                broker.ready("quickpear/v2/inbox/${a.deviceId}"); broker.ready("quickpear/v2/inbox/${b.deviceId}")
                var firstSas = ""; var secondSas = ""
                val host = async { sender.startCloudPairing("948210") { _, sas -> firstSas = sas; true } }
                broker.ready("quickpear/v2/pair/948210")
                assertTrue(receiver.joinCloudPairing("948210") { _, sas -> secondSas = sas; true })
                assertTrue(host.await()); assertEquals(firstSas, secondSas)
                assertTrue(ta.isTrusted(b.deviceId)); assertTrue(tb.isTrusted(a.deviceId))
                val peer = PeerDevice(b.deviceId, "B", "127.0.0.1", connectionType = ConnectionType.CLOUD_P2P)
                assertTrue(sender.sendText(peer, "secret clipboard value"))
                assertEquals("secret clipboard value", text.await())
                val bytes = "private document contents".repeat(10000).encodeToByteArray()
                val path = root / "source.bin"; fs.write(path) { write(bytes) }
                val meta = FileMetadata.create(1, "result.bin", bytes.size.toLong(), sha256 = ChecksumUtil.calculateFileSha256(path, fs))
                assertTrue(sender.sendFiles(peer, mapOf(meta to path), "stable-session"))
                assertContentEquals(bytes, fs.read(root / "downloads" / "result.bin") { readByteArray() })
                assertTrue(broker.wire.none { it.contains("secret clipboard value") || it.contains("private document contents") })
                // An old unsigned application message cannot bypass the session handshake.
                val endpoint = broker.endpoint()
                endpoint.publish("quickpear/v2/inbox/${b.deviceId}", """{"action":"TEXT_SHARE","senderId":"${a.deviceId}","text":"forged"}""".encodeToByteArray())
                assertEquals("secret clipboard value", text.await()); assertEquals(1, textCalls.get())
            } finally { sender.stop(); receiver.stop(); fs.deleteRecursively(root) }
        }
    }
    @Test fun pairingRejectionDoesNotCreateTrust() = runBlocking(Dispatchers.IO) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-pair-${java.util.UUID.randomUUID()}"
        fs.createDirectories(root)
        val a = DeviceIdentity.generate(); val b = DeviceIdentity.generate(); val ta = TrustStore(root / "a"); val tb = TrustStore(root / "b")
        val broker = MemoryBroker()
        val first = SignalingClient(a, { "A" }, DeviceType.WINDOWS, ta, mqttClient = broker.endpoint())
        val second = SignalingClient(b, { "B" }, DeviceType.ANDROID, tb, mqttClient = broker.endpoint())
        first.start(); second.start()
        try {
            broker.ready("quickpear/v2/inbox/${a.deviceId}"); broker.ready("quickpear/v2/inbox/${b.deviceId}")
            val host = async { first.startCloudPairing("123456") { _, _ -> false } }
            broker.ready("quickpear/v2/pair/123456")
            assertFalse(second.joinCloudPairing("123456") { _, _ -> true }); assertFalse(host.await())
            assertFalse(ta.isTrusted(b.deviceId)); assertFalse(tb.isTrusted(a.deviceId))
        } finally { first.stop(); second.stop(); fs.deleteRecursively(root) }
    }
}
