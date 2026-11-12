package com.app.quickpear.security

import com.app.quickpear.network.FrameConnection
import com.app.quickpear.protocol.ProtocolConstants
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString

enum class SessionPurpose { TRANSFER, PAIR, PROBE, TEXT_SHARE, HOTSPOT, RELAY_TRANSFER }

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
internal data class HelloMessage(
    val version: Int, val name: String, val publicKey: String, val ephemeral: String = "",
    val nonce: String, val purpose: String, val binding: String = "", val recipient: String = ""
)
@Serializable
internal data class HelloReply(val version: Int, val name: String, val publicKey: String,
    val ephemeral: String, val nonce: String, val signature: String = "")
@Serializable
internal data class HandshakeCommit(val version: Int, val commitment: String)
@Serializable
internal data class AuthMessage(val signature: String)

/** One authenticated ECDHE handshake for LAN, MQTT and relay transports. */
object Handshake {
    private val json = Json { ignoreUnknownKeys = false }
    private fun cleanName(name: String) = name.filter { it.code >= 32 }.trim().take(64).ifEmpty { "Unknown device" }
    private fun transcript(hello: HelloMessage, reply: HelloReply): ByteArray =
        okio.Buffer().apply {
            val a = json.encodeToString(HelloMessage.serializer(), hello).encodeToByteArray()
            val b = json.encodeToString(HelloReply.serializer(), reply.copy(signature = "")).encodeToByteArray()
            writeUtf8("QuickPear-v2"); writeInt(a.size); write(a); writeInt(b.size); write(b)
        }.readByteArray()
    private fun commitment(bytes: ByteArray) = bytes.toByteString().sha256().hex()
    private suspend fun sendCommit(connection: FrameConnection, type: Byte, bytes: ByteArray) {
        val message = HandshakeCommit(2, commitment(bytes))
        connection.sendFrame(type, json.encodeToString(HandshakeCommit.serializer(), message).encodeToByteArray())
    }
    private suspend fun readCommit(connection: FrameConnection, type: Byte): String {
        val commit = json.decodeFromString(HandshakeCommit.serializer(), payload(connection, type).decodeToString())
        require(commit.version == 2 && commit.commitment.matches(Regex("[a-f0-9]{64}"))) { "Protocol v2 required; update both devices" }
        return commit.commitment
    }
    private fun decode(value: String, nonce: Boolean = false): ByteArray {
        val bytes = value.fromBase64() ?: throw HandshakeException("Invalid handshake encoding")
        require(if (nonce) bytes.size == 32 else bytes.size in 64..256) { "Invalid handshake field" }
        return bytes
    }
    private suspend fun payload(connection: FrameConnection, type: Byte): ByteArray {
        val header = connection.readHeader()
        require(header.messageType == type) { "Protocol v2 required; update both devices" }
        return connection.readFramePayload(header.payloadLength, ProtocolConstants.MAX_HANDSHAKE_PAYLOAD)
    }
    private fun sas(data: ByteArray): String {
        val hash = data.toByteString().sha256().toByteArray()
        var value = 0L
        for (i in 0..3) value = (value shl 8) or (hash[i].toLong() and 255)
        return (value % 1_000_000).toString().padStart(6, '0')
    }
    // Kept for callers displaying key fingerprints; pairing uses the fresh transcript SAS.
    fun calculateSas(pubA: ByteArray, pubB: ByteArray): String =
        sas(listOf(pubA.toByteString().hex(), pubB.toByteString().hex()).sorted().joinToString(":").encodeToByteArray())

