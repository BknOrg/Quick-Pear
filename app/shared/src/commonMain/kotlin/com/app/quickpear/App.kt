package com.app.quickpear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.security.TrustedDevice
import com.app.quickpear.ui.PairingUiState
import com.app.quickpear.ui.RemotePairingSasState
import com.app.quickpear.ui.TransferViewModel
import com.app.quickpear.ui.components.QrCodeView
import com.app.quickpear.ui.components.RadarView
import com.app.quickpear.ui.components.TransferProgressDialog
import okio.Path

@Composable
fun App(
    viewModel: TransferViewModel = remember { TransferViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Sekitar", "Terpercaya", "Pengaturan")

    var textTargetPeer by remember { mutableStateOf<PeerDevice?>(null) }

    val filePickerLauncher = com.app.quickpear.ui.rememberFilePickerLauncher { peer, paths ->
        viewModel.sendFiles(peer, paths)
    }

    val webShareFilePicker = com.app.quickpear.ui.rememberFilePickerLauncher { _, paths ->
        viewModel.startWebShare(paths, withHotspot = true)
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Quick Pear",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = state.localDeviceName.ifEmpty { "Transfer Nirkabel Cepat" },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        // Background readiness & cloud badge
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "● Cloud Hybrid Siap",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF059669)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Text(
                                        text = title,
                                        fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            },
            bottomBar = {
                state.statusMessage?.let { msg ->
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.dismissStatusMessage() }) {
                                Text("Tutup", color = MaterialTheme.colorScheme.inversePrimary)
                            }
                        }
                    ) {
                        Text(text = msg)
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (selectedTabIndex) {
                    0 -> NearbyDevicesTab(
                        devices = state.discoveredDevices,
                        selectedDevice = state.selectedDevice,
                        trustedDevices = state.trustedDevices,
                        isPairing = state.isPairingInProgress,
                        onDeviceSelected = { viewModel.selectDevice(it) },
                        onPairClicked = { viewModel.initiatePairing(it) },
                        onSendClicked = { filePickerLauncher(it) },
                        onSendTextClicked = { textTargetPeer = it },
                        onStartWebShare = {
                            webShareFilePicker(PeerDevice(id = "", name = "Web Share", ipAddress = ""))
                        },
                        onRemotePairClicked = { viewModel.openRemotePairDialog() }
                    )
                    1 -> TrustedDevicesTab(
                        trustedDevices = state.trustedDevices,
                        onlineDevices = state.discoveredDevices,
                        onSendClicked = { filePickerLauncher(it) },
                        onSendTextClicked = { textTargetPeer = it },
                        onRemove = { viewModel.removeTrustedDevice(it.id) },
                        onRemotePairClicked = { viewModel.openRemotePairDialog() }
                    )
                    2 -> SettingsTab(
                        deviceName = state.localDeviceName,
                        deviceId = state.localDeviceId,
                        localIpAddress = state.localIpAddress,
                        allLocalIpAddresses = state.allLocalIpAddresses,
                        localPort = state.localPort,
                        mode = state.discoveryMode,
                        onModeChange = { viewModel.setDiscoveryMode(it) }
                    )
                }

                // Pairing confirmation dialog (Local LAN)
                state.pendingPairing?.let { pairing ->
                    PairingConfirmationDialog(
                        pairing = pairing,
                        onConfirm = { viewModel.confirmPairing(true) },
                        onReject = { viewModel.confirmPairing(false) }
                    )
                }

                // Remote Pairing Dialog (Cross-network pairing via 6-digit code)
                if (state.showRemotePairDialog || state.pendingRemoteSas != null) {
                    RemotePairDialog(
                        generatedCode = state.generatedPairingCode,
                        isPairingInProgress = state.isRemotePairingInProgress,
                        pendingSas = state.pendingRemoteSas,
                        onStartHost = { viewModel.startRemotePairingAsHost() },
                        onJoinClient = { code -> viewModel.joinRemotePairingAsClient(code) },
                        onCancelPairing = { viewModel.cancelRemotePairing() },
                        onConfirmSas = { confirmed -> viewModel.confirmRemoteSas(confirmed) },
                        onDismiss = { viewModel.closeRemotePairDialog() }
                    )
                }

                // Send Text Dialog
                textTargetPeer?.let { target ->
                    SendTextDialog(
                        targetName = target.name,
                        onDismiss = { textTargetPeer = null },
                        onSend = { text ->
                            viewModel.sendText(target, text)
                            textTargetPeer = null
                        }
                    )
                }

                // Incoming Text Dialog
                state.incomingSharedText?.let { (sender, text) ->
                    IncomingTextDialog(
                        senderName = sender,
                        text = text,
                        onDismiss = { viewModel.dismissIncomingText() }
                    )
                }

                // Web Share QR Dialog
                state.webShareUrl?.let { url ->
                    WebShareQrDialog(
                        url = url,
                        files = state.webShareFiles,
                        hotspotInfo = state.webShareHotspotInfo,
                        onDismiss = { viewModel.stopWebShare() }
                    )
                }

                // Non-intrusive floating transfer banner (leaves screen fully usable)
                state.transferProgress?.let { progress ->
                    if (progress.status == com.app.quickpear.domain.TransferStatus.TRANSFERRING) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Mentransfer: ${progress.fileName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${(progress.progressPercentage * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { progress.progressPercentage },
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NearbyDevicesTab(
    devices: List<PeerDevice>,
    selectedDevice: PeerDevice?,
    trustedDevices: List<TrustedDevicesItemState>?,
    isPairing: Boolean,
    onDeviceSelected: (PeerDevice) -> Unit,
    onPairClicked: (PeerDevice) -> Unit,
    onSendClicked: (PeerDevice) -> Unit,
    onSendTextClicked: (PeerDevice) -> Unit,
    onStartWebShare: () -> Unit,
    onRemotePairClicked: () -> Unit = {}
) {
    NearbyDevicesContent(
        devices = devices,
        selectedDevice = selectedDevice,
        trustedDevices = trustedDevices ?: emptyList(),
        isPairing = isPairing,
        onDeviceSelected = onDeviceSelected,
        onPairClicked = onPairClicked,
        onSendClicked = onSendClicked,
        onSendTextClicked = onSendTextClicked,
        onStartWebShare = onStartWebShare,
        onRemotePairClicked = onRemotePairClicked
    )
}

typealias TrustedDevicesItemState = TrustedDevice

@Composable
private fun NearbyDevicesContent(
    devices: List<PeerDevice>,
    selectedDevice: PeerDevice?,
    trustedDevices: List<TrustedDevice>,
    isPairing: Boolean,
    onDeviceSelected: (PeerDevice) -> Unit,
    onPairClicked: (PeerDevice) -> Unit,
    onSendClicked: (PeerDevice) -> Unit,
    onSendTextClicked: (PeerDevice) -> Unit,
    onStartWebShare: () -> Unit,
    onRemotePairClicked: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Radar Visualization
        RadarView(
            devices = devices,
            selectedDevice = selectedDevice,
            onDeviceSelected = onDeviceSelected,
            sizeDp = 220.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Perangkat di Sekitar & Cloud (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Otomatis terhubung lintas Wi-Fi dan data seluler.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onRemotePairClicked,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Pasangkan Kode", fontSize = 11.sp)
                }
                Button(
                    onClick = onStartWebShare,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Bagi Web (QR)", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Mencari perangkat terpercaya & sekitar...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Perangkat terpercaya otomatis muncul saat menyala di mana saja.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRemotePairClicked,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Pasangkan Jarak Jauh (Beda Jaringan)", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onStartWebShare,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Bagi via Web / QR Code (Untuk iPhone & Tamu)", fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { peer ->
                    val isTrusted = trustedDevices.any { it.id == peer.id }
                    DiscoveredDeviceItem(
                        peer = peer,
                        isTrusted = isTrusted,
                        isPairing = isPairing,
                        onPair = { onPairClicked(peer) },
                        onSend = { onSendClicked(peer) },
                        onSendText = { onSendTextClicked(peer) }
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoveredDeviceItem(
    peer: PeerDevice,
    isTrusted: Boolean,
    isPairing: Boolean,
    onPair: () -> Unit,
    onSend: () -> Unit,
    onSendText: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isTrusted) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Terpercaya",
                                    fontSize = 10.sp,
                                    color = Color(0xFF059669),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val connectionLabel = when (peer.connectionType) {
                        ConnectionType.CLOUD_P2P -> "Cloud P2P"
                        ConnectionType.LOCAL_HOTSPOT -> "Hotspot"
                        ConnectionType.WIFI_DIRECT -> "Wi-Fi Direct"
                        ConnectionType.LAN_WIFI -> "Wi-Fi Lokal"
                        ConnectionType.BLE -> "BLE"
                    }

                    val deviceTypeLabel = when (peer.deviceType) {
                        DeviceType.WINDOWS -> "Windows"
                        DeviceType.ANDROID -> "Android"
                        DeviceType.LINUX -> "Linux"
                        DeviceType.MACOS -> "macOS"
                        DeviceType.IOS -> "iOS"
                        DeviceType.WEB -> "Web"
                        DeviceType.UNKNOWN -> "Perangkat"
                    }

                    Text(
                        text = "$deviceTypeLabel • $connectionLabel • ${peer.ipAddress}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isTrusted) {
                        Button(
                            onClick = onSend,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Kirim Berkas", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onSendText,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Kirim Teks", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onSend,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Kirim", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = onPair,
                            enabled = !isPairing,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Pasangkan", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrustedDevicesTab(
    trustedDevices: List<TrustedDevice>,
    onlineDevices: List<PeerDevice>,
    onSendClicked: (PeerDevice) -> Unit,
    onSendTextClicked: (PeerDevice) -> Unit,
    onRemove: (TrustedDevice) -> Unit,
    onRemotePairClicked: () -> Unit = {}
) {
    var deviceToDelete by remember { mutableStateOf<TrustedDevice?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daftar Perangkat Terpercaya (${trustedDevices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Transfer instan 1-klik tanpa konfirmasi lintas jaringan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Button(
                onClick = onRemotePairClicked,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("+ Pasangkan Baru", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (trustedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "Belum Ada Perangkat Terpercaya",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Pasangkan perangkat Anda yang lain (Laptop, HP, Tablet) untuk transfer berkas instan kapan saja tanpa perlu konfirmasi lagi.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRemotePairClicked,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Pasangkan Perangkat (Kode 6-Angka)", fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(trustedDevices) { device ->
                    val onlinePeer = onlineDevices.firstOrNull { it.id == device.id }
                    val isOnline = onlinePeer != null
                    val peerToSend = onlinePeer ?: device.lastKnownIp?.let { ip ->
                        PeerDevice(
                            id = device.id,
                            name = device.name,
                            ipAddress = ip,
                            port = device.lastKnownPort,
                            deviceType = DeviceType.UNKNOWN,
                            connectionType = ConnectionType.LAN_WIFI
                        )
                    } ?: PeerDevice(
                        id = device.id,
                        name = device.name,
                        ipAddress = "cloud-relay",
                        port = 0,
                        deviceType = DeviceType.UNKNOWN,
                        connectionType = ConnectionType.CLOUD_P2P
                    )
                    TrustedDeviceItem(
                        device = device,
                        isOnline = isOnline,
                        onSend = { onSendClicked(peerToSend) },
                        onSendText = { onSendTextClicked(peerToSend) },
                        onDelete = { deviceToDelete = device }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    deviceToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            title = { Text("Hapus Perangkat Terpercaya?") },
            text = {
                Text("Apakah Anda yakin ingin menghapus '${target.name}' dari daftar terpercaya? Anda harus mengonfirmasi secara manual jika perangkat ini mengirim berkas nanti.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove(target)
                        deviceToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Hapus", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deviceToDelete = null }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
fun TrustedDeviceItem(
    device: TrustedDevice,
    isOnline: Boolean,
    onSend: (() -> Unit)? = null,
    onSendText: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color(0xFF10B981) else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isOnline) "Online" else "Offline",
                        fontSize = 11.sp,
                        color = if (isOnline) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                val addressText = if (device.lastKnownIp != null) {
                    "Terkoneksi: ${device.lastKnownIp}:${device.lastKnownPort}"
                } else {
                    "ID: ${device.id.take(12)}..."
                }
                Text(
                    text = addressText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (onSend != null) {
                    Button(
                        onClick = onSend,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isOnline) "Kirim Berkas" else "Kirim", fontSize = 12.sp)
                    }
                }
                if (onSendText != null) {
                    OutlinedButton(
                        onClick = onSendText,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Teks", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Hapus", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    deviceName: String,
    deviceId: String,
    localIpAddress: String,
    allLocalIpAddresses: List<String> = emptyList(),
    localPort: Int,
    mode: DiscoveryMode,
    onModeChange: (DiscoveryMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Informasi & Pengaturan",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status Konektivitas Quick Pear",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Nama Perangkat: $deviceName", fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ID Kriptografi: ${deviceId.take(24)}...",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                val ipDisplay = if (allLocalIpAddresses.isNotEmpty()) {
                    allLocalIpAddresses.joinToString(" • ") { "$it:$localPort" }
                } else {
                    "${if (localIpAddress.isNotBlank()) localIpAddress else "127.0.0.1"}:$localPort"
                }
                Text(
                    text = "Alamat Lokal: $ipDisplay",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Cloud Hybrid Signaling: ",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "Aktif (Google STUN + Cloud Presence)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Dukungan Apple: ",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Text(
                        text = "macOS (.dmg) & iOS (Web Portal)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mode Penemuan (Discovery)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Interval broadcast beacon untuk efisiensi daya:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiscoveryMode.entries.forEach { entry ->
                        val isSelected = mode == entry
                        val label = when (entry) {
                            DiscoveryMode.ACTIVE -> "Aktif (3s)"
                            DiscoveryMode.BACKGROUND -> "Latar Belakang (20s)"
                            DiscoveryMode.POWER_SAVER -> "Hemat Daya (60s)"
                        }
                        Button(
                            onClick = { onModeChange(entry) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Fitur Unggulan Quick Pear",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Otomatis & Bebas Login: Tanpa kata sandi atau akun. Perangkat terpercaya otomatis saling terhubung.\n" +
                            "• Lintas Jaringan: Temukan dan kirim berkas walau berbeda jaringan (misal PC di Wi-Fi rumah, HP di paket data 4G/5G).\n" +
                            "• Bagi Cepat Web (Apple / Tamu): Bagikan berkas ke iPhone, iPad, atau komputer tamu tanpa mereka harus instal aplikasi cukup scan QR Code!\n" +
                            "• Kirim Teks / Clipboard: Salin tautan atau catatan di satu perangkat dan langsung kirim ke perangkat lain.\n" +
                            "• Transfer Folder: Struktur folder dan sub-folder dipertahankan utuh.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
fun PairingConfirmationDialog(
    pairing: PairingUiState,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val sas = pairing.sasCode
    val formattedCode = if (sas.length == 6) "${sas.take(3)} ${sas.takeLast(3)}" else sas

    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(text = "Konfirmasi Pemasangan")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Memasangkan dengan:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = pairing.peer.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Cocokkan kode angka berikut dengan layar perangkat tujuan:",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        text = formattedCode,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Jika kode sama, perangkat akan otomatis ditambahkan ke daftar terpercaya.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cocok & Percayai")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun SendTextDialog(
    targetName: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kirim Teks ke $targetName") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Ketik atau tempel teks dari papan klip untuk dikirim langsung:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Masukkan teks di sini...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (textInput.isNotBlank()) onSend(textInput)
                },
                enabled = textInput.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Kirim Teks")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun IncomingTextDialog(
    senderName: String,
    text: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Teks Diterima dari $senderName") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(14.dp),
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("Selesai")
            }
        }
    )
}

@Composable
fun WebShareQrDialog(
    url: String,
    files: List<Path>,
    hotspotInfo: String? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Mode Bagi Web (Apple & Tamu)")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Buka kamera iPhone / perangkat tamu dan scan kode QR di bawah untuk mengunduh:",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // QR Code
                QrCodeView(
                    content = url,
                    size = 190.dp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = url,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )

                if (hotspotInfo != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Hotspot Mandiri Aktif:\n$hotspotInfo",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Membagikan ${files.size} berkas secara instan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Hentikan Berbagi")
            }
        }
    )
}

@Composable
fun RemotePairDialog(
    generatedCode: String?,
    isPairingInProgress: Boolean,
    pendingSas: RemotePairingSasState?,
    onStartHost: () -> Unit,
    onJoinClient: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onConfirmSas: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (pendingSas != null) {
        val sas = pendingSas.sasCode
        val formattedCode = if (sas.length == 6) "${sas.take(3)} ${sas.takeLast(3)}" else sas

        AlertDialog(
            onDismissRequest = { onConfirmSas(false) },
            title = {
                Text(text = "Konfirmasi Keamanan (SAS)")
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Terhubung dengan perangkat:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = pendingSas.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Pastikan kode keamanan angka berikut SAMA dengan yang ada di layar perangkat tersebut:",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Jika kode sama persis, tekan 'Cocok & Percayai'. Setelah ini, transfer akan otomatis tanpa kode lagi!",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmSas(true) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cocok & Percayai")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { onConfirmSas(false) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Tolak")
                }
            }
        )
        return
    }

    var selectedMode by remember { mutableStateOf(0) }
    var clientCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Pasangkan Jarak Jauh (Beda Jaringan)")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TabRow(
                    selectedTabIndex = selectedMode,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Tab(
                        selected = selectedMode == 0,
                        onClick = {
                            if (selectedMode != 0) {
                                onCancelPairing()
                                selectedMode = 0
                            }
                        },
                        text = { Text("Tampilkan Kode", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedMode == 1,
                        onClick = {
                            if (selectedMode != 1) {
                                onCancelPairing()
                                selectedMode = 1
                            }
                        },
                        text = { Text("Masukkan Kode", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedMode == 0) {
                    val code = generatedCode ?: "------"
                    val formattedCode = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code

                    Text(
                        text = "Bagikan kode ini ke perangkat lain untuk menghubungkan lintas jaringan:",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isPairingInProgress) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Menunggu sambungan dari perangkat lain...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onCancelPairing,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Hentikan Menunggu", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = onStartHost,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Mulai Menunggu Sambungan")
                        }
                    }
                } else {
                    Text(
                        text = "Masukkan kode 6-angka yang tertera di layar perangkat tujuan:",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = clientCodeInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(6)
                            clientCodeInput = filtered
                        },
                        placeholder = { Text("Contoh: 123456", textAlign = TextAlign.Center) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isPairingInProgress) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Menghubungkan ke perangkat tujuan...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onCancelPairing,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Batal", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = { onJoinClient(clientCodeInput) },
                            enabled = clientCodeInput.length == 6,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Hubungkan & Pasangkan")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Pemasangan hanya dilakukan 1x. Setelah dipasangkan, kedua perangkat akan selalu saling terhubung otomatis.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("Tutup")
            }
        }
    )
}
