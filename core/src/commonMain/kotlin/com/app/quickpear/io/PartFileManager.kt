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
    fun resetPart(fileName: String) = synchronized(metaLock) {
        val safeName = sanitizeFileName(fileName)
        activeBitmaps.remove(safeName)
        activeMeta.remove(safeName)
        dirtyCounters.remove(safeName)
        val partPath = getPartPath(fileName)
        val metaPath = getMetaPath(fileName)
        if (fileSystem.exists(partPath)) fileSystem.delete(partPath)
        if (fileSystem.exists(metaPath)) fileSystem.delete(metaPath)
    }

    // In-memory cache for high-performance chunk tracking without per-chunk disk I/O thrashing
    private val activeBitmaps = mutableMapOf<String, ChunkBitmap>()
    private val activeMeta = mutableMapOf<String, PartMetaInfo>()
    private val dirtyCounters = mutableMapOf<String, Int>()
    private val metaLock = Any()

    /**
     * Reads or creates metadata sidecar info for a file.
     */
    fun getOrCreateMeta(
        fileId: Int,
        fileName: String,
        totalSize: Long,
        sha256: String
    ): PartMetaInfo = synchronized(metaLock) {
        val safeName = sanitizeFileName(fileName)
        activeMeta[safeName]?.let { return it }

        val metaPath = getMetaPath(safeName)
        var meta: PartMetaInfo? = null
        if (fileSystem.exists(metaPath)) {
            try {
                val content = fileSystem.read(metaPath) { readUtf8() }
                meta = json.decodeFromString(PartMetaInfo.serializer(), content)
            } catch (_: Exception) {
                // Return fresh if meta corrupt
            }
        }
        if (meta == null) {
            meta = PartMetaInfo(fileId, safeName, totalSize, sha256)
            saveMetaInternal(meta)
        }

        activeMeta[safeName] = meta
        val bitmap = ChunkBitmap()
        for (chunkId in meta.receivedChunkIds) {
            bitmap.set(chunkId)
        }
        activeBitmaps[safeName] = bitmap
        dirtyCounters[safeName] = 0
        return meta
    }

    fun saveMeta(metaInfo: PartMetaInfo) = synchronized(metaLock) {
        saveMetaInternal(metaInfo)
    }

    private fun saveMetaInternal(metaInfo: PartMetaInfo) {
        val safeName = sanitizeFileName(metaInfo.fileName)
        val metaPath = getMetaPath(safeName)
        metaPath.parent?.let { if (!fileSystem.exists(it)) fileSystem.createDirectories(it) }
        val content = json.encodeToString(PartMetaInfo.serializer(), metaInfo)
        fileSystem.write(metaPath) {
            writeUtf8(content)
        }
    }

    /**
     * High-speed O(1) chunk tracking. Sets the bit in the in-memory [ChunkBitmap]
     * and periodically flushes to disk (every 100 chunks) to eliminate disk I/O thrashing.
     */
    fun markChunkReceived(fileName: String, chunkId: Long) = synchronized(metaLock) {
        val safeName = sanitizeFileName(fileName)
        var bitmap = activeBitmaps[safeName]
        var meta = activeMeta[safeName]

        if (bitmap == null || meta == null) {
            meta = getOrCreateMeta(0, safeName, 0L, "")
            bitmap = activeBitmaps[safeName] ?: ChunkBitmap().also { activeBitmaps[safeName] = it }
        }

        bitmap.set(chunkId)
        val dirty = (dirtyCounters[safeName] ?: 0) + 1
        dirtyCounters[safeName] = dirty

        // Batch disk write every 100 chunks to save battery and eliminate I/O lag
        if (dirty >= 100) {
            flushMetaInternal(safeName)
        }
    }

    /**
     * Flushes in-memory metadata and received chunks to disk.
     */
    fun flushMeta(fileName: String) = synchronized(metaLock) {
        flushMetaInternal(sanitizeFileName(fileName))
    }

    private fun flushMetaInternal(safeName: String) {
        val meta = activeMeta[safeName] ?: return
        val bitmap = activeBitmaps[safeName] ?: return

        // Populate received chunk IDs from bitmap
        val count = bitmap.cardinality()
        val chunkSet = HashSet<Long>(count.coerceAtMost(50_000L).toInt())
        // Scan up to largest possible chunk
        var idx = 0L
        while (chunkSet.size < count) {
            if (bitmap.isSet(idx)) {
                chunkSet.add(idx)
            }
            idx++
        }

        val updated = meta.copy(receivedChunkIds = chunkSet)
        activeMeta[safeName] = updated
        saveMetaInternal(updated)
        dirtyCounters[safeName] = 0
    }

    /**
     * Returns the in-memory [ChunkBitmap] for [fileName] if available.
     */
    fun getChunkBitmap(fileName: String): ChunkBitmap? = synchronized(metaLock) {
        return activeBitmaps[sanitizeFileName(fileName)]
    }

    /**
     * Determines starting chunk index for auto-resume. Returns 0 if none.
     * Uses [ChunkBitmap.firstMissingChunk] for accurate contiguous resume.
     */
    fun getStartChunkOffset(fileName: String): Long = synchronized(metaLock) {
        val safeName = sanitizeFileName(fileName)
        val bitmap = activeBitmaps[safeName]
        if (bitmap != null) {
            return bitmap.firstMissingChunk(Long.MAX_VALUE)
        }

        val metaPath = getMetaPath(safeName)
        if (!fileSystem.exists(metaPath)) return 0L
        return try {
            val content = fileSystem.read(metaPath) { readUtf8() }
            val meta = json.decodeFromString(PartMetaInfo.serializer(), content)
            if (meta.receivedChunkIds.isEmpty()) {
                0L
            } else {
                val freshBitmap = ChunkBitmap()
                for (id in meta.receivedChunkIds) {
                    freshBitmap.set(id)
                }
                activeBitmaps[safeName] = freshBitmap
                activeMeta[safeName] = meta
                freshBitmap.firstMissingChunk(Long.MAX_VALUE)
            }
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
        synchronized(metaLock) {
            activeBitmaps.remove(safeName)
            activeMeta.remove(safeName)
            dirtyCounters.remove(safeName)
        }
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
