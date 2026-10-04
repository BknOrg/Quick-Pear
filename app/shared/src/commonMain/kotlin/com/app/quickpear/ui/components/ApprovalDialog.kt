package com.app.quickpear.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.quickpear.domain.MetadataRequest

@Composable
fun ApprovalDialog(
    request: MetadataRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val totalSizeBytes = request.files.sumOf { it.fileSizeBytes }
    val totalFiles = request.files.size

    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(text = "Permintaan Transfer File")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Perangkat lain ingin mengirim $totalFiles berkas (${FormatUtils.formatBytes(totalSizeBytes)}).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                request.files.take(3).forEach { file ->
                    Text(
                        text = "• ${file.fileName} (${FormatUtils.formatBytes(file.fileSizeBytes)})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (totalFiles > 3) {
                    Text(
                        text = "... dan ${totalFiles - 3} berkas lainnya",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Terima")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Text("Tolak")
            }
        }
    )
}
