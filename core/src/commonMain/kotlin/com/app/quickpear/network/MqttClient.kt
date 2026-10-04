package com.app.quickpear.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import kotlin.random.Random

/**
 * Lightweight pure-Kotlin MQTT v3.1.1 client running on top of Ktor raw TCP sockets.
 * Connects to public open brokers (e.g. broker.emqx.io, broker.hivemq.com) for
 * serverless, zero-config cross-network presence and relaying.
 */
class MqttClient(
    val clientId: String = "qp_" + Random.nextLong(100_000_000, 999_999_999),
    val hosts: List<String> = listOf("broker.emqx.io", "broker.hivemq.com"),
    val port: Int = 1883
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val writeMutex = Mutex()
    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null

    private var socket: Socket? = null
    private var writeChannel: ByteWriteChannel? = null
    private var selectorManager: SelectorManager? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val subscriptions = mutableMapOf<String, (String, ByteArray) -> Unit>()
    private var packetIdCounter = 1

    fun start() {
        if (connectionJob != null) return
        connectionJob = scope.launch {
            var hostIndex = 0
            while (isActive) {
                val host = hosts[hostIndex % hosts.size]
                try {
                    connectToBroker(host)
                } catch (_: Exception) {
                    _isConnected.value = false
                    cleanup()
                    hostIndex++
                    delay(3000L)
                }
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        cleanup()
    }

    private fun cleanup() {
        _isConnected.value = false
        pingJob?.cancel()
        pingJob = null
        readJob?.cancel()
        readJob = null
        try {
            socket?.close()
            socket = null
            selectorManager?.close()
            selectorManager = null
        } catch (_: Exception) {
        }
    }

    private suspend fun connectToBroker(host: String) = withContext(Dispatchers.IO) {
        val selector = SelectorManager(Dispatchers.IO)
        selectorManager = selector
        val sock = aSocket(selector).tcp().connect(host, port)
        socket = sock
        val readChan = sock.openReadChannel()
        val writeChan = sock.openWriteChannel(autoFlush = true)
        writeChannel = writeChan

        // Send MQTT CONNECT
        sendConnectPacket(writeChan, clientId)

        // Read CONNACK (Packet Type 0x20, Length 0x02)
        val headerByte = readByteFromChan(readChan)
        val remainingLength = readMqttLength(readChan)
        val connackPayload = ByteArray(remainingLength)
        readChan.readFully(connackPayload)

        if ((headerByte ushr 4) != 2 || connackPayload.size < 2 || connackPayload[1] != 0x00.toByte()) {
            throw IllegalStateException("MQTT connection rejected (code: ${connackPayload.getOrNull(1)})")
        }

        _isConnected.value = true

        // Re-subscribe to existing subscriptions
        for (topic in subscriptions.keys) {
            sendSubscribePacket(writeChan, topic)
        }

        // Start ping loop
        pingJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                try {
                    val pingChan = writeChannel ?: break
                    writeMutex.withLock {
                        pingChan.writeFully(byteArrayOf(0xC0.toByte(), 0x00.toByte()))
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        // Reader loop
        while (isActive) {
            val typeByte = readByteFromChan(readChan)
            val packetType = typeByte ushr 4
            val remLen = readMqttLength(readChan)
            val payload = ByteArray(remLen)
            readChan.readFully(payload)

            when (packetType) {
                3 -> { // PUBLISH
                    handleIncomingPublish(payload)
                }
                13 -> { // PINGRESP
                    // Heartbeat acknowledged
                }
                else -> {
                    // Ignore other packets (SUBACK, PUBACK)
                }
            }
        }
    }

    private fun handleIncomingPublish(data: ByteArray) {
        if (data.size < 2) return
        val topicLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (data.size < 2 + topicLen) return
        val topic = data.decodeToString(2, 2 + topicLen)
        val msgBytes = data.copyOfRange(2 + topicLen, data.size)

        // Dispatch to matching subscriber
        for ((subTopic, callback) in subscriptions) {
            if (topicMatches(subTopic, topic)) {
                callback(topic, msgBytes)
            }
        }
    }

    fun topicMatches(sub: String, actual: String): Boolean {
        if (sub == actual || sub == "#") return true
        val subParts = sub.split('/')
        val actParts = actual.split('/')
        for (i in subParts.indices) {
            val s = subParts[i]
            if (s == "#") return true
            if (i >= actParts.size) return false
            val a = actParts[i]
            if (s != "+" && s != a) return false
        }
        return subParts.size == actParts.size
    }

    suspend fun subscribe(topic: String, onMessage: (String, ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        subscriptions[topic] = onMessage
        val chan = writeChannel
        if (_isConnected.value && chan != null) {
            try {
                sendSubscribePacket(chan, topic)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun unsubscribe(topic: String) = withContext(Dispatchers.IO) {
        subscriptions.remove(topic)
        val chan = writeChannel
        if (_isConnected.value && chan != null) {
            try {
                val buf = Buffer()
                val pid = packetIdCounter++
                buf.writeShort(pid)
                val tBytes = topic.encodeToByteArray()
                buf.writeShort(tBytes.size)
                buf.write(tBytes)
                val variableAndPayload = buf.readByteArray()

                val packet = Buffer()
                packet.writeByte(0xA2) // UNSUBSCRIBE QoS 1
                writeMqttLength(packet, variableAndPayload.size)
                packet.write(variableAndPayload)

                writeMutex.withLock {
                    chan.writeFully(packet.readByteArray())
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun publish(topic: String, message: ByteArray) = withContext(Dispatchers.IO) {
        val chan = writeChannel ?: return@withContext
        if (!_isConnected.value) return@withContext
        try {
            sendPublishPacket(chan, topic, message)
        } catch (_: Exception) {
        }
    }

    private suspend fun sendConnectPacket(chan: ByteWriteChannel, cid: String) {
        val buf = Buffer()
        // Protocol Name
        buf.writeShort(4)
        buf.write("MQTT".encodeToByteArray())
        // Protocol Level 4 (v3.1.1)
        buf.writeByte(4)
        // Connect Flags: Clean Session = 1
        buf.writeByte(0x02)
        // Keep Alive: 60s
        buf.writeShort(60)
        // Payload: Client ID
        val cidBytes = cid.encodeToByteArray()
        buf.writeShort(cidBytes.size)
        buf.write(cidBytes)

        val variableAndPayload = buf.readByteArray()
        val packet = Buffer()
        packet.writeByte(0x10) // CONNECT
        writeMqttLength(packet, variableAndPayload.size)
        packet.write(variableAndPayload)

        writeMutex.withLock {
            chan.writeFully(packet.readByteArray())
        }
    }

    private suspend fun sendSubscribePacket(chan: ByteWriteChannel, topic: String) {
        val buf = Buffer()
        // Packet ID
        val pid = packetIdCounter++
        buf.writeShort(pid)
        // Topic + QoS 0
        val tBytes = topic.encodeToByteArray()
        buf.writeShort(tBytes.size)
        buf.write(tBytes)
        buf.writeByte(0) // QoS 0

        val variableAndPayload = buf.readByteArray()
        val packet = Buffer()
        packet.writeByte(0x82) // SUBSCRIBE
        writeMqttLength(packet, variableAndPayload.size)
        packet.write(variableAndPayload)

        writeMutex.withLock {
            chan.writeFully(packet.readByteArray())
        }
    }

    private suspend fun sendPublishPacket(chan: ByteWriteChannel, topic: String, payload: ByteArray) {
        val buf = Buffer()
        val tBytes = topic.encodeToByteArray()
        buf.writeShort(tBytes.size)
        buf.write(tBytes)
        buf.write(payload)

        val variableAndPayload = buf.readByteArray()
        val packet = Buffer()
        packet.writeByte(0x30) // PUBLISH QoS 0
        writeMqttLength(packet, variableAndPayload.size)
        packet.write(variableAndPayload)

        writeMutex.withLock {
            chan.writeFully(packet.readByteArray())
        }
    }

    private suspend fun readByteFromChan(chan: ByteReadChannel): Int {
        val b = ByteArray(1)
        chan.readFully(b)
        return b[0].toInt() and 0xFF
    }

    private suspend fun readMqttLength(chan: ByteReadChannel): Int {
        var multiplier = 1
        var value = 0
        do {
            val encodedByte = readByteFromChan(chan)
            value += (encodedByte and 0x7F) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) {
                throw IllegalStateException("Malformed Remaining Length in MQTT packet")
            }
        } while ((encodedByte and 0x80) != 0)
        return value
    }

    private fun writeMqttLength(buf: Buffer, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 0x80
            }
            buf.writeByte(encodedByte)
        } while (x > 0)
    }
}
