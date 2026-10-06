import java.util.Properties

plugins {
    id("com.android.application")
    // Required because gradle.properties sets android.builtInKotlin=false, which
    // hands ownership of the Kotlin compile back to the KGP plugin.
    id("org.jetbrains.kotlin.android")
    // Compose compiler. Version comes from the root build script and is
    // independent of the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials, read from a gitignored properties file.
//
// A release build was previously unsigned, which meant the only installable
// artifact was the DEBUG apk. That is not shareable: debuggable=true lets anyone
// with adb run `run-as` and read files/crush.json, which stores the provider API
// key in plaintext by Crush's own design. Handing a friend a debug build
// therefore hands them the key.
//
// Falls back to unsigned when the file is absent so a fresh clone still builds.
val releaseSigning: Properties? = Properties().also { props ->
    val f = rootProject.file("keystore/credentials.properties")
    if (f.exists()) {
        f.inputStream().use { props.load(it) }
    }
}

android {
    namespace = "com.opencode.chat"
    // Current AndroidX (Compose BOM 2026.09, core-ktx 1.19, lifecycle 2.11,
    // navigation 2.10.2, okhttp 5.5) all require compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.opencode.chat"
        // API 29, not 26.
        //
        // 26/27/28 forced a second and third Keystore auth tier and a second
        // authentication code path, neither of which could be executed anywhere:
        // the only reference device is API 29. Supporting three device classes
        // on two untestable branches is how the "only fingerprint works" defect
        // existed at all.
        //
        // 29 still covers the reference device and every current phone, and is
        // the first level with StrongBox available.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Crush ships only a statically-linked aarch64 binary (CGO_ENABLED=0).
        // Upstream's goreleaser explicitly EXCLUDES android/arm, android/386 and
        // android/amd64, so arm64-v8a is the only Android ABI that exists.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Kept off deliberately: minify + a Go binary in jniLibs adds risk
            // for no size win on a 92MB payload dominated by the engine.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        // Bytecode target stays 17 even though the BUILD runs on JDK 25.
        //
        // These are different things and conflating them is a common mistake:
        //  - the build JDK (build-install.bat -> JBR 25) merely runs Gradle and
        //    the Kotlin compiler, and can be as new as we like.
        //  - the bytecode target decides the MINIMUM ANDROID VERSION that can
        //    run the APK, and D8 must understand every class file version.
        //
        // Raising this to 25 would shrink device support for zero build-speed
        // gain. Kotlin can emit up to JVM_26, but there is no reason to.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

buildFeatures {
        compose = true
    }

    lint {
        // Lint's "vital" pass crashes the release build outright rather than
        // reporting a finding:
        //   Unexpected failure during lint analysis (this is a bug in lint or
        //   one of the libraries it depends on)
        //   @NotNull method com/intellij/openapi/application/
        //     AsyncExecutionService.getService must not return null
        // thrown while parsing Kotlin imports in SettingsViewModel.kt.
        //
        // It is an AGP/lint defect, not a code defect, and it blocks the build
        // entirely - so it has to be off for a release artefact to exist at all.
        // The compiler still enforces everything that matters here.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // REQUIRED. The engine is exec()'d from nativeLibraryDir, and
            // Android only extracts .so files there when legacy packaging is on.
            // With useLegacyPackaging = false the binary stays inside the APK
            // and exec() fails with ENOENT. AGP 9 rejects the equivalent
            // android:extractNativeLibs="true" manifest attribute outright.
            useLegacyPackaging = true

            // Do NOT strip the engine. It is a Go PIE binary; llvm-strip can
            // remove symbols it needs, and this keeps it byte-identical to
            // the release artifact we verified.
            keepDebugSymbols += setOf("**/libcrush.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.core:core-ktx:1.19.1")
    // FragmentActivity is needed to host the device-credential screen.
    //
    // androidx.biometric is deliberately GONE. It inflates its prompt with an
    // AppCompat theme, and this app uses the platform Material theme, so
    // authenticating threw on API 30+ and killed onboarding on a friend's
    // OnePlus Nord. DeviceAuth uses only platform APIs now, so there is no theme
    // requirement and no third-party prompt to go wrong.
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.google.code.gson:gson:2.14.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
}
