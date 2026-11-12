package com.app.quickpear.relay

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** Bounded relay. Application traffic is opaque AEAD records, authenticated end to end. */
class StandaloneRelayServer(private val port: Int = RelayProtocol.DEFAULT_RELAY_PORT,
    private val host: String = "0.0.0.0", private val pairingTimeoutMillis: Long = 30_000,
    private val maxWaitingSessions: Int = 128) {
    private var selector: SelectorManager? = null
    private var server: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val mutex = Mutex()
    private val capacity = Semaphore(maxWaitingSessions * 2)
    private class Slot(val socket: Socket, val read: ByteReadChannel, val write: ByteWriteChannel, val role: Byte)
    private class Session(val first: Slot, var second: Slot? = null,
        val paired: CompletableDeferred<Unit> = CompletableDeferred(), val done: CompletableDeferred<Unit> = CompletableDeferred())
    private val sessions = mutableMapOf<String, Session>()
    internal suspend fun pendingSessionCount(): Int = mutex.withLock { sessions.size }
    suspend fun start() {
        check(server == null)
        val manager = SelectorManager(Dispatchers.IO)
        selector = manager
        val listener = aSocket(manager).tcp().bind(host, port)
        server = listener
        val workers = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = workers
        workers.launch {
            while (isActive) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                if (!capacity.tryAcquire()) { socket.close(); continue }
                launch { try { handle(socket) } finally { capacity.release() } }
            }
        }
    }
    private suspend fun handle(socket: Socket) {
        var token: String? = null
        var session: Session? = null
        var owner = false
        try {
            val read = socket.openReadChannel()
            val write = socket.openWriteChannel(autoFlush = true)
            val registration = withTimeout(pairingTimeoutMillis) {
                require(read.readInt() == RelayProtocol.MAGIC)
                val bytes = ByteArray(18); read.readFully(bytes)
                require(bytes[0] == RelayProtocol.MSG_REGISTER && bytes[1] in byteArrayOf(1, 2))
                bytes
            }
            val key = registration.copyOfRange(2, 18).joinToString("") { "%02x".format(it) }
            token = key
            val slot = Slot(socket, read, write, registration[1])
            val selected = mutex.withLock {
                val existing = sessions[key]
                if (existing == null) {
                    require(sessions.size < maxWaitingSessions) { "Relay capacity reached" }
                    owner = true
                    Session(slot).also { sessions[key] = it }
                } else {
                    require(existing.second == null && existing.first.role != slot.role) { "Relay session already paired or wrong role" }
                    existing.second = slot
                    existing
                }
            }
            session = selected
            if (owner) {
                withTimeout(pairingTimeoutMillis) { selected.paired.await() }
                selected.done.await()
            } else {
                val first = selected.first
                for (peer in listOf(first, slot)) {
                    peer.write.writeInt(RelayProtocol.MAGIC)
                    peer.write.writeFully(byteArrayOf(RelayProtocol.MSG_REGISTER_ACK, RelayProtocol.STATUS_PEER_CONNECTED))
                    peer.write.flush()
                }
                selected.paired.complete(Unit)
                coroutineScope {
                    val ended = CompletableDeferred<Unit>()
                    suspend fun pipe(from: Slot, to: Slot) {
                        try {
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = withTimeout(600_000) { from.read.readAvailable(buffer) }
                                if (n < 0) break
                                if (n > 0) withTimeout(30_000) { to.write.writeFully(buffer, 0, n) }
                            }
                        } finally { ended.complete(Unit) }
                    }
                    val a = launch { pipe(first, slot) }
                    val b = launch { pipe(slot, first) }
                    ended.await()
                    first.socket.close(); slot.socket.close()
                    a.cancelAndJoin(); b.cancelAndJoin()
                }
            }
        } catch (e: TimeoutCancellationException) { com.app.quickpear.security.SecurityLog.event("relay_timeout")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (_: Exception) { com.app.quickpear.security.SecurityLog.event("relay_session_rejected") }
        finally {
            val selected = session
            if (selected != null) {
                mutex.withLock { if (sessions[token] === selected) sessions.remove(token) }
                selected.first.socket.close(); selected.second?.socket?.close()
                selected.paired.cancel(); selected.done.complete(Unit)
            }
            socket.close()
        }
    }
    suspend fun stop() {
        scope?.cancel(); server?.close()
        mutex.withLock { sessions.values.forEach { it.first.socket.close(); it.second?.socket?.close(); it.done.cancel() }; sessions.clear() }
        selector?.close(); selector = null; server = null; scope = null
    }
}
