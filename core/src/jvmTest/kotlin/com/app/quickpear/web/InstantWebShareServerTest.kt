package com.app.quickpear.web

import kotlinx.coroutines.*
import okio.FileSystem
import java.net.Socket
import java.net.URI
import kotlin.test.*

class InstantWebShareServerTest {
    private fun request(port: Int, path: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }
    @Test fun tokenRequiredExpiresAndRotates() = runBlocking(Dispatchers.IO) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-web-${java.util.UUID.randomUUID()}"
        fs.createDirectories(root)
        val file = root / "example.txt"; fs.write(file) { writeUtf8("secret download") }
        var clock = 1000L
        val server = InstantWebShareServer(lifetimeMillis = 900_000, now = { clock })
        try {
            val port = server.start(listOf(file), 59810)
            val path = URI(server.shareUrl("127.0.0.1")).path
            assertEquals(64, path.trim('/').length)
            assertTrue(request(port, "/").startsWith("HTTP/1.1 404"))
            assertTrue(request(port, "/download/0").startsWith("HTTP/1.1 404"))
            assertTrue(request(port, path).contains("Cache-Control: no-store"))
            assertTrue(request(port, path + "download/0").endsWith("secret download"))
            clock += 900_001
            assertFalse(server.isRunning)
            assertTrue(request(port, path).startsWith("HTTP/1.1 404"))
            server.start(listOf(file), port)
            assertNotEquals(path, URI(server.shareUrl("127.0.0.1")).path)
            assertTrue(request(server.currentPort, path).startsWith("HTTP/1.1 404"))
        } finally { server.stop(); fs.deleteRecursively(root) }
        assertFalse(server.status.value.active)
    }
    @Test fun limitsConnectionsHeadersAndClosesActiveSocketsOnStop() = runBlocking(Dispatchers.IO) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "qp-web-limit-${java.util.UUID.randomUUID()}"
        fs.createDirectories(root); val file = root / "file"; fs.write(file) { writeByte(1) }
        val server = InstantWebShareServer(requestTimeoutMillis = 1000)
        val clients = mutableListOf<Socket>()
        try {
            val port = server.start(listOf(file), 59830)
            repeat(8) { clients.add(Socket("127.0.0.1", port).apply { soTimeout = 3000 }) }
            // Accepted requests never get headers; both timeout and stop must release their sockets.
            withTimeout(3000) { while (server.activeConnectionCount != 8) delay(5) }
            Socket("127.0.0.1", port).use { ninth ->
                ninth.soTimeout = 3000
                assertEquals(-1, ninth.getInputStream().read())
            }
            server.stop()
            clients.forEach { assertTrue(runCatching { it.getInputStream().read() }.getOrDefault(-1) == -1) }
            server.start(listOf(file), port)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nX: " + "a".repeat(17_000) + "\r\n\r\n").toByteArray())
                assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1) == -1)
            }
        } finally { clients.forEach { it.close() }; server.stop(); fs.deleteRecursively(root) }
    }
}
