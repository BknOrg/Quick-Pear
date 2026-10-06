package com.app.quickpear.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.security.TrustStore

object ShareShortcutPublisher {

    const val CATEGORY_SHARE_TARGET = "com.app.quickpear.category.SHARE_TARGET"
    const val EXTRA_TARGET_PEER_ID = "com.app.quickpear.extra.TARGET_PEER_ID"

    fun updateDynamicShortcuts(
        context: Context,
        onlineDevices: List<PeerDevice>,
        trustStore: TrustStore
    ) {
        val trustedOnline = onlineDevices.filter { trustStore.isTrusted(it.id) }.take(4)

        val shortcuts = trustedOnline.map { device ->
            val trusted = trustStore.get(device.id)
            val displayName = trusted?.displayName() ?: device.name

            val intent = Intent(context, com.app.quickpear.ShareActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(EXTRA_TARGET_PEER_ID, device.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            ShortcutInfoCompat.Builder(context, device.id)
                .setShortLabel(displayName)
                .setLongLabel("Send files to $displayName")
                .setIcon(IconCompat.createWithResource(context, android.R.drawable.stat_sys_upload))
                .setIntent(intent)
                .setCategories(setOf(CATEGORY_SHARE_TARGET))
                .build()
        }

        ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)
    }
}
