plugins {
    id("com.android.application") version "9.4.1" apply false

    // Kotlin is NOT AGP-managed here: gradle.properties sets
    // android.builtInKotlin=false so this project owns the KGP version.
    // 2.4.10 is the current stable line (2.4 branch, supported to Dec 2027).
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false

    // The Compose compiler plugin is versioned INDEPENDENTLY of Kotlin. It is
    // NOT the same number as the KGP version - 2.4.20 tracks the 2.4 line.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false

    // Room's symbol processor (ProjectRegistry persistence, ADR-0001 P2).
    // KSP stopped pairing its version with specific Kotlin builds - the
    // "kotlinVersion-kspVersion" tag scheme retired with the 2.3 line - so
    // every 2.3.x release supports the current Kotlin series and this tracks
    // the KSP release train itself (2.3.13, 2026-10-30, latest stable).
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
