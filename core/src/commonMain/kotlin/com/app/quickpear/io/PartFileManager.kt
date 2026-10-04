package com.app.quickpear.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

@Serializable
data class PartMetaInfo(
    val fileId: Int,
    val fileName: String,
    val totalSize: Long,
    val expectedSha256: String,
    val receivedChunkIds: Set<Long> = emptySet()
)

class PartFileManager(
    private val downloadDirectory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
) {

    /**
     * Strips directory components and characters that are illegal on common filesystems so a
     * remote peer can never write outside [downloadDirectory] (path traversal).
     */
    fun sanitizeFileName(fileName: String): String {
        val lastSegment = fileName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = lastSegment
            .map { c -> if (c.code < 32 || c in "<>:\"|?*") '_' else c }
            .joinToString("")
            .trim()
            .trimEnd('.')
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "file" else cleaned
    }

    fun getPartPath(fileName: String): Path {
        return downloadDirectory / "${sanitizeFileName(fileName)}.part"
    }

    fun getMetaPath(fileName: String): Path {
        return downloadDirectory / "${sanitizeFileName(fileName)}.part.meta"
    }

    /**
     * Checks whether the download directory has sufficient free storage space.
     * If the platform cannot report free space, only writability of the directory is verified.
     */
    fun hasSufficientStorage(requiredBytes: Long): Boolean {
        return try {
            if (!fileSystem.exists(downloadDirectory)) {
                fileSystem.createDirectories(downloadDirectory)
            }
            if (fileSystem.metadataOrNull(downloadDirectory) == null) return false
            val free = availableSpaceBytes(downloadDirectory.toString())
            free == null || free >= requiredBytes
        } catch (_: Exception) {
            true // Default to true if storage check unsupported on target platform
        }
    }

    /** Creates an empty .part file if missing (needed for zero-byte files). */
    fun ensurePartExists(fileName: String) {
        val partPath = getPartPath(fileName)
        partPath.parent?.let { if (!fileSystem.exists(it)) fileSystem.createDirectories(it) }
        if (!fileSystem.exists(partPath)) {
            fileSystem.write(partPath) { }
        }
    }

    /** Deletes any stale .part and .part.meta so the transfer restarts cleanly from chunk 0. */
    fun resetPart(fileName: String) {
        val partPath = getPartPath(fileName)
        val metaPath = getMetaPath(fileName)
        if (fileSystem.exists(partPath)) fileSystem.delete(partPath)
        if (fileSystem.exists(metaPath)) fileSystem.delete(metaPath)
    }

    /**
     * Reads or creates metadata sidecar info for a file.
     */
    fun getOrCreateMeta(
        fileId: Int,
        fileName: String,
        totalSize: Long,
        sha256: String
    ): PartMetaInfo {
        val metaPath = getMetaPath(fileName)
        if (fileSystem.exists(metaPath)) {
            try {
                val content = fileSystem.read(metaPath) { readUtf8() }
                return json.decodeFromString(PartMetaInfo.serializer(), content)
            } catch (_: Exception) {
                // Return fresh if meta corrupt
            }
        }
        val meta = PartMetaInfo(fileId, sanitizeFileName(fileName), totalSize, sha256)
        saveMeta(meta)
        return meta
    }

    fun saveMeta(metaInfo: PartMetaInfo) {
        val metaPath = getMetaPath(metaInfo.fileName)
        metaPath.parent?.let { if (!fileSystem.exists(it)) fileSystem.createDirectories(it) }
        val content = json.encodeToString(PartMetaInfo.serializer(), metaInfo)
        fileSystem.write(metaPath) {
            writeUtf8(content)
        }
    }

    fun markChunkReceived(fileName: String, chunkId: Long) {
        val metaPath = getMetaPath(fileName)
        if (!fileSystem.exists(metaPath)) return
        val content = fileSystem.read(metaPath) { readUtf8() }
        val current = json.decodeFromString(PartMetaInfo.serializer(), content)
        val updated = current.copy(receivedChunkIds = current.receivedChunkIds + chunkId)
        saveMeta(updated)
    }

    /**
     * Determines starting chunk index for auto-resume. Returns 0 if none.
     */
    fun getStartChunkOffset(fileName: String): Long {
        val metaPath = getMetaPath(fileName)
        if (!fileSystem.exists(metaPath)) return 0L
        return try {
            val content = fileSystem.read(metaPath) { readUtf8() }
            val meta = json.decodeFromString(PartMetaInfo.serializer(), content)
            if (meta.receivedChunkIds.isEmpty()) 0L else (meta.receivedChunkIds.maxOrNull() ?: -1L) + 1L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Sanitizes relative path segments preventing directory traversal.
     */
    fun sanitizeRelativePath(relativePath: String): List<String> {
        return relativePath.split('/', '\\')
            .map { segment ->
                segment.map { c -> if (c.code < 32 || c in "<>:\"|?*") '_' else c }
                    .joinToString("").trim().trimEnd('.')
            }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
    }

    /**
     * Finalizes file transfer: renames .part to the original filename (made unique if it
     * already exists) inside [relativePath] and removes .part.meta.
     */
    fun finalizeTransfer(fileName: String, relativePath: String = ""): Path {
        val partPath = getPartPath(fileName)
        val safeName = sanitizeFileName(fileName)
        val segments = sanitizeRelativePath(relativePath)
        var targetDir = downloadDirectory
        for (seg in segments) {
            targetDir = targetDir / seg
        }
        if (!fileSystem.exists(targetDir)) {
            fileSystem.createDirectories(targetDir)
        }
        val finalPath = uniqueFinalPathIn(targetDir, safeName)
        val metaPath = getMetaPath(fileName)

        if (fileSystem.exists(partPath)) {
            fileSystem.atomicMove(partPath, finalPath)
        }
        if (fileSystem.exists(metaPath)) {
            fileSystem.delete(metaPath)
        }
        return finalPath
    }

    private fun uniqueFinalPathIn(directory: Path, safeName: String): Path {
        var candidate = directory / safeName
        if (!fileSystem.exists(candidate)) return candidate
        val base = safeName.substringBeforeLast('.', safeName)
        val ext = if (safeName.contains('.')) "." + safeName.substringAfterLast('.') else ""
        var n = 1
        while (true) {
            candidate = directory / "$base ($n)$ext"
            if (!fileSystem.exists(candidate)) return candidate
            n++
        }
    }
}
