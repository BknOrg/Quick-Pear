package com.app.quickpear.signaling

import com.app.quickpear.domain.*
import com.app.quickpear.engine.TransferEngine
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.network.*
import com.app.quickpear.protocol.ProtocolConstants
import com.app.quickpear.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeHex

@Serializable
internal data class CloudControl(val action: String = "", val text: String = "", val ip: String = "",
    val port: Int = 8888, val deviceType: DeviceType = DeviceType.UNKNOWN,
    val ssid: String = "", val password: String = "", val accepted: Boolean = false,
    val token: String = "")

/** Coordinates authenticated sessions. MQTT never dispatches an application payload directly. */
class SignalingClient(
    private val identity: DeviceIdentity,
    private val deviceNameProvider: () -> String,
    private val deviceType: DeviceType,
    private val trustStore: TrustStore,
    private val partFileManager: PartFileManager? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val localPort: Int = 8888,
    val relayHost: String? = null,
    val relayPort: Int = com.app.quickpear.relay.RelayProtocol.DEFAULT_RELAY_PORT,
    private val onCloudTextReceived: ((PeerIdentity, String) -> Unit)? = null,
    private val onProgressUpdate: ((TransferProgress?) -> Unit)? = null,
    private val onCloudFileOfferReceived: (suspend (String, Int, Long) -> Boolean)? = null,
    private val json: Json = Json { ignoreUnknownKeys = false },
    val mqttClient: MessageTransport = MqttClient()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var worker: Job? = null
    private val hub = MqttFrameHub(identity.deviceId, mqttClient)
    private val presence = CloudPresenceRegistry()
    private val engines = java.util.concurrent.ConcurrentHashMap.newKeySet<TransferEngine>()
    fun cancelTransfers(reason: String = "Transfer cancelled by user") { engines.forEach { it.cancelTransfer(reason) } }
    val onlinePeers: StateFlow<List<PeerDevice>> = presence.devices
    val publicIp: StateFlow<String?> = MutableStateFlow(null)
    val isSignalingActive: StateFlow<Boolean> = mqttClient.isConnected
    var onHotspotOfferReceived: (suspend (String, String, String, String, Int) -> Pair<Boolean, String>)? = null

    fun start() {
        if (worker != null) return
        mqttClient.start()
        hub.onIncoming = { connection -> scope.launch {
            try { incoming(connection) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { SecurityLog.event("cloud_session_failed") }
            finally { connection.close() }
        } }
        worker = scope.launch {
            mqttClient.isConnected.first { it }
            hub.start()
            while (isActive) {
                if (mqttClient.isConnected.value) {
                    trustStore.devices.value.forEach { trusted -> launch {
                        try { session(trusted.id, SessionPurpose.PROBE) { connection ->
                            sendControl(connection, localPresence())
                            val reply = readControl(connection)
                            presence.seen(trusted.id, trusted.name, reply)
                        } } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { SecurityLog.event("presence_probe_failed") }
                    } }
                }
                presence.expire()
                delay(20_000)
            }
        }
    }
    fun stop() { worker?.cancel(); worker = null; scope.coroutineContext.cancelChildren(); hub.stop(); mqttClient.stop(); presence.clear() }
    private fun localPresence() = CloudControl(action = "PRESENCE", ip = LocalIpResolver.getLocalIpAddress(), port = localPort, deviceType = deviceType)
    private suspend fun sendControl(connection: FrameConnection, control: CloudControl) =
        connection.sendFrame(ProtocolConstants.MSG_CONTROL, json.encodeToString(CloudControl.serializer(), control).encodeToByteArray())
    private suspend fun readControl(connection: FrameConnection): CloudControl {
        val header = connection.readHeader()
        require(header.messageType == ProtocolConstants.MSG_CONTROL)
        return json.decodeFromString(CloudControl.serializer(), connection.readFramePayload(header.payloadLength, 8192).decodeToString())
    }
    private suspend fun incoming(connection: MqttFrameHub.MqttConnection) {
        val result = Handshake.respond(connection, identity, deviceNameProvider())
        require(result.peer.deviceId == connection.remoteId) { "Routing identity mismatch" }
        when (result.purpose) {
            SessionPurpose.PROBE -> {
                require(trustStore.isTrusted(result.peer.deviceId))
                val request = readControl(connection)
                require(request.action == "PRESENCE")
                presence.seen(result.peer.deviceId, result.peer.name, request)
                sendControl(connection, localPresence())
            }
            SessionPurpose.TEXT_SHARE -> {
                require(trustStore.isTrusted(result.peer.deviceId))
                val header = connection.readHeader()
                require(header.messageType == ProtocolConstants.MSG_TEXT_SHARE)
                val text = connection.readFramePayload(header.payloadLength, 65536).decodeToString()
                onCloudTextReceived?.invoke(result.peer, text)
                connection.sendFrame(ProtocolConstants.MSG_TEXT_SHARE_ACK, byteArrayOf(1))
            }
            SessionPurpose.TRANSFER -> receiveFiles(connection, result.peer)
            SessionPurpose.HOTSPOT -> {
                require(trustStore.isTrusted(result.peer.deviceId))
                val request = readControl(connection)
                require(request.action == "HOTSPOT" && request.port in 1..65535)
                val reply = onHotspotOfferReceived?.invoke(result.peer.name, request.ssid, request.password, request.ip, request.port) ?: (false to "")
                sendControl(connection, CloudControl(action = "HOTSPOT_REPLY", accepted = reply.first, ip = reply.second))
            }
            SessionPurpose.RELAY_TRANSFER -> {
                require(trustStore.isTrusted(result.peer.deviceId))
                val request = readControl(connection)
                require(request.action == "RELAY" && request.port in 1..65535)
                val token = request.token.decodeHex().toByteArray()
                val socket = com.app.quickpear.relay.BlindRelayClient().connectAndPair(request.ip, request.port, token, 2)
                try {
                    val remote = Handshake.respond(socket, identity, deviceNameProvider())
                    require(remote.peer.deviceId == result.peer.deviceId && remote.purpose == SessionPurpose.TRANSFER)
                    receiveFiles(socket, remote.peer)
                } finally { socket.close() }
            }
            SessionPurpose.PAIR -> error("Use pairing rendezvous")
        }
    }
    private suspend fun receiveFiles(connection: FrameConnection, peer: PeerIdentity) {
        val manager = partFileManager ?: error("Receiving unavailable")
        val engine = TransferEngine(manager, fileSystem).also { engines.add(it) }
        coroutineScope {
            val progress = launch { engine.progressState.collect { onProgressUpdate?.invoke(it) } }
            try { engine.receiveFiles(connection) { request ->
                trustStore.isTrusted(peer.deviceId) || (onCloudFileOfferReceived?.invoke(peer.name, request.files.size, request.files.sumOf { it.fileSizeBytes }) ?: false)
            } } finally { onProgressUpdate?.invoke(engine.progressState.value); progress.cancel(); engines.remove(engine) }
        }
    }
    private suspend fun <T> session(peerId: String, purpose: SessionPurpose, block: suspend (FrameConnection) -> T): T {
        val connection = hub.open(peerId)
        try {
            Handshake.initiate(connection, identity, deviceNameProvider(), purpose, peerId)
            return block(connection)
        } finally { connection.close() }
    }
    suspend fun sendText(targetPeer: PeerDevice, text: String): Boolean = attempt {
        session(targetPeer.id, SessionPurpose.TEXT_SHARE) { connection ->
            require(text.encodeToByteArray().size <= 65536)
            connection.sendFrame(ProtocolConstants.MSG_TEXT_SHARE, text.encodeToByteArray())
            val header = connection.readHeader()
            require(header.messageType == ProtocolConstants.MSG_TEXT_SHARE_ACK)
            require(connection.readFramePayload(header.payloadLength, 1).contentEquals(byteArrayOf(1)))
            true
        }
    }
    suspend fun probeHotspotHandover(targetPeer: PeerDevice, ssid: String, pass: String, hostIp: String,
        port: Int, sessionId: String, timeoutMillis: Long = 5000): Pair<Boolean, String> = try {
        withTimeout(timeoutMillis) { session(targetPeer.id, SessionPurpose.HOTSPOT) { connection ->
            sendControl(connection, CloudControl("HOTSPOT", ip = hostIp, port = port, ssid = ssid, password = pass))
            val reply = readControl(connection)
            require(reply.action == "HOTSPOT_REPLY")
            reply.accepted to reply.ip
        } }
    } catch (e: TimeoutCancellationException) { false to "" }
      catch (e: CancellationException) { throw e }
      catch (_: Exception) { SecurityLog.event("hotspot_failed"); false to "" }

    suspend fun sendFiles(targetPeer: PeerDevice, filesMap: Map<FileMetadata, Path>, sessionId: String = com.app.quickpear.session.TransferSessionIds.forFiles(targetPeer.id, filesMap.keys)): Boolean {
        val manager = partFileManager ?: PartFileManager(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "quickpear-send")
        suspend fun transfer(connection: FrameConnection, cloud: Boolean): Boolean = coroutineScope {
            val engine = TransferEngine(manager, fileSystem).also { engines.add(it) }
            val files = if (!cloud) filesMap else filesMap.entries.associate { (meta, path) ->
                FileMetadata.create(meta.fileId, meta.fileName, meta.fileSizeBytes, 65536, meta.expectedSha256, meta.relativePath) to path
            }
            val progress = launch { engine.progressState.collect { onProgressUpdate?.invoke(it) } }
            try { engine.sendFiles(connection, sessionId, files); engine.progressState.value?.status == TransferStatus.COMPLETED }
            finally { onProgressUpdate?.invoke(engine.progressState.value); progress.cancel(); engines.remove(engine) }
        }
        if (!relayHost.isNullOrBlank()) {
            val success = attempt { session(targetPeer.id, SessionPurpose.RELAY_TRANSFER) { control ->
                val token = Crypto.randomBytes(16)
                sendControl(control, CloudControl("RELAY", ip = relayHost, port = relayPort, token = token.toByteString().hex()))
                val connection = com.app.quickpear.relay.BlindRelayClient().connectAndPair(relayHost, relayPort, token, 1)
                try { Handshake.initiate(connection, identity, deviceNameProvider(), SessionPurpose.TRANSFER, targetPeer.id); transfer(connection, false) }
                finally { connection.close() }
            } }
            if (success) return true
        }
        return attempt { session(targetPeer.id, SessionPurpose.TRANSFER) { transfer(it, true) } }
    }
    suspend fun startCloudPairing(code: String, onSasVerification: suspend (String, String) -> Boolean): Boolean =
        CloudPairing(hub, identity, deviceNameProvider, trustStore).host(code, onSasVerification)
    suspend fun joinCloudPairing(code: String, onSasVerification: suspend (String, String) -> Boolean): Boolean =
        CloudPairing(hub, identity, deviceNameProvider, trustStore).join(code, onSasVerification)
    private suspend fun attempt(block: suspend () -> Boolean): Boolean = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { SecurityLog.event("cloud_operation_failed"); false }
}
