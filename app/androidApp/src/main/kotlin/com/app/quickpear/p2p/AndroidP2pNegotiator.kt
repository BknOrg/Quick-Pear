package com.app.quickpear.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.PeerDevice
import com.app.quickpear.network.LocalHotspotCredentials
import com.app.quickpear.network.P2pLink
import com.app.quickpear.network.P2pLinkNegotiator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidP2pNegotiator(
    private val context: Context
) : P2pLinkNegotiator {

    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val p2pChannel: WifiP2pManager.Channel? = p2pManager?.initialize(context, Looper.getMainLooper(), null)
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager: ConnectivityManager? = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val hotspotController: LocalHotspotController = LocalHotspotController(context)

    override fun isSupported(): Boolean = p2pManager != null && p2pChannel != null

    override fun isHotspotSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    override suspend fun startLocalHotspot(): LocalHotspotCredentials? {
        val info = hotspotController.startHotspot() ?: return null
        return LocalHotspotCredentials(
            ssid = info.ssid,
            passphrase = info.passphrase,
            hostIp = info.hostIp,
            port = 8888
        )
    }

    override suspend fun stopLocalHotspot() {
        hotspotController.stopHotspot()
    }

    override suspend fun connectToHotspot(ssid: String, pass: String, timeoutMillis: Long): Boolean {
        return hotspotController.connectToHotspot(ssid, pass, timeoutMillis)
    }

    override suspend fun disconnectHotspot() {
        hotspotController.disconnectHotspot()
    }

    @SuppressLint("MissingPermission")
    override suspend fun establishHostLink(): P2pLink = withContext(Dispatchers.IO) {
        val manager = p2pManager ?: throw UnsupportedOperationException("Wi-Fi Direct not available")
        val channel = p2pChannel ?: throw UnsupportedOperationException("Wi-Fi Direct channel not available")

        val deferred = CompletableDeferred<P2pLink>()

        // Create an autonomous Wi-Fi Direct group (this device becomes Group Owner at 192.168.49.1)
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                    val goIp = info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    deferred.complete(
                        object : P2pLink {
                            override val connectionType: ConnectionType = ConnectionType.WIFI_DIRECT
                            override val localIp: String = goIp
                            override val remoteIp: String = goIp
                            override val port: Int = 8888
                            override suspend fun release() {
                                withContext(Dispatchers.IO) {
                                    manager.removeGroup(channel, null)
                                }
                            }
                        }
                    )
                }
            }

            override fun onFailure(reason: Int) {
                deferred.completeExceptionally(IllegalStateException("Failed to create Wi-Fi Direct group (code: $reason)"))
            }
        })

        withTimeoutOrNull(15_000L) {
            deferred.await()
        } ?: throw IllegalStateException("Timed out creating Wi-Fi Direct group")
    }

    @SuppressLint("MissingPermission")
    override suspend fun connectClientLink(target: PeerDevice): P2pLink = withContext(Dispatchers.IO) {
        val manager = p2pManager ?: throw UnsupportedOperationException("Wi-Fi Direct not available")
        val channel = p2pChannel ?: throw UnsupportedOperationException("Wi-Fi Direct channel not available")

        val deferred = CompletableDeferred<P2pLink>()

        val config = WifiP2pConfig().apply {
            deviceAddress = target.ipAddress
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                    val goIp = info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    deferred.complete(
                        object : P2pLink {
                            override val connectionType: ConnectionType = ConnectionType.WIFI_DIRECT
                            override val localIp: String = "192.168.49.2"
                            override val remoteIp: String = goIp
                            override val port: Int = target.port
                            override suspend fun release() {
                                withContext(Dispatchers.IO) {
                                    manager.removeGroup(channel, null)
                                }
                            }
                        }
                    )
                }
            }

            override fun onFailure(reason: Int) {
                deferred.completeExceptionally(IllegalStateException("Failed to connect Wi-Fi Direct (code: $reason)"))
            }
        })

        withTimeoutOrNull(15_000L) {
            deferred.await()
        } ?: throw IllegalStateException("Timed out connecting Wi-Fi Direct")
    }
}
