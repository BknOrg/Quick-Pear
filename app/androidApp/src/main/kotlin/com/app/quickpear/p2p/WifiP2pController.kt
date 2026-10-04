package com.app.quickpear.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper

class WifiP2pController(
    private val context: Context
) {
    private val wifiP2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    init {
        wifiP2pManager?.let { manager ->
            channel = manager.initialize(context, Looper.getMainLooper(), null)
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val ch = channel ?: return onFailure(-1)
        try {
            wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onSuccess()
                }

                override fun onFailure(reason: Int) {
                    onFailure(reason)
                }
            })
        } catch (_: SecurityException) {
            onFailure(-1)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String, onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val ch = channel ?: return onFailure(-1)
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        try {
            wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onSuccess()
                }

                override fun onFailure(reason: Int) {
                    onFailure(reason)
                }
            })
        } catch (_: SecurityException) {
            onFailure(-1)
        }
    }
}