    suspend fun initiate(connection: FrameConnection, identity: DeviceIdentity, deviceName: String,
        purpose: SessionPurpose, expectedPeerId: String? = null): HandshakeResult = guard {
        val ephemeral = Crypto.generateKeyPair()
        val hello = HelloMessage(2, cleanName(deviceName), identity.publicKey.toBase64(), ephemeral.publicKey.toBase64(),
            Crypto.randomBytes(32).toBase64(), purpose.name, connection.binding, expectedPeerId ?: "")
        val helloBytes = json.encodeToString(HelloMessage.serializer(), hello).encodeToByteArray()
        sendCommit(connection, ProtocolConstants.MSG_HANDSHAKE_REQ, helloBytes)
        val remoteCommitment = readCommit(connection, ProtocolConstants.MSG_HANDSHAKE_COMMIT)
        connection.sendFrame(ProtocolConstants.MSG_HANDSHAKE_REVEAL, helloBytes)
        val reply = json.decodeFromString(HelloReply.serializer(), payload(connection, ProtocolConstants.MSG_HANDSHAKE_RESP).decodeToString())
        require(commitment(json.encodeToString(HelloReply.serializer(), reply.copy(signature = "")).encodeToByteArray()) == remoteCommitment) { "Handshake commitment mismatch" }
        require(reply.version == 2) { "Protocol v2 required; update both devices" }
        val publicKey = decode(reply.publicKey)
        val peerId = DeviceIdentity.idFromPublicKey(publicKey)
        require(expectedPeerId == null || expectedPeerId == peerId) { "Unexpected peer identity" }
        decode(reply.nonce, true)
        val data = transcript(hello, reply)
        require(DeviceIdentity.verify(publicKey, "responder".encodeToByteArray() + data, decode(reply.signature))) { "Invalid peer signature" }
        val secret = Crypto.agree(ephemeral.privateKey, decode(reply.ephemeral))
        val auth = AuthMessage(identity.sign("initiator".encodeToByteArray() + data).toBase64())
        connection.sendFrame(ProtocolConstants.MSG_HANDSHAKE_AUTH, json.encodeToString(AuthMessage.serializer(), auth).encodeToByteArray())
        connection.activate(SecureSession(secret, data, true), peerId)
        HandshakeResult(PeerIdentity(peerId, cleanName(reply.name), publicKey), purpose, sas(data))
    }

    suspend fun respond(connection: FrameConnection, identity: DeviceIdentity, deviceName: String): HandshakeResult = guard {
        // Commit both fresh contributions before either one is revealed. This prevents SAS grinding.
        val remoteCommitment = readCommit(connection, ProtocolConstants.MSG_HANDSHAKE_REQ)
        val ephemeral = Crypto.generateKeyPair()
        val reply = HelloReply(2, cleanName(deviceName), identity.publicKey.toBase64(), ephemeral.publicKey.toBase64(), Crypto.randomBytes(32).toBase64())
        val replyBytes = json.encodeToString(HelloReply.serializer(), reply).encodeToByteArray()
        sendCommit(connection, ProtocolConstants.MSG_HANDSHAKE_COMMIT, replyBytes)
        val helloBytes = payload(connection, ProtocolConstants.MSG_HANDSHAKE_REVEAL)
        require(commitment(helloBytes) == remoteCommitment) { "Handshake commitment mismatch" }
        val hello = json.decodeFromString(HelloMessage.serializer(), helloBytes.decodeToString())
        require(hello.version == 2) { "Protocol v2 required; update both devices" }
        require(hello.binding == connection.binding && (hello.recipient.isEmpty() || hello.recipient == identity.deviceId)) { "Wrong session recipient" }
        val publicKey = decode(hello.publicKey)
        decode(hello.nonce, true)
        val purpose = SessionPurpose.entries.firstOrNull { it.name == hello.purpose } ?: error("Unknown purpose")
        val data = transcript(hello, reply)
        val signature = identity.sign("responder".encodeToByteArray() + data).toBase64()
        connection.sendFrame(ProtocolConstants.MSG_HANDSHAKE_RESP, json.encodeToString(HelloReply.serializer(), reply.copy(signature = signature)).encodeToByteArray())
        val auth = json.decodeFromString(AuthMessage.serializer(), payload(connection, ProtocolConstants.MSG_HANDSHAKE_AUTH).decodeToString())
        require(DeviceIdentity.verify(publicKey, "initiator".encodeToByteArray() + data, decode(auth.signature))) { "Invalid peer signature" }
        val secret = Crypto.agree(ephemeral.privateKey, decode(hello.ephemeral))
        val peerId = DeviceIdentity.idFromPublicKey(publicKey)
        connection.activate(SecureSession(secret, data, false), peerId)
        HandshakeResult(PeerIdentity(peerId, cleanName(hello.name), publicKey), purpose, sas(data))
    }

    private suspend fun guard(block: suspend () -> HandshakeResult): HandshakeResult = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.withTimeout(30_000) { block() } }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        SecurityLog.event("handshake_timeout")
        throw HandshakeException("Authentication timed out")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        SecurityLog.event("handshake_rejected")
        throw HandshakeException("Protocol v2 authentication failed; update both devices if necessary")
    }
}
