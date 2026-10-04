package com.app.quickpear.ui.components

import kotlin.math.ln
import kotlin.math.pow

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        val value = bytes / 1024.0.pow(exp.toDouble())
        return "${(value * 10).toInt() / 10.0} ${pre}B"
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatTimeRemaining(bytesLeft: Long, bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "--"
        val seconds = bytesLeft / bytesPerSec
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "${minutes}m ${remainingSeconds}s"
    }
}
