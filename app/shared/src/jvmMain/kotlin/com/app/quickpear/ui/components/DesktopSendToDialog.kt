package com.app.quickpear.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.node.QuickPearNode
import com.app.quickpear.security.TrustedDevice
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DesktopSendToDialog(
    filePaths: List<String>,
    node: QuickPearNode,
    icon: Painter? = null,
    onSend: (PeerDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val onlineDevices by node.onlineDevices.collectAsState()
    val trustedDevices by node.trustStore.devices.collectAsState()
    var isSending by remember { mutableStateOf(false) }
    var deviceToRename by remember { mutableStateOf<TrustedDevice?>(null) }
    val scope = rememberCoroutineScope()

    // Trigger aggressive wake-up burst and direct TCP probe as soon as dialog opens!
    LaunchedEffect(Unit) {
        node.triggerBurstBeacon()
        scope.launch { node.probeTrustedDevices() }
    }

    val files = remember(filePaths) { filePaths.map { File(it) }.filter { it.exists() } }
    val totalBytes = remember(files) { files.sumOf { it.length() } }

    val trustedOnlinePeers = onlineDevices.filter { peer -> trustedDevices.any { it.id == peer.id } }
    val otherOnlinePeers = onlineDevices.filterNot { peer -> trustedDevices.any { it.id == peer.id } }

    Window(
        onCloseRequest = onDismiss,
        title = "Send Files via Quick Pear",
        icon = icon,
        state = rememberWindowState(width = 480.dp, height = 560.dp),
        resizable = false
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Send Files via Quick Pear",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${files.size} file(s) selected (${FormatUtils.formatBytes(totalBytes)}). Select target device:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isSending) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Sending...")
                        }
                    } else if (onlineDevices.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Searching for nearby devices...\nMake sure the target device has Quick Pear open or running in the background.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // -------------------------------------------------
                            // SECTION 1: TRUSTED DEVICES (ONLINE)
                            // -------------------------------------------------
                            if (trustedOnlinePeers.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF10B981))
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Trusted Devices (${trustedOnlinePeers.size})",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF10B981)
                                            )
                                        }
                                        Text(
                                            text = "Instant Send",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                items(trustedOnlinePeers) { peer ->
                                    val trusted = trustedDevices.firstOrNull { it.id == peer.id }
                                    val customAlias = trusted?.customName?.takeIf { it.isNotBlank() }
                                    val primaryName = customAlias ?: peer.name

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                isSending = true
                                                onSend(peer)
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = primaryName,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp
                                                    )
                                                    if (customAlias != null) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "(${peer.name})",
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = "${peer.deviceType} • ${peer.ipAddress}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }

                                            OutlinedButton(
                                                onClick = { trusted?.let { deviceToRename = it } },
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text("Rename", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // -------------------------------------------------
                            // SECTION 2: OTHER DISCOVERED DEVICES
                            // -------------------------------------------------
                            if (otherOnlinePeers.isNotEmpty()) {
                                item {
                                    Text(
                                        text = if (trustedOnlinePeers.isNotEmpty()) "Other Nearby Devices (${otherOnlinePeers.size})" else "Nearby Devices (${otherOnlinePeers.size})",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(top = if (trustedOnlinePeers.isNotEmpty()) 6.dp else 2.dp, bottom = 2.dp)
                                    )
                                }

                                items(otherOnlinePeers) { peer ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                isSending = true
                                                onSend(peer)
                                            },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = peer.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 15.sp
                                                )
                                                Text(
                                                    text = "${peer.deviceType} • ${peer.ipAddress}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("Cancel")
                    }
                }

                // Quick Rename Dialog within Desktop SendTo
                deviceToRename?.let { device ->
                    var renameInput by remember(device) { mutableStateOf(device.customName ?: device.name) }
                    AlertDialog(
                        onDismissRequest = { deviceToRename = null },
                        title = { Text("Rename Trusted Device") },
                        text = {
                            Column {
                                Text(
                                    text = "Set a custom alias for '${device.name}'. Leave empty to reset to original name.",
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = renameInput,
                                    onValueChange = { renameInput = it },
                                    singleLine = true,
                                    placeholder = { Text(device.name) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    node.trustStore.rename(device.id, renameInput.trim())
                                    deviceToRename = null
                                }
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { deviceToRename = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
