package com.app.quickpear.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DeviceType {
    ANDROID,
    WINDOWS,
    LINUX,
    MACOS,
    IOS,
    WEB,
    UNKNOWN
}

@Serializable
enum class ConnectionType {
    LAN_WIFI,
    WIFI_DIRECT,
    LOCAL_HOTSPOT,
    BLE,
    CLOUD_P2P
}

@Serializable
data class PeerDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 8888,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val connectionType: ConnectionType = ConnectionType.LAN_WIFI,
    val sessionToken: String = "",
    val lastSeen: Long = 0L
)
