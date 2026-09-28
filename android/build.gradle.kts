plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ru.max.android"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")
}

dependencies {
    implementation(project(":shared"))
}
