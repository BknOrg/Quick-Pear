package com.app.quickpear.domain

/**
 * State machine status for file transfer sessions.
 */
enum class TransferStatus {
    IDLE,
    ADVERTISING,
    CONNECTING,
    AUTHENTICATING,
    TRANSFERRING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Represents real-time transfer progress for UI binding.
 */
data class TransferProgress(
    val fileId: Int,
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val transferSpeedBytesPerSec: Long,
    val currentChunkIndex: Long,
    val totalChunks: Long,
    val status: TransferStatus = TransferStatus.TRANSFERRING,
    val errorMessage: String? = null
) {
    val progressPercentage: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}
