package com.app.quickpear

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern floating context menu for Desktop System Tray.
 * Styled with dark charcoal theme (#3D383C), pear green accent (#6E9D24), and rounded corners.
 */
@Composable
fun ModernTrayMenu(
    isAutostart: Boolean,
    isTransferring: Boolean = false,
    onOpenApp: () -> Unit,
    onOpenDownloads: () -> Unit,
    onCancelTransfer: () -> Unit = {},
    onToggleAutostart: () -> Unit,
    onQuit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF3D383C),
        border = BorderStroke(1.dp, Color(0xFF4B5B76).copy(alpha = 0.5f)),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 10.dp, horizontal = 8.dp)
        ) {
            // Header status row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isTransferring) Color(0xFF405DB7) else Color(0xFF6E9D24))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Quick Pear",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFF6E9)
                    )
                    Text(
                        text = if (isTransferring) "Transfer in progress..." else "Running in background",
                        fontSize = 10.sp,
                        color = Color(0xFFCECECC).copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF4B5B76).copy(alpha = 0.35f))
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Action 1: Open Quick Pear
            ModernTrayMenuItem(
                label = "Open Quick Pear",
                onClick = onOpenApp
            )

            // Action 2: Open Downloads Folder
            ModernTrayMenuItem(
                label = "Open Downloads Folder",
                onClick = onOpenDownloads
            )

            // Action 2.5: Cancel Transfer (Only if active)
            if (isTransferring) {
                ModernTrayMenuItem(
                    label = "Cancel Transfer",
                    textColor = Color(0xFFEF5350),
                    hoverBg = Color(0xFFEF5350).copy(alpha = 0.2f),
                    onClick = onCancelTransfer
                )
            }

            // Action 3: Launch on Startup
            ModernTrayMenuItem(
                label = "Launch on Startup",
                trailing = {
                    if (isAutostart) {
                        Text(
                            text = "✓",
                            color = Color(0xFF6E9D24),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                },
                onClick = onToggleAutostart
            )

            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF4B5B76).copy(alpha = 0.35f))
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Action 4: Quit
            ModernTrayMenuItem(
                label = "Quit",
                textColor = Color(0xFFEF5350),
                hoverBg = Color(0xFFEF5350).copy(alpha = 0.2f),
                onClick = onQuit
            )
        }
    }
}

@Composable
private fun ModernTrayMenuItem(
    label: String,
    textColor: Color = Color(0xFFFFF6E9),
    hoverBg: Color = Color(0xFF4B5B76).copy(alpha = 0.35f),
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) hoverBg else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
        trailing?.invoke()
    }
}
