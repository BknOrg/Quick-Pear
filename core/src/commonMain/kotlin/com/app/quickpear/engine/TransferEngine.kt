package com.app.quickpear.engine

import com.app.quickpear.domain.*
import com.app.quickpear.io.*
import com.app.quickpear.network.FrameConnection
import com.app.quickpear.protocol.ProtocolConstants as P
import com.app.quickpear.security.SecurityLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path

class TransferEngine(private val partFileManager: PartFileManager, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val progress = MutableStateFlow<TransferProgress?>(null)
    val progressState: StateFlow<TransferProgress?> = progress.asStateFlow()
    private val transferLock = Mutex()
    @Volatile private var activeConnection: FrameConnection? = null
    @Volatile private var receivingKey: TransferFileKey? = null
    @Volatile private var cancelled = false
    private val cancelScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun updateProgress(value: TransferProgress?) { progress.value = value }
    private fun failed(message: String) {
        SecurityLog.event("transfer_failed")
        progress.value = TransferProgress(0, "", 0, 0, 0, 0, 0, TransferStatus.FAILED, message)
    }
    fun cancelTransfer(reason: String = "Transfer cancelled by user") {
        cancelled = true
        receivingKey?.let { partFileManager.resetPart(it) }
        val connection = activeConnection
        if (connection != null) cancelScope.launch {
            try { withTimeout(3000) { connection.sendFrame(P.MSG_CANCEL, byteArrayOf()) } }
            catch (_: Exception) { SecurityLog.event("cancel_notification_failed") }
            finally { connection.close() }
        }
        failed(reason)
    }
    private fun update(meta: FileMetadata, bytes: Long, start: Long, index: Long) {
        val seconds = ((System.currentTimeMillis() - start) / 1000.0).coerceAtLeast(0.001)
        progress.value = TransferProgress(meta.fileId, meta.fileName, bytes, meta.fileSizeBytes,
            (bytes / seconds).toLong(), index, meta.totalChunks, TransferStatus.TRANSFERRING)
    }
    suspend fun sendFiles(connection: FrameConnection, sessionId: String, filesMap: Map<FileMetadata, Path>) =
        withContext(Dispatchers.IO) { transferLock.withLock {
            cancelled = false; activeConnection = connection
            try {
                check(connection.authenticatedPeerId != null) { "Authenticated session required" }
                val request = MetadataRequest(sessionId, filesMap.keys.toList())
                request.validate()
                connection.sendMetadataRequest(request)
                val start = System.currentTimeMillis()
                for (meta in request.files) {
                    val response = connection.readHeader(120_000)
                    require(response.messageType == P.MSG_METADATA_RESP)
                    val (code, offset) = connection.readMetadataResponse()
                    require(code == P.METADATA_RESP_ACCEPTED) { "Receiver declined transfer or storage unavailable" }
                    require(offset in 0..meta.totalChunks)
                    val source = filesMap.getValue(meta)
                    require(fileSystem.metadata(source).size == meta.fileSizeBytes) { "Source changed" }
                    var bytes = minOf(offset * meta.chunkSizeBytes, meta.fileSizeBytes)
                    update(meta, bytes, start, offset)
                    FileChunker(fileSystem, meta.chunkSizeBytes).readChunksFlow(source, offset).collect { chunk ->
                        var accepted = false
                        repeat(3) {
                            if (!accepted) {
                                connection.sendChunkData(chunk)
                                val header = connection.readHeader()
                                require(header.messageType == P.MSG_CHUNK_ACK) { "Transfer cancelled or invalid ACK" }
                                val ack = connection.readChunkAck()
                                require(ack.chunkId == chunk.header.chunkId)
                                accepted = ack.status == ChunkAck.STATUS_SUCCESS
                            }
                        }
                        check(accepted) { "Chunk rejected" }
                        bytes += chunk.payloadBytes.size
                        update(meta, bytes, start, chunk.header.chunkId + 1)
                    }
                    connection.sendTransferComplete(meta.fileId, ChecksumUtil.calculateFileSha256Bytes(source, fileSystem))
                    val header = connection.readHeader()
                    require(header.messageType == P.MSG_TRANSFER_COMPLETE_ACK)
                    val (id, status) = connection.readTransferCompleteAck()
                    require(id == meta.fileId && status == 0.toByte()) { "Final integrity check rejected" }
                }
                progress.value = progress.value?.copy(status = TransferStatus.COMPLETED)
            } catch (e: TimeoutCancellationException) { SecurityLog.event("transfer_timeout"); failed("Transfer timed out; retry to resume") }
              catch (e: CancellationException) { failed("Transfer interrupted; retry to resume"); throw e }
              catch (_: Exception) { failed(if (cancelled) "Transfer cancelled by user" else "Transfer failed; retry to resume") }
            finally { activeConnection = null }
        } }

    suspend fun receiveFiles(connection: FrameConnection, onApprovalRequested: suspend (MetadataRequest) -> Boolean) =
        withContext(Dispatchers.IO) { transferLock.withLock {
            cancelled = false; activeConnection = connection
            try {
                val sender = connection.authenticatedPeerId ?: error("Authenticated session required")
                val header = connection.readHeader()
                require(header.messageType == P.MSG_METADATA_REQ)
                val request = connection.readMetadataRequest(header.payloadLength)
                val total = request.validate()
                if (!partFileManager.hasSufficientStorage(total)) {
                    connection.sendMetadataResponse(P.METADATA_RESP_INSUFFICIENT_STORAGE, 0)
                    error("Storage unavailable")
                }
                if (!withTimeout(120_000) { onApprovalRequested(request) }) {
                    connection.sendMetadataResponse(P.METADATA_RESP_REJECTED, 0)
                    error("Transfer rejected")
                }
                val start = System.currentTimeMillis()
                for (meta in request.files) {
                    val key = TransferFileKey("$sender:${request.sessionId}", meta.fileId)
                    receivingKey = key
                    partFileManager.prepare(key, sender, meta)
                    val offset = partFileManager.getStartChunkOffset(key)
                    connection.sendMetadataResponse(P.METADATA_RESP_ACCEPTED, offset)
                    update(meta, partFileManager.receivedBytes(key), start, offset)
                    var done = false
                    while (!done) {
                        val message = connection.readHeader(P.IDLE_TIMEOUT_MS)
                        when (message.messageType) {
                            P.MSG_CANCEL -> {
                                connection.readFramePayload(message.payloadLength, 0)
                                cancelled = true; partFileManager.resetPart(key)
                                error("Transfer cancelled")
                            }
                            P.MSG_CHUNK_DATA -> {
                                val chunk = connection.readChunkData(message.payloadLength)
                                if (ChecksumUtil.calculateChunkChecksum(chunk.payloadBytes) != chunk.header.checksum) {
                                    connection.sendChunkAck(ChunkAck(chunk.header.chunkId, ChunkAck.STATUS_CHECKSUM_MISMATCH))
                                } else {
                                    partFileManager.writeChunk(key, chunk.header.chunkId, chunk.payloadBytes, chunk.header.checksum)
                                    connection.sendChunkAck(ChunkAck(chunk.header.chunkId, ChunkAck.STATUS_SUCCESS))
                                    update(meta, partFileManager.receivedBytes(key), start, chunk.header.chunkId + 1)
                                }
                            }
                            P.MSG_TRANSFER_COMPLETE -> {
                                val (fileId, hash) = connection.readTransferComplete()
                                require(fileId == meta.fileId)
                                try {
                                    require(hash.contentEquals(meta.expectedSha256.hexBytes())) { "Source changed during transfer" }
                                    partFileManager.finalizeTransfer(key)
                                    connection.sendTransferCompleteAck(fileId, 0)
                                    done = true; receivingKey = null
                                } catch (_: Exception) {
                                    partFileManager.resetPart(key)
                                    connection.sendTransferCompleteAck(fileId, 1)
                                    error("Final integrity check failed")
                                }
                            }
                            else -> error("Unexpected transfer frame")
                        }
                    }
                }
                progress.value = progress.value?.copy(status = TransferStatus.COMPLETED)
            } catch (e: TimeoutCancellationException) { SecurityLog.event("transfer_timeout"); failed("Transfer timed out; retry to resume") }
              catch (e: CancellationException) { failed("Transfer interrupted; retry to resume"); throw e }
              catch (_: Exception) { failed(if (cancelled) "Transfer cancelled by user" else "Receiving failed; retry to resume") }
            finally {
                receivingKey?.let { if (cancelled) partFileManager.resetPart(it) else partFileManager.flushMeta(it) }
                receivingKey = null; activeConnection = null
            }
        } }
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
