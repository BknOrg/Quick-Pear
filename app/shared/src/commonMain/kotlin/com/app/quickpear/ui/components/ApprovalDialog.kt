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
            Text(text = "File Transfer Request")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "A nearby device wants to send $totalFiles file(s) (${FormatUtils.formatBytes(totalSizeBytes)}).",
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
                        text = "... and ${totalFiles - 3} more file(s)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) {
                Text("Decline")
            }
        }
    )
}
