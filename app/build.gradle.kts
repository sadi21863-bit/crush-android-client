import java.util.Properties

plugins {
    id("com.android.application")
    // Required because gradle.properties sets android.builtInKotlin=false, which
    // hands ownership of the Kotlin compile back to the KGP plugin.
    id("org.jetbrains.kotlin.android")
    // Compose compiler. Version comes from the root build script and is
    // independent of the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose")

    // Room's KSP processor (ProjectRegistry persistence, ADR-0001 P2).
    // Version lives in the root build script, like the other plugins.
    id("com.google.devtools.ksp")
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
//
// CORRECTION (audit BUILD-01): the previous form was
//     Properties().also { if (f.exists()) load(it) }
// `also` returns the receiver regardless, so this was NEVER null. Both later
// checks therefore always entered, and with the file absent the code read null
// property values and passed a null storeFile to rootProject.file(...). The
// comment above promised an unsigned fallback that could not happen.
//
// takeIf { isFile } is what actually yields null, so the checks downstream
// behave as documented.
val releaseSigning: Properties? = rootProject.file("keystore/credentials.properties")
    .takeIf { it.isFile }
    ?.let { f -> Properties().also { props -> f.inputStream().use { props.load(it) } } }

android {
    namespace = "com.crush.mobile"
    // Current AndroidX (Compose BOM 2026.09, core-ktx 1.19, lifecycle 2.11,
    // navigation 2.10.2, okhttp 5.5) all require compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.crush.mobile"
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
        // Instrumented smoke tests run through the standard runner; declared
        // here so `connectedDebugAndroidTest` works without per-run flags.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Crush ships only a statically-linked aarch64 binary (CGO_ENABLED=0).
        // Upstream's goreleaser explicitly EXCLUDES android/arm, android/386 and
        // android/amd64, so arm64-v8a is the only Android ABI that exists.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            // Fail loudly on a PARTIAL credentials file. Previously a missing
            // key silently produced a null storeFile / null password, which
            // either threw deep inside AGP or produced an unsigned release that
            // looked fine.
            val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
                .filter { releaseSigning.getProperty(it).isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "keystore/credentials.properties exists but is missing: ${missing.joinToString()}. " +
                        "Delete the file to build unsigned, or supply all four values."
                )
            }
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
            // R8 ON (2026-10-31, decision item 1): the measured release
            // artifact carries 47.76 MB of DEX (3 files) against 24.84 MB of
            // compressed engine - the retired "engine dominates the payload"
            // rationale had the compressed composition backwards. Measured
            // ceiling ~45-55 MB APK. Keep rules in proguard-rules.pro
            // (junixsocket JNI, Gson reflection, our DTO/model packages);
            // MANDATORY before a released artifact: one end-to-end UDS turn
            // on device (engine start, streaming, a real model call).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Unsigned by ABSENCE OF CREDENTIALS, not by a silent fallback
                // inside a broken Properties(). Also refuse for anything that
                // actually ships, so an unsigned release can never be mistaken
                // for a distributable one.
                logger.warn(
                    "WARNING: building an UNSIGNED release. This build is for local testing only " +
                        "and must not be shared - see README, 'Why debug builds are not shareable'."
                )
                if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } &&
                    project.hasProperty("requireReleaseSigning")
                ) {
                    throw GradleException(
                        "requireReleaseSigning is set but keystore/credentials.properties is absent."
                    )
                }
            }
        }

        create("r8probe") {
            // Side-by-side R8 runtime-proof build (2026-11-02, decision item 1
            // continuation). PURPOSE: run the mandated R8 end-to-end device
            // check WITHOUT uninstalling the debug app - installing a
            // release-signed applicationId over it is impossible (signature
            // mismatch) and uninstalling destroys the vault ciphertext and
            // every local session, unrecoverably by design.
            //
            // Release-rigored ON PURPOSE: inherits everything above via
            // initWith (minify, proguard rules, release signing), then only
            // the identity differs, so the artifact that proves R8 runtime is
            // byte-equivalent in rigour to what ships. installable BESIDE the
            // debug app because of the suffix.
            initWith(getByName("release"))
            applicationIdSuffix = ".r8probe"
            versionNameSuffix = "-r8probe"
            matchingFallbacks += listOf("release")
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

    testOptions {
        // android.jar's Log/TextUtils methods throw "not mocked" under plain JVM
        // tests, which makes any production line that logs untestable.
        //
        // The workaround that was in place instead wrapped individual log calls in
        // runCatching. That swallowed real failures AND left every future log call
        // a landmine - the SSE workspace-switch tests could not run at all until
        // this was set properly.
        //
        // A no-op is the correct default for a unit test. Robolectric is still
        // used where real Android behaviour matters; it supplies a real Context,
        // which returnDefaultValues cannot.
        unitTests.isReturnDefaultValues = true
    }

    // LintEnabled 2026-10-31 (decision item 1): the historical
    // "lintVital crashes the release build" blocker does NOT reproduce on
    // AGP 9.4.1 - same-day evidence: :app:lintDebug and :app:lintRelease both
    // ran green (1 error + 33/34 warnings, all either fixed in the same batch
    // or waived with dated reasons in app/lint.xml; see that file first).
    // The overrides used to defuse the crash (checkReleaseBuilds=false,
    // abortOnError=false) are therefore GONE - the AGP defaults are in
    // force: lintVitalRelease runs as part of every release assemble, and a
    // lint ERROR (not a warning) aborts any explicit lint task. Warnings
    // stay non-fatal on purpose. The retired comment here claimed the
    // "compiler still enforces everything that matters" - lint helps too
    // now.

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

    // ProjectRegistry persistence (ADR-0001 P2, `RoomProjectStore`). Room
    // 2.8.5 is the maintained 2.x line - a same-day stable release beside
    // Room 3.0.3, which moved coordinates to the new androidx.room3 group;
    // adopting THAT is a deliberate later migration, and this slice needs
    // only the smallest proven stack. room-ktx powers the suspend DAO;
    // room-compiler runs via KSP (root build script).
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
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

    // Unix-domain-socket client for the embedded engine (P1, F01): the engine
    // binds `-H unix://<app-private path>` and everything talks through the
    // socket FILE; kernel file permissions are the authorized path (no sibling
    // UID can connect). junixsocket-common has AFUNIXSocketFactory.FactoryArg,
    // which OkHttp plugs in via socketFactory(...).
    //
    // The JNI bridge (libjunixsocket-native.so) CANNOT come from
    // junixsocket-native-android on Maven Central: that coordinate is
            // POM-packaging, so Gradle downloads only the marker pom and the
            // package ships no natives (symptom: UnsatisfiedLinkError
    // "No implementation found for NativeUnixSocket.createSocket" and the 60s
    // readiness gate failing). The .so is vendored instead, extracted from the
    // AAR published alongside the same version on Central:
    //   https://repo1.maven.org/maven2/com/kohlschutter/junixsocket/
    //     junixsocket-native-android/2.10.1/junixsocket-native-android-2.10.1.aar
    //   sha1 eed2f4d90ebdc0de4964fd9ffe752cb9b57aa57d (verified 2026-10-18)
    // jni/arm64-v8a/libjunixsocket-native.so -> app/src/main/jniLibs/arm64-v8a/
    // (loader: NativeLibraryLoader -> System.loadLibrary("junixsocket-native")).
    implementation("com.kohlschutter.junixsocket:junixsocket-common:2.10.1")

    implementation("com.google.code.gson:gson:2.14.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")

    // Instrumented smoke suite (2026-10-31): the review's "no androidTest"
    // gap. The suite is deliberately launch-level to start - it proves the
    // APK installs, MainActivity composes, and the Compose tree attaches,
    // without depending on provider keys or an engine turn (those already
    // have z-session device evidence and stay on-device evidence, not CI).
    // The explicit androidx.test coordinates are pinned to the versions the
    // Compose test stack enforces strictly (core 1.5.0 / ext-junit 1.1.5 /
    // runner 1.5.0 via espresso 3.5.0) - deviating from them is a hard
    // resolution failure, not a warning.
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
}

