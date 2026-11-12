package com.app.quickpear.security

import kotlin.test.*

class SecureSessionTest {
    @Test fun directionalKeysTamperReplayAndBinding() {
        val secret = ByteArray(32) { it.toByte() }
        val transcript = "bound transcript".encodeToByteArray()
        val a = SecureSession(secret, transcript, true)
        val b = SecureSession(secret, transcript, false)
        val record = a.encrypt("private file".encodeToByteArray())
        assertFalse(record.decodeToString().contains("private file"))
        val tampered = record.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFails { b.decrypt(tampered) }
        assertContentEquals("private file".encodeToByteArray(), b.decrypt(record))
        assertFails { b.decrypt(record) }
        assertFails { SecureSession(secret, "other session".encodeToByteArray(), false).decrypt(record) }
        assertContentEquals("response".encodeToByteArray(), a.decrypt(b.encrypt("response".encodeToByteArray())))
    }
    @Test fun ephemeralAgreementAndHkdfRfc5869Extract() {
        val a = Crypto.generateKeyPair(); val b = Crypto.generateKeyPair()
        assertContentEquals(Crypto.agree(a.privateKey, b.publicKey), Crypto.agree(b.privateKey, a.publicKey))
        val key = ByteArray(13) { it.toByte() }
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            okio.ByteString.of(*Crypto.hmac(key, ByteArray(22) { 0x0b })).hex())
    }
}
