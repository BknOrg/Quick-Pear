package com.app.quickpear.signaling

import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.domain.TransferProgress
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.io.FileChunker
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.network.LocalIpResolver
import com.app.quickpear.network.MqttClient
import com.app.quickpear.security.DeviceIdentity
import com.app.quickpear.security.Handshake
import com.app.quickpear.security.PeerIdentity
import com.app.quickpear.security.TrustStore
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.core.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.use

@Serializable
data class PeerPresencePayload(
    val hashedId: String,
    val name: String,
    val publicIp: String,
    val port: Int,
    val deviceType: String,
    val timestamp: Long
)

@Serializable
data class CloudFileMeta(
    val fileId: Int,
    val fileName: String,
    val fileSizeBytes: Long,
    val chunkSizeBytes: Int = 65536,
    val totalChunks: Long,
    val expectedSha256: String = "",
    val relativePath: String = ""
)

@Serializable
data class CloudMessagePayload(
    val action: String,
    val senderId: String,
    val senderName: String,
    val text: String = "",
    val timestamp: Long = 0L,
    val sessionId: String = "",
    val files: List<CloudFileMeta> = emptyList(),
    val fileId: Int = 0,
    val chunkIndex: Long = 0L,
    val totalChunks: Long = 0L,
    val chunkData: String = "",
    val checksum: Long = 0L,
    val status: String = "",
    val errorMessage: String = "",
    val hotspotSsid: String = "",
    val hotspotPass: String = "",
    val hotspotPort: Int = 8888,
    val hotspotHostIp: String = "192.168.43.1"
)

/**
 * Handles cross-network presence, STUN discovery, cloud message & file relaying,
 * and 6-digit remote cloud pairing using open public MQTT brokers without requiring user logins.
 */
