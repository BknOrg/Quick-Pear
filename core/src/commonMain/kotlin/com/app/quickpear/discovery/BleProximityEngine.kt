package com.app.quickpear.discovery

import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

class BleProximityEngine(
    val lanDiscovery: LanBroadcastDiscovery = LanBroadcastDiscovery(),
    val nativeScanner: DeviceScanner? = null,
    val nativeAdvertiser: DeviceAdvertiser? = null
) : DeviceScanner, DeviceAdvertiser, AdaptiveDiscovery {

    override fun startScanning(): Flow<PeerDevice> {
        val lanFlow = lanDiscovery.startScanning()
        val nativeFlow = nativeScanner?.startScanning()

        return if (nativeFlow != null) {
            merge(lanFlow, nativeFlow)
        } else {
            lanFlow
        }
    }

    override fun stopScanning() {
        lanDiscovery.stopScanning()
        nativeScanner?.stopScanning()
    }

    override fun startAdvertising(device: PeerDevice) {
        lanDiscovery.startAdvertising(device)
        nativeAdvertiser?.startAdvertising(device)
    }

    override fun stopAdvertising() {
        lanDiscovery.stopAdvertising()
        nativeAdvertiser?.stopAdvertising()
    }

    override fun setMode(mode: DiscoveryMode) {
        lanDiscovery.setMode(mode)
        (nativeScanner as? AdaptiveDiscovery)?.setMode(mode)
        (nativeAdvertiser as? AdaptiveDiscovery)?.setMode(mode)
    }

    fun triggerBurstBeacon() {
        lanDiscovery.triggerBurstBeacon()
    }
}
