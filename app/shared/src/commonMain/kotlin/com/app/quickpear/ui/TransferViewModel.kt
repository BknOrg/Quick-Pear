package com.app.quickpear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.domain.TransferProgress
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.node.QuickPearNode
import com.app.quickpear.security.TrustedDevice
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.FileSystem
import okio.Path
import kotlin.coroutines.resume

data class PairingUiState(
    val peer: PeerDevice,
    val sasCode: String,
    val continuation: CancellableContinuation<Boolean>
)

data class RemotePairingSasState(
    val peerName: String,
    val sasCode: String,
    val continuation: CancellableContinuation<Boolean>
)

data class MainUiState(
    val discoveredDevices: List<PeerDevice> = emptyList(),
    val trustedDevices: List<TrustedDevice> = emptyList(),
    val selectedDevice: PeerDevice? = null,
    val pendingPairing: PairingUiState? = null,
    val isPairingInProgress: Boolean = false,
    val transferProgress: TransferProgress? = null,
    val discoveryMode: DiscoveryMode = DiscoveryMode.ACTIVE,
    val localDeviceName: String = "",
    val localDeviceId: String = "",
    val localIpAddress: String = "",
    val allLocalIpAddresses: List<String> = emptyList(),
    val localPort: Int = 8888,
    val webShareUrl: String? = null,
    val webShareFiles: List<Path> = emptyList(),
    val webShareHotspotInfo: String? = null,
    val incomingSharedText: Pair<String, String>? = null,
    val statusMessage: String? = null,
    val showRemotePairDialog: Boolean = false,
    val generatedPairingCode: String? = null,
    val isRemotePairingInProgress: Boolean = false,
    val pendingRemoteSas: RemotePairingSasState? = null
)

