plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    jvm()
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                // Network.framework receive without bridging the content context (nwshim.def).
                val nwshim by creating {
                    defFile(project.file("src/nativeInterop/cinterop/nwshim.def"))
                }
            }
        }
        target.binaries.framework {
            baseName = "MaxCore"
            isStatic = true
        }
    }

    sourceSets {
        // JVM + Android share the java.net / javax.net.ssl connection factory. KMP does not allow
        // one source set to depend on both a JVM and an Android target, so the shared directory is
        // registered as an extra source root on each of those source sets (see jvmAndroidShared).
        val jvmAndroidSharedDir = file("src/jvmAndroidShared/kotlin")

        commonMain.dependencies {
            // No HTTP / networking library in common code. Transport: java.net.Socket + javax.net.ssl on
            // JVM/Android, Apple Network.framework on iOS. Media CDN client (expect defaultMediaHttp):
            // OkHttp on JVM/Android, NSURLSession (platform.Foundation, no dependency) on iOS.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            // MessagePack: pure-Kotlin codec in protocol/MessagePack.kt, no dependency
        }
        commonTest {
            // Fake connection + packet builders, also used by :shared tests
            kotlin.srcDir("src/testFixtures/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        androidMain {
            kotlin.srcDir(jvmAndroidSharedDir)
            dependencies {
                // media/OkHttpMediaHttp.kt (jvmAndroidShared)
                implementation(libs.okhttp)
            }
        }
        jvmMain {
            kotlin.srcDir(jvmAndroidSharedDir)
            dependencies {
                // media/OkHttpMediaHttp.kt (jvmAndroidShared)
                implementation(libs.okhttp)
            }
        }
    }
}

android {
    namespace = "com.maxly.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
