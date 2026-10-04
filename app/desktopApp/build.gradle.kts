import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.app.quickpear.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "Quick Pear"
            packageVersion = "1.0.0"
            description = "Quick Pear Local Wireless File Transfer"
            copyright = "© 2025 Quick Pear Team. All rights reserved."
            vendor = "Quick Pear"

            windows {
                menuGroup = "Quick Pear"
                shortcut = true
            }
            linux {
                shortcut = true
            }
            macOS {
                bundleID = "com.app.quickpear"
                dockName = "Quick Pear"
            }
        }
    }
}
