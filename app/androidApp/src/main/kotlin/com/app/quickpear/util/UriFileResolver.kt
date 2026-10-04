package com.app.quickpear.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.app.quickpear.domain.FileMetadata
import com.app.quickpear.io.ChecksumUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileOutputStream

object UriFileResolver {

    suspend fun resolveUrisToFiles(
        context: Context,
        uris: List<Uri>
    ): Map<FileMetadata, Path> = withContext(Dispatchers.IO) {
        val resultMap = mutableMapOf<FileMetadata, Path>()
        val cacheDir = File(context.cacheDir, "shared_outgoing").apply { mkdirs() }

        var fileIdCounter = 1

        for (uri in uris) {
            val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val targetFile = File(cacheDir, "${System.currentTimeMillis()}_$fileName")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: continue

            val okioPath = targetFile.canonicalPath.toPath()
            val fileSize = targetFile.length()
            val sha256 = ChecksumUtil.calculateFileSha256(okioPath)

            val metadata = FileMetadata.create(
                fileId = fileIdCounter++,
                fileName = fileName,
                fileSizeBytes = fileSize,
                sha256 = sha256
            )
            resultMap[metadata] = okioPath
        }

        resultMap
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
