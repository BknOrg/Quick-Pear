package com.app.quickpear.node

import com.app.quickpear.discovery.AdaptiveDiscovery
import com.app.quickpear.discovery.BleProximityEngine
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.domain.TransferProgress
import com.app.quickpear.engine.TransferEngine
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.network.KtorSocketServer
import com.app.quickpear.security.DeviceIdentity
import com.app.quickpear.security.PeerIdentity
import com.app.quickpear.security.TrustStore
import com.app.quickpear.session.ApprovalHandler
import com.app.quickpear.session.IncomingConnectionHandler
import com.app.quickpear.session.PeerClient
import com.app.quickpear.network.KtorSocketClient
import com.app.quickpear.network.LocalIpResolver
import com.app.quickpear.security.Handshake
import com.app.quickpear.security.SessionPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Unified node running the Quick Pear background daemon (TCP server, discovery engine,
 * identity, trust store, and transfer engine).
 */
class QuickPearNode(
    val dataDirectory: Path,
    val downloadDirectory: Path,
    val deviceNameProvider: () -> String,
    val deviceType: DeviceType,
    val approvalHandler: ApprovalHandler,
    val proximityEngine: BleProximityEngine = BleProximityEngine(),
    val p2pLinkNegotiator: com.app.quickpear.network.P2pLinkNegotiator = com.app.quickpear.network.NoOpP2pLinkNegotiator,
    val port: Int = 8888,
    fileSystem: FileSystem = FileSystem.SYSTEM
) : AdaptiveDiscovery {

    val identity: DeviceIdentity = DeviceIdentity.loadOrCreate(dataDirectory, fileSystem)
    val trustStore: TrustStore = TrustStore(dataDirectory, fileSystem)
    val partFileManager: PartFileManager = PartFileManager(downloadDirectory, fileSystem)
    val transferEngine: TransferEngine = TransferEngine(partFileManager, fileSystem)
    val webShareServer: com.app.quickpear.web.InstantWebShareServer = com.app.quickpear.web.InstantWebShareServer(fileSystem)
    val textReceivedFlow = kotlinx.coroutines.flow.MutableSharedFlow<Pair<PeerIdentity, String>>(extraBufferCapacity = 16)

    val signalingClient: com.app.quickpear.signaling.SignalingClient = com.app.quickpear.signaling.SignalingClient(
        identity = identity,
        deviceNameProvider = deviceNameProvider,
        deviceType = deviceType,
        trustStore = trustStore,
        partFileManager = partFileManager,
        fileSystem = fileSystem,
        localPort = port,
        onCloudTextReceived = { peerIdentity, text ->
            textReceivedFlow.tryEmit(Pair(peerIdentity, text))
        },
        onProgressUpdate = { progress ->
            transferEngine.updateProgress(progress)
        },
        onCloudFileOfferReceived = { senderName, fileCount, totalBytes ->
            val peer = PeerIdentity(
                deviceId = "cloud-peer",
                name = senderName,
                publicKey = byteArrayOf()
            )
            val dummyMeta = listOf(
                FileMetadata(
                    fileId = 1,
                    fileName = "$fileCount berkas",
                    fileSizeBytes = totalBytes,
                    chunkSizeBytes = 65536,
                    totalChunks = 1,
                    expectedSha256 = ""
                )
            )
            val request = com.app.quickpear.domain.MetadataRequest(sessionId = "cloud", files = dummyMeta)
            val decision = approvalHandler.onTransferRequest(peer, request)
            decision == com.app.quickpear.session.ApprovalDecision.ACCEPT || decision == com.app.quickpear.session.ApprovalDecision.ACCEPT_ALWAYS
        }
    )

    val client: PeerClient = PeerClient(
        identity = identity,
        deviceName = deviceNameProvider,
        engine = transferEngine
    )

    private val incomingHandler = IncomingConnectionHandler(
        identity = identity,
        deviceName = deviceNameProvider,
        trustStore = trustStore,
        engine = transferEngine,
        approval = approvalHandler,
        onPeerSeen = { peerIdentity, remoteIp, remotePort ->
            val peer = PeerDevice(
                id = peerIdentity.deviceId,
                name = peerIdentity.name,
                ipAddress = remoteIp,
                port = remotePort,
                deviceType = DeviceType.UNKNOWN,
                lastSeen = System.currentTimeMillis()
            )
            _onlinePeers.update { current ->
                val updated = current + (peer.id to peer)
                _onlineDevices.value = updated.values.toList()
                updated
            }
        },
        onTextReceived = { peerIdentity, text ->
            textReceivedFlow.tryEmit(Pair(peerIdentity, text))
        }
    )

    private val socketClient = KtorSocketClient()
    private val socketServer = KtorSocketServer(port = port, host = "0.0.0.0")
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    private var serverJob: Job? = null
    private var isRunning = false

    private val _onlinePeers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    private val _onlineDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val onlineDevices: StateFlow<List<PeerDevice>> = _onlineDevices.asStateFlow()

    val transferProgress: StateFlow<TransferProgress?> = transferEngine.progressState

    val localDevice: PeerDevice
        get() = PeerDevice(
            id = identity.deviceId,
            name = deviceNameProvider(),
            ipAddress = LocalIpResolver.getLocalIpAddress(),
            port = port,
            deviceType = deviceType
        )

    suspend fun start() = mutex.withLock {
        if (isRunning) return@withLock
        isRunning = true

        socketServer.start()
        serverJob = scope.launch {
            while (isActive) {
                try {
                    socketServer.acceptConnections().collect { connection ->
                        if (!isActive) return@collect
                        scope.launch {
                            try {
                                incomingHandler.handle(connection)
                            } catch (_: Exception) {
                            } finally {
                                connection.close()
                                p2pLinkNegotiator.disconnectHotspot()
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                    kotlinx.coroutines.delay(500)
                }
            }
        }

        // Setup hotspot handover receiver callback
        signalingClient.onHotspotOfferReceived = { senderName, ssid, pass, hostIp, port ->
            if (p2pLinkNegotiator.isHotspotSupported()) {
                val connected = p2pLinkNegotiator.connectToHotspot(ssid, pass, timeoutMillis = 5000L)
                if (connected) {
                    val myLocalIp = LocalIpResolver.getLocalIpAddress()
                    Pair(true, myLocalIp)
                } else {
                    Pair(false, "")
                }
            } else {
                Pair(false, "")
            }
        }

        // Collect from LAN discovery
        scope.launch {
            proximityEngine.lanDiscovery.onlineDevices.collect { lanPeers ->
                _onlinePeers.update { current ->
                    val updated = current.toMutableMap()
                    for (p in lanPeers) {
                        updated[p.id] = p
                    }
                    _onlineDevices.value = updated.values.toList()
                    updated
                }
            }
        }

        // Collect from any native proximity scanners (BLE, etc.)
        scope.launch {
            proximityEngine.startScanning().collect { scannedPeer ->
                _onlinePeers.update { current ->
                    val updated = current + (scannedPeer.id to scannedPeer)
                    _onlineDevices.value = updated.values.toList()
                    updated
                }
            }
        }

        // Collect from Cloud Hybrid Signaling
        signalingClient.start()
        scope.launch {
            signalingClient.onlinePeers.collect { cloudPeers ->
                _onlinePeers.update { current ->
                    val updated = current.toMutableMap()
                    for (p in cloudPeers) {
                        if (!updated.containsKey(p.id)) {
                            updated[p.id] = p
                        }
                    }
                    _onlineDevices.value = updated.values.toList()
                    updated
                }
            }
        }

        // Periodically probe trusted devices via TCP ping
        scope.launch {
            while (isActive) {
                probeTrustedDevices()
                kotlinx.coroutines.delay(10_000L)
            }
        }

        proximityEngine.startAdvertising(localDevice)
    }

    suspend fun probeTrustedDevices() = withContext(Dispatchers.IO) {
        val trusted = trustStore.devices.value
        val now = System.currentTimeMillis()
        for (device in trusted) {
            val ip = device.lastKnownIp ?: continue
            val port = device.lastKnownPort
            try {
                val connection = socketClient.connect(ip, port, timeoutMillis = 2000L)
                try {
                    val handshake = Handshake.initiate(connection, identity, deviceNameProvider(), SessionPurpose.PROBE)
                    try {
                        val header = kotlinx.coroutines.withTimeout(2000L) { connection.readHeader() }
                        if (header.messageType == com.app.quickpear.protocol.ProtocolConstants.MSG_PROBE_ACK) {
                            connection.readFramePayload(header.payloadLength, 64)
                        }
                    } catch (_: Exception) {}

                    val peer = PeerDevice(
                        id = device.id,
                        name = device.name,
                        ipAddress = ip,
                        port = port,
                        deviceType = DeviceType.UNKNOWN,
                        lastSeen = now
                    )
                    _onlinePeers.update { current ->
                        val updated = current + (peer.id to peer)
                        _onlineDevices.value = updated.values.toList()
                        updated
                    }
                } finally {
                    connection.close()
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun connectToIp(ip: String, port: Int = 8888): PeerDevice = withContext(Dispatchers.IO) {
        val connection = socketClient.connect(ip, port, timeoutMillis = 5000L)
        try {
            val handshake = Handshake.initiate(connection, identity, deviceNameProvider(), SessionPurpose.PROBE)
            try {
                val header = kotlinx.coroutines.withTimeout(3000L) { connection.readHeader() }
                if (header.messageType == com.app.quickpear.protocol.ProtocolConstants.MSG_PROBE_ACK) {
                    connection.readFramePayload(header.payloadLength, 64)
                }
            } catch (_: Exception) {}

            val peer = PeerDevice(
                id = handshake.peer.deviceId,
                name = handshake.peer.name,
                ipAddress = ip,
                port = port,
                deviceType = DeviceType.UNKNOWN,
                lastSeen = System.currentTimeMillis()
            )
            _onlinePeers.update { current ->
                val updated = current + (peer.id to peer)
                _onlineDevices.value = updated.values.toList()
                updated
            }
            if (trustStore.isTrusted(peer.id)) {
                trustStore.updateLastKnownIp(peer.id, ip, port)
            }
            peer
        } finally {
            connection.close()
        }
    }

    suspend fun stop() = mutex.withLock {
        if (!isRunning) return@withLock
        isRunning = false

        signalingClient.stop()
        webShareServer.stop()
        proximityEngine.stopAdvertising()
        proximityEngine.stopScanning()
        serverJob?.cancel()
        serverJob = null
        try {
            socketServer.stop()
        } catch (_: Exception) {
        }
    }

    override fun setMode(mode: DiscoveryMode) {
        proximityEngine.setMode(mode)
    }

    fun triggerBurstBeacon() {
        proximityEngine.triggerBurstBeacon()
    }

    @kotlin.concurrent.Volatile
    private var activeSendingJob: kotlinx.coroutines.Job? = null

    fun cancelTransfer(reason: String = "Transfer dibatalkan oleh pengguna") {
        activeSendingJob?.cancel()
        transferEngine.cancelTransfer(reason)
    }

    suspend fun sendFiles(
        target: PeerDevice,
        files: Map<FileMetadata, Path>,
        sessionId: String = "session-${System.currentTimeMillis()}"
    ) = kotlinx.coroutines.coroutineScope {
        activeSendingJob = coroutineContext[kotlinx.coroutines.Job]
        try {
            if (target.connectionType == com.app.quickpear.domain.ConnectionType.CLOUD_P2P) {
                // Tier 1: Direct TCP Probe (if IP is reachable on LAN / public IP)
                if (target.ipAddress.isNotBlank() && target.ipAddress != "cloud-relay") {
                    try {
                        kotlinx.coroutines.withTimeout(2000L) {
                            client.sendFiles(
                                host = target.ipAddress,
                                port = target.port,
                                sessionId = sessionId,
                                files = files,
                                expectedPeerId = target.id
                            )
                        }
                        return@coroutineScope
                    } catch (_: Exception) {
                        // Direct connection failed, proceed to Tier 2
                    }
                }

                // Tier 2: Local Hotspot Handover (High-speed Wi-Fi Direct if devices are physically nearby)
                if (p2pLinkNegotiator.isHotspotSupported()) {
                    try {
                        val hotspot = p2pLinkNegotiator.startLocalHotspot()
                        if (hotspot != null) {
                            try {
                                val (accepted, receiverLocalIp) = signalingClient.probeHotspotHandover(
                                    targetPeer = target,
                                    ssid = hotspot.ssid,
                                    pass = hotspot.passphrase,
                                    hostIp = hotspot.hostIp,
                                    port = hotspot.port,
                                    sessionId = sessionId,
                                    timeoutMillis = 5000L
                                )
                                if (accepted && receiverLocalIp.isNotBlank()) {
                                    client.sendFiles(
                                        host = receiverLocalIp,
                                        port = target.port,
                                        sessionId = sessionId,
                                        files = files,
                                        expectedPeerId = target.id
                                    )
                                    return@coroutineScope
                                }
                            } finally {
                                p2pLinkNegotiator.stopLocalHotspot()
                            }
                        }
                    } catch (_: Exception) {
                        p2pLinkNegotiator.stopLocalHotspot()
                    }
                }

                // Tier 3: Fallback to Cloud Relay (256 KB Chunks with Sliding Window)
                signalingClient.sendFiles(target, files, sessionId)
                return@coroutineScope
            }
            triggerBurstBeacon()
            var link: com.app.quickpear.network.P2pLink? = null
            val (targetHost, targetPort) = if (target.connectionType == com.app.quickpear.domain.ConnectionType.WIFI_DIRECT && p2pLinkNegotiator.isSupported()) {
                val p2p = p2pLinkNegotiator.connectClientLink(target)
                link = p2p
                Pair(p2p.remoteIp, p2p.port)
            } else {
                Pair(target.ipAddress, target.port)
            }

            try {
                client.sendFiles(
                    host = targetHost,
                    port = targetPort,
                    sessionId = sessionId,
                    files = files,
                    expectedPeerId = target.id
                )
            } catch (e: Exception) {
                if (trustStore.isTrusted(target.id)) {
                    signalingClient.sendFiles(target, files, sessionId)
                } else {
                    throw e
                }
            } finally {
                link?.release()
            }
        } finally {
            activeSendingJob = null
        }
    }

    suspend fun sendText(
        target: PeerDevice,
        text: String
    ): Boolean {
        if (target.connectionType == com.app.quickpear.domain.ConnectionType.CLOUD_P2P) {
            if (target.ipAddress.isNotBlank() && target.ipAddress != "cloud-relay") {
                try {
                    val ok = kotlinx.coroutines.withTimeout(2000L) {
                        client.sendText(
                            host = target.ipAddress,
                            port = target.port,
                            text = text,
                            expectedPeerId = target.id
                        )
                    }
                    if (ok) return true
                } catch (_: Exception) {
                }
            }
            return signalingClient.sendText(target, text)
        }
        triggerBurstBeacon()
        return try {
            client.sendText(
                host = target.ipAddress,
                port = target.port,
                text = text,
                expectedPeerId = target.id
            )
        } catch (_: Exception) {
            signalingClient.sendText(target, text)
        }
    }

    suspend fun pair(
        target: PeerDevice,
        confirm: suspend (PeerIdentity, String) -> Boolean
    ): Boolean {
        triggerBurstBeacon()
        return client.pair(
            host = target.ipAddress,
            port = target.port,
            trustStore = trustStore,
            expectedPeerId = target.id,
            confirm = confirm
        )
    }

    fun generateCloudPairingCode(): String {
        return kotlin.random.Random.nextInt(100_000, 999_999).toString()
    }

    suspend fun startCloudPairing(
        code: String,
        confirm: suspend (peerName: String, sasCode: String) -> Boolean
    ): Boolean {
        return signalingClient.startCloudPairing(code, confirm)
    }

    suspend fun joinCloudPairing(
        code: String,
        confirm: suspend (peerName: String, sasCode: String) -> Boolean
    ): Boolean {
        return signalingClient.joinCloudPairing(code, confirm)
    }

    fun clearTrustedDevices() {
        trustStore.clear()
    }
}
