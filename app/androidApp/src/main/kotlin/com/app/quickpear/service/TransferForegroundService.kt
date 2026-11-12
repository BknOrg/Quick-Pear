package com.app.quickpear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.app.quickpear.MainActivity
import com.app.quickpear.R
import com.app.quickpear.ble.AndroidBleProximity
import com.app.quickpear.discovery.BleProximityEngine
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.node.QuickPearNode
import com.app.quickpear.notification.NotificationApprovalHandler
import android.content.pm.ServiceInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import java.io.File

class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> com.app.quickpear.security.SecurityLog.event("android_service_failed") }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    private var progressJob: Job? = null
    private var foregroundStarted = false
    private var lastNotificationState: String? = null

    companion object {
        const val CHANNEL_ID = "quickpear_transfer_channel"
        const val NOTIF_ID = 1001
        const val ACTION_CANCEL_TRANSFER = "com.app.quickpear.action.CANCEL_TRANSFER"

        var activeNode: QuickPearNode? = null
            private set

        fun startService(context: Context, statusMessage: String = "Quick Pear is ready to receive files") {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                putExtra("status", statusMessage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun initNode() {
        if (activeNode != null) return

        val dataDir = filesDir.canonicalPath.toPath()
        val identityDir = noBackupFilesDir.canonicalPath.toPath()
        val legacyIdentity = File(filesDir, "identity.json")
        val protectedIdentity = File(noBackupFilesDir, "identity.json")
        if (legacyIdentity.exists()) {
            if (!protectedIdentity.exists()) {
                val temp = File(noBackupFilesDir, "identity.json.tmp")
                legacyIdentity.copyTo(temp, overwrite = true)
                check(temp.renameTo(protectedIdentity)) { "Identity migration failed" }
            }
            check(legacyIdentity.delete()) { "Legacy identity cleanup failed" }
        }
        val downloadFolder = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
            "Quick Pear"
        ).apply { mkdirs() }
        val downloadDir = downloadFolder.canonicalPath.toPath()

        val androidBle = AndroidBleProximity(applicationContext)
        val proximityEngine = BleProximityEngine(
            nativeScanner = androidBle,
            nativeAdvertiser = androidBle
        )

        val approvalHandler = NotificationApprovalHandler(applicationContext)
        val p2pNegotiator = com.app.quickpear.p2p.AndroidP2pNegotiator(applicationContext)

        val node = QuickPearNode(
            dataDirectory = dataDir,
            identityDirectory = identityDir,
            onFileFinalized = { path ->
                val file = File(path.toString())
                val relativeFolder = file.parentFile?.relativeTo(downloadFolder)?.path?.replace('\\', '/') ?: ""
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Quick Pear/$relativeFolder")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot create received download")
                try {
                    contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Cannot write received download")
                    contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    file.delete()
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            },
            downloadDirectory = downloadDir,
            deviceNameProvider = {
                val model = Build.MODEL ?: "Android"
                val deviceName = Build.DEVICE ?: "Device"
                "$model ($deviceName)"
            },
            deviceType = DeviceType.ANDROID,
            approvalHandler = approvalHandler,
            proximityEngine = proximityEngine,
            p2pLinkNegotiator = p2pNegotiator
        )

        activeNode = node

        scope.launch(Dispatchers.IO) {
            node.start()
            node.setMode(DiscoveryMode.BACKGROUND)
        }

        observeProgress(node)
    }

    private fun observeProgress(node: QuickPearNode) {
        progressJob?.cancel()
        progressJob = scope.launch {
            node.transferProgress.collectLatest { progress ->
                if (progress != null && progress.status == TransferStatus.TRANSFERRING) {
                    acquireTransferLocks()
                    updateTransferNotification(progress)
                } else if (progress != null && progress.status == TransferStatus.COMPLETED) {
                    releaseTransferLocks()
                    updateCompletedNotification(progress.fileName)
                } else {
                    releaseTransferLocks()
                    updateNotification("Quick Pear is ready to receive files")
                }
            }
        }

        scope.launch {
            node.onlineDevices.collectLatest { devices ->
                try {
                    com.app.quickpear.util.ShareShortcutPublisher.updateDynamicShortcuts(
                        applicationContext, devices, node.trustStore
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Like WhatsApp, keep node alive and active in background when task is swiped away
        activeNode?.setMode(DiscoveryMode.BACKGROUND)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reopening an activity must not replace an active transfer notification with readiness.
        if (!foregroundStarted) {
            val message = intent?.getStringExtra("status") ?: "Quick Pear is ready to receive files"
            val notification = createNotification(message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            foregroundStarted = true
            lastNotificationState = "status:$message"
            initNode()
        }
        if (intent?.action == ACTION_CANCEL_TRANSFER) {
            activeNode?.cancelTransfer()
            releaseTransferLocks()
            updateNotification("Transfer cancelled")
            return START_STICKY
        }
        return START_STICKY
    }

    private fun acquireTransferLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickPear::TransferWakeLock").apply {
                    acquire(10 * 60 * 1000L) // 10 min safety timeout per active transfer
                }
            }
        } catch (_: Exception) {}

        try {
            if (wifiLock?.isHeld != true) {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "QuickPear::WifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "QuickPear::WifiLock")
                }.apply {
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseTransferLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background file transfer readiness"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick Pear")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val state = "status:$message"
        if (lastNotificationState == state) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, createNotification(message))
        lastNotificationState = state
    }

    private fun updateTransferNotification(progress: com.app.quickpear.domain.TransferProgress) {
        val percent = (progress.progressPercentage * 100).toInt()
        val speed = formatSpeed(progress.transferSpeedBytesPerSec)
        val text = "${progress.fileName} • $percent% ($speed)"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
        }
        val pendingCancel = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transferring files...")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancel)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, notification)
        lastNotificationState = "transferring"
    }

    private fun updateCompletedNotification(fileName: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quick Pear")
            .setContentText("Transfer completed: $fileName")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingOpen)
            .setOngoing(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, notification)
        lastNotificationState = "completed"
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return "${kb.toLong()} KB/s"
        val mb = kb / 1024.0
        return "${(mb * 10).toLong() / 10.0} MB/s"
    }

    override fun onDestroy() {
        progressJob?.cancel()
        scope.launch(Dispatchers.IO) {
            activeNode?.stop()
            activeNode = null
        }
        releaseTransferLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
