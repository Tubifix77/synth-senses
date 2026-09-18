plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.synthsenses"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.synthsenses"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // yamnet.tflite must not be compressed or MediaPipe can't mmap it
    androidResources {
        noCompress += listOf("tflite")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            // android.jar on the unit-test classpath is a stub whose methods all
            // throw. Habituation and PlaceMemory persist through org.json, so
            // without a real implementation on the test classpath (below) every
            // save/load would blow up. This flag covers the remaining stubs.
            isReturnDefaultValues = true
            all { it.testLogging { events("passed", "failed", "skipped") } }
        }
    }
}

// Kotlin 2.x removed the kotlinOptions DSL; jvmTarget now lives here and must
// match the Java compileOptions above.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose (settings UI only). 2026.06.01 is the newest BOM that AGP 8.x
    // can consume: 2026.08.00 and later declare minCompileSdk=37 and
    // minAndroidGradlePluginVersion=9.1.0. Same story for core-ktx 1.19.0,
    // hence 1.18.0 below. Both confirmed by reading the published
    // aar-metadata.properties rather than by trial and error.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Eyes
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")

    // On-device vision models (bundled — no download, works offline)
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:object-detection:17.0.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Ears. 0.10.14 provably compiled; staying on the same 0.10 line.
    // Ears
    implementation("com.google.mediapipe:tasks-audio:0.10.35")

    // Uplink
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // Unit tests for the three validated algorithms. These run on a plain JVM —
    // no emulator, no Robolectric — because Habituation and PlaceMemory take a
    // File rather than a Context.
    testImplementation("junit:junit:4.13.2")
    // The REAL org.json, shadowing the android.jar stub that would otherwise
    // throw on every JSONObject call.
    testImplementation("org.json:json:20260814")
}
