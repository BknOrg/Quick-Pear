package com.app.quickpear.discovery

object BleConstants {
    const val SERVICE_UUID_STRING = "9f8e7d6c-5b4a-3f2e-1d0c-ba9876543210"
    const val MAX_BLE_PAYLOAD_SIZE = 31

    const val DEVICE_TYPE_ANDROID: Byte = 0x01
    const val DEVICE_TYPE_WINDOWS: Byte = 0x02
    const val DEVICE_TYPE_LINUX: Byte = 0x03
    const val DEVICE_TYPE_MACOS: Byte = 0x04
    const val DEVICE_TYPE_IOS: Byte = 0x05
    const val DEVICE_TYPE_WEB: Byte = 0x06
    const val DEVICE_TYPE_UNKNOWN: Byte = 0x00
}
