import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    val xcframework = XCFramework("MaxlyCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                // Optional C ABI (maxc.def). Headers are not set; this does not export the core.
                val maxc by creating {
                    defFile(project.file("src/nativeInterop/cinterop/maxc.def"))
                }
            }
        }
        target.binaries.framework {
            baseName = "MaxlyCore"
            isStatic = true
            binaryOption("bundleId", "com.maxly.ios.MaxlyCore")
            xcframework.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":shared"))
        }
        iosTest {
            // FakeRawConnection / ScriptedConnectionFactory / packet builders from :core
            kotlin.srcDir("../core/src/testFixtures/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