class TransferViewModel(
    initialNode: QuickPearNode? = null
) : ViewModel() {

    private var activeNode: QuickPearNode? = initialNode

    private val _uiState = MutableStateFlow(
        MainUiState(
            localDeviceName = initialNode?.localDevice?.name ?: "Quick Pear",
            localDeviceId = initialNode?.identity?.deviceId ?: ""
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        initialNode?.let { bindToNode(it) }
    }

    fun attachNode(node: QuickPearNode) {
        if (activeNode === node) return
        activeNode = node
        bindToNode(node)
    }

    private fun bindToNode(node: QuickPearNode) {
        observeJob?.cancel()
        _uiState.update {
            it.copy(
                localDeviceName = node.localDevice.name,
                localDeviceId = node.identity.deviceId,
                localIpAddress = node.localDevice.ipAddress,
                allLocalIpAddresses = com.app.quickpear.network.LocalIpResolver.getAllLocalIpAddresses(),
                localPort = node.localDevice.port
            )
        }

        observeJob = viewModelScope.launch {
            launch {
                node.onlineDevices.collectLatest { devices ->
                    _uiState.update { it.copy(discoveredDevices = devices) }
                }
            }

            launch {
                node.trustStore.devices.collectLatest { trusted ->
                    _uiState.update { it.copy(trustedDevices = trusted) }
                }
            }

            launch {
                node.transferProgress.collectLatest { progress ->
                    _uiState.update { it.copy(transferProgress = progress) }
                    if (progress?.status == com.app.quickpear.domain.TransferStatus.COMPLETED) {
                        _uiState.update { it.copy(statusMessage = "Transfer completed: ${progress.fileName}") }
                    } else if (progress?.status == com.app.quickpear.domain.TransferStatus.FAILED) {
                        _uiState.update { it.copy(statusMessage = "Transfer failed: ${progress.errorMessage ?: "Unknown error"}") }
                    }
                }
            }

            launch {
                node.textReceivedFlow.collectLatest { (sender, text) ->
                    _uiState.update {
                        it.copy(
                            incomingSharedText = Pair(sender.name, text),
                            statusMessage = "Text received from ${sender.name}: \"${text.take(40)}${if (text.length > 40) "..." else ""}\""
                        )
                    }
                }
            }
        }
    }

    fun selectDevice(device: PeerDevice?) {
        _uiState.update { it.copy(selectedDevice = device) }
    }

    fun isDeviceTrusted(deviceId: String): Boolean {
        return activeNode?.trustStore?.isTrusted(deviceId) == true
    }

    fun initiatePairing(peer: PeerDevice) {
        val node = activeNode ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isPairingInProgress = true) }
            try {
                val success = node.pair(peer) { peerIdentity, sasCode ->
                    suspendCancellableCoroutine { cont ->
                        _uiState.update {
                            it.copy(
                                pendingPairing = PairingUiState(
                                    peer = peer,
                                    sasCode = sasCode,
                                    continuation = cont
                                )
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        statusMessage = if (success) {
                            "${peer.name} successfully paired and added to trusted devices!"
                        } else {
                            "Pairing with ${peer.name} was canceled or declined."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Failed to pair: ${e.message}")
                }
            } finally {
                _uiState.update { it.copy(isPairingInProgress = false, pendingPairing = null) }
            }
        }
    }

    fun confirmPairing(confirmed: Boolean) {
        val pending = _uiState.value.pendingPairing
        if (pending != null) {
            if (pending.continuation.isActive) {
                pending.continuation.resume(confirmed)
            }
            _uiState.update { it.copy(pendingPairing = null) }
        }
    }

    fun renameTrustedDevice(deviceId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            activeNode?.trustStore?.rename(deviceId, newName)
            _uiState.update {
                it.copy(statusMessage = "Device renamed successfully")
            }
        }
    }

    fun removeTrustedDevice(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            activeNode?.trustStore?.remove(deviceId)
        }
    }

    fun clearAllTrustedDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            activeNode?.trustStore?.clear()
            _uiState.update {
                it.copy(statusMessage = "All trusted devices cleared")
            }
        }
    }

    fun setDiscoveryMode(mode: DiscoveryMode) {
        activeNode?.setMode(mode)
        _uiState.update { it.copy(discoveryMode = mode) }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun dismissProgress() {
        _uiState.update { it.copy(transferProgress = null) }
    }

    fun dismissIncomingText() {
        _uiState.update { it.copy(incomingSharedText = null) }
    }

    private var activeTransferJob: Job? = null

    fun cancelTransfer() {
        activeTransferJob?.cancel()
        activeNode?.cancelTransfer()
        _uiState.update {
            it.copy(statusMessage = "Transfer cancelled")
        }
    }

    fun sendFiles(peer: PeerDevice, paths: List<Path>) {
        val node = activeNode ?: return
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var counter = 1
                val fileMap = mutableMapOf<FileMetadata, Path>()
                for (path in paths) {
                    val metadata = FileSystem.SYSTEM.metadataOrNull(path) ?: continue
                    val sha256 = ChecksumUtil.calculateFileSha256(path)
                    val meta = FileMetadata.create(
                        fileId = counter++,
                        fileName = path.name,
                        fileSizeBytes = metadata.size ?: 0L,
                        sha256 = sha256
                    )
                    fileMap[meta] = path
                }
                if (fileMap.isNotEmpty()) {
                    node.sendFiles(peer, fileMap)
                    _uiState.update {
                        it.copy(statusMessage = "Sent ${fileMap.size} file(s) to ${peer.name}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update {
                    it.copy(statusMessage = "Transfer to ${peer.name} cancelled")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Failed to send file(s) to ${peer.name}: ${e.message}")
                }
            } finally {
                activeTransferJob = null
            }
        }
    }

    fun sendFilesToTrusted(device: TrustedDevice, paths: List<Path>) {
        val onlinePeer = _uiState.value.discoveredDevices.firstOrNull { it.id == device.id }
        if (onlinePeer != null) {
            sendFiles(onlinePeer, paths)
            return
        }
        val ip = device.lastKnownIp
        if (ip != null) {
            val synthesizedPeer = PeerDevice(
                id = device.id,
                name = device.name,
                ipAddress = ip,
                port = device.lastKnownPort,
                deviceType = com.app.quickpear.domain.DeviceType.UNKNOWN,
                connectionType = com.app.quickpear.domain.ConnectionType.LAN_WIFI
            )
            sendFiles(synthesizedPeer, paths)
        } else {
            val synthesizedCloudPeer = PeerDevice(
                id = device.id,
                name = device.name,
                ipAddress = "cloud-relay",
                port = 0,
                deviceType = com.app.quickpear.domain.DeviceType.UNKNOWN,
                connectionType = com.app.quickpear.domain.ConnectionType.CLOUD_P2P
            )
            sendFiles(synthesizedCloudPeer, paths)
        }
    }

    fun sendText(peer: PeerDevice, text: String) {
        val node = activeNode ?: return
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = node.sendText(peer, text)
                _uiState.update {
                    it.copy(
                        statusMessage = if (ok) "Text sent to ${peer.name}" else "Failed to send text to ${peer.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Failed to send text to ${peer.name}: ${e.message}")
                }
            }
        }
    }

    fun sendTextToTrusted(device: TrustedDevice, text: String) {
        val onlinePeer = _uiState.value.discoveredDevices.firstOrNull { it.id == device.id }
        if (onlinePeer != null) {
            sendText(onlinePeer, text)
            return
        }
        val ip = device.lastKnownIp
        if (ip != null) {
            val synthesizedPeer = PeerDevice(
                id = device.id,
                name = device.name,
                ipAddress = ip,
                port = device.lastKnownPort,
                deviceType = com.app.quickpear.domain.DeviceType.UNKNOWN,
                connectionType = com.app.quickpear.domain.ConnectionType.LAN_WIFI
            )
            sendText(synthesizedPeer, text)
        } else {
            val synthesizedCloudPeer = PeerDevice(
                id = device.id,
                name = device.name,
                ipAddress = "cloud-relay",
                port = 0,
                deviceType = com.app.quickpear.domain.DeviceType.UNKNOWN,
                connectionType = com.app.quickpear.domain.ConnectionType.CLOUD_P2P
            )
            sendText(synthesizedCloudPeer, text)
        }
    }

    private var remotePairingJob: Job? = null

    fun openRemotePairDialog() {
        cancelRemotePairing()
        val node = activeNode
        val code = node?.generateCloudPairingCode() ?: (100_000 + (Math.random() * 900_000).toInt()).toString()
        _uiState.update {
            it.copy(
                showRemotePairDialog = true,
                generatedPairingCode = code,
                isRemotePairingInProgress = false,
                pendingRemoteSas = null
            )
        }
    }

    fun cancelRemotePairing() {
        remotePairingJob?.cancel()
        remotePairingJob = null
        _uiState.update {
            it.copy(
                isRemotePairingInProgress = false,
                pendingRemoteSas = null
            )
        }
    }

    fun closeRemotePairDialog() {
        cancelRemotePairing()
        _uiState.update {
            it.copy(
                showRemotePairDialog = false,
                generatedPairingCode = null,
                isRemotePairingInProgress = false,
                pendingRemoteSas = null
            )
        }
    }

    fun startRemotePairingAsHost() {
        val node = activeNode ?: return
        val code = _uiState.value.generatedPairingCode ?: return
        remotePairingJob?.cancel()
        remotePairingJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRemotePairingInProgress = true) }
            try {
                val success = node.startCloudPairing(code) { peerName, sasCode ->
                    suspendCancellableCoroutine { cont ->
                        _uiState.update {
                            it.copy(
                                pendingRemoteSas = RemotePairingSasState(
                                    peerName = peerName,
                                    sasCode = sasCode,
                                    continuation = cont
                                )
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        showRemotePairDialog = false,
                        statusMessage = if (success) {
                            "Device successfully paired and added to trusted devices!"
                        } else {
                            "Remote pairing was canceled or declined."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Remote pairing failed: ${e.message}")
                }
            } finally {
                _uiState.update {
                    it.copy(isRemotePairingInProgress = false, pendingRemoteSas = null)
                }
            }
        }
    }

    fun joinRemotePairingAsClient(code: String) {
        val node = activeNode ?: return
        val cleanCode = code.trim().replace(" ", "")
        if (cleanCode.length != 6) {
            _uiState.update { it.copy(statusMessage = "Pairing code must be 6 digits.") }
            return
        }
        remotePairingJob?.cancel()
        remotePairingJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRemotePairingInProgress = true) }
            try {
                val success = node.joinCloudPairing(cleanCode) { peerName, sasCode ->
                    suspendCancellableCoroutine { cont ->
                        _uiState.update {
                            it.copy(
                                pendingRemoteSas = RemotePairingSasState(
                                    peerName = peerName,
                                    sasCode = sasCode,
                                    continuation = cont
                                )
                            )
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        showRemotePairDialog = false,
                        statusMessage = if (success) {
                            "Successfully connected and paired with target device!"
                        } else {
                            "Remote pairing was canceled or declined."
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Failed to join: ${e.message}")
                }
            } finally {
                _uiState.update {
                    it.copy(isRemotePairingInProgress = false, pendingRemoteSas = null)
                }
            }
        }
    }

    fun confirmRemoteSas(confirmed: Boolean) {
        val pending = _uiState.value.pendingRemoteSas
        if (pending != null) {
            if (pending.continuation.isActive) {
                pending.continuation.resume(confirmed)
            }
            _uiState.update { it.copy(pendingRemoteSas = null) }
        }
    }

    fun startWebShare(paths: List<Path>, withHotspot: Boolean = false) {
        val node = activeNode ?: return
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var hotspotDesc: String? = null
                var ip = node.localDevice.ipAddress

                if (withHotspot && node.p2pLinkNegotiator.isHotspotSupported()) {
                    val hotspot = node.p2pLinkNegotiator.startLocalHotspot()
                    if (hotspot != null) {
                        ip = hotspot.hostIp
                        hotspotDesc = "Wi-Fi: ${hotspot.ssid} (Password: ${hotspot.passphrase})"
                    }
                }

                val port = node.webShareServer.start(paths)
                val shareUrl = "http://$ip:$port"
                _uiState.update {
                    it.copy(
                        webShareUrl = shareUrl,
                        webShareFiles = paths,
                        webShareHotspotInfo = hotspotDesc,
                        statusMessage = "Web Share active at $shareUrl"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Failed to start Web Share: ${e.message}")
                }
            }
        }
    }

    fun stopWebShare() {
        val node = activeNode ?: return
        viewModelScope.launch(Dispatchers.IO) {
            node.webShareServer.stop()
            if (node.p2pLinkNegotiator.isHotspotSupported()) {
                node.p2pLinkNegotiator.stopLocalHotspot()
            }
            _uiState.update {
                it.copy(
                    webShareUrl = null,
                    webShareFiles = emptyList(),
                    webShareHotspotInfo = null,
                    statusMessage = "Web Share stopped"
                )
            }
        }
    }
}
