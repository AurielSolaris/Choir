import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.androidx.room)
}

/**
 * Release signing is driven by keystore.properties at the repo root, which is
 * git-ignored. When it is absent — CI, a fresh clone — the release build simply
 * goes unsigned rather than failing, so `assembleRelease` still verifies that
 * the code compiles and R8 is happy.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/**
 * True when this invocation is running the instrumented tests.
 *
 * R8 is switched off for those, and only those. The instrumented tests run
 * against the release variant (see `testBuildType`), but the test APK is
 * minified *separately* and linked against the app's mapping — so every class
 * the test code touches that lives in the app APK has to have survived the
 * app's own shrink, under the name the mapping gave it. It routinely has not:
 * R8 trims Kotlin's `Intrinsics` to the overloads the app uses, drops
 * `androidx.tracing.Trace` as unreachable, and each one is a
 * NoSuchMethodError or NoClassDefFoundError in the runner's first instruction,
 * before a single test starts. Keeping them one by one is a list that grows
 * every time a test library does something new.
 *
 * So `assembleRelease` — the build that ships — is minified and shrunk exactly
 * as before, and the R8 rules are still verified every time it runs. A
 * `connectedReleaseAndroidTest` builds the same variant, with the same
 * resources, manifest and signing, minus the shrinker.
 */
val runningInstrumentedTests = gradle.startParameter.taskNames.any {
    it.contains("AndroidTest", ignoreCase = true)
}

android {
    namespace = "app.auriel.choir"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.auriel.choir"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"

        // Choir's own runner, not the stock one — see ChoirTestRunner for the
        // single line it sets and why nothing else can set it.
        testInstrumentationRunner = "app.auriel.choir.ChoirTestRunner"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = !runningInstrumentedTests
            isShrinkResources = !runningInstrumentedTests
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")

            // The instrumented tests are built against this variant, so their
            // own APK is shrunk too and needs rules of its own.
            testProguardFiles("proguard-rules-androidtest.pro")
        }
        debug {
            isMinifyEnabled = false
            // A different package, so a debug build can sit next to a signed
            // release one on the same phone. Without this, installing a debug
            // build to check something means uninstalling the release first,
            // which takes the playlists and likes with it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /**
     * Instrumented tests run against the release variant.
     *
     * Not a preference — the debug test APK does not work. Both APKs carry a
     * `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`, and the
     * test APK's entry (coroutines-test's own `ExceptionCollectorAsService`)
     * shadows the app's `AndroidExceptionPreHandler`. Compose's test rule runs
     * every test inside `runTest`, which looks that handler up before it will
     * start, so every Compose test fails on the device with "Exception handler
     * was not found via a ServiceLoader" and none of them ever composes
     * anything.
     *
     * Testing the release variant is the better answer regardless: it is the
     * build that ships, R8 and resource shrinking included, so a keep rule that
     * is missing shows up here rather than in somebody's hands.
     */
    testBuildType = "release"

    /**
     * The exported Room schemas, handed to the instrumented tests as assets.
     *
     * MigrationTestHelper opens a database *at* an old version and then runs
     * the real migration over it, so it needs the schema JSON for every version
     * that ever shipped — not the current one the compiler knows about. Without
     * this line the migration tests fail with a file-not-found that reads as a
     * missing schema rather than a missing source directory.
     */
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }

    /**
     * Both coroutine exception handlers, in one services file.
     *
     * `ServiceLoader` is asked for them against coroutines-core's *own*
     * classloader, which is the app APK's — so an entry that exists only in the
     * test APK is invisible, and coroutines-test refuses to start a `runTest`
     * whose `ExceptionCollectorAsService` it cannot find. The answer is to put
     * that entry in the app APK too (see the dependencies block, which adds
     * coroutines-test only while the instrumented tests are being built) and to
     * concatenate the two services files rather than let one win.
     *
     * Scoped to that one file. A blanket merge of META-INF/services would
     * change how every other service is resolved as a side effect.
     */
    packaging {
        resources {
            merges += "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler"
        }
    }

    testOptions {
        unitTests {
            // Framework stubs throw by default, so any code path that logs
            // would fail a JVM test for no good reason.
            isReturnDefaultValues = true
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.navigation.compose)

    // Home screen widgets. Glance composes to RemoteViews, so none of the app's
    // own components can be reused inside one — only the design tokens.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    compileOnly(libs.checker.qual)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Only while the instrumented tests are being built, and never in a
    // shipping APK: this is here for its META-INF/services entry alone — see
    // the packaging block above.
    if (runningInstrumentedTests) {
        implementation(libs.kotlinx.coroutines.test)
    }

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // The same manifest, added to the release variant while the instrumented
    // tests are being built and at no other time. It contributes one thing: the
    // bare ComponentActivity that Compose's test rule hosts a composition in.
    // Without it every Compose test dies at "Unable to resolve activity" — and
    // the usual debugImplementation is no help, because the tests run against
    // the release variant. It is not in a shipping APK; see
    // runningInstrumentedTests.
    if (runningInstrumentedTests) {
        releaseImplementation(libs.androidx.compose.ui.test.manifest)
    }

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Instrumented tests. JUnit 4, not 5: the on-device runner is
    // AndroidJUnitRunner, which has no JUnit Platform to launch.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Declared for the test APK as well as the app's. Compose's test rule runs
    // every test inside `runTest`, which refuses to start unless it can find
    // Android's coroutine exception handler through a ServiceLoader — and that
    // entry only ships in coroutines-android, which otherwise reaches the test
    // APK as a transitive dependency without its META-INF/services resource.
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
