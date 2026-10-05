package com.app.quickpear.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.quickpear.domain.PeerDevice

private val PearGreen = Color(0xFF6E9D24)
private val WarmCream = Color(0xFFFFF6E9)
private val WarmGray = Color(0xFFCECECC)
private val SlateIndigo = Color(0xFF4B5B76)
private val CardSurface = Color(0xFF353033)

/**
 * Modern compact scanning status header.
 * Replaces cumbersome orbital bubbles with a clean animated pulse beacon and dynamic status.
 * All detected peers are rendered directly in the scrollable vertical list to prevent overlapping.
 */
@Composable
fun RadarView(
    devices: List<PeerDevice>,
    selectedDevice: PeerDevice? = null,
    onDeviceSelected: (PeerDevice) -> Unit = {},
    modifier: Modifier = Modifier,
    sizeDp: Dp = Dp.Unspecified
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarRadius"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, SlateIndigo.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated Pulse Beacon
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = size.width / 2f

                    // Concentric static base ring
                    drawCircle(
                        color = PearGreen.copy(alpha = 0.2f),
                        radius = maxRadius * 0.85f,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Expanding wave pulse
                    drawCircle(
                        color = PearGreen.copy(alpha = ((1f - pulseProgress) * 0.45f).coerceIn(0f, 0.45f)),
                        radius = (maxRadius * 0.35f) + (maxRadius * 0.65f * pulseProgress),
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Inner glowing center beacon dot
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(PearGreen)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Status Typography
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (devices.isEmpty()) "Scanning for nearby devices..." else "${devices.size} device${if (devices.size > 1) "s" else ""} found nearby",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarmCream
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (devices.isEmpty()) "Visible on local Wi-Fi, hotspot & cloud" else "Select a device below to start transfer",
                    fontSize = 11.sp,
                    color = WarmGray
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status Pill Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(PearGreen.copy(alpha = 0.15f))
                    .border(BorderStroke(1.dp, PearGreen.copy(alpha = 0.35f)), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(PearGreen)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (devices.isEmpty()) "Scanning" else "Online (${devices.size})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PearGreen
                    )
                }
            }
        }
    }
}
