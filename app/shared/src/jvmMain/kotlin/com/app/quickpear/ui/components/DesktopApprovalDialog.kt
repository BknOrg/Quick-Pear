package com.app.quickpear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.app.quickpear.session.ApprovalDecision
import com.app.quickpear.session.DesktopApprovalHandler
import com.app.quickpear.session.DesktopPairingApproval
import com.app.quickpear.session.DesktopTransferApproval
import kotlinx.coroutines.delay

@Composable
fun DesktopApprovalHost(
    handler: DesktopApprovalHandler,
    pendingTransfer: DesktopTransferApproval?,
    pendingPairing: DesktopPairingApproval?
) {
    if (pendingTransfer != null) {
        DesktopTransferApprovalDialog(
            approval = pendingTransfer,
            onDecision = { decision -> handler.submitTransferDecision(decision) }
        )
    }

    if (pendingPairing != null) {
        DesktopPairingApprovalDialog(
            approval = pendingPairing,
            onConfirm = { accepted -> handler.submitPairingDecision(accepted) }
        )
    }
}

@Composable
fun DesktopTransferApprovalDialog(
    approval: DesktopTransferApproval,
    onDecision: (ApprovalDecision) -> Unit
) {
    var secondsLeft by remember { mutableStateOf(60) }

    LaunchedEffect(approval) {
        secondsLeft = 60
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft--
        }
        onDecision(ApprovalDecision.REJECT)
    }

    val totalSize = approval.request.files.sumOf { it.fileSizeBytes }
    val formattedSize = FormatUtils.formatBytes(totalSize)
    val windowState = rememberWindowState(width = 460.dp, height = 400.dp)

    Window(
        onCloseRequest = { onDecision(ApprovalDecision.REJECT) },
        title = "Quick Pear - Incoming File Request",
        state = windowState,
        alwaysOnTop = true,
        resizable = false
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Incoming File Request",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${secondsLeft}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "${approval.peer.name} wants to send ${approval.request.files.size} file(s) ($formattedSize).",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            approval.request.files.take(3).forEach { file ->
                                Text(
                                    text = "• ${file.fileName} (${FormatUtils.formatBytes(file.fileSizeBytes)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            if (approval.request.files.size > 3) {
                                Text(
                                    text = "+${approval.request.files.size - 3} more file(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onDecision(ApprovalDecision.ACCEPT) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Accept")
                            }

                            OutlinedButton(
                                onClick = { onDecision(ApprovalDecision.REJECT) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Decline")
                            }
                        }

                        Button(
                            onClick = { onDecision(ApprovalDecision.ACCEPT_ALWAYS) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text("Always Accept from ${approval.peer.name}", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopPairingApprovalDialog(
    approval: DesktopPairingApproval,
    onConfirm: (Boolean) -> Unit
) {
    val windowState = rememberWindowState(width = 400.dp, height = 360.dp)
    val sas = approval.sasCode
    val formattedCode = if (sas.length == 6) "${sas.take(3)} ${sas.takeLast(3)}" else sas

    Window(
        onCloseRequest = { onConfirm(false) },
        title = "Quick Pear - Pair Device",
        state = windowState,
        alwaysOnTop = true,
        resizable = false
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Pairing Confirmation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${approval.peer.name} wants to pair.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Make sure the verification code matches on ${approval.peer.name}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Text(
                                text = formattedCode,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 4.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onConfirm(false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Decline")
                        }

                        Button(
                            onClick = { onConfirm(true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Match & Trust")
                        }
                    }
                }
            }
        }
    }
}
