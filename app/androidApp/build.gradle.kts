import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

val releaseStorePath = providers.environmentVariable("QUICKPEAR_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("QUICKPEAR_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("QUICKPEAR_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("QUICKPEAR_KEY_PASSWORD").orNull
val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
    val credentials = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    doLast {
        check(credentials.all { !it.isNullOrBlank() }) {
            "Release signing requires QUICKPEAR_KEYSTORE_PATH, QUICKPEAR_KEYSTORE_PASSWORD, QUICKPEAR_KEY_ALIAS and QUICKPEAR_KEY_PASSWORD"
        }
        check(File(credentials[0]!!).isFile) { "Release keystore file is missing" }
    }
}
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    dependsOn(validateReleaseCredentials)
}

android {
    namespace = "com.app.quickpear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.app.quickpear"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("quickpear.versionCode").get().toInt()
        versionName = providers.gradleProperty("quickpear.versionName").get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            storeFile = releaseStorePath?.let { file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}