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

            val menuTitle = "Kirim dengan Quick Pear"
            val psScript = """
                ${'$'}exe = '${exePath.replace("'", "''")}'
                ${'$'}title = '$menuTitle'

                # 1. All Files
                New-Item -Path 'Registry::HKCU\Software\Classes\*\shell\QuickPear\command' -Force | Out-Null
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\*\shell\QuickPear' -Name '(Default)' -Value ${'$'}title
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\*\shell\QuickPear' -Name 'Icon' -Value "`"${'$'}exe`",0"
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\*\shell\QuickPear\command' -Name '(Default)' -Value "`"${'$'}exe`" --send `"%1`""

                # 2. Directories
                New-Item -Path 'Registry::HKCU\Software\Classes\Directory\shell\QuickPear\command' -Force | Out-Null
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\Directory\shell\QuickPear' -Name '(Default)' -Value ${'$'}title
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\Directory\shell\QuickPear' -Name 'Icon' -Value "`"${'$'}exe`",0"
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\Directory\shell\QuickPear\command' -Name '(Default)' -Value "`"${'$'}exe`" --send `"%1`""

                # 3. Directory Background (Right click inside a folder)
                New-Item -Path 'Registry::HKCU\Software\Classes\Directory\Background\shell\QuickPear\command' -Force | Out-Null
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\Directory\Background\shell\QuickPear' -Name '(Default)' -Value ${'$'}title
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\Directory\Background\shell\QuickPear' -Name 'Icon' -Value "`"${'$'}exe`",0"
                Set-ItemProperty -Path 'Registry::HKCU\Software\Classes\Directory\Background\shell\QuickPear\command' -Name '(Default)' -Value "`"${'$'}exe`" --send `"%V`""
            """.trimIndent()

            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                markerFile.writeText(exePath)
            }
        } catch (_: Exception) {
        }
    }

    fun resolveExecutablePath(): String {
        // Java 9+ ProcessHandle provides the real current process executable path
        val currentCmd = ProcessHandle.current().info().command().orElse("")
        if (currentCmd.endsWith(".exe", ignoreCase = true) && !currentCmd.endsWith("java.exe", ignoreCase = true) && !currentCmd.endsWith("javaw.exe", ignoreCase = true)) {
            return currentCmd
        }

        // Check common packaging output paths
        val userHome = System.getProperty("user.home", ".")
        val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
        val candidates = listOf(
            File("C:/Program Files/Quick Pear/Quick Pear.exe"),
            File("$localAppData/Programs/Quick Pear/Quick Pear.exe"),
            File("$localAppData/Quick Pear/Quick Pear.exe"),
            File("C:/Program Files (x86)/Quick Pear/Quick Pear.exe")
        )
        for (candidate in candidates) {
            if (candidate.exists()) return candidate.absolutePath
        }

        // Development fallback: create a relay launcher script in AppData
        val appData = System.getenv("APPDATA") ?: "$userHome/AppData/Roaming"
        val qpDir = File(appData, "QuickPear").apply { mkdirs() }
        val runnerScript = File(qpDir, "quickpear-send.cmd")
        val projectDir = File(".").canonicalPath
        val gradlewBat = File(projectDir, "gradlew.bat")
        val scriptContent = if (gradlewBat.exists()) {
            "@echo off\r\ncd /d \"$projectDir\"\r\ncall gradlew.bat :app:desktopApp:run --args=\"--send %*\"\r\n"
        } else {
            "@echo off\r\n\"$currentCmd\" -jar \"$projectDir/app/desktopApp/build/libs/desktopApp-all.jar\" --send %*\r\n"
        }
        runnerScript.writeText(scriptContent)
        return runnerScript.absolutePath
    }
}
