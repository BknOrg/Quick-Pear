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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.app.quickpear.domain.PeerDevice
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
            Toast.makeText(this, "Tidak ada berkas yang dipilih", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val directPeerId = intent.getStringExtra(ShareShortcutPublisher.EXTRA_TARGET_PEER_ID)
        val node = TransferForegroundService.activeNode

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
            Toast.makeText(this, "Layanan Quick Pear belum siap", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "Mengirim ${uris.size} berkas ke ${target.name} di latar belakang...", Toast.LENGTH_SHORT).show()

        val appContext = applicationContext
        // Dispatch to background scope so finishing activity doesn't cancel the transfer
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val files = UriFileResolver.resolveUrisToFiles(appContext, uris)
                node.sendFiles(target, files)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Gagal mengirim: ${e.message}", Toast.LENGTH_LONG).show()
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

    var isSending by remember { mutableStateOf(false) }

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
                text = "Kirim dengan Quick Pear",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${uris.size} berkas dipilih. Ketuk perangkat tujuan:",
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
                    Text("Menyiapkan dan mengirim berkas...")
                }
            } else if (onlineDevices.isEmpty()) {
                Text(
                    text = "Mencari perangkat di sekitar...\nPastikan perangkat tujuan membuka Quick Pear atau menjalankan layanan di background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(onlineDevices) { peer ->
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
