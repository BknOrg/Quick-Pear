package com.app.quickpear.signaling

import com.app.quickpear.network.*
import com.app.quickpear.protocol.*
import com.app.quickpear.security.SecurityLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

@Serializable
private data class RoutedFrame(val session: String, val sender: String, val recipient: String, val data: String)

/** Routing data is untrusted. The bound handshake authenticates the identity before any action. */
internal class MqttFrameHub(val localId: String, val transport: MessageTransport) {
    private val json = Json
    private val connections = java.util.concurrent.ConcurrentHashMap<String, MqttConnection>()
    var onIncoming: (MqttConnection) -> Unit = { it.close() }
    private fun inbox(id: String) = "quickpear/v2/inbox/$id"
    suspend fun start() { transport.subscribe(inbox(localId)) { _, bytes -> receive(bytes, onIncoming) } }
    fun stop() { connections.values.toList().forEach { it.close() } }
    suspend fun listenPair(code: String, accept: (MqttConnection) -> Unit) {
        require(code.matches(Regex("[0-9]{6}")))
        transport.subscribe("quickpear/v2/pair/$code") { _, bytes -> receive(bytes, accept) }
    }
    suspend fun stopPair(code: String) { transport.unsubscribe("quickpear/v2/pair/$code") }
    @Synchronized fun open(peer: String, pairCode: String? = null): MqttConnection {
        check(connections.size < 16) { "Too many cloud sessions" }
        val id = java.util.UUID.randomUUID().toString()
        return MqttConnection(id, peer, pairCode).also { connections[id] = it }
    }
    @Synchronized private fun receive(bytes: ByteArray, accept: (MqttConnection) -> Unit) {
        try {
            require(bytes.size <= MqttClient.MAX_MQTT_PACKET_LENGTH)
            val route = json.decodeFromString(RoutedFrame.serializer(), bytes.decodeToString())
            require(route.session.matches(Regex("[a-f0-9-]{36}")))
            require(route.sender.matches(Regex("[a-f0-9]{32}")) && route.sender != localId)
            require(route.recipient.isEmpty() || route.recipient == localId)
            val packet = route.data.decodeBase64()?.toByteArray() ?: error("Invalid packet")
            var connection = connections[route.session]
            if (connection == null) {
                require(connections.size < 16)
                val header = BinaryFrameDecoder().readHeader(Buffer().write(packet))
                require(header.messageType == ProtocolConstants.MSG_HANDSHAKE_REQ && header.payloadLength <= 8192)
                FrameLimits.validateWire(header)
                require(packet.size.toLong() == 13 + header.payloadLength)
                val candidate = MqttConnection(route.session, route.sender, null)
                connection = connections.putIfAbsent(route.session, candidate) ?: candidate.also { accept(it) }
            }
            require(connection.remoteId == route.sender || connection.remoteId.isEmpty())
            if (connection.remoteId.isEmpty()) connection.remoteId = route.sender
            val header = BinaryFrameDecoder().readHeader(Buffer().write(packet))
            FrameLimits.validateWire(header)
            require(packet.size.toLong() == 13 + header.payloadLength)
            if (!connection.packets.trySend(packet).isSuccess) connection.close()
        } catch (_: Exception) { SecurityLog.event("cloud_frame_rejected") }
    }
    inner class MqttConnection(override val binding: String, var remoteId: String, private var pairCode: String?) : FrameConnection() {
        internal val packets = Channel<ByteArray>(8)
        override val remoteAddress get() = "mqtt:$remoteId"
        override suspend fun receivePacket() = packets.receive()
        override suspend fun sendPacket(packet: ByteArray) {
            val route = RoutedFrame(binding, localId, remoteId, packet.toByteString().base64())
            val topic = pairCode?.let { "quickpear/v2/pair/$it" } ?: inbox(remoteId)
            pairCode = null
            transport.publish(topic, json.encodeToString(RoutedFrame.serializer(), route).encodeToByteArray())
        }
        override fun close() { connections.remove(binding, this); packets.close() }
    }
}
