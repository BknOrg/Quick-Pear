package com.app.quickpear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.wifi.WifiManager
import android.os.ParcelUuid
import com.app.quickpear.discovery.AdaptiveDiscovery
import com.app.quickpear.discovery.BleConstants
import com.app.quickpear.discovery.BlePayload
import com.app.quickpear.discovery.DeviceAdvertiser
import com.app.quickpear.discovery.DeviceScanner
import com.app.quickpear.discovery.DiscoveryMode
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

class AndroidBleProximity(
    private val context: Context
) : DeviceScanner, DeviceAdvertiser, AdaptiveDiscovery {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _discoveredPeers = MutableSharedFlow<PeerDevice>(extraBufferCapacity = 64)
    private var activeScanCallback: ScanCallback? = null
    private var activeAdvertiseCallback: AdvertiseCallback? = null

    private var currentMode: DiscoveryMode = DiscoveryMode.ACTIVE

    private val serviceParcelUuid = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID_STRING))

    override fun setMode(mode: DiscoveryMode) {
        currentMode = mode
    }

    @SuppressLint("MissingPermission")
    override fun startScanning(): Flow<PeerDevice> {
        acquireMulticastLock()

        scanner = bluetoothAdapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(serviceParcelUuid)
            .build()

        val scanMode = when (currentMode) {
            DiscoveryMode.ACTIVE -> ScanSettings.SCAN_MODE_LOW_LATENCY
            DiscoveryMode.BACKGROUND, DiscoveryMode.POWER_SAVER -> ScanSettings.SCAN_MODE_LOW_POWER
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val record = result?.scanRecord ?: return
                val serviceData = record.getServiceData(serviceParcelUuid) ?: record.bytes
                val peer = BlePayload.decodeFromByteArray(serviceData, peerIp = result.device?.address ?: "0.0.0.0")
                if (peer != null) {
                    _discoveredPeers.tryEmit(peer.copy(lastSeen = System.currentTimeMillis()))
                }
            }
        }

        activeScanCallback = callback
        try {
            scanner?.startScan(listOf(filter), settings, callback)
        } catch (_: SecurityException) {
        }

        return _discoveredPeers.asSharedFlow()
    }

    @SuppressLint("MissingPermission")
    override fun stopScanning() {
        activeScanCallback?.let { callback ->
            try {
                scanner?.stopScan(callback)
            } catch (_: SecurityException) {
            }
        }
        activeScanCallback = null
        releaseMulticastLock()
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(device: PeerDevice) {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val payload = BlePayload(
            deviceType = DeviceType.ANDROID,
            port = device.port,
            deviceName = device.name,
            sessionToken = device.sessionToken
        )

        val advertiseMode = when (currentMode) {
            DiscoveryMode.ACTIVE -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            DiscoveryMode.BACKGROUND, DiscoveryMode.POWER_SAVER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(serviceParcelUuid)
            .addServiceData(serviceParcelUuid, payload.encodeToByteArray())
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
            override fun onStartFailure(errorCode: Int) {}
        }

        activeAdvertiseCallback = callback
        try {
            advertiser?.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopAdvertising() {
        activeAdvertiseCallback?.let { callback ->
            try {
                advertiser?.stopAdvertising(callback)
            } catch (_: SecurityException) {
            }
        }
        activeAdvertiseCallback = null
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("QuickPearMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }
}
