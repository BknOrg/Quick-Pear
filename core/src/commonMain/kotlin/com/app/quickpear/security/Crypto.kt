package com.app.quickpear.security

internal class RawKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

/**
 * Platform crypto primitives: ECDSA P-256 signatures and secure randomness.
 * P-256 is used because (unlike Ed25519) it is available on every supported Android API level.
 * Public keys are X.509 encoded, private keys PKCS#8 encoded.
 */
internal expect object Crypto {
    fun generateKeyPair(): RawKeyPair
    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    fun randomBytes(size: Int): ByteArray
}
