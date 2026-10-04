package com.app.quickpear.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarView(
    devices: List<PeerDevice>,
    selectedDevice: PeerDevice?,
    onDeviceSelected: (PeerDevice) -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 300.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarRadius"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.width.coerceAtMost(size.height) / 2f

            // Concentric radar grid rings
            for (i in 1..3) {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.2f),
                    radius = maxRadius * (i / 3f),
                    center = centerOffset,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Expanding wave pulse
            drawCircle(
                color = primaryColor.copy(alpha = (1f - sweepProgress).coerceIn(0f, 0.4f)),
                radius = maxRadius * sweepProgress,
                center = centerOffset,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Center host node
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(primaryColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Me",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        // Render discovered devices around center radar
        devices.forEachIndexed { index, device ->
            val angleRad = (2 * Math.PI / devices.size) * index
            val radiusPx = (sizeDp.value / 2.5f)
            val offsetX = (cos(angleRad) * radiusPx).dp
            val offsetY = (sin(angleRad) * radiusPx).dp

            val isSelected = selectedDevice?.id == device.id

            Box(
                modifier = Modifier
                    .offset(x = offsetX, y = offsetY)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .clickable { onDeviceSelected(device) }
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = device.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = device.deviceType.name,
                        fontSize = 10.sp,
                        color = if (isSelected) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
