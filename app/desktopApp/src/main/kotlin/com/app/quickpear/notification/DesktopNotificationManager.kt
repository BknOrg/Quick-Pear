package com.app.quickpear.notification

import com.app.quickpear.domain.TransferProgress
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.node.QuickPearNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class DesktopNotificationManager(
    private val node: QuickPearNode,
    private val downloadDirectory: File,
    private val onOpenWindow: () -> Unit
) {
    private val trayIconRef = AtomicReference<TrayIcon?>(null)
    private var lastStatus: TransferStatus? = null
    private var lastFileId: Int? = null
    private var lastCompletedTime: Long = 0L

    fun attachTrayIcon(trayIcon: TrayIcon) {
        trayIconRef.set(trayIcon)
        trayIcon.addActionListener {
            val now = System.currentTimeMillis()
            if (now - lastCompletedTime < 30_000L) {
                // If clicked recently after a completed transfer, open downloads folder
                openDownloadFolder()
            }
            onOpenWindow()
        }
    }

    fun start(scope: CoroutineScope) {
        // Observe transfer progress transitions
        scope.launch(Dispatchers.Default) {
            node.transferProgress.collectLatest { progress ->
                handleProgressChange(progress)
            }
        }

        // Observe incoming text messages
        scope.launch(Dispatchers.Default) {
            node.textReceivedFlow.collectLatest { (sender, text) ->
                showNotification(
                    title = "Quick Pear — ${sender.name}",
                    message = if (text.length > 80) text.take(80) + "..." else text,
                    type = TrayIcon.MessageType.INFO
                )
            }
        }
    }

    private fun handleProgressChange(progress: TransferProgress?) {
        if (progress == null) {
            lastStatus = null
            lastFileId = null
            return
        }

        when (progress.status) {
            TransferStatus.TRANSFERRING -> {
                if (lastStatus != TransferStatus.TRANSFERRING || lastFileId != progress.fileId) {
                    lastStatus = TransferStatus.TRANSFERRING
                    lastFileId = progress.fileId
                    showNotification(
                        title = "Quick Pear — Transfer Started",
                        message = "Transferring: ${progress.fileName}",
                        type = TrayIcon.MessageType.INFO
                    )
                }
            }
            TransferStatus.COMPLETED -> {
                if (lastStatus != TransferStatus.COMPLETED) {
                    lastStatus = TransferStatus.COMPLETED
                    lastCompletedTime = System.currentTimeMillis()
                    showNotification(
                        title = "Quick Pear — Transfer Complete",
                        message = "Successfully transferred: ${progress.fileName}\nClick to view files",
                        type = TrayIcon.MessageType.INFO
                    )
                }
            }
            TransferStatus.FAILED -> {
                if (lastStatus != TransferStatus.FAILED) {
                    lastStatus = TransferStatus.FAILED
                    val reason = progress.errorMessage ?: "Transfer failed or was cancelled"
                    showNotification(
                        title = "Quick Pear — Transfer Alert",
                        message = reason,
                        type = if (reason.contains("batal", ignoreCase = true) || reason.contains("cancel", ignoreCase = true)) {
                            TrayIcon.MessageType.INFO
                        } else {
                            TrayIcon.MessageType.WARNING
                        }
                    )
                }
            }
            else -> {
                // Other statuses (IDLE, CONNECTING, etc.)
            }
        }
    }

    fun showNotification(
        title: String,
        message: String,
        type: TrayIcon.MessageType = TrayIcon.MessageType.INFO
    ) {
        val icon = trayIconRef.get() ?: findSystemTrayIcon()
        if (icon != null) {
            try {
                icon.displayMessage(title, message, type)
            } catch (_: Exception) {
            }
        }
    }

    fun openDownloadFolder() {
        try {
            if (downloadDirectory.exists()) {
                val os = System.getProperty("os.name", "").lowercase()
                if (os.contains("win")) {
                    ProcessBuilder("explorer.exe", downloadDirectory.canonicalPath).start()
                } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(downloadDirectory)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun findSystemTrayIcon(): TrayIcon? {
        if (SystemTray.isSupported()) {
            val tray = SystemTray.getSystemTray()
            val first = tray.trayIcons.firstOrNull()
            if (first != null) {
                trayIconRef.set(first)
                return first
            }
        }
        return null
    }
}
