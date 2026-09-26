plugins {
    id("com.android.library")
    alias(libs.plugins.ktlint)
}

// Self-instrumenting microbenchmark module.
//
// This was previously a `com.android.test` module with `targetProjectPath = ":app"` while using
// `androidx.benchmark.junit4.AndroidBenchmarkRunner` -- the MICRObenchmark runner, which expects a
// self-instrumenting APK. That combination silently broke every benchmark here:
//   * the instrumentation ran inside the tested app's process, so androidx.test and Kotlin stdlib
//     classes resolved against the APP's minified dex. AGP does not duplicate dependencies it
//     considers app-provided into the test APK, and the app's R8 had shrunk the ones only the test
//     infrastructure uses -- so they were in neither APK (NoClassDefFoundError androidx/tracing/Trace,
//     then kotlin/LazyKt). The same AGP dedupe mechanism is documented in
//     scripts/run-instrumented-tests.sh, which hit it once before via androidx.benchmark.macro.
//   * AGP then demanded obfuscation parity, forcing `isMinifyEnabled` on the test APK.
//   * `AndroidBenchmarkRunner` could not launch its own `IsolationActivity` from the tested app's
//     process, so `connectedBenchmarkAndroidTest` exited 0 having run ZERO tests.
//
// These benchmarks exercise Room, SQLCipher and the scoring engine in-process; none drives app UI.
// That is microbenchmark work, so the module is a library whose `androidTest` variant carries the
// tests. `connectedDebugAndroidTest` -- which CI already runs -- now sweeps them, and
// `.github/workflows/ci.yml` compiles the androidTest sources on every PR so the constructor drift
// that left this module uncompilable cannot return unnoticed.
//
// The macrobenchmark module `:benchmark` is unaffected: it legitimately uses `com.android.test` +
// `targetProjectPath` because it drives the installed app from a separate process.
android {
    namespace = "app.readylytics.health.databasebenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Module-scoped: the routine `connectedDebugAndroidTest` sweep (CI and local) runs the fast
        // correctness tests only. Heavy measurement runs -- the 1M-row fixtures -- are @LargeTest and
        // are opted into explicitly, e.g.
        //   ./gradlew :database-benchmark:connectedDebugAndroidTest \
        //     -Pandroid.testInstrumentationRunnerArguments.annotation=androidx.test.filters.LargeTest
        // Scoped to this module, so no other module's @LargeTest tests are affected.
        testInstrumentationRunnerArguments["notAnnotation"] = "androidx.test.filters.LargeTest"
        // BenchmarkRule hard-fails on a debug variant unless these environment errors are
        // suppressed. Correctness still runs; only the timing numbers are untrustworthy here.
        // A real measurement run uses the connected release-shaped device build. Mirrors the
        // sibling :benchmark module and scripts/run-instrumented-tests.sh.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] =
            "ACTIVITY-MISSING,DEBUGGABLE,EMULATOR,NOT-AOT-COMPILED"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("androidTest").assets.directories.add(rootProject.file("core/database/schemas").absolutePath)
    }
}

ktlint {
    version.set("1.5.0")
}

dependencies {
    implementation(libs.androidx.arch.core.runtime)
    implementation(libs.androidx.benchmark.junit4)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.testing)
    implementation(libs.sqlcipher.android)
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    implementation(project(":core:database-schema"))
    implementation(project(":core:scoring"))
    implementation(project(":core:healthconnect"))
    implementation(libs.hilt.android)
}
