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
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
