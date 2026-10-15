package com.app.quickpear.util

import java.io.File

object DesktopAutostartManager {

    private const val APP_NAME = "Quick Pear"
    private const val REG_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""

    fun isAutostartEnabled(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> isWindowsAutostartEnabled()
            os.contains("linux") -> isLinuxAutostartEnabled()
            else -> false
        }
    }

    fun setAutostartEnabled(enabled: Boolean): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> setWindowsAutostartEnabled(enabled)
            os.contains("linux") -> setLinuxAutostartEnabled(enabled)
            else -> false
        }
    }

    private fun isWindowsAutostartEnabled(): Boolean {
        return try {
            val process = ProcessBuilder("reg", "query", REG_KEY, "/v", APP_NAME)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() == 0 && output.contains(APP_NAME)
        } catch (_: Exception) {
            false
        }
    }

    private fun setWindowsAutostartEnabled(enabled: Boolean): Boolean {
        return try {
            if (enabled) {
                val exePath = getExecutablePath()
                // In Win32 reg.exe, escaping a double quote requires double quotes ("") inside /d
                val command = "\"\"$exePath\"\" --background"
                val process = ProcessBuilder("reg", "add", REG_KEY, "/v", APP_NAME, "/t", "REG_SZ", "/d", command, "/f")
                    .redirectErrorStream(true)
                    .start()
                val success = process.waitFor() == 0
                if (!success) {
                    // Fallback using PowerShell .NET Registry API
                    val psScript = "\$val = [char]34 + '$exePath' + [char]34 + ' --background'; [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\\Microsoft\\Windows\\CurrentVersion\\Run', \$true).SetValue('$APP_NAME', \$val)"
                    val psProcess = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
                        .redirectErrorStream(true)
                        .start()
                    psProcess.waitFor() == 0
                } else {
                    true
                }
            } else {
                val process = ProcessBuilder("reg", "delete", REG_KEY, "/v", APP_NAME, "/f")
                    .redirectErrorStream(true)
                    .start()
                val success = process.waitFor() == 0
                if (!success) {
                    val psScript = "[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\\Microsoft\\Windows\\CurrentVersion\\Run', \$true).DeleteValue('$APP_NAME', \$false)"
                    val psProcess = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
                        .redirectErrorStream(true)
                        .start()
                    psProcess.waitFor() == 0
                } else {
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isLinuxAutostartEnabled(): Boolean {
        return try {
            val autostartFile = getLinuxAutostartFile()
            autostartFile.exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun setLinuxAutostartEnabled(enabled: Boolean): Boolean {
        return try {
            val autostartFile = getLinuxAutostartFile()
            if (enabled) {
                autostartFile.parentFile?.mkdirs()
                val exePath = getExecutablePath()
                val content = """
                    [Desktop Entry]
                    Type=Application
                    Exec="$exePath" --background
                    Hidden=false
                    NoDisplay=false
                    X-GNOME-Autostart-enabled=true
                    Name=Quick Pear
                    Comment=Quick Pear Local Wireless File Transfer
                """.trimIndent()
                autostartFile.writeText(content)
                true
            } else {
                if (autostartFile.exists()) autostartFile.delete() else true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getLinuxAutostartFile(): File {
        val configHome = System.getenv("XDG_CONFIG_HOME")
            ?: (System.getProperty("user.home") + "/.config")
        return File(configHome, "autostart/quickpear.desktop")
    }

    private fun getExecutablePath(): String {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) {
            // 1. ProcessHandle of running process if it is a compiled Windows exe
            val currentCmd = ProcessHandle.current().info().command().orElse("")
            if (currentCmd.endsWith(".exe", ignoreCase = true) &&
                !currentCmd.endsWith("java.exe", ignoreCase = true) &&
                !currentCmd.endsWith("javaw.exe", ignoreCase = true)
            ) {
                val f = File(currentCmd)
                if (f.exists()) return f.canonicalPath
            }

            // 2. Standard Windows installation directories
            val userHome = System.getProperty("user.home", ".")
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
            val candidates = listOf(
                File("$localAppData/Quick Pear/Quick Pear.exe"),
                File("$localAppData/Programs/Quick Pear/Quick Pear.exe"),
                File("C:/Program Files/Quick Pear/Quick Pear.exe"),
                File("C:/Program Files (x86)/Quick Pear/Quick Pear.exe")
            )
            for (candidate in candidates) {
                if (candidate.exists()) {
                    return candidate.canonicalPath
                }
            }
        }

        // Fallback to current JAR or Java launcher
        return try {
            val location = DesktopAutostartManager::class.java.protectionDomain.codeSource.location.toURI().path
            File(location).absolutePath
        } catch (_: Exception) {
            "Quick Pear.exe"
        }
    }
}
