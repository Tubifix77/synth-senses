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
    // Lifecycle stays on 2.10.0, not 2.11.0. Every lifecycle artifact carries
    // constraints aligning its siblings to the same version, and 2.11.0's
    // lifecycle-runtime-compose-android (pulled in transitively by
    // activity-compose and Compose UI) declares minCompileSdk=37 /
    // minAndroidGradlePluginVersion=9.1.0. At 2.10.0 every lifecycle artifact
    // declares <= 35 / 8.6.0. Read from the published aar-metadata.properties;
    // tools/transitive_sweep.py checks the whole resolved graph the same way.
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
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

    // Ears. 1.0.0 verified before merging, not assumed: tasks-audio ships the
    // same 12 classes as 0.10.35, and every class and method AudioSensor.kt
    // names — AudioData, AudioDataFormat.Builder, BaseOptions.Builder,
    // Category.categoryName/score, Classifications.categories — is present in
    // the tasks-core 1.0.0 it pulls in. Exactly one class disappears anywhere
    // in that library between the two versions and it belongs to the vision
    // segmenter, which this app never touches.
    implementation("com.google.mediapipe:tasks-audio:1.0.0")

    // Uplink. Trialling okhttp 5.4.0.
    //
    // 5.5.0 (Dependabot #7) cannot be used: okhttp 5 is a multiplatform module
    // whose `okhttp` coordinate redirects to okhttp-android, and that artifact
    // declares minCompileSdk=37 at 5.5.0. 5.4.0 declares 36, so it is the
    // highest release this project can consume until it moves to compileSdk 37.
    //
    // The API is not the obstacle it was assumed to be: every okhttp symbol
    // Link.kt names is present in 5.x, read out of the bytecode. What that
    // check cannot prove is that the signatures still match, which is what
    // this branch is for.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

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
