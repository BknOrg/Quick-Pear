package com.app.quickpear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.app.quickpear.MainActivity
import com.app.quickpear.ble.AndroidBleProximity
import com.app.quickpear.discovery.BleProximityEngine
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.node.QuickPearNode
import com.app.quickpear.notification.NotificationApprovalHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import java.io.File

class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    companion object {
        const val CHANNEL_ID = "quickpear_transfer_channel"
        const val NOTIF_ID = 1001

        var activeNode: QuickPearNode? = null
            private set

        fun startService(context: Context, statusMessage: String = "Quick Pear siap menerima berkas") {
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
        acquireLocks()
        initNode()
    }

    private fun initNode() {
        if (activeNode != null) return

        val dataDir = filesDir.canonicalPath.toPath()
        val downloadFolder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
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
                    updateTransferNotification(progress)
                } else if (progress != null && progress.status == TransferStatus.COMPLETED) {
                    updateCompletedNotification(progress.fileName)
                } else {
                    updateNotification("Quick Pear siap menerima berkas")
                }
            }
        }

        scope.launch {
            node.onlineDevices.collectLatest { devices ->
                com.app.quickpear.util.ShareShortcutPublisher.updateDynamicShortcuts(
                    applicationContext, devices, node.trustStore
                )
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Like WhatsApp, keep node alive and active in background when task is swiped away
        activeNode?.setMode(DiscoveryMode.BACKGROUND)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusMessage = intent?.getStringExtra("status") ?: "Quick Pear siap menerima berkas"
        val notification = createNotification(statusMessage)
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickPear::TransferWakeLock").apply {
            acquire(60 * 60 * 1000L) // 1 hour max safety
        }

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

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Latar Belakang",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status kesiapan menerima berkas di latar belakang"
                setShowBadge(false)
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
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, createNotification(message))
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mentransfer berkas...")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, notification)
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
            .setContentText("Transfer selesai: $fileName")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingOpen)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, notification)
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
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
