package com.app.quickpear.discovery

import com.app.quickpear.domain.DeviceType
import com.app.quickpear.domain.PeerDevice
import okio.Buffer

data class BlePayload(
    val deviceType: DeviceType,
    val port: Int,
    val deviceName: String,
    val sessionToken: String
) {
    fun encodeToByteArray(): ByteArray {
        val buffer = Buffer()
        val typeByte = when (deviceType) {
            DeviceType.ANDROID -> BleConstants.DEVICE_TYPE_ANDROID
            DeviceType.WINDOWS -> BleConstants.DEVICE_TYPE_WINDOWS
            DeviceType.LINUX -> BleConstants.DEVICE_TYPE_LINUX
            DeviceType.MACOS -> BleConstants.DEVICE_TYPE_MACOS
            DeviceType.IOS -> BleConstants.DEVICE_TYPE_IOS
            DeviceType.WEB -> BleConstants.DEVICE_TYPE_WEB
            DeviceType.UNKNOWN -> BleConstants.DEVICE_TYPE_UNKNOWN
        }
        buffer.writeByte(typeByte.toInt())
        buffer.writeShort(port)

        val tokenBytes = sessionToken.take(8).encodeToByteArray()
        buffer.writeByte(tokenBytes.size)
        buffer.write(tokenBytes)

        val nameBytes = deviceName.take(16).encodeToByteArray()
        buffer.writeByte(nameBytes.size)
        buffer.write(nameBytes)

        return buffer.readByteArray()
    }

    companion object {
        fun decodeFromByteArray(bytes: ByteArray, peerIp: String): PeerDevice? {
            return try {
                val buffer = Buffer().write(bytes)
                val typeByte = buffer.readByte()
                val port = buffer.readShort().toInt() and 0xFFFF

                val tokenLength = buffer.readByte().toInt() and 0xFF
                val tokenBytes = buffer.readByteArray(tokenLength.toLong())
                val sessionToken = tokenBytes.decodeToString()

                val nameLength = buffer.readByte().toInt() and 0xFF
                val nameBytes = buffer.readByteArray(nameLength.toLong())
                val deviceName = nameBytes.decodeToString()

                val deviceType = when (typeByte) {
                    BleConstants.DEVICE_TYPE_ANDROID -> DeviceType.ANDROID
                    BleConstants.DEVICE_TYPE_WINDOWS -> DeviceType.WINDOWS
                    BleConstants.DEVICE_TYPE_LINUX -> DeviceType.LINUX
                    BleConstants.DEVICE_TYPE_MACOS -> DeviceType.MACOS
                    BleConstants.DEVICE_TYPE_IOS -> DeviceType.IOS
                    BleConstants.DEVICE_TYPE_WEB -> DeviceType.WEB
                    else -> DeviceType.UNKNOWN
                }

                PeerDevice(
                    id = sessionToken.ifEmpty { deviceName },
                    name = deviceName,
                    ipAddress = peerIp,
                    port = if (port > 0) port else 8888,
                    deviceType = deviceType,
                    sessionToken = sessionToken
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
