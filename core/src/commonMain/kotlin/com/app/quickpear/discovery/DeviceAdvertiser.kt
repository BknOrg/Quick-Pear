package com.app.quickpear.discovery

import com.app.quickpear.domain.PeerDevice

interface DeviceAdvertiser {
    fun startAdvertising(device: PeerDevice)
    fun stopAdvertising()
}
