import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    
    android {
       namespace = "com.app.quickpear.core"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        commonMain.dependencies {
            api(libs.okio)
            implementation(libs.ktor.network)
            implementation("io.ktor:ktor-network-tls:${libs.versions.ktor.get()}")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
// Public broker checks are opt-in and never gate deterministic CI.
tasks.named<Test>("jvmTest") {
    filter.excludeTestsMatching("*Live*")
    filter.excludeTestsMatching("*testLive*")
}
tasks.register<Test>("liveIntegrationTest") {
    val unit = tasks.named<Test>("jvmTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    filter.includeTestsMatching("*Live*")
    filter.includeTestsMatching("*testLive*")
    shouldRunAfter(unit)
}
