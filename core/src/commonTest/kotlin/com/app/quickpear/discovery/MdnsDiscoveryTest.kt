package com.app.quickpear.discovery

import com.app.quickpear.domain.ConnectionType
import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MdnsDiscoveryTest {

    @Test
    fun testMdnsDiscoveryScanAndAdvertise() = runTest {
        val mdns = MdnsDiscovery()
        val peer = PeerDevice(
            id = "test-peer-1",
            name = "Laptop-Dev",
            ipAddress = "192.168.1.50",
            port = 8888,
            deviceType = DeviceType.WINDOWS,
            connectionType = ConnectionType.LAN_WIFI
        )

        mdns.startAdvertising(peer)
        mdns.addDiscoveredDevice(peer)

        val discovered = mdns.startScanning().first()
        assertEquals("test-peer-1", discovered.id)
        assertEquals("Laptop-Dev", discovered.name)
        assertEquals("192.168.1.50", discovered.ipAddress)

        mdns.stopAdvertising()
        mdns.stopScanning()
    }
}
