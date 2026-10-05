package com.app.quickpear.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.app.quickpear.R
import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.security.PeerIdentity
import com.app.quickpear.session.ApprovalDecision
import com.app.quickpear.session.ApprovalHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NotificationApprovalHandler(
    private val context: Context
) : ApprovalHandler {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val idCounter = AtomicInteger(2000)

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val approvalChannel = NotificationChannel(
                CHANNEL_APPROVAL_ID,
                "Transfer & Pairing Confirmation",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Interactive notifications to accept or decline files and device pairing"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(approvalChannel)
        }
    }

    override suspend fun onTransferRequest(peer: PeerIdentity, request: MetadataRequest): ApprovalDecision {
        val notifId = idCounter.incrementAndGet()
        val deferred = CompletableDeferred<ApprovalDecision>()
        pendingTransfers[notifId] = deferred

        val totalBytes = request.files.sumOf { it.fileSizeBytes }
        val sizeFormatted = formatBytes(totalBytes)
        val fileCount = request.files.size

        val acceptIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_ACCEPT
            putExtra(TransferActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val acceptAlwaysIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_ACCEPT_ALWAYS
            putExtra(TransferActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val rejectIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_REJECT
            putExtra(TransferActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVAL_ID)
            .setContentTitle("Incoming Files from ${peer.name}")
            .setContentText("$fileCount files ($sizeFormatted)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${peer.name} wants to send $fileCount files ($sizeFormatted). Choose an action below:"))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(0, "Accept", PendingIntent.getBroadcast(context, notifId * 10 + 1, acceptIntent, flags))
            .addAction(0, "Always Accept", PendingIntent.getBroadcast(context, notifId * 10 + 2, acceptAlwaysIntent, flags))
            .addAction(0, "Decline", PendingIntent.getBroadcast(context, notifId * 10 + 3, rejectIntent, flags))
            .build()

        notificationManager.notify(notifId, notification)

        val decision = withTimeoutOrNull(TIMEOUT_TRANSFER_MS) {
            deferred.await()
        } ?: ApprovalDecision.REJECT

        pendingTransfers.remove(notifId)
        notificationManager.cancel(notifId)
        return decision
    }

    override suspend fun onPairingRequest(peer: PeerIdentity, sasCode: String): Boolean {
        val notifId = idCounter.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pendingPairings[notifId] = deferred

        val matchIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_PAIR_ACCEPT
            putExtra(TransferActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val rejectIntent = Intent(context, TransferActionReceiver::class.java).apply {
            action = TransferActionReceiver.ACTION_PAIR_REJECT
            putExtra(TransferActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVAL_ID)
            .setContentTitle("Pairing Request")
            .setContentText("Code: $sasCode from ${peer.name}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Device '${peer.name}' wants to pair.\nVerification code: $sasCode\nDoes the code match on both screens?"))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .addAction(0, "Match", PendingIntent.getBroadcast(context, notifId * 10 + 1, matchIntent, flags))
            .addAction(0, "Decline", PendingIntent.getBroadcast(context, notifId * 10 + 2, rejectIntent, flags))
            .build()

        notificationManager.notify(notifId, notification)

        val accepted = withTimeoutOrNull(TIMEOUT_PAIRING_MS) {
            deferred.await()
        } ?: false

        pendingPairings.remove(notifId)
        notificationManager.cancel(notifId)
        return accepted
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${(kb * 10).toLong() / 10.0} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${(mb * 10).toLong() / 10.0} MB"
        val gb = mb / 1024.0
        return "${(gb * 10).toLong() / 10.0} GB"
    }

    companion object {
        const val CHANNEL_APPROVAL_ID = "quickpear_approval_channel"
        private const val TIMEOUT_TRANSFER_MS = 60_000L
        private const val TIMEOUT_PAIRING_MS = 120_000L

        internal val pendingTransfers = ConcurrentHashMap<Int, CompletableDeferred<ApprovalDecision>>()
        internal val pendingPairings = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    }
}
