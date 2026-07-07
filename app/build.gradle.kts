import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version can be overridden from CI (the release tag): -PversionName=1.2.3 -PversionCode=4.
val appVersionName = (project.findProperty("versionName") as String?) ?: "1.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

// Release signing is driven by a keystore + credentials supplied out-of-band (a local
// keystore.properties for manual builds, or env vars in CI). When none is present the
// release build stays unsigned so the project still compiles without secrets.
data class ReleaseSigning(
    val storeFile: java.io.File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val releaseSigning: ReleaseSigning? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val props = Properties().apply { FileInputStream(propsFile).use { load(it) } }
        return@run ReleaseSigning(
            rootProject.file(props.getProperty("storeFile")),
            props.getProperty("storePassword"),
            props.getProperty("keyAlias"),
            props.getProperty("keyPassword"),
        )
    }
    val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
    if (ksPath != null) {
        return@run ReleaseSigning(
            file(ksPath),
            System.getenv("ANDROID_KEYSTORE_PASSWORD"),
            System.getenv("ANDROID_KEY_ALIAS"),
            System.getenv("ANDROID_KEY_PASSWORD"),
        )
    }
    null
}

android {
    namespace = "com.clairvoyant.glasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clairvoyant.glasses"
        minSdk = 28 // Rokid glasses run Android 12+, but 28 for broader compat
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = s.storeFile
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Material Design (provides TabLayout)
    implementation("com.google.android.material:material:1.11.0")

    // Session UI
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Relay WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX for QR scanning
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Barcode Scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests (JVM). org.json provides a real impl so protocol code runs off-device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
