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
