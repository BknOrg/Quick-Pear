package com.app.quickpear.discovery

import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.network.LocalIpResolver
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.core.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LanBeaconPayload(
    val id: String,
    val name: String,
    val port: Int = 8888,
    val deviceType: DeviceType = DeviceType.UNKNOWN
)

class LanBroadcastDiscovery(
    private val discoveryPort: Int = 8889,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : DeviceScanner, DeviceAdvertiser, AdaptiveDiscovery {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    private val _discoveredDevices = MutableSharedFlow<PeerDevice>(extraBufferCapacity = 64)

    private val _onlinePeers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    private val _onlineDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val onlineDevices: StateFlow<List<PeerDevice>> = _onlineDevices.asStateFlow()

    private val _mode = MutableStateFlow(DiscoveryMode.ACTIVE)
    val mode: StateFlow<DiscoveryMode> = _mode.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var socket: BoundDatagramSocket? = null
    private var selectorManager: SelectorManager? = null

    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var pruneJob: Job? = null

    private var currentLocalDevice: PeerDevice? = null
    private var isScanningActive = false

    override fun setMode(mode: DiscoveryMode) {
        _mode.value = mode
    }

    /**
     * Initializes the UDP socket if not already bound.
     */
    suspend fun start() = lifecycleMutex.withLock {
        ensureSocketBoundLocked()
    }

    private suspend fun ensureSocketBoundLocked() {
        if (socket != null) return
        try {
            val selector = SelectorManager(Dispatchers.IO)
            selectorManager = selector
            val datagramSocket = aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", discoveryPort)) {
                reuseAddress = true
                broadcast = true
            }
            socket = datagramSocket
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = "Failed to bind discovery socket: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun startListening(datagramSocket: BoundDatagramSocket) {
        listenJob?.cancel()
        listenJob = scope.launch {
            try {
                while (isActive) {
                    val datagram = datagramSocket.receive()
                    val bytes = datagram.packet.readBytes()
                    val text = bytes.decodeToString()

                    if (text.startsWith(BEACON_PREFIX)) {
                        val jsonStr = text.removePrefix(BEACON_PREFIX)
                        try {
                            val payload = json.decodeFromString(LanBeaconPayload.serializer(), jsonStr)

                            // Do not discover self
                            if (payload.id != currentLocalDevice?.id) {
                                val peerIp = extractIpAddress(datagram.address.toString())
                                val currentTime = nowMillis()
                                val peer = PeerDevice(
                                    id = payload.id,
                                    name = payload.name,
                                    ipAddress = peerIp,
                                    port = payload.port,
                                    deviceType = payload.deviceType,
                                    lastSeen = currentTime
                                )
                                _discoveredDevices.tryEmit(peer)
                                _onlinePeers.update { current ->
                                    val updated = current + (peer.id to peer)
                                    _onlineDevices.value = updated.values.toList()
                                    updated
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore corrupted or malformed beacon packets
                        }
                    }
                }
            } catch (_: Exception) {
                // Socket closed or interrupted
            }
        }

        // Start pruning routine to expire offline peers according to TTL
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                val currentTtl = _mode.value.ttlMillis
                val now = nowMillis()
                _onlinePeers.update { current ->
                    val updated = current.filterValues { now - it.lastSeen <= currentTtl }
                    _onlineDevices.value = updated.values.toList()
                    updated
                }
                delay(PRUNE_INTERVAL_MILLIS)
            }
        }
    }

    override fun startScanning(): Flow<PeerDevice> {
        scope.launch {
            lifecycleMutex.withLock {
                isScanningActive = true
                ensureSocketBoundLocked()
                socket?.let { startListening(it) }
            }
        }
        return _discoveredDevices.asSharedFlow()
    }

    override fun stopScanning() {
        scope.launch {
            lifecycleMutex.withLock {
                isScanningActive = false
                listenJob?.cancel()
                listenJob = null
                pruneJob?.cancel()
                pruneJob = null
            }
        }
    }

    override fun startAdvertising(device: PeerDevice) {
        currentLocalDevice = device
        scope.launch {
            lifecycleMutex.withLock {
                ensureSocketBoundLocked()
                if (isScanningActive && listenJob == null) {
                    socket?.let { startListening(it) }
                }

                broadcastJob?.cancel()
                broadcastJob = scope.launch {
                    val beacon = LanBeaconPayload(
                        id = device.id,
                        name = device.name,
                        port = device.port,
                        deviceType = device.deviceType
                    )
                    val beaconText = BEACON_PREFIX + json.encodeToString(LanBeaconPayload.serializer(), beacon)
                    val bytes = beaconText.encodeToByteArray()

                    while (isActive) {
                        try {
                            val datagramSocket = socket
                            if (datagramSocket != null) {
                                val targets = mutableSetOf("255.255.255.255")
                                targets.addAll(LocalIpResolver.getBroadcastAddresses())
                                for (target in targets) {
                                    try {
                                        val packet = buildPacket { writeFully(bytes) }
                                        val targetAddress = InetSocketAddress(target, discoveryPort)
                                        datagramSocket.send(Datagram(packet, targetAddress))
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {
                        }
                        // Adaptive broadcast delay based on current DiscoveryMode
                        delay(_mode.value.beaconIntervalMillis)
                    }
                }
            }
        }
    }

    override fun stopAdvertising() {
        scope.launch {
            lifecycleMutex.withLock {
                broadcastJob?.cancel()
                broadcastJob = null
            }
        }
    }

    /**
     * Sends an immediate burst of beacons to rapidly wake and notify listening peers
     * when a transfer or pairing is initiated.
     */
    fun triggerBurstBeacon(count: Int = 3, intervalMillis: Long = 100L) {
        val device = currentLocalDevice ?: return
        scope.launch {
            val beacon = LanBeaconPayload(
                id = device.id,
                name = device.name,
                port = device.port,
                deviceType = device.deviceType
            )
            val beaconText = BEACON_PREFIX + json.encodeToString(LanBeaconPayload.serializer(), beacon)
            val bytes = beaconText.encodeToByteArray()
            repeat(count) {
                try {
                    val datagramSocket = socket
                    if (datagramSocket != null) {
                        val targets = mutableSetOf("255.255.255.255")
                        targets.addAll(LocalIpResolver.getBroadcastAddresses())
                        for (target in targets) {
                            try {
                                val packet = buildPacket { writeFully(bytes) }
                                val targetAddress = InetSocketAddress(target, discoveryPort)
                                datagramSocket.send(Datagram(packet, targetAddress))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                delay(intervalMillis)
            }
        }
    }

    fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                isScanningActive = false
                broadcastJob?.cancel()
                broadcastJob = null
                listenJob?.cancel()
                listenJob = null
                pruneJob?.cancel()
                pruneJob = null
                socket?.close()
                socket = null
                selectorManager?.close()
                selectorManager = null
                _onlinePeers.value = emptyMap()
                _onlineDevices.value = emptyList()
            }
        }
    }

    private fun extractIpAddress(addressStr: String): String {
        val clean = if (addressStr.contains("/")) {
            addressStr.substringAfter("/").substringBefore(":")
        } else {
            addressStr.substringBefore(":")
        }
        return clean.ifEmpty { "127.0.0.1" }
    }

    companion object {
        private const val BEACON_PREFIX = "QUICKPEAR_BEACON:"
        private const val PRUNE_INTERVAL_MILLIS = 2_000L
    }
}