/**
 * JVM unit tests must not die on an android.util.Log call.
 *
 * Without this, android.jar's Log methods throw "not mocked" and take the whole
 * test with them - so any production line that logs becomes untestable. The
 * workaround used before was to wrap each log call in runCatching, which silently
 * swallowed a real failure AND left every future log call a landmine: the SSE
 * workspace-switch tests could not run until this was set.
 *
 * Return-defaults makes the no-op the RIGHT default for a unit test. Robolectric
 * is still there for the cases that need real Android behaviour - it supplies a
 * real Context, which returnDefaultValues cannot.
 */
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.logging", "stdout")

    // STRIKE-2 MITIGATIONS (2026-11-09, socket-identity diagnosis doc): the
    // 60s budget bump could not stop the strikes because the healthy path
    // costs ~1s - a strike is a >59x sustained stall, not a slow ladder. The
    // suite runs all 600+ tests (Robolectric classloader churn among them)
    // in ONE worker JVM at the DEFAULT heap with no fork policy, so two
    // stall classes are plausible: GC pressure near the heap ceiling, and
    // cross-class static/classloader leakage. Both are capped here:
    // maxHeapSize = 2g gives the suite real headroom, and forkEvery = 50
    // hands every 50 tests a FRESH JVM, resetting heap and static state
    // mid-suite. maxParallelForks stays untouched ON PURPOSE: parallel
    // forks on the 2-vCPU CI host would slow everything and interact with
    // this suite's timing-sensitive tests. Evidence rule for the next
    // full-suite gate run: two consecutive clean runs (local + CI) keep
    // this in; a strike WITH a fingerprint goes back to the diagnosis doc
    // and its branch table - no further budget bumps either way.
    maxHeapSize = "2g"
    forkEvery = 50
}
