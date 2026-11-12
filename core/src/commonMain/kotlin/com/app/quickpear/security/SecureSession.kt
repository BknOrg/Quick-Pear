package com.app.quickpear.security

import okio.Buffer
import okio.ByteString.Companion.toByteString

/** Directional AEAD record keys. Counters are consumed only by authenticated records. */
internal class SecureSession(secret: ByteArray, transcript: ByteArray, initiator: Boolean) {
    private val binding = transcript.toByteString().sha256().toByteArray()
    private val prk = Crypto.hmac(binding, secret)
    private fun expand(label: String) = Crypto.hmac(prk, label.encodeToByteArray() + byteArrayOf(1))
    private val sendKey = expand(if (initiator) "QP-v2 initiator" else "QP-v2 responder")
    private val receiveKey = expand(if (initiator) "QP-v2 responder" else "QP-v2 initiator")
    private var sent = 0L
    private var received = 0L

    @Synchronized fun encrypt(plain: ByteArray): ByteArray {
        check(sent < Long.MAX_VALUE) { "Session exhausted" }
        val counter = sent++
        return Buffer().writeLong(counter).write(Crypto.crypt(true, sendKey, nonce(counter), binding, plain)).readByteArray()
    }

    @Synchronized fun decrypt(record: ByteArray): ByteArray {
        require(record.size >= 24) { "Truncated encrypted record" }
        val buffer = Buffer().write(record)
        val counter = buffer.readLong()
        require(counter == received && received < Long.MAX_VALUE) { "Replayed or out-of-order record" }
        val plain = Crypto.crypt(false, receiveKey, nonce(counter), binding, buffer.readByteArray())
        received++
        return plain
    }

    private fun nonce(counter: Long) = Buffer().writeInt(0).writeLong(counter).readByteArray()
}
