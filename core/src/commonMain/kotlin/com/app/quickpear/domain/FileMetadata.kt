package com.app.quickpear.domain

import kotlinx.serialization.Serializable

/**
 * Represents metadata for a file to be transferred in a batch.
 */
@Serializable
data class FileMetadata(
    val fileId: Int,
    val fileName: String,
    val fileSizeBytes: Long,
    val chunkSizeBytes: Int = 2_097_152, // Default 2 MB chunks
    val totalChunks: Long,
    val expectedSha256: String,
    val relativePath: String = ""
) {
    companion object {
        fun create(
            fileId: Int,
            fileName: String,
            fileSizeBytes: Long,
            chunkSizeBytes: Int = 2_097_152,
            sha256: String,
            relativePath: String = ""
        ): FileMetadata {
            val chunks = if (fileSizeBytes == 0L) 1L else (fileSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes
            return FileMetadata(
                fileId = fileId,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                chunkSizeBytes = chunkSizeBytes,
                totalChunks = chunks,
                expectedSha256 = sha256,
                relativePath = relativePath
            )
        }
    }
}

/**
 * Request payload containing metadata for a batch of files (0x03 METADATA_REQ).
 */
@Serializable
data class MetadataRequest(
    val sessionId: String,
    val files: List<FileMetadata>
)
