package com.app.quickpear.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileOutputStream

@Composable
actual fun rememberFilePickerLauncher(
    onFilesSelected: (peer: PeerDevice, paths: List<Path>) -> Unit
): (peer: PeerDevice) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingPeer by remember { mutableStateOf<PeerDevice?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val peer = pendingPeer
        if (peer != null && uris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val paths = resolveUrisToPaths(context, uris)
                if (paths.isNotEmpty()) {
                    onFilesSelected(peer, paths)
                }
            }
        }
        pendingPeer = null
    }

    return remember {
        { peer ->
            pendingPeer = peer
            launcher.launch(arrayOf("*/*"))
        }
    }
}

private suspend fun resolveUrisToPaths(
    context: Context,
    uris: List<Uri>
): List<Path> = withContext(Dispatchers.IO) {
    val resultPaths = mutableListOf<Path>()
    val cacheDir = File(context.cacheDir, "shared_outgoing").apply { mkdirs() }

    for (uri in uris) {
        val fileName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        val targetFile = File(cacheDir, "${System.currentTimeMillis()}_$fileName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: continue

        resultPaths.add(targetFile.canonicalPath.toPath())
    }
    resultPaths
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
