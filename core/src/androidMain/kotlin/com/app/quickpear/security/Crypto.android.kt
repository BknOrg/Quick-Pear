package com.app.quickpear.security

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

internal actual object Crypto {
    private val secureRandom = SecureRandom()

    actual fun generateKeyPair(): RawKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        return RawKeyPair(pair.public.encoded, pair.private.encoded)
    }

    actual fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(data)
        return signature.sign()
    }

    actual fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(data)
        verifier.verify(signature)
    } catch (_: Exception) {
        false
    }

    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
}
