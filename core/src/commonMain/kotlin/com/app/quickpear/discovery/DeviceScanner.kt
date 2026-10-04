package com.app.quickpear.discovery

import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.flow.Flow

interface DeviceScanner {
    fun startScanning(): Flow<PeerDevice>
    fun stopScanning()
}
