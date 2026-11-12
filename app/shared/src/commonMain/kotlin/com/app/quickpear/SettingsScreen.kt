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
