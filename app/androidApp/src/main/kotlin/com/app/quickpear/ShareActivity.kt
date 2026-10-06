package com.app.quickpear

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.security.TrustedDevice
import com.app.quickpear.service.TransferForegroundService
import com.app.quickpear.util.ShareShortcutPublisher
import com.app.quickpear.util.UriFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure service is running
        TransferForegroundService.startService(this)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val directPeerId = intent.getStringExtra(ShareShortcutPublisher.EXTRA_TARGET_PEER_ID)
        val node = TransferForegroundService.activeNode

        // Trigger aggressive wake-up burst and TCP probe for instant discovery
        node?.triggerBurstBeacon()
        lifecycleScope.launch {
            node?.probeTrustedDevices()
        }

        if (directPeerId != null && node != null) {
            val target = node.onlineDevices.value.firstOrNull { it.id == directPeerId }
            if (target != null) {
                sendDirectly(target, uris)
                return
            }
        }

        // Show picker bottom sheet
        setContent {
            MaterialTheme {
                ShareBottomSheet(
                    uris = uris,
                    onPeerSelected = { peer -> sendDirectly(peer, uris) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun sendDirectly(target: PeerDevice, uris: List<Uri>) {
        val node = TransferForegroundService.activeNode
        if (node == null) {
            Toast.makeText(this, "Quick Pear service is not ready", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "Sending ${uris.size} file(s) to ${target.name} in background...", Toast.LENGTH_SHORT).show()

        val appContext = applicationContext
        // Dispatch to background scope so finishing activity doesn't cancel the transfer
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val files = UriFileResolver.resolveUrisToFiles(appContext, uris)
                node.sendFiles(target, files)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Failed to send: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        finish()
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
                } else {
                    (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { uris.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
                }
            }
        }
        return uris
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareBottomSheet(
    uris: List<Uri>,
    onPeerSelected: (PeerDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val node = TransferForegroundService.activeNode
    val onlineDevices by (node?.onlineDevices ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val trustedDevices by (node?.trustStore?.devices ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()

    var isSending by remember { mutableStateOf(false) }
    var deviceToRename by remember { mutableStateOf<TrustedDevice?>(null) }

    val trustedOnlinePeers = onlineDevices.filter { peer -> trustedDevices.any { it.id == peer.id } }
    val otherOnlinePeers = onlineDevices.filterNot { peer -> trustedDevices.any { it.id == peer.id } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "Send with Quick Pear",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${uris.size} file(s) selected. Tap target device:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isSending) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Preparing and sending files...")
                }
            } else if (onlineDevices.isEmpty()) {
                Text(
                    text = "Searching for nearby devices...\nMake sure the target device has Quick Pear open or running in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        onPeerSelected(peer)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = primaryName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
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
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
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
                                        onPeerSelected(peer)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peer.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp
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

            // Quick Rename Dialog within Android Share Sheet
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
                                node?.trustStore?.rename(device.id, renameInput.trim())
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
