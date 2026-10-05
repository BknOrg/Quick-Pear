package com.app.quickpear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.app.quickpear.domain.PeerDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import java.awt.FileDialog
import java.awt.Frame
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Composable
actual fun rememberFilePickerLauncher(
    onFilesSelected: (peer: PeerDevice, paths: List<Path>) -> Unit
): (peer: PeerDevice) -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        { peer ->
            scope.launch(Dispatchers.IO) {
                val paths = openNativeFilePicker("Select Files to Send to ${peer.name}")
                if (paths.isNotEmpty()) {
                    onFilesSelected(peer, paths)
                }
            }
        }
    }
}

private suspend fun openNativeFilePicker(title: String): List<Path> = withContext(Dispatchers.IO) {
    val os = System.getProperty("os.name", "").lowercase()
    if (os.contains("win")) {
        try {
            val pickerExe = resolveWindowsPickerExecutable()
            if (pickerExe != null && pickerExe.exists()) {
                val process = ProcessBuilder(pickerExe.absolutePath, title)
                    .redirectErrorStream(true)
                    .start()
                val selectedPaths = mutableListOf<Path>()
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val pathStr = line.trim()
                        if (pathStr.isNotEmpty()) {
                            val f = File(pathStr)
                            if (f.exists()) {
                                selectedPaths.add(f.canonicalPath.toPath())
                            }
                        }
                        line = reader.readLine()
                    }
                }
                process.waitFor()
                if (selectedPaths.isNotEmpty() || process.exitValue() == 0) {
                    return@withContext selectedPaths
                }
            }
        } catch (_: Exception) {
            // Fallback to AWT FileDialog below
        }
    }

    // Native fallback for macOS (Finder sheet), Linux, or fallback on Windows
    try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val files = dialog.files
        if (!files.isNullOrEmpty()) {
            return@withContext files.map { it.canonicalPath.toPath() }
        }
    } catch (_: Exception) {
    }

    emptyList()
}

private fun resolveWindowsPickerExecutable(): File? {
    try {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val targetDir = File(appData, "QuickPear/bin").apply { mkdirs() }
        val targetFile = File(targetDir, "QuickPearPicker.exe")

        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream("win/QuickPearPicker.exe")
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream("QuickPearPicker.exe")
            ?: File("app/desktopApp/src/main/resources/win/QuickPearPicker.exe").takeIf { it.exists() }?.inputStream()
            ?: File("app/shared/src/jvmMain/resources/win/QuickPearPicker.exe").takeIf { it.exists() }?.inputStream()

        if (stream != null) {
            val bytes = stream.use { it.readBytes() }
            if (!targetFile.exists() || targetFile.length() != bytes.size.toLong()) {
                targetFile.writeBytes(bytes)
            }
            return targetFile
        } else if (targetFile.exists()) {
            return targetFile
        }
    } catch (_: Exception) {
    }
    return null
}
