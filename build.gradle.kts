// Versions resolved against Google Maven and Maven Central rather than recalled.
//
// AGP 9.4.1 and compileSdk 37. Staying on 8.x was the right call while the
// project had never compiled; it is the wrong call now. Four separate
// artifacts — core-ktx, lifecycle, the Compose BOM and okhttp — had reached
// releases that declare minCompileSdk=37, and every one of them was pinned
// below its current version by that single wall. Holding the line meant the
// gap widening on every Dependabot run.
//
// AGP 9.4.1 requires Gradle 9.6.0 or newer, read out of the plugin jar, hence
// the 9.7.1 wrapper. Before changing anything, every DSL property this build
// file uses was diffed between the AGP 8.13.2 and 9.4.1 `gradle-api` jars:
// none of them were dropped.
// AGP 9 compiles Kotlin itself: the org.jetbrains.kotlin.android plugin is not
// merely unnecessary, it refuses to apply. AGP brings its own Kotlin Gradle
// plugin, 2.2.10 for AGP 9.4.1, read from its POM. This project is on 2.4.20
// and raising AGP's copy is the documented way to keep it there — otherwise
// the language version would quietly go backwards. The Compose compiler plugin
// must stay on the same version as the Kotlin compiler, hence both below.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
