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
    fun validate() {
        require(fileName.length in 1..512 && relativePath.length <= 2048)
        require(fileSizeBytes >= 0 && chunkSizeBytes in 1..4_194_304)
        val count = if (fileSizeBytes == 0L) 1L else (fileSizeBytes - 1) / chunkSizeBytes + 1
        require(totalChunks == count && totalChunks <= 1_000_000) { "Invalid chunk count" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
    }
    companion object {
        fun create(
            fileId: Int,
            fileName: String,
            fileSizeBytes: Long,
            chunkSizeBytes: Int = 2_097_152,
            sha256: String,
            relativePath: String = ""
        ): FileMetadata {
            require(fileSizeBytes >= 0 && chunkSizeBytes in 1..4_194_304)
            val chunks = if (fileSizeBytes == 0L) 1L else (fileSizeBytes - 1) / chunkSizeBytes + 1
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
) {
    fun validate(): Long {
        require(sessionId.length in 1..128 && files.size in 1..1000)
        require(files.map { it.fileId }.toSet().size == files.size) { "Duplicate file id" }
        var total = 0L
        files.forEach { it.validate(); require(it.fileSizeBytes <= Long.MAX_VALUE - total); total += it.fileSizeBytes }
        return total
    }
}
