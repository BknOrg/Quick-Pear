package com.app.quickpear.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.quickpear.session.ApprovalDecision

class TransferActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId == -1) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notifId)

        when (intent.action) {
            ACTION_ACCEPT -> {
                NotificationApprovalHandler.pendingTransfers[notifId]?.complete(ApprovalDecision.ACCEPT)
            }
            ACTION_ACCEPT_ALWAYS -> {
                NotificationApprovalHandler.pendingTransfers[notifId]?.complete(ApprovalDecision.ACCEPT_ALWAYS)
            }
            ACTION_REJECT -> {
                NotificationApprovalHandler.pendingTransfers[notifId]?.complete(ApprovalDecision.REJECT)
            }
            ACTION_PAIR_ACCEPT -> {
                NotificationApprovalHandler.pendingPairings[notifId]?.complete(true)
            }
            ACTION_PAIR_REJECT -> {
                NotificationApprovalHandler.pendingPairings[notifId]?.complete(false)
            }
        }
    }

    companion object {
        const val EXTRA_NOTIF_ID = "com.app.quickpear.extra.NOTIF_ID"

        const val ACTION_ACCEPT = "com.app.quickpear.action.ACCEPT"
        const val ACTION_ACCEPT_ALWAYS = "com.app.quickpear.action.ACCEPT_ALWAYS"
        const val ACTION_REJECT = "com.app.quickpear.action.REJECT"
        const val ACTION_PAIR_ACCEPT = "com.app.quickpear.action.PAIR_ACCEPT"
        const val ACTION_PAIR_REJECT = "com.app.quickpear.action.PAIR_REJECT"
    }
}
