plugins {
    id("com.android.application") version "9.4.1" apply false

    // Kotlin is NOT AGP-managed here: gradle.properties sets
    // android.builtInKotlin=false so this project owns the KGP version.
    // 2.4.10 is the current stable line (2.4 branch, supported to Dec 2027).
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false

    // The Compose compiler plugin is versioned INDEPENDENTLY of Kotlin. It is
    // NOT the same number as the KGP version - 2.4.20 tracks the 2.4 line.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
