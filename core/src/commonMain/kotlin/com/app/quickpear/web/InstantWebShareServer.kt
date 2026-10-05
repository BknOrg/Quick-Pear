package com.app.quickpear.web

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Ephemeral HTTP server for sharing files directly with web browsers (e.g. Safari on iOS,
 * Chrome on guest PCs) without requiring any client application to be installed.
 */
class InstantWebShareServer(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val onFilesReceived: ((List<Path>) -> Unit)? = null
) {
    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var currentPort: Int = 8890
        private set

    var sharedFiles: List<Path> = emptyList()
        private set

    val isRunning: Boolean
        get() = serverSocket != null && serverJob?.isActive == true

    suspend fun start(
        files: List<Path>,
        requestedPort: Int = 8890
    ): Int = withContext(Dispatchers.IO) {
        stop()
        sharedFiles = files

        var port = requestedPort
        var bound = false
        val selector = SelectorManager(Dispatchers.IO)
        selectorManager = selector

        while (!bound && port < requestedPort + 20) {
            try {
                serverSocket = aSocket(selector).tcp().bind("0.0.0.0", port)
                bound = true
                currentPort = port
            } catch (_: Exception) {
                port++
            }
        }

        if (!bound || serverSocket == null) {
            selector.close()
            selectorManager = null
            throw IllegalStateException("Failed to bind InstantWebShareServer on ports $requestedPort..${requestedPort + 20}")
        }

        serverJob = scope.launch {
            val server = serverSocket ?: return@launch
            while (isActive) {
                try {
                    val socket = server.accept()
                    launch {
                        handleClient(socket)
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }

        currentPort
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            serverJob?.cancel()
            serverJob = null
            serverSocket?.close()
            serverSocket = null
            selectorManager?.close()
            selectorManager = null
            sharedFiles = emptyList()
        } catch (_: Exception) {
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val readChannel = socket.openReadChannel()
            val writeChannel = socket.openWriteChannel(autoFlush = true)

            val requestLine = readChannel.readUTF8Line() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readChannel.readUTF8Line() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val k = line.substring(0, colonIdx).trim().lowercase()
                    val v = line.substring(colonIdx + 1).trim()
                    headers[k] = v
                }
            }

            when {
                method == "GET" && (uri == "/" || uri.startsWith("/?")) -> {
                    serveIndexHtml(writeChannel)
                }
                method == "GET" && uri.startsWith("/download/") -> {
                    val indexStr = uri.removePrefix("/download/").substringBefore("?")
                    val index = indexStr.toIntOrNull()
                    if (index != null && index in sharedFiles.indices) {
                        serveFileDownload(writeChannel, sharedFiles[index])
                    } else {
                        serveNotFound(writeChannel)
                    }
                }
                else -> {
                    serveNotFound(writeChannel)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun serveIndexHtml(writeChannel: ByteWriteChannel) {
        val fileItemsHtml = if (sharedFiles.isEmpty()) {
            "<p style='color: #64748b; font-style: italic;'>No files are currently shared.</p>"
        } else {
            sharedFiles.mapIndexed { idx, path ->
                val meta = fileSystem.metadataOrNull(path)
                val size = meta?.size ?: 0L
                val sizeStr = formatSize(size)
                """
                <div class="file-card">
                    <div class="file-info">
                        <span class="file-name">${escapeHtml(path.name)}</span>
                        <span class="file-size">$sizeStr</span>
                    </div>
                    <a class="btn-download" href="/download/$idx" download="${escapeHtml(path.name)}">Download</a>
                </div>
                """.trimIndent()
            }.joinToString("\n")
        }

        val html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Quick Pear Web Share</title>
            <style>
                :root {
                    --primary: #10b981;
                    --primary-dark: #059669;
                    --bg: #f8fafc;
                    --surface: #ffffff;
                    --text: #0f172a;
                    --text-secondary: #64748b;
                    --border: #e2e8f0;
                }
                @media (prefers-color-scheme: dark) {
                    :root {
                        --bg: #0f172a;
                        --surface: #1e293b;
                        --text: #f8fafc;
                        --text-secondary: #94a3b8;
                        --border: #334155;
                    }
                }
                * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
                body { background: var(--bg); color: var(--text); padding: 24px 16px; display: flex; justify-content: center; }
                .container { max-width: 560px; width: 100%; }
                .header { text-align: center; margin-bottom: 28px; }
                .header .logo { font-size: 40px; margin-bottom: 8px; }
                .header h1 { font-size: 24px; font-weight: 800; color: var(--primary); }
                .header p { font-size: 14px; color: var(--text-secondary); margin-top: 4px; }
                .card { background: var(--surface); border: 1px solid var(--border); border-radius: 16px; padding: 20px; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05); }
                .card-title { font-size: 16px; font-weight: 700; margin-bottom: 16px; }
                .file-card { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; background: var(--bg); border: 1px solid var(--border); border-radius: 12px; margin-bottom: 10px; }
                .file-info { display: flex; flex-direction: column; overflow: hidden; padding-right: 12px; }
                .file-name { font-size: 14px; font-weight: 600; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; }
                .file-size { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
                .btn-download { background: var(--primary); color: white; text-decoration: none; padding: 8px 14px; border-radius: 8px; font-size: 13px; font-weight: 600; white-space: nowrap; transition: background 0.2s; }
                .btn-download:hover { background: var(--primary-dark); }
                .footer { text-align: center; margin-top: 32px; font-size: 12px; color: var(--text-secondary); }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="logo">🍐</div>
                    <h1>Quick Pear Web Share</h1>
                    <p>Download files directly to Apple, Android, or PC without installing any app.</p>
                </div>
                <div class="card">
                    <div class="card-title">Available Files (${sharedFiles.size})</div>
                    $fileItemsHtml
                </div>
                <div class="footer">
                    Quick Pear P2P Local Wireless Transfer &copy; 2025
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()

        val bytes = html.encodeToByteArray()
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        writeChannel.writeStringUtf8(headers)
        writeChannel.writeFully(bytes)
    }

    private suspend fun serveFileDownload(writeChannel: ByteWriteChannel, path: Path) {
        val meta = fileSystem.metadataOrNull(path)
        val size = meta?.size ?: 0L
        val fileName = path.name

        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"$fileName\"\r\n" +
                "Content-Length: $size\r\n" +
                "Connection: close\r\n\r\n"

        writeChannel.writeStringUtf8(headers)

        // Stream file contents
        fileSystem.source(path).buffer().use { source ->
            val buf = ByteArray(65536)
            while (true) {
                val read = source.read(buf)
                if (read <= 0) break
                writeChannel.writeFully(buf, 0, read)
            }
        }
    }

    private suspend fun serveNotFound(writeChannel: ByteWriteChannel) {
        val body = "404 Not Found"
        val response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n" + body
        writeChannel.writeStringUtf8(response)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0)).toInt()
        val unit = "KMGTPE"[exp - 1] + "B"
        val size = bytes / Math.pow(1024.0, exp.toDouble())
        return "${(size * 10).toLong() / 10.0} $unit"
    }
}
