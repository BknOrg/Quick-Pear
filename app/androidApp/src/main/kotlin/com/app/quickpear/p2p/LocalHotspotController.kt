package com.app.quickpear.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HotspotInfo(
    val ssid: String,
    val passphrase: String,
    val hostIp: String = "192.168.43.1"
)

class LocalHotspotController(
    private val context: Context
) {
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager: ConnectivityManager? = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var clientNetworkCallback: ConnectivityManager.NetworkCallback? = null

    val isHotspotActive: Boolean
        get() = hotspotReservation != null

    @SuppressLint("MissingPermission")
    suspend fun startHotspot(): HotspotInfo? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@withContext null
        if (hotspotReservation != null) {
            val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hotspotReservation?.softApConfiguration
            } else null
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config?.ssid ?: "QuickPearHotspot" else "QuickPearHotspot"
            val passphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config?.passphrase ?: "" else ""
            return@withContext HotspotInfo(ssid = ssid, passphrase = passphrase)
        }

        val deferred = CompletableDeferred<HotspotInfo?>()
        try {
            wifiManager?.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration
                    } else null
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config?.ssid ?: "QuickPearHotspot" else "QuickPearHotspot"
                    val passphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config?.passphrase ?: "" else ""
                    deferred.complete(HotspotInfo(ssid = ssid, passphrase = passphrase))
                }

                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    deferred.complete(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            deferred.complete(null)
        }

        withTimeoutOrNull(8000L) {
            deferred.await()
        }
    }

    fun stopHotspot() {
        try {
            hotspotReservation?.close()
        } catch (_: Exception) {}
        hotspotReservation = null
    }

    @SuppressLint("MissingPermission")
    suspend fun connectToHotspot(ssid: String, passphrase: String, timeoutMillis: Long = 6000L): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val cm = connectivityManager ?: return@withContext false

        disconnectHotspot()

        val deferred = CompletableDeferred<Boolean>()
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
        if (passphrase.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(passphrase)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                cm.bindProcessToNetwork(network)
                deferred.complete(true)
            }

            override fun onUnavailable() {
                super.onUnavailable()
                deferred.complete(false)
            }
        }
        clientNetworkCallback = callback

        try {
            cm.requestNetwork(request, callback)
            val success = withTimeoutOrNull(timeoutMillis) {
                deferred.await()
            } ?: false
            if (!success) {
                disconnectHotspot()
            }
            success
        } catch (_: Exception) {
            disconnectHotspot()
            false
        }
    }

    fun disconnectHotspot() {
        val cm = connectivityManager
        try {
            clientNetworkCallback?.let {
                cm?.unregisterNetworkCallback(it)
                cm?.bindProcessToNetwork(null)
            }
        } catch (_: Exception) {}
        clientNetworkCallback = null
    }

    @SuppressLint("MissingPermission")
    fun startLocalOnlyHotspot(
        onStarted: (HotspotInfo) -> Unit,
        onFailed: (Int) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailed(-1)
            return
        }
        try {
            wifiManager?.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    super.onStarted(reservation)
                    hotspotReservation = reservation
                    val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration
                    } else null
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config?.ssid ?: "QuickPearHotspot" else "QuickPearHotspot"
                    val passphrase = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) config?.passphrase ?: "" else ""
                    onStarted(HotspotInfo(ssid = ssid, passphrase = passphrase))
                }
                override fun onStopped() {
                    super.onStopped()
                    hotspotReservation = null
                }
                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    onFailed(reason)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            onFailed(-1)
        }
    }

    fun stopLocalOnlyHotspot() {
        stopHotspot()
    }
}
