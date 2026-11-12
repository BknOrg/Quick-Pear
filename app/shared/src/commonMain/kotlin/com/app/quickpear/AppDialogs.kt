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
fun PairingConfirmationDialog(
    pairing: PairingUiState,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    val sas = pairing.sasCode
    val formattedCode = if (sas.length == 6) "${sas.take(3)} ${sas.takeLast(3)}" else sas

    AlertDialog(
        onDismissRequest = onReject,
        containerColor = QuickPearColors.SurfaceDark,
        title = {
            Text("Confirm Pairing", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pairing with:", fontSize = 12.sp, color = QuickPearColors.WarmGray)
                Text(pairing.peer.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = QuickPearColors.WarmCream)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Compare this 6-digit code with the screen of the other device:",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = QuickPearColors.WarmGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = formattedCode,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        color = QuickPearColors.PearGreen,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "If the codes match, this device will be added to your trusted devices list.",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = QuickPearColors.WarmGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
            ) {
                Text("Match & Trust", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SendTextDialog(
    targetName: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Send Text to $targetName", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Type or paste text to send directly:", fontSize = 12.sp, color = QuickPearColors.WarmGray)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Enter text here...", color = QuickPearColors.SlateMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = QuickPearColors.WarmCream,
                        unfocusedTextColor = QuickPearColors.WarmCream,
                        focusedBorderColor = QuickPearColors.PearGreen,
                        unfocusedBorderColor = QuickPearColors.SlateIndigo
                    ),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (textInput.isNotBlank()) onSend(textInput) },
                enabled = textInput.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
            ) {
                Text("Send Text", color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun IncomingTextDialog(
    senderName: String,
    text: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Text Received from $senderName", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.4f))
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(14.dp),
                    fontSize = 13.sp,
                    color = QuickPearColors.WarmCream
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
            ) {
                Text("Done", color = Color.White)
            }
        }
    )
}

@Composable
fun WebShareQrDialog(
    url: String,
    files: List<Path>,
    hotspotInfo: String? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Web Share (Apple & Guests)", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Scan this QR code with your iPhone camera or guest browser to download:",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = QuickPearColors.WarmGray
                )
                Spacer(modifier = Modifier.height(14.dp))
                // QR Code on a clean card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        QrCodeView(content = url, size = 170.dp)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = url,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF90CAF9)
                )
                if (hotspotInfo != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Hotspot Active: $hotspotInfo",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD6E2FF),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Local HTTP - active for up to 15 minutes. Anyone with this link can download ${files.size} file(s).",
                    fontSize = 11.sp,
                    color = QuickPearColors.WarmGray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue)
            ) {
                Text("Stop Sharing", color = Color.White)
            }
        }
    )
}

@Composable
fun RemotePairDialog(
    generatedCode: String?,
    isPairingInProgress: Boolean,
    pendingSas: RemotePairingSasState?,
    onStartHost: () -> Unit,
    onJoinClient: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onConfirmSas: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (pendingSas != null) {
        val sas = pendingSas.sasCode
        val formattedCode = if (sas.length == 6) "${sas.take(3)} ${sas.takeLast(3)}" else sas

        AlertDialog(
            onDismissRequest = { onConfirmSas(false) },
            containerColor = QuickPearColors.SurfaceDark,
            title = { Text("Security Verification (SAS)", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connected to:", fontSize = 12.sp, color = QuickPearColors.WarmGray)
                    Text(pendingSas.peerName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = QuickPearColors.WarmCream)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Verify that this security code matches the code on the remote screen:",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = QuickPearColors.PearGreen,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "If identical, click 'Match & Trust'. Both devices will stay paired automatically in the future!",
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirmSas(true) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.PearGreen)
                ) {
                    Text("Match & Trust", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { onConfirmSas(false) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                ) {
                    Text("Decline")
                }
            }
        )
        return
    }

    var selectedMode by remember { mutableStateOf(0) }
    var clientCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = QuickPearColors.SurfaceDark,
        title = { Text("Remote Pairing (Cross-Network)", color = QuickPearColors.WarmCream, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TabRow(
                    selectedTabIndex = selectedMode,
                    containerColor = QuickPearColors.CardSurface
                ) {
                    Tab(
                        selected = selectedMode == 0,
                        onClick = {
                            if (selectedMode != 0) {
                                onCancelPairing()
                                selectedMode = 0
                            }
                        },
                        text = {
                            Text(
                                "Display Code",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selectedMode == 0) QuickPearColors.PearGreen else QuickPearColors.WarmGray
                            )
                        }
                    )
                    Tab(
                        selected = selectedMode == 1,
                        onClick = {
                            if (selectedMode != 1) {
                                onCancelPairing()
                                selectedMode = 1
                            }
                        },
                        text = {
                            Text(
                                "Enter Code",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selectedMode == 1) QuickPearColors.PearGreen else QuickPearColors.WarmGray
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedMode == 0) {
                    val code = generatedCode ?: "------"
                    val formattedCode = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code

                    Text(
                        text = "Share this 6-digit code with your other device to connect across networks:",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = QuickPearColors.CardSurface),
                        border = BorderStroke(1.dp, QuickPearColors.SlateIndigo.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = formattedCode,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = QuickPearColors.PearGreen,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    if (isPairingInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = QuickPearColors.PearGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Waiting for connection...", fontSize = 12.sp, color = QuickPearColors.PearGreen)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCancelPairing,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                        ) {
                            Text("Stop Waiting", fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = onStartHost,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Waiting for Connection", color = Color.White)
                        }
                    }
                } else {
                    Text(
                        text = "Enter the 6-digit code shown on the target device:",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = QuickPearColors.WarmGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = clientCodeInput,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(6)
                            clientCodeInput = filtered
                        },
                        placeholder = { Text("e.g. 123456", textAlign = TextAlign.Center, color = QuickPearColors.SlateMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = QuickPearColors.WarmCream,
                            unfocusedTextColor = QuickPearColors.WarmCream,
                            focusedBorderColor = QuickPearColors.RoyalBlue,
                            unfocusedBorderColor = QuickPearColors.SlateIndigo
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    if (isPairingInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = QuickPearColors.RoyalBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting to device...", fontSize = 12.sp, color = QuickPearColors.RoyalBlue)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCancelPairing,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
                        ) {
                            Text("Cancel", fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = { onJoinClient(clientCodeInput) },
                            enabled = clientCodeInput.length == 6,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuickPearColors.RoyalBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect & Pair", color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, QuickPearColors.SlateIndigo),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = QuickPearColors.WarmCream)
            ) {
                Text("Close")
            }
        }
    )
}
