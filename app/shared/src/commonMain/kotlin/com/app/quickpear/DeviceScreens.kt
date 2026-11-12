package com.app.quickpear

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import org.jetbrains.compose.resources.painterResource
import quickpear.app.shared.generated.resources.Res
import quickpear.app.shared.generated.resources.app_logo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
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
import okio.Path

@Composable
fun NearbyDevicesScreen(
    devices: List<PeerDevice>,
    selectedDevice: PeerDevice?,
    trustedDevices: List<TrustedDevice>?,
    isPairing: Boolean,
    onDeviceSelected: (PeerDevice) -> Unit,
    onPairClicked: (PeerDevice) -> Unit,
    onSendClicked: (PeerDevice) -> Unit,
    onSendTextClicked: (PeerDevice) -> Unit,
    onRenameClicked: (TrustedDevice) -> Unit,
    onStartWebShare: () -> Unit,
    onRemotePairClicked: () -> Unit
) {
    val trustedList = trustedDevices ?: emptyList()
    val trustedOnlinePeers = devices.filter { peer -> trustedList.any { it.id == peer.id } }
    val otherOnlinePeers = devices.filterNot { peer -> trustedList.any { it.id == peer.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick Action Buttons Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRemotePairClicked,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pair with Code", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                OutlinedButton(
                    onClick = onStartWebShare,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QuickPearColors.RoyalBlue),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD6E2FF)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Web Share (QR)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Compact Discovery Scanning Header
        item {
            RadarView(
                devices = devices,
                selectedDevice = selectedDevice,
                onDeviceSelected = onDeviceSelected,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ---------------------------------------------------------
        // SECTION 1: TRUSTED DEVICES (ONLINE)
        // ---------------------------------------------------------
        if (trustedOnlinePeers.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(QuickPearColors.PearGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Trusted Devices (${trustedOnlinePeers.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = QuickPearColors.WarmCream
                        )
                    }
                    Text(
                        text = "Instant Transfer",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = QuickPearColors.PearGreen
                    )
                }
            }

            items(trustedOnlinePeers) { peer ->
                val trusted = trustedList.firstOrNull { it.id == peer.id }
                DiscoveredDeviceCard(
                    peer = peer,
                    trustedDevice = trusted,
                    isPairing = isPairing,
                    onPair = { onPairClicked(peer) },
                    onSend = { onSendClicked(peer) },
                    onSendText = { onSendTextClicked(peer) },
                    onRename = { trusted?.let { onRenameClicked(it) } }
                )
            }
        }

        // ---------------------------------------------------------
        // SECTION 2: OTHER DISCOVERED DEVICES
        // ---------------------------------------------------------
        if (otherOnlinePeers.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = if (trustedOnlinePeers.isNotEmpty()) 10.dp else 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (trustedOnlinePeers.isNotEmpty()) "Other Discovered Devices (${otherOnlinePeers.size})" else "Discovered Devices (${otherOnlinePeers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = QuickPearColors.WarmCream
                    )
                    Text(
                        text = "Auto-connected",
                        fontSize = 11.sp,
                        color = QuickPearColors.WarmGray
                    )
                }
            }

            items(otherOnlinePeers) { peer ->
                DiscoveredDeviceCard(
                    peer = peer,
                    trustedDevice = null,
                    isPairing = isPairing,
                    onPair = { onPairClicked(peer) },
                    onSend = { onSendClicked(peer) },
                    onSendText = { onSendTextClicked(peer) },
                    onRename = {}
                )
            }
        }

        // Empty state when absolutely no devices are discovered
        if (devices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = QuickPearColors.PearGreen,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Looking for devices...",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = QuickPearColors.WarmCream
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Make sure Quick Pear is open on your other devices. You can also pair across networks using a 6-digit code.",
                            fontSize = 12.sp,
                            color = QuickPearColors.WarmGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoveredDeviceCard(
    peer: PeerDevice,
    trustedDevice: TrustedDevice?,
    isPairing: Boolean,
    onPair: () -> Unit,
    onSend: () -> Unit,
    onSendText: () -> Unit,
    onRename: () -> Unit
) {
    val isTrusted = trustedDevice != null
    val customAlias = trustedDevice?.customName?.takeIf { it.isNotBlank() }
    val primaryDisplayName = customAlias ?: peer.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTrusted) QuickPearColors.CardSurface else QuickPearColors.CardSurface
        ),
        border = BorderStroke(
            1.dp,
            if (isTrusted) QuickPearColors.PearGreen.copy(alpha = 0.5f)
            else QuickPearColors.SlateIndigo.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Device name & connection tags
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = primaryDisplayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = QuickPearColors.WarmCream
                        )
                        if (customAlias != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(${peer.name})",
                                fontSize = 12.sp,
                                color = QuickPearColors.WarmGray
                            )
                        }
                    }

                    if (isTrusted) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(QuickPearColors.PearGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Trusted",
                                fontSize = 10.sp,
                                color = QuickPearColors.PearGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val connectionLabel = when (peer.connectionType) {
                    ConnectionType.CLOUD_P2P -> "Cloud Relay"
                    ConnectionType.LOCAL_HOTSPOT -> "Hotspot"
                    ConnectionType.WIFI_DIRECT -> "Wi-Fi Direct"
                    ConnectionType.LAN_WIFI -> "Local Wi-Fi"
                    ConnectionType.BLE -> "BLE"
                }

                val deviceTypeLabel = when (peer.deviceType) {
                    DeviceType.WINDOWS -> "Windows"
                    DeviceType.ANDROID -> "Android"
                    DeviceType.LINUX -> "Linux"
                    DeviceType.MACOS -> "macOS"
                    DeviceType.IOS -> "iOS"
                    DeviceType.WEB -> "Web"
                    DeviceType.UNKNOWN -> "Device"
                }

                Text(
                    text = "$deviceTypeLabel • $connectionLabel • ${peer.ipAddress}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = QuickPearColors.WarmGray
                )
            }

            // Action row placed underneath
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isTrusted) {
                    Button(
                        onClick = onSend,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = onSendText,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Text", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = onRename,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD6E2FF)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Rename", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onSend,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onPair,
                        enabled = !isPairing,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text(
                            if (isPairing) "Pairing..." else "Pair",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 2. TRUSTED DEVICES SCREEN
// -------------------------------------------------------------
@Composable
fun TrustedDevicesScreen(
    trustedDevices: List<TrustedDevice>,
    onlineDevices: List<PeerDevice>,
    onSendClicked: (PeerDevice) -> Unit,
    onSendTextClicked: (PeerDevice) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (TrustedDevice) -> Unit,
    onRemotePairClicked: () -> Unit
) {
    var deviceToDelete by remember { mutableStateOf<TrustedDevice?>(null) }
    var deviceToRename by remember { mutableStateOf<TrustedDevice?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trusted Devices (${trustedDevices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = QuickPearColors.WarmCream
                    )
                    Text(
                        text = "Instant 1-click sharing without repetitive prompts.",
                        fontSize = 11.sp,
                        color = QuickPearColors.WarmGray
                    )
                }
                Button(
                    onClick = onRemotePairClicked,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue)
                ) {
                    Text("+ Pair New", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }

        if (trustedDevices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No Trusted Devices Yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = QuickPearColors.WarmCream
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Pair your computer, phone, or tablet once to enable instant wireless transfers anytime without confirmation prompts.",
                            fontSize = 12.sp,
                            color = QuickPearColors.WarmGray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onRemotePairClicked,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue)
                        ) {
                            Text("Pair with 6-Digit Code", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        } else {
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

                TrustedDeviceCard(
                    device = device,
                    isOnline = isOnline,
                    onSend = { onSendClicked(peerToSend) },
                    onSendText = { onSendTextClicked(peerToSend) },
                    onRename = { deviceToRename = device },
                    onDelete = { deviceToDelete = device }
                )
            }
        }
    }

    // Rename Device Dialog
    deviceToRename?.let { device ->
        var renameInput by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            containerColor = QuickPearColors.SurfaceDark,
            title = {
                Text("Rename Trusted Device", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        text = "Enter a custom name for this trusted device:",
                        fontSize = 12.sp,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        placeholder = { Text("Device name", color = QuickPearColors.SlateMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = QuickPearColors.WarmCream,
                            unfocusedTextColor = QuickPearColors.WarmCream,
                            focusedBorderColor = QuickPearColors.PearGreen,
                            unfocusedBorderColor = QuickPearColors.SlateIndigo
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRename(device.id, renameInput.trim())
                            deviceToRename = null
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
                ) {
                    Text("Save", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deviceToRename = null },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    deviceToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { deviceToDelete = null },
            containerColor = QuickPearColors.SurfaceDark,
            title = { Text("Remove Trusted Device?", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to remove '${target.name}' from your trusted devices? Future transfers will require manual confirmation.",
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove(target)
                        deviceToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Remove", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deviceToDelete = null },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TrustedDeviceCard(
    device: TrustedDevice,
    isOnline: Boolean,
    onSend: () -> Unit,
    onSendText: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Device Name, Online Status Badge, and Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val customAlias = device.customName?.takeIf { it.isNotBlank() }
                    Text(
                        text = customAlias ?: device.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = QuickPearColors.WarmCream
                    )
                    if (customAlias != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(${device.name})",
                            fontSize = 12.sp,
                            color = QuickPearColors.WarmGray
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isOnline) QuickPearColors.PearGreen.copy(alpha = 0.2f)
                                else QuickPearColors.SlateMuted.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isOnline) QuickPearColors.PearGreen else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOnline) "Online" else "Offline",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isOnline) QuickPearColors.PearGreen else QuickPearColors.WarmGray
                            )
                        }
                    }
                }

                // Compact Remove Button at the top-right
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                    modifier = Modifier.size(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("✕", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Middle Subtitle: ID or Last IP
            val addressText = if (device.lastKnownIp != null) {
                "Last IP: ${device.lastKnownIp}:${device.lastKnownPort}"
            } else {
                "Device ID: ${device.id}"
            }
            Text(
                text = addressText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = QuickPearColors.WarmGray
            )

            // Bottom Action Row: Send, Text, Rename placed neatly below name & ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSend,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text("Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                OutlinedButton(
                    onClick = onSendText,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Text", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onRename,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD6E2FF)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Rename", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// -------------------------------------------------------------
// 3. SETTINGS SCREEN
// -------------------------------------------------------------
