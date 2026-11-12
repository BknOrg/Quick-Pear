package com.app.quickpear.signaling

import com.app.quickpear.network.FrameConnection
import com.app.quickpear.protocol.ProtocolConstants
import com.app.quickpear.security.*
import kotlinx.coroutines.*

internal class CloudPairing(private val hub: MqttFrameHub, private val identity: DeviceIdentity,
    private val name: () -> String, private val trust: TrustStore) {
    suspend fun host(code: String, confirm: suspend (String, String) -> Boolean): Boolean {
        val pending = CompletableDeferred<MqttFrameHub.MqttConnection>()
        hub.listenPair(code) { if (!pending.complete(it)) it.close() }
        var connection: MqttFrameHub.MqttConnection? = null
        return try {
            withTimeout(120_000) {
                val accepted = pending.await().also { connection = it }
                val result = Handshake.respond(accepted, identity, name())
                require(result.purpose == SessionPurpose.PAIR && result.peer.deviceId == accepted.remoteId)
                finish(accepted, result, confirm)
            }
        } finally { connection?.close(); hub.stopPair(code) }
    }
    suspend fun join(code: String, confirm: suspend (String, String) -> Boolean): Boolean {
        require(code.matches(Regex("[0-9]{6}")))
        val connection = hub.open("", code)
        return try { withTimeout(120_000) {
            val result = Handshake.initiate(connection, identity, name(), SessionPurpose.PAIR)
            require(result.peer.deviceId == connection.remoteId)
            finish(connection, result, confirm)
        } } finally { connection.close() }
    }
    private suspend fun finish(connection: FrameConnection, result: HandshakeResult, confirm: suspend (String, String) -> Boolean): Boolean {
        val accepted = confirm(result.peer.name, result.sasCode)
        connection.sendFrame(ProtocolConstants.MSG_PAIR_CONFIRM, byteArrayOf(if (accepted) 1 else 0))
        val header = connection.readHeader(120_000)
        require(header.messageType == ProtocolConstants.MSG_PAIR_CONFIRM)
        val remote = connection.readFramePayload(header.payloadLength, 1).contentEquals(byteArrayOf(1))
        if (accepted && remote) trust.add(result.peer.deviceId, result.peer.name, result.peer.publicKey)
        return accepted && remote
    }
}
