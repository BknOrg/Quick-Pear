package com.app.quickpear.session

import com.app.quickpear.domain.FileMetadata
import okio.Buffer

/** Stable across retries and application restarts; completed staging data is removed. */
object TransferSessionIds {
    fun forFiles(recipient: String, files: Collection<FileMetadata>): String {
        val buffer = Buffer()
        fun field(value: String) { val bytes = value.encodeToByteArray(); buffer.writeInt(bytes.size); buffer.write(bytes) }
        field(recipient)
        files.sortedBy { it.fileId }.forEach {
            buffer.writeInt(it.fileId); field(it.fileName); field(it.relativePath)
            buffer.writeLong(it.fileSizeBytes); field(it.expectedSha256.lowercase())
        }
        return buffer.readByteString().sha256().hex()
    }
}
