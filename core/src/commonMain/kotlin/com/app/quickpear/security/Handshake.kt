package com.app.quickpear.security

import com.app.quickpear.network.SocketConnection
import com.app.quickpear.protocol.ProtocolConstants
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

enum class SessionPurpose { TRANSFER, PAIR, PROBE, TEXT_SHARE }

/** Authenticated identity of the remote side, proven by a valid signature during the handshake. */
class PeerIdentity(
    val deviceId: String,
    val name: String,
    val publicKey: ByteArray
)

class HandshakeResult(
    val peer: PeerIdentity,
    val purpose: SessionPurpose,
    /** 6-digit Short Authentication String; identical on both sides unless a man-in-the-middle is present. */
    val sasCode: String
)

class HandshakeException(message: String) : Exception(message)

@Serializable
internal class HelloMessage(
    val version: Int,
    val name: String,
    val publicKey: String,
    val nonce: String,
    val purpose: String
)

@Serializable
internal class HelloReply(
    val name: String,
    val publicKey: String,
    val nonce: String,
    val signature: String
)

@Serializable
internal class AuthMessage(val signature: String)

/**
 * Mutually authenticated handshake run at the start of every connection:
 *
 * 1. initiator -> HANDSHAKE_REQ  (name, public key, nonce, purpose)
 * 2. responder -> HANDSHAKE_RESP (name, public key, nonce, signature over transcript)
 * 3. initiator -> HANDSHAKE_AUTH (signature over transcript)
 *
 * Both signatures cover both nonces, both public keys and the purpose, so they cannot be replayed
 * and the purpose cannot be tampered with. Device ids are derived from the public keys, so a
 * verified signature proves ownership of the claimed id.
 */
object Handshake {
    private const val NONCE_SIZE = 32
    private const val MAX_NAME = 64
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun initiate(
        connection: SocketConnection,
        identity: DeviceIdentity,
        deviceName: String,
        purpose: SessionPurpose,
        expectedPeerId: String? = null
    ): HandshakeResult = guard {
        val clientNonce = Crypto.randomBytes(NONCE_SIZE)
        val hello = HelloMessage(
            version = ProtocolConstants.PROTOCOL_VERSION,
            name = cleanName(deviceName),
            publicKey = identity.publicKey.toBase64(),
            nonce = clientNonce.toBase64(),
            purpose = purpose.name
        )
        connection.sendFrame(ProtocolConstants.MSG_HANDSHAKE_REQ, json.encodeToString(HelloMessage.serializer(), hello).encodeToByteArray())

        val header = connection.readHeader()
        if (header.messageType != ProtocolConstants.MSG_HANDSHAKE_RESP) {
            throw HandshakeException("Expected HANDSHAKE_RESP but got ${header.messageType}")
        }
        val reply = json.decodeFromString(
            HelloReply.serializer(),
            connection.readFramePayload(header.payloadLength, ProtocolConstants.MAX_HANDSHAKE_PAYLOAD).decodeToString()
        )
        val serverPub = reply.publicKey.fromBase64() ?: throw HandshakeException("Invalid public key")
        val serverNonce = reply.nonce.fromBase64()?.takeIf { it.size == NONCE_SIZE } ?: throw HandshakeException("Invalid nonce")
        val signature = reply.signature.fromBase64() ?: throw HandshakeException("Invalid signature encoding")

        val respData = signedData("QP-RESP-v1", clientNonce, serverNonce, identity.publicKey, serverPub, purpose)
        if (!DeviceIdentity.verify(serverPub, respData, signature)) {
            throw HandshakeException("Peer signature verification failed")
        }
        val peerId = DeviceIdentity.idFromPublicKey(serverPub)
        if (expectedPeerId != null && expectedPeerId != peerId) {
            throw HandshakeException("Connected device is not the expected device")
        }

        val authData = signedData("QP-INIT-v1", clientNonce, serverNonce, identity.publicKey, serverPub, purpose)
        val auth = AuthMessage(identity.sign(authData).toBase64())
        connection.sendFrame(ProtocolConstants.MSG_HANDSHAKE_AUTH, json.encodeToString(AuthMessage.serializer(), auth).encodeToByteArray())

        HandshakeResult(
            peer = PeerIdentity(peerId, cleanName(reply.name), serverPub),
            purpose = purpose,
            sasCode = sas(identity.publicKey, serverPub, clientNonce, serverNonce)
        )
    }

