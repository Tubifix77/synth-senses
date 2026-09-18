plugins {
    id("com.android.application")
    // no org.jetbrains.kotlin.android: AGP 9 has built-in Kotlin support and
    // rejects that plugin outright. See the buildscript block in the root file.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "net.synthsenses"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.synthsenses"
        minSdk = 29
        // targetSdk stays at 36 deliberately. compileSdk changes what the app is
        // compiled against; targetSdk changes how Android behaves towards it at
        // runtime. This app has never run on a device, so opting into a new
        // year of runtime behaviour changes is not something anyone could
        // verify right now. Raise it once the thing has been watched working.
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
        // app/src/main/assets/README.md is instructions for a human, and it was
        // being packaged into the APK and shipped to devices. Found by opening
        // the artifact CI produces, which nobody had done before.
        ignoreAssetsPatterns += listOf("README.md")
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
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // Lifecycle 2.11.0. It was pinned to 2.10.0 for as long as this project
    // was on AGP 8.x, because 2.11.0's lifecycle-runtime-compose-android —
    // pulled in transitively by activity-compose and Compose UI, never named
    // directly — declares minCompileSdk=37. That was the wall; it is gone.
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose (settings UI only). 2026.09.00 is the newest BOM whose libraries
    // fit under compileSdk 37 / AGP 9.4.1, confirmed by resolving
    // animation-core out of each BOM and reading its aar-metadata.properties
    // rather than by trial and error.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
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

    // Ears. 1.0.0 verified before merging, not assumed: tasks-audio ships the
    // same 12 classes as 0.10.35, and every class and method AudioSensor.kt
    // names — AudioData, AudioDataFormat.Builder, BaseOptions.Builder,
    // Category.categoryName/score, Classifications.categories — is present in
    // the tasks-core 1.0.0 it pulls in. Exactly one class disappears anywhere
    // in that library between the two versions and it belongs to the vision
    // segmenter, which this app never touches.
    implementation("com.google.mediapipe:tasks-audio:1.0.0")

    // Uplink. okhttp 5.5.0, which Dependabot #7 proposed and which could not be
    // built under the old ceiling: okhttp 5 is a multiplatform module whose
    // `okhttp` coordinate redirects to okhttp-android, and that artifact
    // declares minCompileSdk=37 at 5.5.0. The API objection recorded against
    // okhttp 5 was unfounded — every okhttp symbol Link.kt names is present in
    // 5.x, read out of the bytecode.
    //
    // Worth knowing if a downgrade is ever needed: 4.12.0 carries no published
    // advisories, and 5.4.0 is the highest release that builds at compileSdk 36.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

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
