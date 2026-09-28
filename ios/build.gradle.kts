plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                // TODO: point at a C header / static lib (C ABI), e.g. from a native core.
                val maxc by creating {
                    defFile(project.file("src/nativeInterop/cinterop/maxc.def"))
                }
            }
        }
        target.binaries.framework {
            baseName = "MaxIos"
            isStatic = true
        }
    }

    sourceSets {
        iosMain.dependencies {
            implementation(project(":shared"))
        }
    }
}
