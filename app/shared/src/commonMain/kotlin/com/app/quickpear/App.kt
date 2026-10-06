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

/**
 * 7 User Theme Palette - Dark Charcoal (#3D383C) Mode:
 * - #3D383C: Dark Espresso Charcoal (Main Background & Container)
 * - #FFF6E9: Warm Cream (Primary Headings & Text)
 * - #6E9D24: Pear Green (Brand Primary Accent & Online Status)
 * - #606974: Slate Muted (Inactive Elements & Helper Labels)
 * - #CECECC: Warm Gray (Outlines, Dividers & Secondary Typography)
 * - #405DB7: Royal Blue (Secondary Action Buttons & Links)
 * - #4B5B76: Slate Indigo (Card Borders, Category Chips & Tags)
 */
object QuickPearColors {
    val DarkCharcoal = Color(0xFF3D383C) // Base dark background (#3d383c)
    val SurfaceDark = Color(0xFF2C282B)  // Top bar, bottom bar & dialog container
    val CardSurface = Color(0xFF353034)  // Cards background
    val WarmCream = Color(0xFFFFF6E9)    // Primary text & headings (#fff6e9)
    val WarmGray = Color(0xFFCECECC)     // Outlines & subtle borders (#cececc)
    val PearGreen = Color(0xFF6E9D24)    // Brand Accent, Online status & primary buttons (#6e9d24)
    val SlateMuted = Color(0xFF606974)   // Inactive tab icons, metadata (#606974)
    val RoyalBlue = Color(0xFF405DB7)    // Secondary Action buttons & links (#405db7)
    val SlateIndigo = Color(0xFF4B5B76)  // Card borders, category tags & chips (#4b5b76)
}

val QuickPearDarkColorScheme = darkColorScheme(
    primary = QuickPearColors.PearGreen,
    onPrimary = Color.White,
    primaryContainer = QuickPearColors.PearGreen.copy(alpha = 0.25f),
    onPrimaryContainer = QuickPearColors.WarmCream,
    secondary = QuickPearColors.RoyalBlue,
    onSecondary = Color.White,
    secondaryContainer = QuickPearColors.RoyalBlue.copy(alpha = 0.25f),
    onSecondaryContainer = Color(0xFFDCE4F9),
    tertiary = QuickPearColors.SlateIndigo,
    onTertiary = Color.White,
    tertiaryContainer = QuickPearColors.SlateIndigo.copy(alpha = 0.35f),
    onTertiaryContainer = QuickPearColors.WarmCream,
    background = QuickPearColors.DarkCharcoal,
    onBackground = QuickPearColors.WarmCream,
    surface = QuickPearColors.SurfaceDark,
    onSurface = QuickPearColors.WarmCream,
    surfaceVariant = QuickPearColors.CardSurface,
    onSurfaceVariant = QuickPearColors.WarmGray,
    outline = QuickPearColors.SlateIndigo.copy(alpha = 0.4f),
    outlineVariant = QuickPearColors.WarmGray.copy(alpha = 0.2f)
)

enum class AppTab(val title: String) {
    NEARBY("Nearby"),
    TRUSTED("Trusted"),
    SETTINGS("Settings")
}

