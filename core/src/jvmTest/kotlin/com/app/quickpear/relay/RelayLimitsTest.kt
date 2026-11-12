package com.app.quickpear.relay

import java.net.Socket
import java.io.DataOutputStream
import kotlinx.coroutines.*
import kotlin.test.*

class RelayLimitsTest {
    private fun register(port: Int, role: Int, token: Int): Socket = Socket("127.0.0.1", port).also {
        it.soTimeout = 3000
        DataOutputStream(it.getOutputStream()).apply {
            writeInt(RelayProtocol.MAGIC); writeByte(RelayProtocol.MSG_REGISTER.toInt()); writeByte(role)
            write(ByteArray(16) { token.toByte() }); flush()
        }
    }
    @Test fun wrongRoleAndCapacityAreRejectedAndWaitingSessionExpires() = runBlocking(Dispatchers.IO) {
        val server = StandaloneRelayServer(59860, "127.0.0.1", 1000, 1)
        server.start()
        try {
            register(59860, 1, 1).use { first ->
                withTimeout(3000) { while (server.pendingSessionCount() != 1) delay(5) }
                register(59860, 1, 1).use { assertEquals(-1, it.getInputStream().read()) }
                register(59860, 2, 2).use { assertEquals(-1, it.getInputStream().read()) }
                assertEquals(-1, first.getInputStream().read())
                withTimeout(3000) { while (server.pendingSessionCount() != 0) delay(5) }
            }
        } finally { server.stop() }
    }
    @Test fun thirdRegistrantCannotReplacePairedPeer() = runBlocking(Dispatchers.IO) {
        val server = StandaloneRelayServer(59861, "127.0.0.1")
        server.start()
        try {
            register(59861, 1, 1).use { first ->
                withTimeout(3000) { while (server.pendingSessionCount() != 1) delay(5) }
                register(59861, 2, 1).use { second ->
                    val ack = ByteArray(6)
                    java.io.DataInputStream(first.getInputStream()).readFully(ack)
                    java.io.DataInputStream(second.getInputStream()).readFully(ack)
                    register(59861, 2, 1).use { assertEquals(-1, it.getInputStream().read()) }
                    first.getOutputStream().write(42); first.getOutputStream().flush()
                    assertEquals(42, second.getInputStream().read())
                }
            }
        } finally { server.stop() }
    }
}
