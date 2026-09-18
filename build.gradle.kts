// Versions resolved against Google Maven and Maven Central rather than recalled.
// Deliberately the newest 8.x AGP, not 9.4.1: AGP 9 is a major release with DSL
// breaks, and this project had never compiled at all until now — one variable at
// a time.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
