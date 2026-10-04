package com.app.quickpear.session

import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.engine.TransferEngine
import com.app.quickpear.network.KtorSocketClient
import com.app.quickpear.network.SocketConnection
import com.app.quickpear.protocol.ProtocolConstants
import com.app.quickpear.security.DeviceIdentity
import com.app.quickpear.security.Handshake
import com.app.quickpear.security.HandshakeException
import com.app.quickpear.security.HandshakeResult
import com.app.quickpear.security.PeerIdentity
import com.app.quickpear.security.SessionPurpose
import com.app.quickpear.security.TrustStore
import kotlinx.coroutines.withTimeout
import okio.Path
import com.app.quickpear.domain.FileMetadata

enum class ApprovalDecision { ACCEPT, ACCEPT_ALWAYS, REJECT }

/**
 * Asks the user (notification on mobile, popup on desktop) about incoming requests.
 * Implementations must resolve to a decision in bounded time (e.g. auto-reject after 60 s).
 */
interface ApprovalHandler {
    suspend fun onTransferRequest(peer: PeerIdentity, request: MetadataRequest): ApprovalDecision

    /** Both users compare [sasCode]; return true when it matches on this device. */
    suspend fun onPairingRequest(peer: PeerIdentity, sasCode: String): Boolean
}

private const val PAIR_CONFIRM_TIMEOUT_MS = 120_000L

/** Exchanges PAIR_CONFIRM with the peer; both sides must accept for the device to be trusted. */
private suspend fun runPairing(
    connection: SocketConnection,
    result: HandshakeResult,
    trustStore: TrustStore,
    confirm: suspend (PeerIdentity, String) -> Boolean
): Boolean {
    val localAccepted = confirm(result.peer, result.sasCode)
    connection.sendFrame(ProtocolConstants.MSG_PAIR_CONFIRM, byteArrayOf(if (localAccepted) 1 else 0))

    val header = withTimeout(PAIR_CONFIRM_TIMEOUT_MS) { connection.readHeader() }
    if (header.messageType != ProtocolConstants.MSG_PAIR_CONFIRM) {
        throw HandshakeException("Expected PAIR_CONFIRM but got ${header.messageType}")
    }
    val peerAccepted = connection.readFramePayload(header.payloadLength, 16).firstOrNull() == 1.toByte()

    if (localAccepted && peerAccepted) {
        val remoteIp = connection.remoteAddress.removePrefix("/").substringBefore(":")
        val remotePort = connection.remoteAddress.substringAfter(":", "8888").toIntOrNull() ?: 8888
        trustStore.add(result.peer.deviceId, result.peer.name, result.peer.publicKey, remoteIp, remotePort)
        return true
    }
    return false
}

