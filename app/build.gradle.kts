import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.afterdrop.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.afterdrop.app"
        minSdk = 36 // Android 16. Every Pixel 7, 8 and 9 has been offered it; older versions are not tested.
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // A fixed debug key kept in the repository, so every CI build can be installed over the last.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    // The release key never lives in the repository. CI writes it from a secret and names it here;
    // without it, a release build is left unsigned and cannot be installed, which is the safe failure.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
    val releasePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    if (!releaseKeystore.isNullOrBlank() && !releasePassword.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = releasePassword
            keyAlias = "afterdrop"
            keyPassword = releasePassword
        }
        buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    buildTypes.getByName("release") { isMinifyEnabled = false }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The golden specs double as the built-in gallery.
    sourceSets["main"].assets.srcDir(rootProject.file("golden/valid"))
    lint { abortOnError = false }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":composer"))
    implementation(project(":ai"))
    implementation(project(":data"))
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
