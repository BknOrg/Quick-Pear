package com.app.quickpear.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

@Serializable
data class TrustedDevice(
    val id: String,
    val name: String,
    /** Base64 X.509 public key. The id must equal [DeviceIdentity.idFromPublicKey] of this key. */
    val publicKey: String,
    val addedAtMillis: Long,
    val lastKnownIp: String? = null,
    val lastKnownPort: Int = 8888
)

/**
 * Persistent list of devices allowed to send files without being asked each time.
 * Stored as JSON in [directory]/trusted_devices.json and observable via [devices].
 */
class TrustStore(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = directory / FILE_NAME
    private val serializer = ListSerializer(TrustedDevice.serializer())

    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    fun isTrusted(deviceId: String): Boolean = _devices.value.any { it.id == deviceId }

    fun get(deviceId: String): TrustedDevice? = _devices.value.firstOrNull { it.id == deviceId }

    /** Adds (or refreshes the name of) a trusted device. Rejects ids that do not match the key. */
    fun add(
        deviceId: String,
        name: String,
        publicKey: ByteArray,
        ipAddress: String? = null,
        port: Int = 8888
    ) {
        require(DeviceIdentity.idFromPublicKey(publicKey) == deviceId) { "Device id does not match public key" }
        val entry = TrustedDevice(deviceId, name, publicKey.toBase64(), nowMillis(), ipAddress, port)
        _devices.update { list ->
            val existing = list.firstOrNull { it.id == deviceId }
            val merged = if (existing != null) {
                existing.copy(
                    name = name,
                    lastKnownIp = ipAddress ?: existing.lastKnownIp,
                    lastKnownPort = port
                )
            } else {
                entry
            }
            list.filterNot { it.id == deviceId } + merged
        }
        save()
    }

    fun updateLastKnownIp(deviceId: String, ipAddress: String, port: Int = 8888) {
        _devices.update { list ->
            list.map {
                if (it.id == deviceId) it.copy(lastKnownIp = ipAddress, lastKnownPort = port) else it
            }
        }
        save()
    }

    fun remove(deviceId: String) {
        _devices.update { list -> list.filterNot { it.id == deviceId } }
        save()
    }

    private fun load(): List<TrustedDevice> {
        if (!fileSystem.exists(file)) return emptyList()
        return try {
            json.decodeFromString(serializer, fileSystem.read(file) { readUtf8() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save() {
        fileSystem.createDirectories(directory)
        val tmp = directory / "$FILE_NAME.tmp"
        fileSystem.write(tmp) { writeUtf8(json.encodeToString(serializer, _devices.value)) }
        fileSystem.atomicMove(tmp, file)
    }

    private companion object {
        const val FILE_NAME = "trusted_devices.json"
    }
}
