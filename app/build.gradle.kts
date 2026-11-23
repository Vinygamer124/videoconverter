plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.alex.videoconv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.alex.videoconv"
        minSdk = 29; targetSdk = 34
        versionCode = 140 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0); versionName = "1.4"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_ARM_NEON=TRUE") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs { create("fixed") { storeFile = file("fixed.keystore"); storePassword = "videoconv"; keyAlias = "videoconv"; keyPassword = "videoconv" } }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-decoder:1.4.1")
    implementation("com.google.guava:guava:32.1.3-android")
}