/** Server side: authenticates every incoming connection, then pairs or receives files. */
class IncomingConnectionHandler(
    private val identity: DeviceIdentity,
    private val deviceName: () -> String,
    private val trustStore: TrustStore,
    private val engine: TransferEngine,
    private val approval: ApprovalHandler,
    private val onPeerSeen: ((PeerIdentity, String, Int) -> Unit)? = null,
    private val onTextReceived: ((PeerIdentity, String) -> Unit)? = null
) {
    /** Returns the handshake result, or null when the peer failed authentication. */
    suspend fun handle(connection: SocketConnection): HandshakeResult? {
        val handshake = try {
            Handshake.respond(connection, identity, deviceName())
        } catch (_: HandshakeException) {
            return null
        }

        val remoteIp = connection.remoteAddress.removePrefix("/").substringBefore(":")
        val remotePort = connection.remoteAddress.substringAfter(":", "8888").toIntOrNull() ?: 8888

        onPeerSeen?.invoke(handshake.peer, remoteIp, remotePort)

        when (handshake.purpose) {
            SessionPurpose.PROBE -> {
                if (trustStore.isTrusted(handshake.peer.deviceId)) {
                    trustStore.updateLastKnownIp(handshake.peer.deviceId, remoteIp, remotePort)
                }
                connection.sendFrame(ProtocolConstants.MSG_PROBE_ACK, byteArrayOf(1))
            }
            SessionPurpose.PAIR -> runPairing(connection, handshake, trustStore) { peer, sas ->
                approval.onPairingRequest(peer, sas)
            }
            SessionPurpose.TEXT_SHARE -> {
                if (trustStore.isTrusted(handshake.peer.deviceId)) {
                    trustStore.updateLastKnownIp(handshake.peer.deviceId, remoteIp, remotePort)
                }
                val header = connection.readHeader()
                if (header.messageType == ProtocolConstants.MSG_TEXT_SHARE) {
                    val textBytes = connection.readFramePayload(header.payloadLength, 65536)
                    val text = textBytes.decodeToString()
                    onTextReceived?.invoke(handshake.peer, text)
                    connection.sendFrame(ProtocolConstants.MSG_TEXT_SHARE_ACK, byteArrayOf(1))
                }
            }
            SessionPurpose.TRANSFER -> engine.receiveFiles(connection) { request ->
                if (trustStore.isTrusted(handshake.peer.deviceId)) {
                    trustStore.updateLastKnownIp(handshake.peer.deviceId, remoteIp, remotePort)
                    true
                } else {
                    when (approval.onTransferRequest(handshake.peer, request)) {
                        ApprovalDecision.ACCEPT -> true
                        ApprovalDecision.ACCEPT_ALWAYS -> {
                            trustStore.add(handshake.peer.deviceId, handshake.peer.name, handshake.peer.publicKey, remoteIp, remotePort)
                            true
                        }
                        ApprovalDecision.REJECT -> false
                    }
                }
            }
        }
        return handshake
    }
}

/** Client side: connects, authenticates, then pairs or sends files. */
class PeerClient(
    private val identity: DeviceIdentity,
    private val deviceName: () -> String,
    private val engine: TransferEngine,
    private val socketClient: KtorSocketClient = KtorSocketClient()
) {
    /**
     * @param expectedPeerId when set, the connection fails unless the remote device proves it owns this id
     * (protects trusted-device sends from an impostor answering on the same IP).
     * @throws HandshakeException if authentication fails.
     */
    suspend fun sendFiles(
        host: String,
        port: Int,
        sessionId: String,
        files: Map<FileMetadata, Path>,
        expectedPeerId: String? = null
    ) {
        val connection = socketClient.connect(host, port)
        try {
            Handshake.initiate(connection, identity, deviceName(), SessionPurpose.TRANSFER, expectedPeerId)
            engine.sendFiles(connection, sessionId, files)
        } finally {
            connection.close()
        }
    }

    /**
     * Sends a text or clipboard message directly to a peer.
     */
    suspend fun sendText(
        host: String,
        port: Int,
        text: String,
        expectedPeerId: String? = null
    ): Boolean {
        val connection = socketClient.connect(host, port)
        try {
            Handshake.initiate(connection, identity, deviceName(), SessionPurpose.TEXT_SHARE, expectedPeerId)
            val bytes = text.encodeToByteArray()
            connection.sendFrame(ProtocolConstants.MSG_TEXT_SHARE, bytes)
            val ackHeader = withTimeout(10_000L) { connection.readHeader() }
            return ackHeader.messageType == ProtocolConstants.MSG_TEXT_SHARE_ACK
        } finally {
            connection.close()
        }
    }

    /** @return true if both users confirmed the code and the peer was added to [trustStore]. */
    suspend fun pair(
        host: String,
        port: Int,
        trustStore: TrustStore,
        expectedPeerId: String? = null,
        confirm: suspend (PeerIdentity, String) -> Boolean
    ): Boolean {
        val connection = socketClient.connect(host, port)
        try {
            val handshake = Handshake.initiate(connection, identity, deviceName(), SessionPurpose.PAIR, expectedPeerId)
            return runPairing(connection, handshake, trustStore, confirm)
        } finally {
            connection.close()
        }
    }
}