@Composable
fun App(
    viewModel: TransferViewModel = remember { TransferViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(AppTab.NEARBY) }
    var textTargetPeer by remember { mutableStateOf<PeerDevice?>(null) }
    var deviceToRename by remember { mutableStateOf<TrustedDevice?>(null) }

    val filePickerLauncher = com.app.quickpear.ui.rememberFilePickerLauncher { peer, paths ->
        viewModel.sendFiles(peer, paths)
    }

    val webShareFilePicker = com.app.quickpear.ui.rememberFilePickerLauncher { _, paths ->
        viewModel.startWebShare(paths, withHotspot = true)
    }

    MaterialTheme(colorScheme = QuickPearDarkColorScheme) {
        // Responsive Outer Container: Centered max-width for tablet & desktop, edge-to-edge for mobile
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF262325)), // Sleek deep backdrop on desktop
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .background(QuickPearColors.DarkCharcoal)
            ) {
                Scaffold(
                    containerColor = QuickPearColors.DarkCharcoal,
                    topBar = {
                        Surface(
                            color = QuickPearColors.SurfaceDark,
                            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f)),
                            shadowElevation = 4.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding() // Adapts cleanly to mobile status bar & camera cutout
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(Res.drawable.app_logo),
                                        contentDescription = "Quick Pear Logo",
                                        modifier = Modifier.size(38.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Quick Pear",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = QuickPearColors.WarmCream
                                        )
                                        Text(
                                            text = state.localDeviceName.ifEmpty { "Fast Wireless Sharing" },
                                            fontSize = 11.sp,
                                            color = QuickPearColors.WarmGray
                                        )
                                    }
                                }

                                // Online Status Badge
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(QuickPearColors.PearGreen.copy(alpha = 0.2f))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(QuickPearColors.PearGreen)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Cloud Ready",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = QuickPearColors.PearGreen
                                        )
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        Column(
                            modifier = Modifier
                                .background(QuickPearColors.SurfaceDark)
                                .navigationBarsPadding() // Ensures navigation bar doesn't touch Android gesture pill
                        ) {
                            // Status message snackbar
                            state.statusMessage?.let { msg ->
                                Snackbar(
                                    modifier = Modifier.padding(12.dp),
                                    containerColor = Color(0xFF221F21),
                                    contentColor = QuickPearColors.WarmCream,
                                    action = {
                                        TextButton(onClick = { viewModel.dismissStatusMessage() }) {
                                            Text("Dismiss", color = QuickPearColors.PearGreen)
                                        }
                                    }
                                ) {
                                    Text(text = msg)
                                }
                            }

                            // Mobile-First Bottom Navigation Bar
                            NavigationBar(
                                containerColor = QuickPearColors.SurfaceDark,
                                tonalElevation = 6.dp
                            ) {
                                AppTab.entries.forEach { tab ->
                                    val isSelected = selectedTab == tab
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = { selectedTab = tab },
                                        icon = { AppTabIcon(tab, isSelected) },
                                        label = {
                                            Text(
                                                text = tab.title,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = QuickPearColors.PearGreen.copy(alpha = 0.22f),
                                            selectedIconColor = QuickPearColors.PearGreen,
                                            unselectedIconColor = QuickPearColors.SlateMuted,
                                            selectedTextColor = QuickPearColors.WarmCream,
                                            unselectedTextColor = QuickPearColors.SlateMuted
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .background(QuickPearColors.DarkCharcoal)
                    ) {
                        when (selectedTab) {
                            AppTab.NEARBY -> NearbyDevicesScreen(
                                devices = state.discoveredDevices,
                                selectedDevice = state.selectedDevice,
                                trustedDevices = state.trustedDevices,
                                isPairing = state.isPairingInProgress,
                                onDeviceSelected = { viewModel.selectDevice(it) },
                                onPairClicked = { viewModel.initiatePairing(it) },
                                onSendClicked = { filePickerLauncher(it) },
                                onSendTextClicked = { textTargetPeer = it },
                                onRenameClicked = { deviceToRename = it },
                                onStartWebShare = {
                                    webShareFilePicker(PeerDevice(id = "", name = "Web Share", ipAddress = ""))
                                },
                                onRemotePairClicked = { viewModel.openRemotePairDialog() }
                            )
                            AppTab.TRUSTED -> TrustedDevicesScreen(
                                trustedDevices = state.trustedDevices,
                                onlineDevices = state.discoveredDevices,
                                onSendClicked = { filePickerLauncher(it) },
                                onSendTextClicked = { textTargetPeer = it },
                                onRename = { deviceId, newName -> viewModel.renameTrustedDevice(deviceId, newName) },
                                onRemove = { viewModel.removeTrustedDevice(it.id) },
                                onRemotePairClicked = { viewModel.openRemotePairDialog() }
                            )
                            AppTab.SETTINGS -> SettingsScreen(
                                deviceName = state.localDeviceName,
                                deviceId = state.localDeviceId,
                                localIpAddress = state.localIpAddress,
                                allLocalIpAddresses = state.allLocalIpAddresses,
                                localPort = state.localPort,
                                mode = state.discoveryMode,
                                onModeChange = { viewModel.setDiscoveryMode(it) },
                                trustedCount = state.trustedDevices.size,
                                onClearTrustedDevices = { viewModel.clearAllTrustedDevices() }
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

                        // Floating Transfer Banner
                        state.transferProgress?.let { progress ->
                            if (progress.status == com.app.quickpear.domain.TransferStatus.TRANSFERRING) {
                                Card(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = QuickPearColors.SurfaceDark),
                                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Transferring: ${progress.fileName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = QuickPearColors.WarmCream,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${(progress.progressPercentage * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = QuickPearColors.PearGreen
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFFEF5350).copy(alpha = 0.2f))
                                                    .clickable { viewModel.cancelTransfer() }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Cancel",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFEF5350)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { progress.progressPercentage },
                                            color = QuickPearColors.PearGreen,
                                            trackColor = QuickPearColors.SlateIndigo.copy(alpha = 0.3f),
                                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Global Rename Device Dialog (accessible from both Nearby and Trusted tabs)
        deviceToRename?.let { device ->
            var renameInput by remember(device) { mutableStateOf(device.customName ?: device.name) }
            AlertDialog(
                onDismissRequest = { deviceToRename = null },
                containerColor = QuickPearColors.SurfaceDark,
                title = {
                    Text("Rename Trusted Device", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            text = "Set a custom alias for '${device.name}'. Leave empty to reset to original name.",
                            fontSize = 12.sp,
                            color = QuickPearColors.WarmGray
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = renameInput,
                            onValueChange = { renameInput = it },
                            singleLine = true,
                            placeholder = { Text(device.name, color = QuickPearColors.SlateMuted) },
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
                            viewModel.renameTrustedDevice(device.id, renameInput.trim())
                            deviceToRename = null
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
    }
}

@Composable
private fun AppTabIcon(tab: AppTab, isSelected: Boolean) {
    val color = if (isSelected) QuickPearColors.PearGreen else QuickPearColors.SlateMuted
    Canvas(modifier = Modifier.size(22.dp)) {
        when (tab) {
            AppTab.NEARBY -> {
                val center = Offset(size.width / 2f, size.height * 0.72f)
                drawCircle(color = color, radius = 2.5.dp.toPx(), center = center)
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.22f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.72f),
                    style = Stroke(width = 2.dp.toPx())
                )
                drawArc(
                    color = color,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.04f, size.height * 0.04f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.92f, size.height * 1.08f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            AppTab.TRUSTED -> {
                val path = ComposePath().apply {
                    moveTo(size.width * 0.5f, size.height * 0.08f)
                    lineTo(size.width * 0.88f, size.height * 0.24f)
                    lineTo(size.width * 0.88f, size.height * 0.54f)
                    cubicTo(
                        size.width * 0.88f, size.height * 0.82f,
                        size.width * 0.5f, size.height * 0.96f,
                        size.width * 0.5f, size.height * 0.96f
                    )
                    cubicTo(
                        size.width * 0.5f, size.height * 0.96f,
                        size.width * 0.12f, size.height * 0.82f,
                        size.width * 0.12f, size.height * 0.54f
                    )
                    lineTo(size.width * 0.12f, size.height * 0.24f)
                    close()
                }
                drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = color, radius = 2.2.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.5f))
            }
            AppTab.SETTINGS -> {
                drawLine(color, Offset(size.width * 0.12f, size.height * 0.32f), Offset(size.width * 0.88f, size.height * 0.32f), strokeWidth = 2.dp.toPx())
                drawCircle(color, radius = 2.8.dp.toPx(), center = Offset(size.width * 0.36f, size.height * 0.32f))
                drawLine(color, Offset(size.width * 0.12f, size.height * 0.68f), Offset(size.width * 0.88f, size.height * 0.68f), strokeWidth = 2.dp.toPx())
                drawCircle(color, radius = 2.8.dp.toPx(), center = Offset(size.width * 0.64f, size.height * 0.68f))
            }
        }
    }
}

// -------------------------------------------------------------
// 1. NEARBY DEVICES SCREEN
// -------------------------------------------------------------
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
@Composable
fun SettingsScreen(
    deviceName: String,
    deviceId: String,
    localIpAddress: String,
    allLocalIpAddresses: List<String> = emptyList(),
    localPort: Int,
    mode: DiscoveryMode,
    onModeChange: (DiscoveryMode) -> Unit,
    trustedCount: Int = 0,
    onClearTrustedDevices: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Information & Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = QuickPearColors.WarmCream
        )

        // Device & Network Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Quick Pear Connectivity Status",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmCream
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "Device Name: $deviceName", fontSize = 12.sp, color = QuickPearColors.WarmCream)
                Text(
                    text = "Cryptographic ID: ${deviceId.take(24)}...",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = QuickPearColors.WarmGray
                )
                val ipDisplay = if (allLocalIpAddresses.isNotEmpty()) {
                    allLocalIpAddresses.joinToString(" • ") { "$it:$localPort" }
                } else {
                    "${if (localIpAddress.isNotBlank()) localIpAddress else "127.0.0.1"}:$localPort"
                }
                Text(
                    text = "Local Address: $ipDisplay",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF90CAF9)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Cloud Hybrid Signaling: ", fontSize = 11.sp, color = QuickPearColors.WarmGray)
                    Text(
                        text = "Active (STUN + Cloud Presence)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = QuickPearColors.PearGreen
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Apple Ecosystem: ", fontSize = 11.sp, color = QuickPearColors.WarmGray)
                    Text(
                        text = "macOS (.dmg) & iOS (Web Portal)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFD6E2FF)
                    )
                }
            }
        }

        // Discovery Mode Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Discovery Beacon Mode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmCream
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Broadcast frequency for power and battery optimization:",
                    fontSize = 11.sp,
                    color = QuickPearColors.WarmGray
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiscoveryMode.entries.forEach { entry ->
                        val isSelected = mode == entry
                        val label = when (entry) {
                            DiscoveryMode.ACTIVE -> "Active (2s)"
                            DiscoveryMode.BACKGROUND -> "Background (6s)"
                            DiscoveryMode.POWER_SAVER -> "Power Saver (15s)"
                        }
                        Button(
                            onClick = { onModeChange(entry) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) QuickPearColors.PearGreen else QuickPearColors.SurfaceDark,
                                contentColor = if (isSelected) Color.White else QuickPearColors.WarmGray
                            ),
                            border = if (!isSelected) BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f)) else null,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        // Storage & Clean Uninstall Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Storage & App Data",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmCream
                )
                Text(
                    text = "Trusted devices and cryptographic pairings are stored locally in this device's AppData.",
                    fontSize = 11.sp,
                    color = QuickPearColors.WarmGray
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saved Devices: $trustedCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = QuickPearColors.WarmCream
                    )
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        enabled = trustedCount > 0,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350))
                    ) {
                        Text("Clear All Devices", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(QuickPearColors.SlateIndigo.copy(alpha = 0.25f))
                )

                Text(
                    text = "Clean Uninstall & Windows Cleanup",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = QuickPearColors.WarmCream
                )
                Text(
                    text = "To completely remove the Explorer right-click context menu, SendTo shortcut, Autostart entries, and wipe all local data, run the generated 'clean-uninstall.cmd' utility.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = QuickPearColors.WarmGray
                )
            }
        }

        // Features Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Key Features",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmCream
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Zero-Login & Auto-Connect: No passwords or accounts. Trusted devices pair once and stay connected.\n" +
                            "• Cross-Network Ready: Transfer files even when devices are on different networks (e.g., home Wi-Fi to 5G cellular).\n" +
                            "• Instant Web Share: Share files with iPhone, iPad, or guest computers via quick QR scan without installing apps.\n" +
                            "• Text & Clipboard Sharing: Send notes, links, or code snippets instantly between devices.\n" +
                            "• Folder Preservation: Full folder structures and nested subdirectories are transferred intact.",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = QuickPearColors.WarmGray
                )
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = QuickPearColors.SurfaceDark,
            title = { Text("Clear All Trusted Devices?", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to remove all $trustedCount trusted devices? You will need to pair again to transfer files without confirmation.",
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearTrustedDevices()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear All", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearConfirm = false },
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

// -------------------------------------------------------------
// DIALOGS
// -------------------------------------------------------------
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
        containerColor = QuickPearColors.SurfaceDark,
        title = {
            Text("Confirm Pairing", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pairing with:", fontSize = 12.sp, color = QuickPearColors.WarmGray)
                Text(pairing.peer.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = QuickPearColors.WarmCream)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Compare this 6-digit code with the screen of the other device:",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = QuickPearColors.WarmGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = formattedCode,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        color = QuickPearColors.PearGreen,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "If the codes match, this device will be added to your trusted devices list.",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = QuickPearColors.WarmGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
            ) {
                Text("Match & Trust", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
            ) {
                Text("Cancel")
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
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Send Text to $targetName", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Type or paste text to send directly:", fontSize = 12.sp, color = QuickPearColors.WarmGray)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Enter text here...", color = QuickPearColors.SlateMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = QuickPearColors.WarmCream,
                        unfocusedTextColor = QuickPearColors.WarmCream,
                        focusedBorderColor = QuickPearColors.PearGreen,
                        unfocusedBorderColor = QuickPearColors.SlateIndigo
                    ),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (textInput.isNotBlank()) onSend(textInput) },
                enabled = textInput.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
            ) {
                Text("Send Text", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
            ) {
                Text("Cancel")
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
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Text Received from $senderName", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.4f))
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(14.dp),
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmCream
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
            ) {
                Text("Done", color = Color.White)
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
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Web Share (Apple & Guests)", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Scan this QR code with your iPhone camera or guest browser to download:",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = QuickPearColors.WarmGray
                )
                Spacer(modifier = Modifier.height(14.dp))
                // QR Code on a clean card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        QrCodeView(content = url, size = 170.dp)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = url,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF90CAF9)
                )
                if (hotspotInfo != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Hotspot Active: $hotspotInfo",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD6E2FF),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sharing ${files.size} file(s) instantly.",
                    fontSize = 11.sp,
                    color = QuickPearColors.WarmGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue)
            ) {
                Text("Stop Sharing", color = Color.White)
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
            containerColor = QuickPearColors.SurfaceDark,
            title = { Text("Security Verification (SAS)", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connected to:", fontSize = 12.sp, color = QuickPearColors.WarmGray)
                    Text(pendingSas.peerName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = QuickPearColors.WarmCream)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Verify that this security code matches the code on the remote screen:",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = QuickPearColors.PearGreen,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "If identical, click 'Match & Trust'. Both devices will stay paired automatically in the future!",
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmSas(true) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
                ) {
                    Text("Match & Trust", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { onConfirmSas(false) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                ) {
                    Text("Decline")
                }
            }
        )
        return
    }

    var selectedMode by remember { mutableStateOf(0) }
    var clientCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Remote Pairing (Cross-Network)", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TabRow(
                    selectedTabIndex = selectedMode,
                    containerColor = QuickPearColors.CardSurface
                ) {
                    Tab(
                        selected = selectedMode == 0,
                        onClick = {
                            if (selectedMode != 0) {
                                onCancelPairing()
                                selectedMode = 0
                            }
                        },
                        text = {
                            Text(
                                "Display Code",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selectedMode == 0) QuickPearColors.PearGreen else QuickPearColors.WarmGray
                            )
                        }
                    )
                    Tab(
                        selected = selectedMode == 1,
                        onClick = {
                            if (selectedMode != 1) {
                                onCancelPairing()
                                selectedMode = 1
                            }
                        },
                        text = {
                            Text(
                                "Enter Code",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selectedMode == 1) QuickPearColors.PearGreen else QuickPearColors.WarmGray
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedMode == 0) {
                    val code = generatedCode ?: "------"
                    val formattedCode = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code

                    Text(
                        text = "Share this 6-digit code with your other device to connect across networks:",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = QuickPearColors.PearGreen,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    if (isPairingInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = QuickPearColors.PearGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Waiting for connection...", fontSize = 12.sp, color = QuickPearColors.PearGreen)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCancelPairing,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                        ) {
                            Text("Stop Waiting", fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = onStartHost,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Waiting for Connection", color = Color.White)
                        }
                    }
                } else {
                    Text(
                        text = "Enter the 6-digit code shown on the target device:",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = clientCodeInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(6)
                            clientCodeInput = filtered
                        },
                        placeholder = { Text("e.g. 123456", textAlign = TextAlign.Center, color = QuickPearColors.SlateMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = QuickPearColors.WarmCream,
                            unfocusedTextColor = QuickPearColors.WarmCream,
                            focusedBorderColor = QuickPearColors.RoyalBlue,
                            unfocusedBorderColor = QuickPearColors.SlateIndigo
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (isPairingInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = QuickPearColors.RoyalBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting to device...", fontSize = 12.sp, color = QuickPearColors.RoyalBlue)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCancelPairing,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                        ) {
                            Text("Cancel", fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = { onJoinClient(clientCodeInput) },
                            enabled = clientCodeInput.length == 6,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect & Pair", color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
            ) {
                Text("Close")
            }
        }
    )
}
