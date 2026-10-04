package com.app.quickpear.discovery

import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fallback mDNS Proximity Discovery implementation for local Wi-Fi subnet.
 */
class MdnsDiscovery : DeviceScanner, DeviceAdvertiser {

    private val _discoveredDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    private var isAdvertising = false
    private var isScanning = false

    override fun startScanning(): Flow<PeerDevice> = flow {
        isScanning = true
        // Simulated local broadcast mDNS advertisement listener
        _discoveredDevices.value.forEach { emit(it) }
    }

    private fun flow(block: suspend kotlinx.coroutines.flow.FlowCollector<PeerDevice>.() -> Unit): Flow<PeerDevice> {
        return kotlinx.coroutines.flow.flow(block)
    }

    override fun stopScanning() {
        isScanning = false
    }

    override fun startAdvertising(device: PeerDevice) {
        isAdvertising = true
    }

    override fun stopAdvertising() {
        isAdvertising = false
    }

    fun addDiscoveredDevice(device: PeerDevice) {
        _discoveredDevices.value = _discoveredDevices.value + device
    }
}
