package com.app.quickpear.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.quickpear.domain.TransferProgress
import com.app.quickpear.domain.TransferStatus

@Composable
fun TransferProgressDialog(
    progress: TransferProgress,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = when (progress.status) {
                    TransferStatus.TRANSFERRING -> "Mengirim berkas..."
                    TransferStatus.COMPLETED -> "Transfer Selesai"
                    TransferStatus.FAILED -> "Transfer Gagal"
                    else -> "Proses Transfer"
                }
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = progress.fileName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { progress.progressPercentage },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "${FormatUtils.formatBytes(progress.bytesTransferred)} / ${FormatUtils.formatBytes(progress.totalBytes)} (${(progress.progressPercentage * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (progress.status == TransferStatus.TRANSFERRING) {
                    val bytesLeft = progress.totalBytes - progress.bytesTransferred
                    val timeRemaining = FormatUtils.formatTimeRemaining(bytesLeft, progress.transferSpeedBytesPerSec)
                    Text(
                        text = "Kecepatan: ${FormatUtils.formatSpeed(progress.transferSpeedBytesPerSec)} • Sisa waktu: $timeRemaining",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                progress.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Error: $error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text(if (progress.status == TransferStatus.COMPLETED) "Tutup" else "Batal")
            }
        }
    )
}
