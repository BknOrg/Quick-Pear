package com.app.quickpear.discovery

import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveDiscoveryTest {

    @Test
    fun testDiscoveryModeIntervals() {
        assertEquals(2_000L, DiscoveryMode.ACTIVE.beaconIntervalMillis)
        assertEquals(6_000L, DiscoveryMode.ACTIVE.ttlMillis)

        assertEquals(6_000L, DiscoveryMode.BACKGROUND.beaconIntervalMillis)
        assertEquals(20_000L, DiscoveryMode.BACKGROUND.ttlMillis)

        assertEquals(15_000L, DiscoveryMode.POWER_SAVER.beaconIntervalMillis)
        assertEquals(45_000L, DiscoveryMode.POWER_SAVER.ttlMillis)
    }

    @Test
    fun testAdaptiveDiscoveryModeSwitching() {
        val discovery = LanBroadcastDiscovery(discoveryPort = 18889)
        assertEquals(DiscoveryMode.ACTIVE, discovery.mode.value)

        discovery.setMode(DiscoveryMode.BACKGROUND)
        assertEquals(DiscoveryMode.BACKGROUND, discovery.mode.value)

        discovery.setMode(DiscoveryMode.POWER_SAVER)
        assertEquals(DiscoveryMode.POWER_SAVER, discovery.mode.value)
    }

    @Test
    fun testDiscoveryLifecycleRepeatedStartStop() = runTest {
        withContext(Dispatchers.Default) {
            val discovery = LanBroadcastDiscovery(discoveryPort = 18890)

            // Multiple starts and stops must not throw or leave orphaned state
            discovery.start()
            val flow1 = discovery.startScanning()
            discovery.stopScanning()

            val flow2 = discovery.startScanning()
            discovery.stop()

            assertTrue(discovery.onlineDevices.value.isEmpty())
        }
    }

    @Test
    fun testEngineForwardingMode() {
        val lan = LanBroadcastDiscovery(discoveryPort = 18891)
        val engine = BleProximityEngine(lanDiscovery = lan)

        assertEquals(DiscoveryMode.ACTIVE, lan.mode.value)
        engine.setMode(DiscoveryMode.BACKGROUND)
        assertEquals(DiscoveryMode.BACKGROUND, lan.mode.value)
    }
}
