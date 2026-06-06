plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
    id("jacoco")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

android {
    namespace = "com.gregor.lauritz.healthdashboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gregor.lauritz.healthdashboard"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        testOptions {
            unitTests.isReturnDefaultValues = true
            unitTests.isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            enableUnitTestCoverage = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        xmlReport = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ktlint {
    version.set("1.5.0")
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
    }

    val fileFilter =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
            "**/hilt_aggregated_deps/**",
            "**/*_HiltModules*",
            "**/*_MembersInjector*",
            "**/*Hilt_*",
            "**/*_Factory*",
            "**/Dagger*Component*.*",
            "**/*ComposableSingletons*",
            "**/*_Impl*",
            "**/databinding/**",
            "**/di/**",
        )

    // Search broadly across all known AGP output locations for compiled Kotlin/Java class files
    val debugTree =
        fileTree(layout.buildDirectory.get()) {
            include(
                "tmp/kotlin-classes/debug/**/*.class",
                "intermediates/kotlinc/debug/**/*.class",
                "intermediates/javac/debug/**/*.class",
            )
            fileFilter.forEach { exclude(it) }
        }

    doFirst {
        val count = debugTree.files.size
        println("jacocoTestReport: classDirectories has $count class file(s)")
        if (count == 0) {
            val buildDir = project.layout.buildDirectory.get().asFile
            buildDir
                .walkTopDown()
                .filter { it.extension == "class" }
                .take(20)
                .forEach { println("  class: ${it.relativeTo(buildDir)}") }
        }
    }

    val mainSrc = "${project.projectDir}/src/main/java"

    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
}

tasks.register("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")
    doLast {
        val reportFile =
            layout.buildDirectory
                .file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml")
                .get()
                .asFile
        if (!reportFile.exists()) {
            throw GradleException("Coverage report not found: ${reportFile.absolutePath}")
        }
        val xml = reportFile.readText()
        // Find the last INSTRUCTION counter element (bundle-level); handles any attribute order/whitespace
        val counterElement =
            Regex("<counter[^>]*type=\"INSTRUCTION\"[^>]*/>")
                .findAll(xml)
                .lastOrNull()
                ?.value
                ?: throw GradleException(
                    "Could not parse coverage report (report size: ${xml.length} bytes)",
                )
        val missed =
            Regex("missed=\"(\\d+)\"")
                .find(counterElement)
                ?.groupValues
                ?.get(1)
                ?.toLong()
                ?: throw GradleException("Could not parse missed count from: $counterElement")
        val covered =
            Regex("covered=\"(\\d+)\"")
                .find(counterElement)
                ?.groupValues
                ?.get(1)
                ?.toLong()
                ?: throw GradleException("Could not parse covered count from: $counterElement")
        val total = missed + covered
        val pct = if (total > 0) covered.toDouble() / total.toDouble() * 100.0 else 0.0
        println("Coverage: ${"%.2f".format(pct)}% ($covered/$total instructions)")
        if (pct < 25.0) {
            throw GradleException(
                "Coverage gate FAILED: ${"%.2f".format(pct)}% < 25% minimum required.",
            )
        }
        println("Coverage gate PASSED: ${"%.2f".format(pct)}% >= 25%")
    }
}

protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.material)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // DataStore
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.kotlin.lite)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Health Connect
    implementation(libs.androidx.health.connect.client)

    // Lifecycle extras
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Serialization (type-safe nav destinations)
    implementation(libs.kotlinx.serialization.json)

    // Vico charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // Google Drive backup
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)

    // WorkManager + Hilt integration
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    implementation(libs.zip4j)
    ksp(libs.hilt.work.compiler)

    // Security
    implementation(libs.google.tink.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation("com.lemonappdev:konsist:0.13.0")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.arch.core.testing)
    kspTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.compose.ui:ui-test")
    androidTestImplementation(libs.androidx.benchmark.macro)
    androidTestImplementation(libs.play.services.stats)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
