package com.app.quickpear.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.node.QuickPearNode
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
    var isSending by remember { mutableStateOf(false) }

    val files = remember(filePaths) { filePaths.map { File(it) }.filter { it.exists() } }
    val totalBytes = remember(files) { files.sumOf { it.length() } }

    Window(
        onCloseRequest = onDismiss,
        title = "Kirim Berkas via Quick Pear",
        icon = icon,
        state = rememberWindowState(width = 460.dp, height = 520.dp),
        resizable = false
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Kirim Berkas via Quick Pear",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${files.size} berkas terpilih (${FormatUtils.formatBytes(totalBytes)}). Pilih perangkat tujuan:",
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
                            Text("Mengirim...")
                        }
                    } else if (onlineDevices.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Mencari perangkat di sekitar...\nPastikan perangkat tujuan membuka Quick Pear atau menjalankan layanan di background.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(onlineDevices) { peer ->
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

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("Batal")
                    }
                }
            }
        }
    }
}
