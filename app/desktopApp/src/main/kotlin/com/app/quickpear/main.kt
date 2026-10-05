package com.app.quickpear

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.node.QuickPearNode
import com.app.quickpear.notification.DesktopNotificationManager
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
    return try {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream("drawable/app_logo.png")
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream("app_logo.png")
            ?: File("icon.png").takeIf { it.exists() }?.inputStream()
            ?: File("app.png").takeIf { it.exists() }?.inputStream()
            ?: File("../app.png").takeIf { it.exists() }?.inputStream()
        if (stream != null) {
            val bufferedImage = stream.use { javax.imageio.ImageIO.read(it) }
            BitmapPainter(bufferedImage.toComposeImageBitmap())
        } else {
            createFallbackIconPainter()
        }
    } catch (_: Exception) {
        createFallbackIconPainter()
    }
}

private fun createFallbackIconPainter(): Painter {
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
            WindowsContextMenuRegistry.createCleanUninstallScript()
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

        val downloadDirFile = remember {
            val userHome = System.getProperty("user.home", ".")
            File(userHome, "Downloads/Quick Pear").apply { mkdirs() }
        }

        val node = remember {
            val userHome = System.getProperty("user.home", ".")
            val os = System.getProperty("os.name", "").lowercase()
            val dataDir = when {
                os.contains("win") -> (System.getenv("APPDATA") ?: "$userHome/AppData/Roaming") + "/QuickPear"
                else -> "$userHome/.local/share/quickpear"
            }.toPath()

            val downloadDir = downloadDirFile.canonicalPath.toPath()
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

        val transferProgress by node.transferProgress.collectAsState()
        val isTransferring = transferProgress?.status == TransferStatus.TRANSFERRING

        val notificationManager = remember {
            DesktopNotificationManager(
                node = node,
                downloadDirectory = downloadDirFile,
                onOpenWindow = {
                    EventQueue.invokeLater {
                        isWindowVisible = true
                        node.setMode(DiscoveryMode.ACTIVE)
                    }
                }
            )
        }

        LaunchedEffect(Unit) {
            notificationManager.start(scope)
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

        var isTrayMenuVisible by remember { mutableStateOf(false) }
        val menuHeightDp = if (isTransferring) 275.dp else 235.dp
        val trayWindowState = rememberWindowState(size = DpSize(230.dp, menuHeightDp))
        var trayMenuTargetLocation by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var lastTrayClickTime by remember { mutableStateOf(0L) }

        val showModernTrayMenu: (clickX: Int, clickY: Int) -> Unit = { clickX, clickY ->
            val now = System.currentTimeMillis()
            if (now - lastTrayClickTime > 200) {
                lastTrayClickTime = now

                val pointerLoc = if (clickX > 0 && clickY > 0) {
                    java.awt.Point(clickX, clickY)
                } else {
                    java.awt.MouseInfo.getPointerInfo()?.location ?: java.awt.Point(100, 100)
                }

                val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                val screenDevice = ge.screenDevices.firstOrNull { device ->
                    device.defaultConfiguration.bounds.contains(pointerLoc)
                } ?: ge.defaultScreenDevice

                val config = screenDevice.defaultConfiguration
                val screenBounds = config.bounds
                val screenInsets = try {
                    java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
                } catch (_: Exception) {
                    java.awt.Insets(0, 0, 0, 0)
                }

                val menuWidth = 230
                val menuHeight = if (isTransferring) 275 else 235
                trayWindowState.size = DpSize(menuWidth.dp, menuHeight.dp)

                val usableX = screenBounds.x + screenInsets.left
                val usableY = screenBounds.y + screenInsets.top
                val usableWidth = screenBounds.width - screenInsets.left - screenInsets.right
                val usableHeight = screenBounds.height - screenInsets.top - screenInsets.bottom
                val usableRight = usableX + usableWidth
                val usableBottom = usableY + usableHeight

                // Center menu horizontally around click, clamped strictly within screen margins
                val targetX = (pointerLoc.x - menuWidth / 2).coerceIn(
                    usableX + 8,
                    (usableRight - menuWidth - 8).coerceAtLeast(usableX + 8)
                )

                // If in bottom half of screen (typical taskbar position), float above the taskbar
                val isBottomHalf = pointerLoc.y > (usableY + usableHeight / 2)
                val targetY = if (isBottomHalf) {
                    val idealY = minOf(pointerLoc.y - menuHeight - 10, usableBottom - menuHeight - 8)
                    idealY.coerceAtLeast(usableY + 8)
                } else {
                    val idealY = maxOf(pointerLoc.y + 10, usableY + 8)
                    idealY.coerceAtMost((usableBottom - menuHeight - 8).coerceAtLeast(usableY + 8))
                }

                trayWindowState.position = WindowPosition.Absolute(targetX.dp, targetY.dp)
                trayMenuTargetLocation = Pair(targetX, targetY)
                isTrayMenuVisible = true
            }
        }

        // Attach mouse listener to TrayIcon to catch right-click
        LaunchedEffect(Unit) {
            if (java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                for (attempt in 1..30) {
                    val icons = tray.trayIcons
                    if (icons.isNotEmpty()) {
                        val trayIcon = icons.first()
                        notificationManager.attachTrayIcon(trayIcon)
                        trayIcon.addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                                if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) {
                                    showModernTrayMenu(e.xOnScreen, e.yOnScreen)
                                }
                            }
                            override fun mousePressed(e: java.awt.event.MouseEvent) {
                                if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) {
                                    showModernTrayMenu(e.xOnScreen, e.yOnScreen)
                                }
                            }
                        })
                        break
                    }
                    kotlinx.coroutines.delay(100)
                }
            }
        }

        // System Tray Icon (Left-click opens app)
        Tray(
            icon = icon,
            tooltip = "Quick Pear",
            onAction = {
                isTrayMenuVisible = false
                isWindowVisible = true
                node.setMode(DiscoveryMode.ACTIVE)
            }
        )

        // Modern Floating System Tray Context Menu (Matches sleek dark flyout design)
        if (isTrayMenuVisible) {
            Window(
                onCloseRequest = { isTrayMenuVisible = false },
                undecorated = true,
                transparent = true,
                alwaysOnTop = true,
                resizable = false,
                focusable = true,
                state = trayWindowState
            ) {
                LaunchedEffect(trayWindowState.position) {
                    trayMenuTargetLocation?.let { (x, y) ->
                        window.setLocation(x, y)
                    }
                }

                DisposableEffect(window) {
                    val focusListener = object : java.awt.event.WindowFocusListener {
                        override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                            isTrayMenuVisible = false
                        }
                    }
                    window.addWindowFocusListener(focusListener)
                    onDispose {
                        window.removeWindowFocusListener(focusListener)
                    }
                }

                ModernTrayMenu(
                    isAutostart = isAutostart,
                    isTransferring = isTransferring,
                    onOpenApp = {
                        isTrayMenuVisible = false
                        isWindowVisible = true
                        node.setMode(DiscoveryMode.ACTIVE)
                    },
                    onOpenDownloads = {
                        isTrayMenuVisible = false
                        notificationManager.openDownloadFolder()
                    },
                    onCancelTransfer = {
                        isTrayMenuVisible = false
                        node.cancelTransfer()
                    },
                    onToggleAutostart = {
                        DesktopAutostartManager.setAutostartEnabled(!isAutostart)
                        isAutostart = DesktopAutostartManager.isAutostartEnabled()
                    },
                    onQuit = {
                        isTrayMenuVisible = false
                        singleInstance.stop()
                        scope.launch(Dispatchers.IO) { node.stop() }
                        exitApplication()
                    }
                )
            }
        }

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