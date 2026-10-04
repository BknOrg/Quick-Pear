package com.app.quickpear.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/**
 * Long-lived cryptographic identity of this device.
 *
 * [deviceId] is derived from the public key (first 128 bits of its SHA-256), so a peer cannot
 * claim an id without owning the matching private key. The identity is generated once and
 * persisted, replacing the old random per-launch `node-xxxxxx` id.
 */
class DeviceIdentity internal constructor(
    val publicKey: ByteArray,
    private val privateKey: ByteArray
) {
    val deviceId: String = idFromPublicKey(publicKey)

    internal fun sign(data: ByteArray): ByteArray = Crypto.sign(privateKey, data)

    @Serializable
    private class Stored(val publicKey: String, val privateKey: String)

    companion object {
        private const val FILE_NAME = "identity.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun idFromPublicKey(publicKey: ByteArray): String =
            publicKey.toByteString().sha256().hex().take(32)

        internal fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
            Crypto.verify(publicKey, data, signature)

        /** Creates a throw-away identity that is not persisted (tests, previews). */
        fun generate(): DeviceIdentity {
            val pair = Crypto.generateKeyPair()
            return DeviceIdentity(pair.publicKey, pair.privateKey)
        }

        /**
         * Loads the identity stored in [directory], creating and saving a new one if none exists.
         * A corrupt file is moved aside (identity.json.corrupt) instead of being silently overwritten.
         */
        fun loadOrCreate(directory: Path, fileSystem: FileSystem = FileSystem.SYSTEM): DeviceIdentity {
            val file = directory / FILE_NAME
            if (fileSystem.exists(file)) {
                try {
                    val stored = json.decodeFromString(Stored.serializer(), fileSystem.read(file) { readUtf8() })
                    val pub = stored.publicKey.decodeBase64()?.toByteArray()
                    val priv = stored.privateKey.decodeBase64()?.toByteArray()
                    if (pub != null && priv != null) return DeviceIdentity(pub, priv)
                } catch (_: Exception) {
                    // fall through to quarantine
                }
                val corrupt = directory / "$FILE_NAME.corrupt"
                if (fileSystem.exists(corrupt)) fileSystem.delete(corrupt)
                fileSystem.atomicMove(file, corrupt)
            }

            val identity = generate()
            fileSystem.createDirectories(directory)
            val stored = Stored(
                publicKey = identity.publicKey.toByteString().base64(),
                privateKey = identity.privateKey.toByteString().base64()
            )
            fileSystem.write(file) { writeUtf8(json.encodeToString(Stored.serializer(), stored)) }
            return identity
        }
    }
}

internal fun ByteArray.toBase64(): String = toByteString().base64()
internal fun String.fromBase64(): ByteArray? = decodeBase64()?.toByteArray()
internal fun concatBytes(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (p in parts) { p.copyInto(out, offset); offset += p.size }
    return out
}
