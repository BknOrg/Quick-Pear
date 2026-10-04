package com.app.quickpear.discovery

import com.app.quickpear.domain.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlePayloadTest {

    @Test
    fun testBlePayloadEncodeAndDecode() {
        val original = BlePayload(
            deviceType = DeviceType.ANDROID,
            port = 8888,
            deviceName = "Galaxy-S24",
            sessionToken = "tok12345"
        )

        val bytes = original.encodeToByteArray()
        assertNotNull(bytes)

        val decodedPeer = BlePayload.decodeFromByteArray(bytes, peerIp = "192.168.49.1")
        assertNotNull(decodedPeer)
        assertEquals("Galaxy-S24", decodedPeer.name)
        assertEquals(8888, decodedPeer.port)
        assertEquals(DeviceType.ANDROID, decodedPeer.deviceType)
        assertEquals("192.168.49.1", decodedPeer.ipAddress)
    }
}
