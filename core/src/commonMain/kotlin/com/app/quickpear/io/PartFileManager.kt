package com.app.quickpear.io

import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.security.SecurityLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

@Serializable data class TransferFileKey(val sessionId: String, val fileId: Int)
@Serializable data class PartMetaInfo(val key: TransferFileKey, val senderId: String, val metadata: FileMetadata,
    val receivedChunkIds: Set<Long> = emptySet())

/** A manager owns its staging directory. Every state transition and finalization is serialized. */
class PartFileManager(private val downloadDirectory: Path, private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val freeSpace: (String) -> Long? = ::availableSpaceBytes,
    private val onFinalized: (Path) -> Unit = {}) {
    private val staging = downloadDirectory / ".quickpear-partials-v2"
    private val active = mutableMapOf<TransferFileKey, PartMetaInfo>()
    init {
        fileSystem.createDirectories(downloadDirectory)
        require(fileSystem.metadata(downloadDirectory).symlinkTarget == null) { "Download directory must not be a symlink" }
        migrateLegacy()
        fileSystem.createDirectories(staging)
        require(fileSystem.metadata(staging).symlinkTarget == null)
    }
    private fun migrateLegacy() {
        val marker = downloadDirectory / ".quickpear-v2-migrated"
        if (fileSystem.exists(marker)) return
        for (path in fileSystem.list(downloadDirectory)) {
            if (!path.name.endsWith(".part.meta") || fileSystem.metadata(path).symlinkTarget != null) continue
            try {
                val objectValue = json.parseToJsonElement(fileSystem.read(path) { readUtf8() }).jsonObject
                val name = objectValue["fileName"]?.jsonPrimitive?.content ?: continue
                if (path.name != "${sanitizeFileName(name)}.part.meta" || (objectValue["fileId"] == null || objectValue["totalSize"] == null || objectValue["expectedSha256"] == null)) continue
                val part = downloadDirectory / "${sanitizeFileName(name)}.part"
                if (fileSystem.exists(part)) fileSystem.delete(part)
                fileSystem.delete(path)
            } catch (_: Exception) { SecurityLog.event("legacy_partial_ignored") }
        }
        fileSystem.write(marker) { writeUtf8("2") }
    }
    fun sanitizeFileName(fileName: String): String {
        val value = fileName.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.code < 32 || it in "<>:\"|?*") '_' else it }.joinToString("").trim().trimEnd('.')
            .take(180)
        val safe = value.ifEmpty { "file" }
        return if (safe.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", *(1..9).map { "COM$it" }.toTypedArray(), *(1..9).map { "LPT$it" }.toTypedArray())) "_$safe" else safe
    }
    fun sanitizeRelativePath(path: String): List<String> {
        require(path.length <= 2048)
        return path.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }.map(::sanitizeFileName)
    }
    private fun id(key: TransferFileKey): String {
        require(key.sessionId.length in 1..512)
        return "${key.sessionId}:${key.fileId}".encodeToByteArray().toByteString().sha256().hex()
    }
    fun getPartPath(key: TransferFileKey) = staging / "${id(key)}.part"
    fun getMetaPath(key: TransferFileKey) = staging / "${id(key)}.json"
    fun hasSufficientStorage(requiredBytes: Long): Boolean {
        require(requiredBytes >= 0)
        return try {
            val free = freeSpace(downloadDirectory.toString())
            // If capacity cannot be reported, verify actual writability rather than assuming it.
            val probe = staging / ".probe-${java.util.UUID.randomUUID()}"
            fileSystem.write(probe) { writeByte(0) }; fileSystem.delete(probe)
            free == null || free >= requiredBytes
        } catch (_: Exception) { SecurityLog.event("storage_check_failed"); false }
    }
    @Synchronized fun prepare(key: TransferFileKey, senderId: String, metadata: FileMetadata): PartMetaInfo {
        metadata.validate()
        val path = getMetaPath(key)
        var previous = active[key]
        if (previous == null && fileSystem.exists(path)) {
            previous = try {
                require((fileSystem.metadata(path).size ?: Long.MAX_VALUE) <= 16 * 1024 * 1024)
                json.decodeFromString(PartMetaInfo.serializer(), fileSystem.read(path) { readUtf8() })
            } catch (_: Exception) { null }
        }
        val part = getPartPath(key)
        if (previous?.key != key || previous.senderId != senderId || previous.metadata != metadata || !fileSystem.exists(part) ||
            previous.receivedChunkIds.any { it !in 0 until metadata.totalChunks } ||
            (fileSystem.metadataOrNull(part)?.size ?: 0) > metadata.fileSizeBytes ||
            (previous.receivedChunkIds.maxOrNull()?.let { minOf((it + 1) * metadata.chunkSizeBytes, metadata.fileSizeBytes) } ?: 0) >
                (fileSystem.metadataOrNull(part)?.size ?: 0)) {
            resetPart(key)
            previous = PartMetaInfo(key, senderId, metadata)
            fileSystem.write(part) { }
        }
        active[key] = previous
        flushMeta(key)
        return previous
    }
    @Synchronized fun getStartChunkOffset(key: TransferFileKey): Long {
        val meta = active[key] ?: error("Prepare transfer before resume")
        if (meta.metadata.fileSizeBytes == 0L) return 0
        return (0 until meta.metadata.totalChunks).firstOrNull { it !in meta.receivedChunkIds } ?: meta.metadata.totalChunks
    }
    @Synchronized fun receivedBytes(key: TransferFileKey): Long {
        val meta = active[key] ?: return 0
        return meta.receivedChunkIds.sumOf { minOf(meta.metadata.chunkSizeBytes.toLong(), meta.metadata.fileSizeBytes - it * meta.metadata.chunkSizeBytes) }
    }
    /** Returns false for a valid duplicate. ACK is sent only after data and metadata are durable. */
    @Synchronized fun writeChunk(key: TransferFileKey, index: Long, bytes: ByteArray, checksum: Long): Boolean {
        val meta = active[key] ?: error("Unknown transfer")
        val metadata = meta.metadata
        require(index in 0 until metadata.totalChunks && metadata.fileSizeBytes > 0)
        val offset = index * metadata.chunkSizeBytes.toLong()
        require(bytes.size.toLong() == minOf(metadata.chunkSizeBytes.toLong(), metadata.fileSizeBytes - offset)) { "Invalid chunk range" }
        require(ChecksumUtil.calculateChunkChecksum(bytes) == checksum) { "Chunk checksum mismatch" }
        if (index in meta.receivedChunkIds) return false
        fileSystem.openReadWrite(getPartPath(key)).use { it.write(offset, bytes, 0, bytes.size); it.flush() }
        active[key] = meta.copy(receivedChunkIds = meta.receivedChunkIds + index)
        flushMeta(key)
        return true
    }
    @Synchronized fun flushMeta(key: TransferFileKey) {
        val meta = active[key] ?: return
        val path = getMetaPath(key); val tmp = staging / "${id(key)}.tmp"
        fileSystem.write(tmp) { writeUtf8(json.encodeToString(PartMetaInfo.serializer(), meta)) }
        fileSystem.atomicMove(tmp, path)
    }
    @Synchronized fun resetPart(key: TransferFileKey) {
        active.remove(key)
        listOf(getPartPath(key), getMetaPath(key), staging / "${id(key)}.tmp").forEach { if (fileSystem.exists(it)) fileSystem.delete(it) }
    }
    @Synchronized fun finalizeTransfer(key: TransferFileKey): Path {
        val meta = active[key] ?: error("Unknown transfer")
        val path = getPartPath(key)
        require(meta.metadata.fileSizeBytes == 0L || meta.receivedChunkIds.size.toLong() == meta.metadata.totalChunks) { "Missing chunks" }
        if (fileSystem.metadata(path).size != meta.metadata.fileSizeBytes ||
            !ChecksumUtil.calculateFileSha256(path, fileSystem).equals(meta.metadata.expectedSha256, true)) {
            resetPart(key)
            error("Final file integrity check failed")
        }
        var target = downloadDirectory
        for (segment in sanitizeRelativePath(meta.metadata.relativePath)) {
            target /= segment
            if (!fileSystem.exists(target)) fileSystem.createDirectory(target)
            require(fileSystem.metadata(target).symlinkTarget == null) { "Symlink destination refused" }
        }
        val name = sanitizeFileName(meta.metadata.fileName)
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var destination = target / name
        var suffix = 1
        while (fileSystem.exists(destination)) destination = target / "$base (${suffix++})$ext"
        fileSystem.atomicMove(path, destination)
        onFinalized(destination)
        resetPart(key)
        return destination
    }
}
