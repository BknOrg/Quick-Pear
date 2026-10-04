package com.app.quickpear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath
import java.awt.FileDialog
import java.awt.Frame

@Composable
actual fun rememberFilePickerLauncher(
    onFilesSelected: (peer: PeerDevice, paths: List<Path>) -> Unit
): (peer: PeerDevice) -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        { peer ->
            scope.launch(Dispatchers.IO) {
                try {
                    val dialog = FileDialog(null as Frame?, "Pilih Berkas untuk Dikirim ke ${peer.name}", FileDialog.LOAD)
                    dialog.isMultipleMode = true
                    dialog.isVisible = true
                    val files = dialog.files
                    if (!files.isNullOrEmpty()) {
                        val paths = files.map { it.canonicalPath.toPath() }
                        onFilesSelected(peer, paths)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
