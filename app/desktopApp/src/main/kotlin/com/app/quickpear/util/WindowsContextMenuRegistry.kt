package com.app.quickpear.util

import java.io.File

object WindowsContextMenuRegistry {

    fun ensureRegistered() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return

        try {
            val exePath = resolveExecutablePath()
            if (exePath.isBlank()) return

            val appData = System.getenv("APPDATA") ?: return
            val markerDir = File(appData, "QuickPear").apply { mkdirs() }
            val markerFile = File(markerDir, "context_menu_registered.txt")
            if (markerFile.exists() && markerFile.readText().trim() == exePath) {
                return // Already registered for this exact executable path!
            }

            // Clean up any legacy or corrupt keys
            cleanLegacyKeys()

            val menuTitle = "Send with Quick Pear"
            val iconVal = "\\\"$exePath\\\",0"

            val isScript = exePath.endsWith(".cmd", ignoreCase = true) || exePath.endsWith(".bat", ignoreCase = true)
            val fileCommand = if (isScript) {
                "cmd.exe /c \\\"$exePath\\\" \\\"%1\\\""
            } else {
                "\\\"$exePath\\\" --send \\\"%1\\\""
            }

            val dirBgCommand = if (isScript) {
                "cmd.exe /c \\\"$exePath\\\" \\\"%V\\\""
            } else {
                "\\\"$exePath\\\" --send \\\"%V\\\""
            }

            // 1. All Files: HKCU\Software\Classes\*\shell\QuickPear
            runRegAdd("HKCU\\Software\\Classes\\*\\shell\\QuickPear", "/ve", "/d", menuTitle)
            runRegAdd("HKCU\\Software\\Classes\\*\\shell\\QuickPear", "/v", "Icon", "/d", iconVal)
            runRegAdd("HKCU\\Software\\Classes\\*\\shell\\QuickPear\\command", "/ve", "/d", fileCommand)

            // 2. Directories: HKCU\Software\Classes\Directory\shell\QuickPear
            runRegAdd("HKCU\\Software\\Classes\\Directory\\shell\\QuickPear", "/ve", "/d", menuTitle)
            runRegAdd("HKCU\\Software\\Classes\\Directory\\shell\\QuickPear", "/v", "Icon", "/d", iconVal)
            runRegAdd("HKCU\\Software\\Classes\\Directory\\shell\\QuickPear\\command", "/ve", "/d", fileCommand)

            // 3. Directory Background: HKCU\Software\Classes\Directory\Background\shell\QuickPear
            runRegAdd("HKCU\\Software\\Classes\\Directory\\Background\\shell\\QuickPear", "/ve", "/d", menuTitle)
            runRegAdd("HKCU\\Software\\Classes\\Directory\\Background\\shell\\QuickPear", "/v", "Icon", "/d", iconVal)
            runRegAdd("HKCU\\Software\\Classes\\Directory\\Background\\shell\\QuickPear\\command", "/ve", "/d", dirBgCommand)

            markerFile.writeText(exePath)
        } catch (_: Exception) {
        }
    }

    fun unregister() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return

        try {
            val keys = listOf(
                "HKCU\\Software\\Classes\\*\\shell\\QuickPear",
                "HKCU\\Software\\Classes\\Directory\\shell\\QuickPear",
                "HKCU\\Software\\Classes\\Directory\\Background\\shell\\QuickPear",
                "HKCU\\Software\\Classes\\*\\shell\\Kirim dengan Quick Pear",
                "HKCU\\Software\\Classes\\Directory\\shell\\Kirim dengan Quick Pear",
                "HKCU\\Software\\Classes\\Directory\\Background\\shell\\Kirim dengan Quick Pear"
            )
            for (key in keys) {
                runRegDelete(key)
            }
            val appData = System.getenv("APPDATA") ?: return
            val markerFile = File(File(appData, "QuickPear"), "context_menu_registered.txt")
            if (markerFile.exists()) markerFile.delete()
        } catch (_: Exception) {}
    }

    private fun cleanLegacyKeys() {
        val legacy = listOf(
            "HKCU\\Software\\Classes\\*\\shell\\Kirim dengan Quick Pear",
            "HKCU\\Software\\Classes\\Directory\\shell\\Kirim dengan Quick Pear",
            "HKCU\\Software\\Classes\\Directory\\Background\\shell\\Kirim dengan Quick Pear"
        )
        for (key in legacy) {
            runRegDelete(key)
        }
    }

    private fun runRegAdd(key: String, vararg extraArgs: String) {
        try {
            val cmd = mutableListOf("reg", "add", key)
            cmd.addAll(extraArgs)
            cmd.add("/f")
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (_: Exception) {}
    }

    private fun runRegDelete(key: String) {
        try {
            ProcessBuilder("reg", "delete", key, "/f")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } catch (_: Exception) {}
    }

    fun resolveExecutablePath(): String {
        // 1. ProcessHandle of running process if it is a compiled Windows exe
        val currentCmd = ProcessHandle.current().info().command().orElse("")
        if (currentCmd.endsWith(".exe", ignoreCase = true) &&
            !currentCmd.endsWith("java.exe", ignoreCase = true) &&
            !currentCmd.endsWith("javaw.exe", ignoreCase = true)
        ) {
            val f = File(currentCmd)
            if (f.exists()) return f.canonicalPath
        }

        // 2. Installed application directories
        val userHome = System.getProperty("user.home", ".")
        val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
        val candidates = listOf(
            File("C:/Program Files/Quick Pear/Quick Pear.exe"),
            File("C:/Program Files (x86)/Quick Pear/Quick Pear.exe"),
            File("$localAppData/Programs/Quick Pear/Quick Pear.exe"),
            File("$localAppData/Quick Pear/Quick Pear.exe")
        )
        for (candidate in candidates) {
            if (candidate.exists()) return candidate.canonicalPath
        }

        // 3. Built binary in project directory
        val projectDir = File(".").canonicalPath
        val devExe = File(projectDir, "app/desktopApp/build/compose/binaries/main/app/Quick Pear/Quick Pear.exe")
        if (devExe.exists()) return devExe.canonicalPath

        // 4. Fallback development relay launcher in AppData
        val appData = System.getenv("APPDATA") ?: "$userHome/AppData/Roaming"
        val qpDir = File(appData, "QuickPear").apply { mkdirs() }
        val runnerScript = File(qpDir, "quickpear-send.cmd")
        val gradlewBat = File(projectDir, "gradlew.bat")
        val scriptContent = if (gradlewBat.exists()) {
            "@echo off\r\ncd /d \"$projectDir\"\r\ncall gradlew.bat :app:desktopApp:run --args=\"--send %*\"\r\n"
        } else {
            "@echo off\r\n\"$currentCmd\" -jar \"$projectDir/app/desktopApp/build/libs/desktopApp-all.jar\" --send %*\r\n"
        }
        runnerScript.writeText(scriptContent)
        return runnerScript.canonicalPath
    }

    fun createCleanUninstallScript() {
        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return

        try {
            val appData = System.getenv("APPDATA") ?: return
            val qpDir = File(appData, "QuickPear").apply { mkdirs() }
            val scriptFile = File(qpDir, "clean-uninstall.cmd")

            val scriptText = """
                @echo off
                title Quick Pear - Clean Uninstall Utility
                echo ==============================================
                echo       Quick Pear Clean Uninstall Utility
                echo ==============================================
                echo.
                echo Stopping running Quick Pear instances...
                taskkill /F /IM "Quick Pear.exe" >nul 2>&1
                
                echo Removing Windows Explorer context menu entries...
                reg delete "HKCU\Software\Classes\*\shell\QuickPear" /f >nul 2>&1
                reg delete "HKCU\Software\Classes\Directory\shell\QuickPear" /f >nul 2>&1
                reg delete "HKCU\Software\Classes\Directory\Background\shell\QuickPear" /f >nul 2>&1
                reg delete "HKCU\Software\Classes\*\shell\Kirim dengan Quick Pear" /f >nul 2>&1
                reg delete "HKCU\Software\Classes\Directory\shell\Kirim dengan Quick Pear" /f >nul 2>&1
                reg delete "HKCU\Software\Classes\Directory\Background\shell\Kirim dengan Quick Pear" /f >nul 2>&1
                
                echo Removing Startup / Autostart entries...
                reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "QuickPear" /f >nul 2>&1
                
                echo Removing SendTo shortcut...
                del /f /q "%APPDATA%\Microsoft\Windows\SendTo\Quick Pear.lnk" >nul 2>&1
                
                echo Removing application data, identity, and trusted devices...
                rmdir /s /q "%APPDATA%\QuickPear" >nul 2>&1
                
                echo.
                echo [SUCCESS] All Quick Pear registry keys, shortcuts, and data have been completely removed.
                pause
            """.trimIndent().replace("\n", "\r\n")

            scriptFile.writeText(scriptText)

            // Also copy to root project directory for convenient developer/user access
            try {
                File("clean-uninstall.cmd").writeText(scriptText)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }
}
