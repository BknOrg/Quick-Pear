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
            val targetPath = WindowsContextMenuRegistry.resolveExecutablePath()
            if (targetPath.isBlank()) return

            // If shortcut already exists and is non-empty, keep it
            if (shortcut.exists() && shortcut.length() > 0) return

            val isScript = targetPath.endsWith(".cmd", ignoreCase = true) || targetPath.endsWith(".bat", ignoreCase = true)
            val exeTarget = if (isScript) "cmd.exe" else targetPath
            val args = if (isScript) "/c \"$targetPath\"" else "--send"

            val psCommand = """
                ${'$'}ws = New-Object -ComObject WScript.Shell
                ${'$'}s = ${'$'}ws.CreateShortcut('${shortcut.absolutePath.replace("'", "''")}')
                ${'$'}s.TargetPath = '${exeTarget.replace("'", "''")}'
                ${'$'}s.Arguments = '$args'
                ${'$'}s.Save()
            """.trimIndent()

            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (_: Exception) {
        }
    }

    fun uninstall() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return
        try {
            val appData = System.getenv("APPDATA") ?: return
            val shortcut = File(appData, "Microsoft\\Windows\\SendTo\\Quick Pear.lnk")
            if (shortcut.exists()) shortcut.delete()
        } catch (_: Exception) {}
    }
}
