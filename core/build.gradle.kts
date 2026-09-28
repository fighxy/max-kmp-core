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
            // ktor-client-* engines are listed in libs.versions.toml but unused: the Max protocol
            // needs a raw TLS byte stream, which HTTP engines do not expose. Used instead:
            // java.net.Socket + javax.net.ssl on JVM/Android; iOS TLS is still TODO (ktor-network-tls
            // on Native is a stub in 3.1.1 — see DefaultConnectionFactory.ios.kt).
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            // MessagePack: pure-Kotlin codec in protocol/MessagePack.kt, no dependency
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain {
            kotlin.srcDir(jvmAndroidSharedDir)
            dependencies {
                // Kept for the next HTTP layer (media uploads / ws2); the transport does not use it.
                implementation(libs.ktor.client.okhttp)
            }
        }
        iosMain {
            dependencies {
                // Kept for the next HTTP layer; the transport does not use it (no raw TLS here).
                implementation(libs.ktor.client.darwin)
            }
        }
        jvmMain {
            kotlin.srcDir(jvmAndroidSharedDir)
            dependencies {
                // Kept for the next HTTP layer; the transport uses java.net.Socket, not CIO.
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

android {
    namespace = "com.max.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
