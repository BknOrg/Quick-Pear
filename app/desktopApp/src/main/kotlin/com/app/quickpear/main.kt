package com.app.quickpear

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.node.QuickPearNode
import com.app.quickpear.session.DesktopApprovalHandler
import com.app.quickpear.ui.components.DesktopApprovalHost
import com.app.quickpear.ui.components.DesktopSendToDialog
import com.app.quickpear.util.DesktopAutostartManager
import com.app.quickpear.util.SendToInstaller
import com.app.quickpear.util.WindowsContextMenuRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import java.awt.Color
import java.awt.EventQueue
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import kotlin.system.exitProcess

fun createIconPainter(): Painter {
    val size = 64
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.color = Color(0x10, 0xB9, 0x81) // Quick Pear emerald
    g.fillRoundRect(4, 4, size - 8, size - 8, 16, 16)
    g.color = Color.WHITE
    g.font = Font("SansSerif", Font.BOLD, 36)
    g.drawString("Q", 18, 48)
    g.dispose()
    return BitmapPainter(image.toComposeImageBitmap())
}

fun main(args: Array<String>) {
    val initialVisible = !args.contains("--background") && !args.contains("--send")
    val initialSendFiles = if (args.contains("--send")) {
        val sendIdx = args.indexOf("--send")
        args.drop(sendIdx + 1)
    } else {
        emptyList()
    }

    var onOpenRequested: (() -> Unit)? = null
    var onSendFilesRequested: ((List<String>) -> Unit)? = null

    val singleInstance = SingleInstanceManager { command ->
        if (command.startsWith("--send\t") || command == "--send") {
            val filePaths = command.split("\t").drop(1).filter { it.isNotBlank() }
            onSendFilesRequested?.invoke(filePaths)
        } else if (command.startsWith("--send ")) {
            val filePaths = listOf(command.removePrefix("--send ").trim()).filter { it.isNotBlank() }
            onSendFilesRequested?.invoke(filePaths)
        } else if (command.startsWith("OPEN") || command.isEmpty()) {
            onOpenRequested?.invoke()
        }
    }

    if (!singleInstance.startOrForward(args)) {
        // Another instance is already running; command was forwarded to it, exit immediately
        exitProcess(0)
    }

    // Register Windows 11 & 10 Explorer context menu and SendTo shortcut asynchronously in background
    Thread {
        try {
            SendToInstaller.ensureInstalled()
            WindowsContextMenuRegistry.ensureRegistered()
        } catch (_: Exception) {}
    }.apply {
        isDaemon = true
        name = "QuickPear-RegistryInstaller"
        start()
    }

    application {
        val scope = rememberCoroutineScope()
        var isWindowVisible by remember { mutableStateOf(initialVisible) }
        var isAutostart by remember { mutableStateOf(DesktopAutostartManager.isAutostartEnabled()) }
        var pendingSendFiles by remember { mutableStateOf(initialSendFiles) }
        val icon = remember { createIconPainter() }

        val approvalHandler = remember { DesktopApprovalHandler() }
        val pendingTransfer by approvalHandler.pendingTransfer.collectAsState()
        val pendingPairing by approvalHandler.pendingPairing.collectAsState()

        val node = remember {
            val userHome = System.getProperty("user.home", ".")
            val os = System.getProperty("os.name", "").lowercase()
            val dataDir = when {
                os.contains("win") -> (System.getenv("APPDATA") ?: "$userHome/AppData/Roaming") + "/QuickPear"
                else -> "$userHome/.local/share/quickpear"
            }.toPath()

            val downloadDir = File(userHome, "Downloads/Quick Pear").apply { mkdirs() }.canonicalPath.toPath()
            val hostName = System.getenv("COMPUTERNAME") ?: try {
                java.net.InetAddress.getLocalHost().hostName
            } catch (_: Exception) {
                "Desktop"
            }

            QuickPearNode(
                dataDirectory = dataDir,
                downloadDirectory = downloadDir,
                deviceNameProvider = { hostName },
                deviceType = if (os.contains("win")) DeviceType.WINDOWS else DeviceType.LINUX,
                approvalHandler = approvalHandler
            )
        }

        onOpenRequested = {
            EventQueue.invokeLater {
                isWindowVisible = true
                node.setMode(DiscoveryMode.ACTIVE)
            }
        }

        onSendFilesRequested = { files ->
            EventQueue.invokeLater {
                pendingSendFiles = files
            }
        }

        // Start node daemon in background
        remember {
            scope.launch(Dispatchers.IO) {
                node.start()
                node.setMode(if (isWindowVisible) DiscoveryMode.ACTIVE else DiscoveryMode.BACKGROUND)
            }
        }

        // System Tray Icon & Clean Context Menu
        Tray(
            icon = icon,
            tooltip = "Quick Pear",
            onAction = {
                // Single click on tray icon opens the application
                isWindowVisible = true
                node.setMode(DiscoveryMode.ACTIVE)
            },
            menu = {
                Item("Quick Pear (Aktif di latar belakang)", enabled = false, onClick = {})
                Separator()
                Item("Buka Quick Pear", onClick = {
                    isWindowVisible = true
                    node.setMode(DiscoveryMode.ACTIVE)
                })
                CheckboxItem(
                    text = "Mulai bersama sistem (Autostart)",
                    checked = isAutostart,
                    onCheckedChange = { checked ->
                        DesktopAutostartManager.setAutostartEnabled(checked)
                        isAutostart = DesktopAutostartManager.isAutostartEnabled()
                    }
                )
                Separator()
                Item("Keluar dari Quick Pear", onClick = {
                    singleInstance.stop()
                    scope.launch(Dispatchers.IO) { node.stop() }
                    exitApplication()
                })
            }
        )

        // Main Application Window
        if (isWindowVisible) {
            Window(
                onCloseRequest = {
                    isWindowVisible = false
                    node.setMode(DiscoveryMode.BACKGROUND)
                },
                title = "Quick Pear",
                icon = icon
            ) {
                App(viewModel = remember { com.app.quickpear.ui.TransferViewModel(node) })
            }
        }

        // Desktop Approval & Pairing Windows (Safe standalone windows that never crash outside a parent window)
        DesktopApprovalHost(
            handler = approvalHandler,
            pendingTransfer = pendingTransfer,
            pendingPairing = pendingPairing
        )

        // Desktop SendTo Dialog (shown when files are sent via Explorer context menu)
        if (pendingSendFiles.isNotEmpty()) {
            DesktopSendToDialog(
                filePaths = pendingSendFiles,
                node = node,
                icon = icon,
                onSend = { targetPeer ->
                    scope.launch {
                        val fileMap = withContext(Dispatchers.IO) {
                            val map = mutableMapOf<FileMetadata, Path>()
                            var counter = 1
                            for (pathStr in pendingSendFiles) {
                                val file = File(pathStr)
                                if (!file.exists()) continue
                                val okioPath = file.canonicalPath.toPath()
                                val sha256 = ChecksumUtil.calculateFileSha256(okioPath)
                                val meta = FileMetadata.create(
                                    fileId = counter++,
                                    fileName = file.name,
                                    fileSizeBytes = file.length(),
                                    sha256 = sha256
                                )
                                map[meta] = okioPath
                            }
                            map
                        }
                        withContext(Dispatchers.IO) {
                            try {
                                node.sendFiles(targetPeer, fileMap)
                            } catch (_: Exception) {
                            }
                        }
                        pendingSendFiles = emptyList()
                    }
                },
                onDismiss = {
                    pendingSendFiles = emptyList()
                }
            )
        }
    }
}