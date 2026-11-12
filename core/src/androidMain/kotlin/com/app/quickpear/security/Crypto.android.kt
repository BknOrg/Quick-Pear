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
    actual fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("EC")
        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH")
        agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(privateKey)))
        val remote = factory.generatePublic(X509EncodedKeySpec(publicKey)) as java.security.interfaces.ECPublicKey
        require(remote.params.curve.field.fieldSize == 256) { "Expected P-256 key" }
        agreement.doPhase(remote, true)
        return agreement.generateSecret()
    }

    actual fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    actual fun crypt(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(data)
        }
}
