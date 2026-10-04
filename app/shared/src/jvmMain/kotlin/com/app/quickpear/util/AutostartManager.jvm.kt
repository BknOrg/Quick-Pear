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
                val command = "\"$exePath\" --background"
                val process = ProcessBuilder("reg", "add", REG_KEY, "/v", APP_NAME, "/t", "REG_SZ", "/d", command, "/f")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } else {
                val process = ProcessBuilder("reg", "delete", REG_KEY, "/v", APP_NAME, "/f")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
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
        // Fallback to current JAR or Java launcher
        val location = DesktopAutostartManager::class.java.protectionDomain.codeSource.location.toURI().path
        return File(location).absolutePath
    }
}