class SignalingClient(
    private val identity: DeviceIdentity,
    private val deviceNameProvider: () -> String,
    private val deviceType: DeviceType,
    private val trustStore: TrustStore,
    private val partFileManager: PartFileManager? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val localPort: Int = 8888,
    private val onCloudTextReceived: ((PeerIdentity, String) -> Unit)? = null,
    private val onProgressUpdate: ((TransferProgress?) -> Unit)? = null,
    private val onCloudFileOfferReceived: (suspend (senderName: String, fileCount: Int, totalBytes: Long) -> Boolean)? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var workerJob: Job? = null

    val mqttClient = MqttClient()

    var onHotspotOfferReceived: (suspend (senderName: String, ssid: String, pass: String, hostIp: String, port: Int) -> Pair<Boolean, String>)? = null

    private val _onlinePeers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    private val _onlineDevicesList = MutableStateFlow<List<PeerDevice>>(emptyList())
    val onlinePeers: StateFlow<List<PeerDevice>> = _onlineDevicesList.asStateFlow()

    private val _publicIp = MutableStateFlow<String?>(null)
    val publicIp: StateFlow<String?> = _publicIp.asStateFlow()

    private val sessionMessagesFlow = MutableSharedFlow<CloudMessagePayload>(extraBufferCapacity = 128)

    private val pendingResponses = mutableMapOf<String, CompletableDeferred<CloudMessagePayload>>()
    private val pendingMutex = Mutex()

    private suspend fun registerPendingResponse(key: String): CompletableDeferred<CloudMessagePayload> {
        val deferred = CompletableDeferred<CloudMessagePayload>()
        pendingMutex.withLock {
            pendingResponses[key] = deferred
        }
        return deferred
    }

    private suspend fun completePendingResponse(key: String, payload: CloudMessagePayload) {
        val deferred = pendingMutex.withLock {
            pendingResponses.remove(key)
        }
        deferred?.complete(payload)
    }

    private class ReceiveSession(
        val senderId: String,
        val sessionId: String,
        val files: List<CloudFileMeta>,
        val totalBytes: Long,
        val startTime: Long = System.currentTimeMillis(),
        var bytesReceived: Long = 0L,
        val sessionMutex: Mutex = Mutex()
    )
    private val activeReceiveSessions = mutableMapOf<String, ReceiveSession>()

    val isSignalingActive: StateFlow<Boolean> = mqttClient.isConnected

    val hashedLocalId: String
        get() = ChecksumUtil.calculateSha256(identity.deviceId.encodeToByteArray())

    fun start() {
        if (workerJob != null) return

        mqttClient.start()

        workerJob = scope.launch {
            // First resolve public IP via STUN
            resolvePublicIpViaStun()

            // Subscribe to our incoming private message topic
            val myMsgTopic = "quickpear/msg/$hashedLocalId"
            mqttClient.subscribe(myMsgTopic) { _, payloadBytes ->
                handleIncomingCloudMessage(payloadBytes)
            }

            // Subscribe to presence updates
            mqttClient.subscribe("quickpear/p/+") { topic, payloadBytes ->
                val senderHash = topic.removePrefix("quickpear/p/")
                handleIncomingPresence(senderHash, payloadBytes)
            }

            while (isActive) {
                try {
                    broadcastPresence()
                    cleanStalePeers()
                } catch (_: Exception) {
                }
                delay(10_000L)
            }
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        mqttClient.stop()
        _onlinePeers.value = emptyMap()
        _onlineDevicesList.value = emptyList()
    }

    private suspend fun broadcastPresence() = withContext(Dispatchers.IO) {
        val payload = PeerPresencePayload(
            hashedId = hashedLocalId,
            name = deviceNameProvider(),
            publicIp = _publicIp.value ?: LocalIpResolver.getLocalIpAddress(),
            port = localPort,
            deviceType = deviceType.name,
            timestamp = System.currentTimeMillis()
        )
        val jsonStr = json.encodeToString(PeerPresencePayload.serializer(), payload)
        mqttClient.publish("quickpear/p/$hashedLocalId", jsonStr.encodeToByteArray())
    }

    private fun handleIncomingPresence(senderHash: String, payloadBytes: ByteArray) {
        if (senderHash == hashedLocalId) return
        try {
            val presence = json.decodeFromString(PeerPresencePayload.serializer(), payloadBytes.decodeToString())
            // Check if sender is in our trusted devices
            val trusted = trustStore.devices.value.firstOrNull {
                ChecksumUtil.calculateSha256(it.id.encodeToByteArray()) == senderHash
            }
            if (trusted != null) {
                val devType = DeviceType.entries.firstOrNull { it.name == presence.deviceType } ?: DeviceType.UNKNOWN
                val peer = PeerDevice(
                    id = trusted.id,
                    name = presence.name.ifEmpty { trusted.name },
                    ipAddress = presence.publicIp,
                    port = presence.port,
                    deviceType = devType,
                    connectionType = ConnectionType.CLOUD_P2P,
                    lastSeen = System.currentTimeMillis()
                )
                _onlinePeers.update { current ->
                    val updated = current + (peer.id to peer)
                    _onlineDevicesList.value = updated.values.toList()
                    updated
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleIncomingCloudMessage(payloadBytes: ByteArray) {
        try {
            val msg = json.decodeFromString(CloudMessagePayload.serializer(), payloadBytes.decodeToString())
            sessionMessagesFlow.tryEmit(msg)

            scope.launch {
                when (msg.action) {
                    "FILE_ACCEPT" -> completePendingResponse("accept:${msg.sessionId}", msg)
                    "FILE_CANCEL" -> completePendingResponse("cancel:${msg.sessionId}", msg)
                    "CHUNK_ACK" -> completePendingResponse("ack:${msg.sessionId}:${msg.fileId}:${msg.chunkIndex}", msg)
                    "FILE_COMPLETE" -> completePendingResponse("complete:${msg.sessionId}:${msg.fileId}", msg)
                    "HOTSPOT_ACCEPT", "HOTSPOT_UNREACHABLE" -> completePendingResponse("hotspot:${msg.sessionId}", msg)
                }
            }

            when (msg.action) {
                "HOTSPOT_OFFER" -> {
                    scope.launch {
                        val senderHashed = ChecksumUtil.calculateSha256(msg.senderId.encodeToByteArray())
                        val isTrusted = trustStore.isTrusted(msg.senderId)
                        val (connected, receiverLocalIp) = if (isTrusted && onHotspotOfferReceived != null) {
                            try {
                                onHotspotOfferReceived?.invoke(msg.senderName, msg.hotspotSsid, msg.hotspotPass, msg.hotspotHostIp, msg.hotspotPort) ?: Pair(false, "")
                            } catch (_: Exception) {
                                Pair(false, "")
                            }
                        } else {
                            Pair(false, "")
                        }

                        val replyAction = if (connected) "HOTSPOT_ACCEPT" else "HOTSPOT_UNREACHABLE"
                        val replyMsg = CloudMessagePayload(
                            action = replyAction,
                            senderId = identity.deviceId,
                            senderName = deviceNameProvider(),
                            sessionId = msg.sessionId,
                            status = if (connected) "CONNECTED" else "UNREACHABLE",
                            hotspotHostIp = receiverLocalIp
                        )
                        mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), replyMsg).encodeToByteArray())
                    }
                }
                "TEXT_SHARE" -> {
                    if (trustStore.isTrusted(msg.senderId)) {
                        val peerIdentity = PeerIdentity(
                            deviceId = msg.senderId,
                            name = msg.senderName,
                            publicKey = byteArrayOf()
                        )
                        onCloudTextReceived?.invoke(peerIdentity, msg.text)
                    }
                }
                "FILE_OFFER" -> {
                    val mgr = partFileManager ?: return
                    val totalBytes = msg.files.sumOf { it.fileSizeBytes }
                    val senderHashed = ChecksumUtil.calculateSha256(msg.senderId.encodeToByteArray())

                    scope.launch {
                        val isTrusted = trustStore.isTrusted(msg.senderId)
                        val approved = if (isTrusted) {
                            true
                        } else {
                            onCloudFileOfferReceived?.invoke(msg.senderName, msg.files.size, totalBytes) ?: false
                        }

                        if (!approved) {
                            val cancelMsg = CloudMessagePayload(
                                action = "FILE_CANCEL",
                                senderId = identity.deviceId,
                                senderName = deviceNameProvider(),
                                sessionId = msg.sessionId,
                                errorMessage = "Transfer ditolak oleh penerima"
                            )
                            mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), cancelMsg).encodeToByteArray())
                            return@launch
                        }

                        if (!mgr.hasSufficientStorage(totalBytes)) {
                            val cancelMsg = CloudMessagePayload(
                                action = "FILE_CANCEL",
                                senderId = identity.deviceId,
                                senderName = deviceNameProvider(),
                                sessionId = msg.sessionId,
                                errorMessage = "Penyimpanan tidak mencukupi"
                            )
                            mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), cancelMsg).encodeToByteArray())
                            return@launch
                        }

                        for (f in msg.files) {
                            mgr.ensurePartExists(f.fileName)
                        }

                        activeReceiveSessions[msg.sessionId] = ReceiveSession(
                            senderId = msg.senderId,
                            sessionId = msg.sessionId,
                            files = msg.files,
                            totalBytes = totalBytes
                        )

                        val acceptMsg = CloudMessagePayload(
                            action = "FILE_ACCEPT",
                            senderId = identity.deviceId,
                            senderName = deviceNameProvider(),
                            sessionId = msg.sessionId,
                            status = "ACCEPTED"
                        )
                        mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), acceptMsg).encodeToByteArray())

                        onProgressUpdate?.invoke(
                            TransferProgress(
                                fileId = msg.files.firstOrNull()?.fileId ?: 0,
                                fileName = msg.files.firstOrNull()?.fileName ?: "",
                                bytesTransferred = 0,
                                totalBytes = totalBytes,
                                transferSpeedBytesPerSec = 0,
                                currentChunkIndex = 0,
                                totalChunks = msg.files.firstOrNull()?.totalChunks ?: 0,
                                status = TransferStatus.TRANSFERRING
                            )
                        )
                    }
                }
                "FILE_CHUNK" -> {
                    scope.launch {
                        val mgr = partFileManager ?: return@launch
                        val session = activeReceiveSessions[msg.sessionId] ?: return@launch
                        val fileMeta = session.files.firstOrNull { it.fileId == msg.fileId } ?: return@launch
                        val senderHashed = ChecksumUtil.calculateSha256(msg.senderId.encodeToByteArray())

                        val chunkBytes = msg.chunkData.decodeBase64()?.toByteArray() ?: return@launch
                        val computedChecksum = ChecksumUtil.calculateChunkChecksum(chunkBytes)
                        if (computedChecksum != msg.checksum) {
                            val nack = CloudMessagePayload(
                                action = "CHUNK_ACK",
                                senderId = identity.deviceId,
                                senderName = deviceNameProvider(),
                                sessionId = msg.sessionId,
                                fileId = msg.fileId,
                                chunkIndex = msg.chunkIndex,
                                status = "RETRY"
                            )
                            mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), nack).encodeToByteArray())
                            return@launch
                        }

                        val partPath = mgr.getPartPath(fileMeta.fileName)
                        val targetOffset = msg.chunkIndex * fileMeta.chunkSizeBytes
                        fileSystem.openReadWrite(partPath).use { handle ->
                            handle.write(
                                fileOffset = targetOffset,
                                array = chunkBytes,
                                arrayOffset = 0,
                                byteCount = chunkBytes.size
                            )
                        }
                        mgr.markChunkReceived(fileMeta.fileName, msg.chunkIndex)

                        session.sessionMutex.withLock {
                            session.bytesReceived += chunkBytes.size
                            val elapsed = ((System.currentTimeMillis() - session.startTime) / 1000.0).coerceAtLeast(0.001)
                            val speed = (session.bytesReceived / elapsed).toLong()

                            onProgressUpdate?.invoke(
                                TransferProgress(
                                    fileId = msg.fileId,
                                    fileName = fileMeta.fileName,
                                    bytesTransferred = session.bytesReceived,
                                    totalBytes = session.totalBytes,
                                    transferSpeedBytesPerSec = speed,
                                    currentChunkIndex = msg.chunkIndex + 1,
                                    totalChunks = fileMeta.totalChunks,
                                    status = TransferStatus.TRANSFERRING
                                )
                            )
                        }

                        val ack = CloudMessagePayload(
                            action = "CHUNK_ACK",
                            senderId = identity.deviceId,
                            senderName = deviceNameProvider(),
                            sessionId = msg.sessionId,
                            fileId = msg.fileId,
                            chunkIndex = msg.chunkIndex,
                            status = "OK"
                        )
                        mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), ack).encodeToByteArray())
                    }
                }
                "FILE_FINALIZE" -> {
                    val mgr = partFileManager ?: return
                    val session = activeReceiveSessions[msg.sessionId] ?: return
                    val fileMeta = session.files.firstOrNull { it.fileId == msg.fileId } ?: return
                    val senderHashed = ChecksumUtil.calculateSha256(msg.senderId.encodeToByteArray())

                    mgr.finalizeTransfer(fileMeta.fileName, fileMeta.relativePath)

                    val isLast = session.files.lastOrNull()?.fileId == msg.fileId
                    if (isLast) {
                        activeReceiveSessions.remove(msg.sessionId)
                        onProgressUpdate?.invoke(
                            TransferProgress(
                                fileId = msg.fileId,
                                fileName = fileMeta.fileName,
                                bytesTransferred = session.totalBytes,
                                totalBytes = session.totalBytes,
                                transferSpeedBytesPerSec = 0,
                                currentChunkIndex = fileMeta.totalChunks,
                                totalChunks = fileMeta.totalChunks,
                                status = TransferStatus.COMPLETED
                            )
                        )
                    }

                    val completeMsg = CloudMessagePayload(
                        action = "FILE_COMPLETE",
                        senderId = identity.deviceId,
                        senderName = deviceNameProvider(),
                        sessionId = msg.sessionId,
                        fileId = msg.fileId
                    )
                    scope.launch {
                        mqttClient.publish("quickpear/msg/$senderHashed", json.encodeToString(CloudMessagePayload.serializer(), completeMsg).encodeToByteArray())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun cleanStalePeers() {
        val now = System.currentTimeMillis()
        _onlinePeers.update { current ->
            val active = current.filterValues { now - it.lastSeen < 35_000L }
            _onlineDevicesList.value = active.values.toList()
            active
        }
    }

    suspend fun sendText(targetPeer: PeerDevice, text: String): Boolean = withContext(Dispatchers.IO) {
        val targetHashed = ChecksumUtil.calculateSha256(targetPeer.id.encodeToByteArray())
        val msg = CloudMessagePayload(
            action = "TEXT_SHARE",
            senderId = identity.deviceId,
            senderName = deviceNameProvider(),
            text = text,
            timestamp = System.currentTimeMillis()
        )
        val jsonStr = json.encodeToString(CloudMessagePayload.serializer(), msg)
        mqttClient.publish("quickpear/msg/$targetHashed", jsonStr.encodeToByteArray())
        true
    }

    suspend fun probeHotspotHandover(
        targetPeer: PeerDevice,
        ssid: String,
        pass: String,
        hostIp: String,
        port: Int,
        sessionId: String,
        timeoutMillis: Long = 5000L
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val targetHashed = ChecksumUtil.calculateSha256(targetPeer.id.encodeToByteArray())
        val hotspotDeferred = registerPendingResponse("hotspot:$sessionId")

        val offer = CloudMessagePayload(
            action = "HOTSPOT_OFFER",
            senderId = identity.deviceId,
            senderName = deviceNameProvider(),
            sessionId = sessionId,
            hotspotSsid = ssid,
            hotspotPass = pass,
            hotspotHostIp = hostIp,
            hotspotPort = port,
            timestamp = System.currentTimeMillis()
        )
        mqttClient.publish("quickpear/msg/$targetHashed", json.encodeToString(CloudMessagePayload.serializer(), offer).encodeToByteArray())

        val reply = withTimeoutOrNull(timeoutMillis) {
            hotspotDeferred.await()
        }
        val isAccepted = reply?.action == "HOTSPOT_ACCEPT"
        val receiverIp = reply?.hotspotHostIp ?: ""
        Pair(isAccepted, receiverIp)
    }

    suspend fun sendFiles(
        targetPeer: PeerDevice,
        filesMap: Map<FileMetadata, Path>,
        sessionId: String = "session-${System.currentTimeMillis()}"
    ): Boolean = withContext(Dispatchers.IO) {
        val targetHashed = ChecksumUtil.calculateSha256(targetPeer.id.encodeToByteArray())
        val chunkSizeBytes = 262144L // 256 KB chunks for optimal throughput and broker stability
        val cloudFiles = filesMap.keys.map { meta ->
            CloudFileMeta(
                fileId = meta.fileId,
                fileName = meta.fileName,
                fileSizeBytes = meta.fileSizeBytes,
                chunkSizeBytes = chunkSizeBytes.toInt(),
                totalChunks = if (meta.fileSizeBytes == 0L) 1L else (meta.fileSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes,
                expectedSha256 = meta.expectedSha256,
                relativePath = meta.relativePath
            )
        }

        val totalBytes = cloudFiles.sumOf { it.fileSizeBytes }
        var totalBytesSent = 0L
        val progressMutex = Mutex()
        val startTime = System.currentTimeMillis()

        // 1. Register pending accept and cancel listeners BEFORE publishing offer!
        val acceptDeferred = registerPendingResponse("accept:$sessionId")
        val cancelDeferred = registerPendingResponse("cancel:$sessionId")

        val offer = CloudMessagePayload(
            action = "FILE_OFFER",
            senderId = identity.deviceId,
            senderName = deviceNameProvider(),
            sessionId = sessionId,
            files = cloudFiles,
            timestamp = System.currentTimeMillis()
        )
        mqttClient.publish("quickpear/msg/$targetHashed", json.encodeToString(CloudMessagePayload.serializer(), offer).encodeToByteArray())

        // 2. Wait for FILE_ACCEPT or FILE_CANCEL
        val acceptMsg = withTimeoutOrNull(25_000L) {
            select<CloudMessagePayload?> {
                acceptDeferred.onAwait { it }
                cancelDeferred.onAwait { it }
            }
        }

        if (acceptMsg == null || acceptMsg.action == "FILE_CANCEL") {
            val reason = if (acceptMsg?.action == "FILE_CANCEL") {
                acceptMsg.errorMessage.ifEmpty { "Transfer ditolak atau dibatalkan oleh penerima" }
            } else {
                "Perangkat tujuan tidak merespons transfer cloud"
            }
            onProgressUpdate?.invoke(
                TransferProgress(
                    fileId = 0, fileName = "", bytesTransferred = 0, totalBytes = totalBytes,
                    transferSpeedBytesPerSec = 0, currentChunkIndex = 0, totalChunks = 0,
                    status = TransferStatus.FAILED, errorMessage = reason
                )
            )
            return@withContext false
        }

        // 3. Send chunks for each file using a sliding window pipeline
        val chunker = FileChunker(fileSystem, chunkSizeBytes = chunkSizeBytes.toInt())
        for (cloudFile in cloudFiles) {
            val entry = filesMap.entries.firstOrNull { it.key.fileName == cloudFile.fileName } ?: continue
            val sourcePath = entry.value

            val windowSemaphore = Semaphore(permits = 5)
            val chunkJobs = mutableListOf<Job>()
            var fileFailed = false
            var failureReason = ""

            chunker.readChunksFlow(sourcePath).collect { chunkData ->
                if (fileFailed) return@collect

                windowSemaphore.acquire()
                val job = scope.launch {
                    try {
                        val base64Data = chunkData.payloadBytes.toByteString().base64()
                        var chunkAcked = false

                        for (attempt in 1..3) {
                            val ackDeferred = registerPendingResponse("ack:$sessionId:${cloudFile.fileId}:${chunkData.header.chunkId}")

                            val chunkMsg = CloudMessagePayload(
                                action = "FILE_CHUNK",
                                senderId = identity.deviceId,
                                senderName = deviceNameProvider(),
                                sessionId = sessionId,
                                fileId = cloudFile.fileId,
                                chunkIndex = chunkData.header.chunkId,
                                totalChunks = cloudFile.totalChunks,
                                chunkData = base64Data,
                                checksum = chunkData.header.checksum,
                                timestamp = System.currentTimeMillis()
                            )
                            mqttClient.publish("quickpear/msg/$targetHashed", json.encodeToString(CloudMessagePayload.serializer(), chunkMsg).encodeToByteArray())

                            val ack = withTimeoutOrNull(15_000L) { ackDeferred.await() }
                            if (ack != null && ack.status == "OK") {
                                chunkAcked = true
                                break
                            }
                        }

                        if (!chunkAcked) {
                            fileFailed = true
                            failureReason = "Gagal mengirim chunk ${chunkData.header.chunkId}"
                            return@launch
                        }

                        progressMutex.withLock {
                            totalBytesSent += chunkData.payloadBytes.size
                            val elapsed = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.001)
                            val speed = (totalBytesSent / elapsed).toLong()

                            onProgressUpdate?.invoke(
                                TransferProgress(
                                    fileId = cloudFile.fileId,
                                    fileName = cloudFile.fileName,
                                    bytesTransferred = totalBytesSent,
                                    totalBytes = totalBytes,
                                    transferSpeedBytesPerSec = speed,
                                    currentChunkIndex = chunkData.header.chunkId + 1,
                                    totalChunks = cloudFile.totalChunks,
                                    status = TransferStatus.TRANSFERRING
                                )
                            )
                        }
                    } finally {
                        windowSemaphore.release()
                    }
                }
                chunkJobs.add(job)
            }

            chunkJobs.joinAll()

            if (fileFailed) {
                onProgressUpdate?.invoke(
                    TransferProgress(
                        fileId = cloudFile.fileId,
                        fileName = cloudFile.fileName,
                        bytesTransferred = totalBytesSent,
                        totalBytes = totalBytes,
                        transferSpeedBytesPerSec = 0,
                        currentChunkIndex = 0,
                        totalChunks = cloudFile.totalChunks,
                        status = TransferStatus.FAILED,
                        errorMessage = failureReason
                    )
                )
                throw IllegalStateException(failureReason)
            }

            // Send FILE_FINALIZE
            val compDeferred = registerPendingResponse("complete:$sessionId:${cloudFile.fileId}")
            val finMsg = CloudMessagePayload(
                action = "FILE_FINALIZE",
                senderId = identity.deviceId,
                senderName = deviceNameProvider(),
                sessionId = sessionId,
                fileId = cloudFile.fileId
            )
            mqttClient.publish("quickpear/msg/$targetHashed", json.encodeToString(CloudMessagePayload.serializer(), finMsg).encodeToByteArray())

            withTimeoutOrNull(15_000L) { compDeferred.await() }
        }

        onProgressUpdate?.invoke(
            TransferProgress(
                fileId = cloudFiles.lastOrNull()?.fileId ?: 0,
                fileName = cloudFiles.lastOrNull()?.fileName ?: "",
                bytesTransferred = totalBytes,
                totalBytes = totalBytes,
                transferSpeedBytesPerSec = 0,
                currentChunkIndex = cloudFiles.lastOrNull()?.totalChunks ?: 0,
                totalChunks = cloudFiles.lastOrNull()?.totalChunks ?: 0,
                status = TransferStatus.COMPLETED
            )
        )
        true
    }

    /**
     * Initiates cloud pairing as host by generating a 6-digit code.
     */
    suspend fun startCloudPairing(
        code: String,
        onSasVerification: suspend (peerName: String, sasCode: String) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val pairTopic = "quickpear/pair/$code"
        val responseDeferred = CompletableDeferred<CloudMessagePayload>()
        val confirmDeferred = CompletableDeferred<CloudMessagePayload>()

        mqttClient.subscribe(pairTopic) { _, bytes ->
            try {
                val msg = json.decodeFromString(CloudMessagePayload.serializer(), bytes.decodeToString())
                if (msg.senderId != identity.deviceId) {
                    if (msg.action == "PAIR_RESPONSE") {
                        responseDeferred.complete(msg)
                    } else if (msg.action == "PAIR_CONFIRM") {
                        confirmDeferred.complete(msg)
                    }
                }
            } catch (_: Exception) {}
        }

        val beaconJob = scope.launch {
            val req = CloudMessagePayload(
                action = "PAIR_REQUEST",
                senderId = identity.deviceId,
                senderName = deviceNameProvider(),
                text = identity.publicKey.toByteString().base64(),
                timestamp = System.currentTimeMillis()
            )
            val bytes = json.encodeToString(CloudMessagePayload.serializer(), req).encodeToByteArray()
            while (isActive) {
                mqttClient.publish(pairTopic, bytes)
                delay(1200L)
            }
        }

        try {
            val clientResponse = withTimeoutOrNull(120_000L) { responseDeferred.await() }
            beaconJob.cancel()
            if (clientResponse == null) return@withContext false

            val peerPublicKey = clientResponse.text.decodeBase64()?.toByteArray() ?: return@withContext false
            val sas = Handshake.calculateSas(identity.publicKey, peerPublicKey)

            val userConfirmed = onSasVerification(clientResponse.senderName, sas)
            val confirmMsg = CloudMessagePayload(
                action = "PAIR_CONFIRM",
                senderId = identity.deviceId,
                senderName = deviceNameProvider(),
                status = if (userConfirmed) "ACCEPTED" else "REJECTED"
            )
            mqttClient.publish(pairTopic, json.encodeToString(CloudMessagePayload.serializer(), confirmMsg).encodeToByteArray())

            if (!userConfirmed) return@withContext false

            val peerConfirm = withTimeoutOrNull(60_000L) { confirmDeferred.await() }
            if (peerConfirm?.status == "ACCEPTED") {
                trustStore.add(clientResponse.senderId, clientResponse.senderName, peerPublicKey)
                return@withContext true
            }
            false
        } finally {
            beaconJob.cancel()
            mqttClient.unsubscribe(pairTopic)
        }
    }

    /**
     * Joins cloud pairing as client by entering the 6-digit code.
     */
    suspend fun joinCloudPairing(
        code: String,
        onSasVerification: suspend (peerName: String, sasCode: String) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val pairTopic = "quickpear/pair/$code"
        val requestDeferred = CompletableDeferred<CloudMessagePayload>()
        val confirmDeferred = CompletableDeferred<CloudMessagePayload>()

        mqttClient.subscribe(pairTopic) { _, bytes ->
            try {
                val msg = json.decodeFromString(CloudMessagePayload.serializer(), bytes.decodeToString())
                if (msg.senderId != identity.deviceId) {
                    if (msg.action == "PAIR_REQUEST") {
                        requestDeferred.complete(msg)
                    } else if (msg.action == "PAIR_CONFIRM") {
                        confirmDeferred.complete(msg)
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val hostRequest = withTimeoutOrNull(45_000L) { requestDeferred.await() }
            if (hostRequest == null) return@withContext false

            val hostPublicKey = hostRequest.text.decodeBase64()?.toByteArray() ?: return@withContext false
            val sas = Handshake.calculateSas(hostPublicKey, identity.publicKey)

            val respMsg = CloudMessagePayload(
                action = "PAIR_RESPONSE",
                senderId = identity.deviceId,
                senderName = deviceNameProvider(),
                text = identity.publicKey.toByteString().base64(),
                timestamp = System.currentTimeMillis()
            )
            mqttClient.publish(pairTopic, json.encodeToString(CloudMessagePayload.serializer(), respMsg).encodeToByteArray())

            val userConfirmed = onSasVerification(hostRequest.senderName, sas)
            val confirmMsg = CloudMessagePayload(
                action = "PAIR_CONFIRM",
                senderId = identity.deviceId,
                senderName = deviceNameProvider(),
                status = if (userConfirmed) "ACCEPTED" else "REJECTED"
            )
            mqttClient.publish(pairTopic, json.encodeToString(CloudMessagePayload.serializer(), confirmMsg).encodeToByteArray())

            if (!userConfirmed) return@withContext false

            val hostConfirm = withTimeoutOrNull(60_000L) { confirmDeferred.await() }
            if (hostConfirm?.status == "ACCEPTED") {
                trustStore.add(hostRequest.senderId, hostRequest.senderName, hostPublicKey)
                return@withContext true
            }
            false
        } finally {
            mqttClient.unsubscribe(pairTopic)
        }
    }

    /**
     * Queries Google public STUN server to discover WAN public IP.
     */
    suspend fun resolvePublicIpViaStun(): String? = withContext(Dispatchers.IO) {
        val stunServers = listOf(
            Pair("stun.l.google.com", 19302),
            Pair("stun1.l.google.com", 19302)
        )

        for ((host, port) in stunServers) {
            val ip = queryStun(host, port)
            if (ip != null) {
                _publicIp.value = ip
                return@withContext ip
            }
        }
        null
    }

    private suspend fun queryStun(host: String, port: Int): String? {
        val selector = SelectorManager(Dispatchers.IO)
        return try {
            withTimeoutOrNull(3000L) {
                val socket = aSocket(selector).udp().bind()
                try {
                    val stunBuf = Buffer()
                    stunBuf.writeShort(0x0001)
                    stunBuf.writeShort(0x0000)
                    stunBuf.writeInt(0x2112A442)
                    stunBuf.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
                    val reqBytes = stunBuf.readByteArray()

                    val req = buildPacket {
                        writeFully(reqBytes)
                    }

                    val serverAddr = InetSocketAddress(host, port)
                    socket.send(Datagram(req, serverAddr))

                    val respDatagram = socket.receive()
                    @Suppress("DEPRECATION")
                    val respBytes = respDatagram.packet.readBytes()

                    parseStunMappedAddress(respBytes)
                } finally {
                    socket.close()
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            selector.close()
        }
    }

    private fun parseStunMappedAddress(bytes: ByteArray): String? {
        if (bytes.size < 20) return null
        val magicCookie = 0x2112A442

        var offset = 20
        while (offset + 4 <= bytes.size) {
            val attrType = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            val attrLen = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            offset += 4

            if (offset + attrLen > bytes.size) break

            if (attrType == 0x0020 && attrLen >= 8) { // XOR-MAPPED-ADDRESS
                val family = bytes[offset + 1].toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    val b0 = (bytes[offset + 4].toInt() and 0xFF) xor (magicCookie ushr 24 and 0xFF)
                    val b1 = (bytes[offset + 5].toInt() and 0xFF) xor (magicCookie ushr 16 and 0xFF)
                    val b2 = (bytes[offset + 6].toInt() and 0xFF) xor (magicCookie ushr 8 and 0xFF)
                    val b3 = (bytes[offset + 7].toInt() and 0xFF) xor (magicCookie and 0xFF)
                    return "$b0.$b1.$b2.$b3"
                }
            } else if (attrType == 0x0001 && attrLen >= 8) { // MAPPED-ADDRESS
                val family = bytes[offset + 1].toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    val b0 = bytes[offset + 4].toInt() and 0xFF
                    val b1 = bytes[offset + 5].toInt() and 0xFF
                    val b2 = bytes[offset + 6].toInt() and 0xFF
                    val b3 = bytes[offset + 7].toInt() and 0xFF
                    return "$b0.$b1.$b2.$b3"
                }
            }

            offset += attrLen
            val padding = (4 - (attrLen % 4)) % 4
            offset += padding
        }
        return null
    }
}
