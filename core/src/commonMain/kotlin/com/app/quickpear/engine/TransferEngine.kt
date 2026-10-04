package com.app.quickpear.engine

import com.app.quickpear.domain.ChunkAck
import com.app.quickpear.domain.ChunkData
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.domain.MetadataRequest
import com.app.quickpear.domain.TransferProgress
import com.app.quickpear.domain.TransferStatus
import com.app.quickpear.io.ChecksumUtil
import com.app.quickpear.io.ChunkWriter
import com.app.quickpear.io.FileChunker
import com.app.quickpear.io.PartFileManager
import com.app.quickpear.network.SocketConnection
import com.app.quickpear.protocol.ProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

class TransferEngine(
    private val partFileManager: PartFileManager,
    private val fileSystem: FileSystem = FileSystem.SYSTEM
) {
    private val _progressState = MutableStateFlow<TransferProgress?>(null)
    val progressState: StateFlow<TransferProgress?> = _progressState.asStateFlow()

    private val chunkWriter = ChunkWriter(fileSystem)
    private val fileChunker = FileChunker(fileSystem)

    private fun failed(message: String, totalBytes: Long = 0) {
        _progressState.value = TransferProgress(
            fileId = 0, fileName = "", bytesTransferred = 0, totalBytes = totalBytes,
            transferSpeedBytesPerSec = 0, currentChunkIndex = 0, totalChunks = 0,
            status = TransferStatus.FAILED, errorMessage = message
        )
    }

    fun updateProgress(progress: TransferProgress?) {
        _progressState.value = progress
    }

    /** Sends [chunkData] and waits for a successful ACK, retrying up to [MAX_CHUNK_ATTEMPTS] times. */
    private suspend fun sendChunkWithRetry(connection: SocketConnection, chunkData: ChunkData) {
        var attempt = 0
        while (true) {
            attempt++
            connection.sendChunkData(chunkData)
            val ackHeader = connection.readHeader()
            if (ackHeader.messageType != ProtocolConstants.MSG_CHUNK_ACK) {
                throw IllegalStateException("Expected CHUNK_ACK but got ${ackHeader.messageType}")
            }
            val ack = connection.readChunkAck()
            if (ack.status == ChunkAck.STATUS_SUCCESS) return
            if (attempt >= MAX_CHUNK_ATTEMPTS) {
                throw IllegalStateException("Chunk ${chunkData.header.chunkId} failed after $attempt attempts")
            }
        }
    }

    /**
     * Handles sending a batch of files to a remote peer via socket connection.
     */
    suspend fun sendFiles(
        connection: SocketConnection,
        sessionId: String,
        filesMap: Map<FileMetadata, Path>
    ) = withContext(Dispatchers.IO) {
        try {
            val files = filesMap.keys.toList()
            connection.sendMetadataRequest(MetadataRequest(sessionId = sessionId, files = files))

            // METADATA_RESP
            val header = connection.readHeader()
            if (header.messageType != ProtocolConstants.MSG_METADATA_RESP) {
                failed("Unexpected response message type: ${header.messageType}")
                return@withContext
            }
            val (respCode, resumeOffset) = connection.readMetadataResponse()
            when (respCode) {
                ProtocolConstants.METADATA_RESP_ACCEPTED -> Unit
                ProtocolConstants.METADATA_RESP_INSUFFICIENT_STORAGE -> {
                    failed("Penyimpanan perangkat penerima tidak mencukupi")
                    return@withContext
                }
                else -> {
                    failed("Transfer rejected by remote peer (code: $respCode)")
                    return@withContext
                }
            }

            val startTime = System.currentTimeMillis()

            for ((index, metadata) in files.withIndex()) {
                val sourcePath = filesMap[metadata] ?: continue
                // The resume offset only applies to the first file of the batch.
                val startChunk = if (index == 0) resumeOffset else 0L
                var bytesSent = (startChunk * metadata.chunkSizeBytes).coerceAtMost(metadata.fileSizeBytes)

                _progressState.value = TransferProgress(
                    fileId = metadata.fileId,
                    fileName = metadata.fileName,
                    bytesTransferred = bytesSent,
                    totalBytes = metadata.fileSizeBytes,
                    transferSpeedBytesPerSec = 0,
                    currentChunkIndex = startChunk,
                    totalChunks = metadata.totalChunks,
                    status = TransferStatus.TRANSFERRING
                )

                val sessionStartBytes = bytesSent
                fileChunker.readChunksFlow(sourcePath, startChunkIndex = startChunk).collect { chunkData ->
                    sendChunkWithRetry(connection, chunkData)

                    bytesSent += chunkData.payloadBytes.size
                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.001)
                    val speed = ((bytesSent - sessionStartBytes) / elapsedSec).toLong()

                    _progressState.value = TransferProgress(
                        fileId = metadata.fileId,
                        fileName = metadata.fileName,
                        bytesTransferred = bytesSent,
                        totalBytes = metadata.fileSizeBytes,
                        transferSpeedBytesPerSec = speed,
                        currentChunkIndex = chunkData.header.chunkId,
                        totalChunks = metadata.totalChunks,
                        status = TransferStatus.TRANSFERRING
                    )
                }

                // TRANSFER_COMPLETE -> TRANSFER_COMPLETE_ACK
                val sha256Bytes = ChecksumUtil.calculateFileSha256Bytes(sourcePath, fileSystem)
                connection.sendTransferComplete(metadata.fileId, sha256Bytes)

                val completeHeader = connection.readHeader()
                if (completeHeader.messageType != ProtocolConstants.MSG_TRANSFER_COMPLETE_ACK) {
                    throw IllegalStateException("Expected TRANSFER_COMPLETE_ACK but got ${completeHeader.messageType}")
                }
                val (_, finalStatus) = connection.readTransferCompleteAck()
                if (finalStatus != 0x00.toByte()) {
                    failed("File ${metadata.fileName} rusak saat diterima (checksum tidak cocok)")
                    return@withContext
                }
            }

            _progressState.value = _progressState.value?.copy(status = TransferStatus.COMPLETED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed("Transfer gagal: ${e.message}")
        }
    }

    /**
     * Handles receiving files from a remote peer via socket connection.
     */
    suspend fun receiveFiles(
        connection: SocketConnection,
        onApprovalRequested: suspend (MetadataRequest) -> Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            val header = connection.readHeader()
            if (header.messageType != ProtocolConstants.MSG_METADATA_REQ) {
                return@withContext
            }

            val request = connection.readMetadataRequest(header.payloadLength)
            val totalRequiredBytes = request.files.sumOf { it.fileSizeBytes }

            if (!partFileManager.hasSufficientStorage(totalRequiredBytes)) {
                connection.sendMetadataResponse(ProtocolConstants.METADATA_RESP_INSUFFICIENT_STORAGE, 0L)
                failed("Kapasitas penyimpanan tidak mencukupi", totalRequiredBytes)
                return@withContext
            }

            if (!onApprovalRequested(request)) {
                connection.sendMetadataResponse(ProtocolConstants.METADATA_RESP_REJECTED, 0L)
                failed("Transfer rejected by user")
                return@withContext
            }

            val firstFile = request.files.firstOrNull() ?: return@withContext
            val resumeOffset = partFileManager.getStartChunkOffset(firstFile.fileName)
            connection.sendMetadataResponse(ProtocolConstants.METADATA_RESP_ACCEPTED, resumeOffset)

            val startTime = System.currentTimeMillis()

            for ((index, metadata) in request.files.withIndex()) {
                val startChunk = if (index == 0) resumeOffset else 0L
                if (startChunk == 0L) partFileManager.resetPart(metadata.fileName)

                partFileManager.getOrCreateMeta(
                    fileId = metadata.fileId,
                    fileName = metadata.fileName,
                    totalSize = metadata.fileSizeBytes,
                    sha256 = metadata.expectedSha256
                )

                val partPath = partFileManager.getPartPath(metadata.fileName)
                partFileManager.ensurePartExists(metadata.fileName)

                var bytesReceived = (startChunk * metadata.chunkSizeBytes).coerceAtMost(metadata.fileSizeBytes)
                val sessionStartBytes = bytesReceived

                _progressState.value = TransferProgress(
                    fileId = metadata.fileId,
                    fileName = metadata.fileName,
                    bytesTransferred = bytesReceived,
                    totalBytes = metadata.fileSizeBytes,
                    transferSpeedBytesPerSec = 0,
                    currentChunkIndex = startChunk,
                    totalChunks = metadata.totalChunks,
                    status = TransferStatus.TRANSFERRING
                )

                // Keep reading until the sender signals TRANSFER_COMPLETE for this file.
                // Not bounded by byte count so the final handshake is always consumed
                // (also required for zero-byte files).
                var fileDone = false
                while (!fileDone) {
                    val msg = connection.readHeader()
                    when (msg.messageType) {
                        ProtocolConstants.MSG_CHUNK_DATA -> {
                            val chunkData = connection.readChunkData(msg.payloadLength)
                            if (chunkWriter.writeChunk(partPath, chunkData)) {
                                partFileManager.markChunkReceived(metadata.fileName, chunkData.header.chunkId)
                                connection.sendChunkAck(ChunkAck(chunkData.header.chunkId, ChunkAck.STATUS_SUCCESS))
                                bytesReceived += chunkData.payloadBytes.size
                            } else {
                                connection.sendChunkAck(ChunkAck(chunkData.header.chunkId, ChunkAck.STATUS_CHECKSUM_MISMATCH))
                            }

                            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.001)
                            _progressState.value = TransferProgress(
                                fileId = metadata.fileId,
                                fileName = metadata.fileName,
                                bytesTransferred = bytesReceived,
                                totalBytes = metadata.fileSizeBytes,
                                transferSpeedBytesPerSec = ((bytesReceived - sessionStartBytes) / elapsedSec).toLong(),
                                currentChunkIndex = chunkData.header.chunkId,
                                totalChunks = metadata.totalChunks,
                                status = TransferStatus.TRANSFERRING
                            )
                        }
                        ProtocolConstants.MSG_TRANSFER_COMPLETE -> {
                            val (fileId, _) = connection.readTransferComplete()
                            val computedSha = ChecksumUtil.calculateFileSha256(partPath, fileSystem)
                            if (computedSha.equals(metadata.expectedSha256, ignoreCase = true)) {
                                partFileManager.finalizeTransfer(metadata.fileName, metadata.relativePath)
                                connection.sendTransferCompleteAck(fileId, 0x00.toByte())
                                fileDone = true
                            } else {
                                // Corrupted: discard so the next attempt restarts from chunk 0.
                                partFileManager.resetPart(metadata.fileName)
                                connection.sendTransferCompleteAck(fileId, 0x01.toByte())
                                failed("File ${metadata.fileName} rusak (checksum tidak cocok)")
                                return@withContext
                            }
                        }
                        else -> throw IllegalStateException("Unexpected message type ${msg.messageType}")
                    }
                }
            }

            _progressState.value = _progressState.value?.copy(status = TransferStatus.COMPLETED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed("Penerimaan gagal: ${e.message}")
        }
    }

    private companion object {
        const val MAX_CHUNK_ATTEMPTS = 3
    }
}
