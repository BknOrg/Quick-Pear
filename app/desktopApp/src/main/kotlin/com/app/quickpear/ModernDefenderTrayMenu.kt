package com.app.quickpear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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

// Quick Pear Theme Palette
private val DeepBackdrop = Color(0xFF221F21)
private val CardSurface = Color(0xFF353034)
private val BorderStroke = Color(0xFF3D383C)
private val WarmCream = Color(0xFFFFF6E9)
private val WarmGray = Color(0xFFCECECC)
private val PearGreen = Color(0xFF6E9D24)
private val CancelRed = Color(0xFFEF5350)

/**
 * Modern Windows Defender / Fluent style Tray Flyout for Quick Pear.
 * Designed to be rendered inside Compose Desktop DialogWindow.
 */
@Composable
fun ModernDefenderTrayMenu(
    isAutostart: Boolean,
    isTransferring: Boolean,
    onOpenApp: () -> Unit,
    onOpenDownloads: () -> Unit,
    onCancelTransfer: () -> Unit,
    onToggleAutostart: () -> Unit,
    onQuit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DeepBackdrop)
            .border(1.dp, BorderStroke, RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp, horizontal = 6.dp)
    ) {
        Column {
            // Header (Status indicator like Windows Defender / Antigravity)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Quick Pear",
                    color = WarmCream,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isTransferring) CancelRed else PearGreen)
                    )
                    Text(
                        text = if (isTransferring) "Transferring" else "Ready",
                        color = WarmGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 1.dp,
                color = BorderStroke.copy(alpha = 0.6f)
            )

            // Menu items
            DefenderMenuItem(
                text = "Open Quick Pear",
                onClick = onOpenApp
            )

            DefenderMenuItem(
                text = "Open Downloads Folder",
                onClick = onOpenDownloads
            )

            if (isTransferring) {
                DefenderMenuItem(
                    text = "Cancel Transfer",
                    textColor = CancelRed,
                    onClick = onCancelTransfer
                )
            }

            DefenderToggleItem(
                text = "Launch on Startup",
                isChecked = isAutostart,
                onClick = onToggleAutostart
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 1.dp,
                color = BorderStroke.copy(alpha = 0.6f)
            )

            DefenderMenuItem(
                text = "Quit",
                onClick = onQuit
            )
        }
    }
}

@Composable
private fun DefenderMenuItem(
    text: String,
    textColor: Color = WarmCream,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) CardSurface else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun DefenderToggleItem(
    text: String,
    isChecked: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) CardSurface else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            color = WarmCream,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        )

        // Minimal modern checkmark indicator
        if (isChecked) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PearGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, WarmGray.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            )
        }
    }
}