    suspend fun respond(
        connection: SocketConnection,
        identity: DeviceIdentity,
        deviceName: String
    ): HandshakeResult = guard {
        val header = connection.readHeader()
        if (header.messageType != ProtocolConstants.MSG_HANDSHAKE_REQ) {
            throw HandshakeException("Expected HANDSHAKE_REQ but got ${header.messageType}")
        }
        val hello = json.decodeFromString(
            HelloMessage.serializer(),
            connection.readFramePayload(header.payloadLength, ProtocolConstants.MAX_HANDSHAKE_PAYLOAD).decodeToString()
        )
        if (hello.version != ProtocolConstants.PROTOCOL_VERSION) {
            throw HandshakeException("Unsupported protocol version ${hello.version}")
        }
        val clientPub = hello.publicKey.fromBase64() ?: throw HandshakeException("Invalid public key")
        val clientNonce = hello.nonce.fromBase64()?.takeIf { it.size == NONCE_SIZE } ?: throw HandshakeException("Invalid nonce")
        val purpose = SessionPurpose.entries.firstOrNull { it.name == hello.purpose }
            ?: throw HandshakeException("Unknown session purpose")

        val serverNonce = Crypto.randomBytes(NONCE_SIZE)
        val respData = signedData("QP-RESP-v1", clientNonce, serverNonce, clientPub, identity.publicKey, purpose)
        val reply = HelloReply(
            name = cleanName(deviceName),
            publicKey = identity.publicKey.toBase64(),
            nonce = serverNonce.toBase64(),
            signature = identity.sign(respData).toBase64()
        )
        connection.sendFrame(ProtocolConstants.MSG_HANDSHAKE_RESP, json.encodeToString(HelloReply.serializer(), reply).encodeToByteArray())

        val authHeader = connection.readHeader()
        if (authHeader.messageType != ProtocolConstants.MSG_HANDSHAKE_AUTH) {
            throw HandshakeException("Expected HANDSHAKE_AUTH but got ${authHeader.messageType}")
        }
        val auth = json.decodeFromString(
            AuthMessage.serializer(),
            connection.readFramePayload(authHeader.payloadLength, ProtocolConstants.MAX_HANDSHAKE_PAYLOAD).decodeToString()
        )
        val authSig = auth.signature.fromBase64() ?: throw HandshakeException("Invalid signature encoding")
        val authData = signedData("QP-INIT-v1", clientNonce, serverNonce, clientPub, identity.publicKey, purpose)
        if (!DeviceIdentity.verify(clientPub, authData, authSig)) {
            throw HandshakeException("Peer signature verification failed")
        }

        HandshakeResult(
            peer = PeerIdentity(DeviceIdentity.idFromPublicKey(clientPub), cleanName(hello.name), clientPub),
            purpose = purpose,
            sasCode = sas(clientPub, identity.publicKey, clientNonce, serverNonce)
        )
    }

    private suspend fun guard(block: suspend () -> HandshakeResult): HandshakeResult = try {
        block()
    } catch (e: SerializationException) {
        throw HandshakeException("Malformed handshake message")
    } catch (e: IllegalArgumentException) {
        throw HandshakeException(e.message ?: "Invalid handshake message")
    }

    private fun signedData(
        tag: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientPub: ByteArray,
        serverPub: ByteArray,
        purpose: SessionPurpose
    ): ByteArray = concatBytes(
        tag.encodeToByteArray(), clientNonce, serverNonce, clientPub, serverPub, purpose.name.encodeToByteArray()
    )

    private fun sas(clientPub: ByteArray, serverPub: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): String {
        val hash = concatBytes("QP-SAS-v1".encodeToByteArray(), clientPub, serverPub, clientNonce, serverNonce)
            .toByteString().sha256().toByteArray()
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (hash[i].toLong() and 0xFF)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun calculateSas(pubA: ByteArray, pubB: ByteArray): String {
        val (first, second) = if (pubA.toByteString().hex() < pubB.toByteString().hex()) Pair(pubA, pubB) else Pair(pubB, pubA)
        val hash = concatBytes("QP-SAS-CLOUD-v1".encodeToByteArray(), first, second)
            .toByteString().sha256().toByteArray()
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (hash[i].toLong() and 0xFF)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    private fun cleanName(name: String): String =
        name.filter { it.code >= 32 }.trim().take(MAX_NAME).ifEmpty { "Unknown device" }
}
