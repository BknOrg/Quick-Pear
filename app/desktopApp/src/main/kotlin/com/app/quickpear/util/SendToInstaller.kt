package com.app.quickpear.util

import java.io.File

object SendToInstaller {

    fun ensureInstalled() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return

        try {
            val appData = System.getenv("APPDATA") ?: return
            val sendToDir = File(appData, "Microsoft\\Windows\\SendTo")
            if (!sendToDir.exists()) return

            val shortcut = File(sendToDir, "Quick Pear.lnk")
            if (shortcut.exists() && shortcut.length() > 0) return

            val targetPath = WindowsContextMenuRegistry.resolveExecutablePath()
            if (targetPath.isBlank()) return

            val psCommand = """
                ${'$'}ws = New-Object -ComObject WScript.Shell
                ${'$'}s = ${'$'}ws.CreateShortcut('${shortcut.absolutePath.replace("'", "''")}')
                ${'$'}s.TargetPath = '${targetPath.replace("'", "''")}'
                ${'$'}s.Arguments = '--send'
                ${'$'}s.Save()
            """.trimIndent()

            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (_: Exception) {
        }
    }
}
